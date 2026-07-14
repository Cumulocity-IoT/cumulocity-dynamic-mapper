/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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
 */

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.sdk.client.SDKException;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.ConnectorStatusEvent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;

/**
 * Unit tests for the subscription-init retry/backoff path in
 * {@link AConnectorClient#initializeSubscriptionsAfterConnect()}, added to handle transient
 * Cumulocity backend outages (502/503/504 from the Inventory API during
 * {@code rebuildMappingCaches}) without failing the connector outright.
 *
 * <p>See {@code resources/script/backend/TEST-SETUP-C8Y-UNAVAILABLE.md} for the full background
 * and end-to-end verification plan this unit test complements (Path A).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AConnectorClientSubscriptionInitRetryTest {

    private static final String TENANT = "test-tenant";
    private static final String CONNECTOR_NAME = "test-connector";
    private static final String CONNECTOR_IDENTIFIER = "conn_1";

    /**
     * Minimal concrete subclass exposing only no-op implementations of the abstract methods,
     * following the same pattern as {@link ConnectorRetryReconnectTest.TestableConnector}.
     */
    static class TestableConnector extends AConnectorClient {

        @Override
        public boolean initialize() {
            return true;
        }

        @Override
        public void connect() {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public boolean isConfigValid(ConnectorConfiguration configuration) {
            return true;
        }

        @Override
        public void publishMEAO(ProcessingContext<?> context) {
            // no-op
        }

        @Override
        protected void subscribe(String topic, Qos qos) {
            // no-op
        }

        @Override
        protected void unsubscribe(String topic) {
            // no-op
        }

        @Override
        protected void connectorSpecificHousekeeping(String tenant) {
            // no-op
        }

        @Override
        public Boolean supportsWildcardInTopic(Direction direction) {
            return Boolean.FALSE;
        }

        @Override
        public List<Direction> supportedDirections() {
            return List.of(Direction.INBOUND);
        }
    }

    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private MappingService mappingService;
    @Mock
    private ServiceConfiguration serviceConfiguration;

    private TestableConnector connector;
    private Map<String, ConnectorStatusEvent> statusMap;

    @BeforeEach
    void setUp() {
        statusMap = new HashMap<>();
        when(connectorRegistry.getConnectorStatusMap(TENANT)).thenReturn(statusMap);
        when(mappingService.getCacheOutboundMappings(TENANT)).thenReturn(new HashMap<>());
        when(mappingService.getCacheInboundMappings(TENANT)).thenReturn(new HashMap<>());
        when(serviceConfiguration.getSendConnectorLifecycle()).thenReturn(false);

        connector = new TestableConnector();
        connector.tenant = TENANT;
        connector.connectorName = CONNECTOR_NAME;
        connector.connectorIdentifier = CONNECTOR_IDENTIFIER;
        connector.connectorId = new ConnectorId(CONNECTOR_NAME, CONNECTOR_IDENTIFIER);
        connector.connectorRegistry = connectorRegistry;
        connector.mappingService = mappingService;
        connector.serviceConfiguration = serviceConfiguration;

        // Wires up connectionStateManager + housekeepingExecutor (AConnectorClient.java:508-535)
        connector.initializeManagers();
        connector.getConnectionStateManager().setConnected(true);
    }

    @AfterEach
    void tearDown() {
        connector.stopHousekeepingAndClose();
    }

    // ── retryable 502/503/504 on the initial call → RETRYING, retry scheduled ──────────

    @Test
    void retryable502_marksConnectorRetrying_andSchedulesRetry() throws Exception {
        doThrow(new SDKException(502, "Bad Gateway"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        assertDoesNotThrow(() -> connector.initializeSubscriptionsAfterConnect());

        assertEquals(ConnectorStatus.RETRYING, connector.getConnectionStateManager().getCurrentStatus());
        assertEquals(ConnectorStatus.RETRYING, statusMap.get(CONNECTOR_IDENTIFIER).getStatus());
        assertEquals(1, housekeepingQueueSize(connector), "exactly one retry task must be scheduled");
    }

    @Test
    void retryable503_marksConnectorRetrying() {
        doThrow(new SDKException(503, "Service Unavailable"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        assertDoesNotThrow(() -> connector.initializeSubscriptionsAfterConnect());

        assertEquals(ConnectorStatus.RETRYING, connector.getConnectionStateManager().getCurrentStatus());
    }

    @Test
    void retryable504_marksConnectorRetrying() {
        doThrow(new SDKException(504, "Gateway Timeout"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        assertDoesNotThrow(() -> connector.initializeSubscriptionsAfterConnect());

        assertEquals(ConnectorStatus.RETRYING, connector.getConnectionStateManager().getCurrentStatus());
    }

    @Test
    void wrappedSdkException_isStillDetectedAsRetryable() {
        RuntimeException wrapper = new RuntimeException("wrapped", new SDKException(502, "Bad Gateway"));
        doThrow(wrapper).when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        assertDoesNotThrow(() -> connector.initializeSubscriptionsAfterConnect());

        assertEquals(ConnectorStatus.RETRYING, connector.getConnectionStateManager().getCurrentStatus());
    }

    // ── non-retryable error on the initial call → propagates, no retry scheduled ───────

    @Test
    void nonRetryableError_onInitialCall_propagatesAndSchedulesNoRetry() throws Exception {
        doThrow(new SDKException(403, "Forbidden"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        assertThrows(SDKException.class, () -> connector.initializeSubscriptionsAfterConnect());

        assertEquals(0, housekeepingQueueSize(connector));
        // AConnectorClient itself doesn't set FAILED here — that's the caller's responsibility
        // (e.g. AMQTTClient#connect's catch block), so the status is left at CONNECTED.
        assertEquals(ConnectorStatus.CONNECTED, connector.getConnectionStateManager().getCurrentStatus());
    }

    // ── scheduled retry succeeds → back to CONNECTED ────────────────────────────────────

    @Test
    void retrySucceeds_marksConnectorConnectedAgain() throws Exception {
        doThrow(new SDKException(502, "Bad Gateway"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);
        connector.initializeSubscriptionsAfterConnect();
        assertEquals(ConnectorStatus.RETRYING, connector.getConnectionStateManager().getCurrentStatus());

        reset(mappingService);
        when(mappingService.getCacheOutboundMappings(TENANT)).thenReturn(new HashMap<>());
        when(mappingService.getCacheInboundMappings(TENANT)).thenReturn(new HashMap<>());
        doNothing().when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        invokeRunSubscriptionInitRetry(connector, 10L);

        assertEquals(ConnectorStatus.CONNECTED, connector.getConnectionStateManager().getCurrentStatus());
        assertFalse(retryScheduledFlag(connector), "retry-in-flight flag must reset after success");
    }

    // ── scheduled retry still fails retryably → backoff doubles, stays RETRYING ────────

    @Test
    void retryStillFailsRetryably_doublesBackoff_staysRetrying() throws Exception {
        doThrow(new SDKException(502, "Bad Gateway"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);
        connector.initializeSubscriptionsAfterConnect();

        invokeRunSubscriptionInitRetry(connector, 10L); // still fails -> next delay = 20s

        assertEquals(ConnectorStatus.RETRYING, connector.getConnectionStateManager().getCurrentStatus());
        assertTrue(statusMap.get(CONNECTOR_IDENTIFIER).getMessage().contains("Retrying in 20s"),
                "backoff delay must double from 10s to 20s");
    }

    @Test
    void backoff_capsAtConfiguredMaximum() throws Exception {
        doThrow(new SDKException(502, "Bad Gateway"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);
        connector.initializeSubscriptionsAfterConnect();

        invokeRunSubscriptionInitRetry(connector, 250L); // would double past the 300s cap

        assertTrue(statusMap.get(CONNECTOR_IDENTIFIER).getMessage().contains("Retrying in 300s"),
                "backoff must be capped at 300s");
    }

    // ── scheduled retry fails with a non-retryable error → gives up, connector FAILED ──

    @Test
    void retryFailsNonRetryably_marksConnectorFailed_andStopsRetryChain() throws Exception {
        doThrow(new SDKException(502, "Bad Gateway"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);
        connector.initializeSubscriptionsAfterConnect();

        reset(mappingService);
        doThrow(new SDKException(403, "Forbidden"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);

        invokeRunSubscriptionInitRetry(connector, 10L);

        assertEquals(ConnectorStatus.FAILED, connector.getConnectionStateManager().getCurrentStatus());
        assertFalse(retryScheduledFlag(connector), "retry-in-flight flag must reset after giving up");
    }

    // ── disconnect abandons a pending retry ─────────────────────────────────────────────

    @Test
    void disconnectedConnector_abandonsScheduledRetry() throws Exception {
        doThrow(new SDKException(502, "Bad Gateway"))
                .when(mappingService).rebuildMappingCaches(TENANT, connector.connectorId);
        connector.initializeSubscriptionsAfterConnect();

        connector.getConnectionStateManager().setConnected(false);
        invokeRunSubscriptionInitRetry(connector, 10L);

        // No further rebuildMappingCaches attempt, no status flip to CONNECTED/FAILED — the retry
        // just bails out because isConnected() is now false.
        verify(mappingService, times(1)).rebuildMappingCaches(TENANT, connector.connectorId);
        assertEquals(ConnectorStatus.DISCONNECTED, connector.getConnectionStateManager().getCurrentStatus());
    }

    // ── reflection helpers for the private retry-chain internals ───────────────────────

    private static void invokeRunSubscriptionInitRetry(AConnectorClient connector, long previousDelayMs)
            throws Exception {
        Method m = AConnectorClient.class.getDeclaredMethod("runSubscriptionInitRetry", long.class);
        m.setAccessible(true);
        m.invoke(connector, previousDelayMs);
    }

    private static boolean retryScheduledFlag(AConnectorClient connector) throws Exception {
        Field f = AConnectorClient.class.getDeclaredField("subscriptionInitRetryScheduled");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(connector)).get();
    }

    private static int housekeepingQueueSize(AConnectorClient connector) throws Exception {
        Field f = AConnectorClient.class.getDeclaredField("housekeepingExecutor");
        f.setAccessible(true);
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) f.get(connector);
        return executor.getQueue().size();
    }
}
