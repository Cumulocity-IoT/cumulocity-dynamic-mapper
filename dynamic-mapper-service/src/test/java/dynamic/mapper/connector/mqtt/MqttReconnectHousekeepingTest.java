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

package dynamic.mapper.connector.mqtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;

/**
 * The housekeeping reconnects an MQTT client that is not connected, backed off by {@code ReconnectBackoff}: the first
 * attempt at once, the next ones held back. A connected client is held out of the way until the disconnect listener
 * says it dropped unexpectedly. No network: the HiveMQ client is a mock and the reconnect is a recorded submit.
 */
class MqttReconnectHousekeepingTest {

    /** One client of each version, with the mock that stands in for its HiveMQ client. */
    private record Fixture(String name, AMQTTClient client, ExecutorService pool, Consumer<MqttClientState> state) {
    }

    private List<Fixture> fixtures(boolean shouldConnect) {
        MQTT3Client mqtt3 = new MQTT3Client() {
            @Override
            public boolean shouldConnect() {
                return shouldConnect;
            }
        };
        Mqtt3BlockingClient blocking3 = mock(Mqtt3BlockingClient.class);
        ExecutorService pool3 = mock(ExecutorService.class);
        ReflectionTestUtils.setField(mqtt3, "tenant", "t1");
        ReflectionTestUtils.setField(mqtt3, "mqttClient", blocking3);
        ReflectionTestUtils.setField(mqtt3, "virtualThreadPool", pool3);

        MQTT5Client mqtt5 = new MQTT5Client() {
            @Override
            public boolean shouldConnect() {
                return shouldConnect;
            }
        };
        Mqtt5BlockingClient blocking5 = mock(Mqtt5BlockingClient.class);
        ExecutorService pool5 = mock(ExecutorService.class);
        ReflectionTestUtils.setField(mqtt5, "tenant", "t1");
        ReflectionTestUtils.setField(mqtt5, "mqttClient", blocking5);
        ReflectionTestUtils.setField(mqtt5, "virtualThreadPool", pool5);

        Fixture f3 = new Fixture("MQTT 3", mqtt3, pool3, state -> when(blocking3.getState()).thenReturn(state));
        Fixture f5 = new Fixture("MQTT 5", mqtt5, pool5, state -> when(blocking5.getState()).thenReturn(state));
        f3.state().accept(MqttClientState.DISCONNECTED);
        f5.state().accept(MqttClientState.DISCONNECTED);
        return List.of(f3, f5);
    }

    private void housekeeping(Fixture f) {
        ReflectionTestUtils.invokeMethod(f.client(), "connectorSpecificHousekeeping", "t1");
    }

    private void verifyReconnects(Fixture f, int times) {
        verify(f.pool(), times(times)).submit(any(Runnable.class));
    }

    @Test
    void aDisconnectedClientIsReconnectedAtOnceTheFirstTime() {
        for (Fixture f : fixtures(true)) {
            housekeeping(f);

            verifyReconnects(f, 1);
        }
    }

    @Test
    void theNextAttemptIsHeldBackByTheBackoff() {
        for (Fixture f : fixtures(true)) {
            housekeeping(f);
            housekeeping(f); // the next cycle, 30 seconds later in production, is inside the 10 second step: not here

            verifyReconnects(f, 1);
        }
    }

    @Test
    void anAttemptWhoseBackoffElapsedReconnectsAgain() {
        for (Fixture f : fixtures(true)) {
            f.client().reconnectBackoff.next(1L); // an attempt a long time ago: its delay is over
            housekeeping(f);

            verifyReconnects(f, 1);
        }
    }

    @Test
    void aClientThatIsConnectedIsLeftAlone() {
        for (Fixture f : fixtures(true)) {
            f.state().accept(MqttClientState.CONNECTED);

            housekeeping(f);

            verifyReconnects(f, 0);
        }
    }

    @Test
    void aClientThatReconnectsByItselfIsLeftAloneUntilItDroppedUnexpectedly() {
        for (Fixture f : fixtures(true)) {
            // after a connect: the HiveMQ client reconnects by itself, the housekeeping stays out of the way even
            // while it momentarily reports DISCONNECTED
            f.client().reconnectBackoff.holdWhileConnected();
            housekeeping(f);
            verifyReconnects(f, 0);

            // the disconnect listener found an unexpected drop: the next cycle reconnects without waiting
            f.client().reconnectBackoff.retryNow();
            housekeeping(f);
            verifyReconnects(f, 1);
        }
    }

    @Test
    void aConnectorThatShouldNotConnectIsNotReconnected() {
        for (Fixture f : fixtures(false)) { // disabled, or an invalid configuration
            housekeeping(f);

            verifyReconnects(f, 0);
        }
    }

    @Test
    void aConnectorWithoutAClientIsNotReconnectedByTheHousekeeping() {
        for (Fixture f : fixtures(true)) {
            ReflectionTestUtils.setField(f.client(), "mqttClient", null);

            housekeeping(f);

            verify(f.pool(), never()).submit(any(Runnable.class));
        }
    }
}
