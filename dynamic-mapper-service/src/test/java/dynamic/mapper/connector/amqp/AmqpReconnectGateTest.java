/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.connector.amqp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Recoverable;

/**
 * The housekeeping reconnects a lost AMQP connection. Both clients used to set the reconnect gate to
 * {@code Long.MAX_VALUE} on connect and never reset it, so a lost connection was never reconnected and the
 * outbound notifications were held for good. These tests use no network.
 */
class AmqpReconnectGateTest {

    private static final long NOW = 1_000_000L;

    private static AMQPClient amqp() {
        return new AMQPClient() {
            @Override
            public boolean shouldConnect() {
                return true;
            }
        };
    }

    private static AMQP10Client amqp10() {
        return new AMQP10Client() {
            @Override
            public boolean shouldConnect() {
                return true;
            }
        };
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getSuperclass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Connection connection(boolean open, boolean recoverable) {
        Connection connection = recoverable
                ? mock(Connection.class, withSettings().extraInterfaces(Recoverable.class))
                : mock(Connection.class);
        when(connection.isOpen()).thenReturn(open);
        return connection;
    }

    private static Channel channel(boolean open) {
        Channel channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(open);
        return channel;
    }

    // ---- AMQP 0-9-1

    @Test
    void aLostPlainConnectionIsReconnectedAfterAConnect() throws Exception {
        AMQPClient client = amqp();
        set(client, "connection", connection(false, false)); // automaticRecovery=false: not Recoverable
        set(client, "channel", channel(false));
        client.markConnected(); // what connect() does; it used to close the gate for good

        assertTrue(client.isReconnectDue(NOW));
    }

    @Test
    void aConnectionTheLibraryIsRecoveringIsLeftAlone() throws Exception {
        AMQPClient client = amqp();
        set(client, "connection", connection(false, true));
        set(client, "channel", channel(false));
        client.markConnected();

        assertFalse(client.isReconnectDue(NOW));
    }

    @Test
    void aChannelClosedByAnErrorOnAnOpenConnectionIsReconnected() throws Exception {
        AMQPClient client = amqp();
        set(client, "connection", connection(true, true));
        set(client, "channel", channel(false));
        client.markConnected();

        assertTrue(client.isReconnectDue(NOW));
    }

    @Test
    void aHealthyConnectionIsLeftAlone() throws Exception {
        AMQPClient client = amqp();
        set(client, "connection", connection(true, true));
        set(client, "channel", channel(true));
        client.markConnected();

        assertFalse(client.isReconnectDue(NOW));
    }

    @Test
    void theBackoffHoldsTheNextAttemptBack() throws Exception {
        AMQPClient client = amqp();
        set(client, "connection", connection(false, false));
        client.reconnectBackoff.next(NOW); // the first attempt holds the next one back by one step: 10 seconds

        assertFalse(client.isReconnectDue(NOW));
        assertTrue(client.isReconnectDue(NOW + 10_001));
    }

    // ---- AMQP 1.0

    @Test
    void aLostAmqp10ConnectionIsReconnectedAfterAConnect() {
        AMQP10Client client = amqp10();
        client.markConnected(); // used to set the gate to Long.MAX_VALUE; nothing reset it when the JMS listener fired

        assertTrue(client.isReconnectDue(NOW));
    }

    @Test
    void theAmqp10BackoffHoldsTheNextAttemptBack() throws Exception {
        AMQP10Client client = amqp10();
        client.reconnectBackoff.next(NOW);

        assertFalse(client.isReconnectDue(NOW));
        assertTrue(client.isReconnectDue(NOW + 10_001));
    }
}
