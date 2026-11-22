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

package dynamic.mapper.processor.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;

/**
 * A notification that arrives while the connector's broker connection is down must not be acknowledged, or the
 * message is lost. A connector that holds outbound messages while disconnected reports it as not deliverable
 * (retried, not counted toward the poison-pill limit); other connectors keep ignoring it.
 */
class OutboundMessageDispatcherConnectorDownTest {

    private static final String RAW = "ack-1\n/t1/managedobjects\nCREATE\n\n{}";
    private static final Logger LOG = LoggerFactory.getLogger(OutboundMessageDispatcherConnectorDownTest.class);

    private AConnectorClient connector;
    private OutboundMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getVirtualThreadPool()).thenReturn(mock(ExecutorService.class));
        connector = mock(AConnectorClient.class);
        when(connector.getTenant()).thenReturn("t1");
        when(connector.getConnectorName()).thenReturn("Local Kafka (test)");
        when(connector.getConnectorIdentifier()).thenReturn("kafka1");
        when(connector.getConnectorType()).thenReturn(ConnectorType.KAFKA);
        when(connector.hasOutboundExplorerListeners()).thenReturn(false);
        when(connector.isConnected()).thenReturn(false);
        when(connector.isReadyForOutbound()).thenReturn(false);
        dispatcher = new OutboundMessageDispatcher(registry, connector, mock(OutboundMessageRouter.class),
                mock(NotificationSubscriber.class));
    }

    @Test
    void aConnectorThatHoldsOutboundReportsTheMessageAsNotDeliverable() {
        when(connector.holdsOutboundWhileDisconnected()).thenReturn(true);

        ProcessingResultWrapper<?> result = dispatcher.onNotification(Notification.parse(RAW));

        assertNotNull(result.getProcessingResult(), "an empty result would be acknowledged and the message lost");
        assertEquals(AckOutcome.RETRY_CONNECTOR_UNAVAILABLE,
                ProcessingResultHelper.awaitOutcome(result, "t1", "MEASUREMENT#CREATE", LOG, null, true));
    }

    @Test
    void otherConnectorsKeepIgnoringTheMessage() {
        when(connector.holdsOutboundWhileDisconnected()).thenReturn(false);

        ProcessingResultWrapper<?> result = dispatcher.onNotification(Notification.parse(RAW));

        assertNull(result.getProcessingResult());
        assertEquals(AckOutcome.ACK,
                ProcessingResultHelper.awaitOutcome(result, "t1", "MEASUREMENT#CREATE", LOG, null, true));
    }

    @Test
    void theGuardDoesNotProbeTheBrokerForEveryMessage() {
        when(connector.holdsOutboundWhileDisconnected()).thenReturn(true);
        when(connector.isReadyForOutbound()).thenReturn(true);

        dispatcher.onNotification(Notification.parse(RAW));

        // isConnected() probes the broker for Kafka: the per-message guard must use the cheap check instead
        org.mockito.Mockito.verify(connector, org.mockito.Mockito.never()).isConnected();
    }
}
