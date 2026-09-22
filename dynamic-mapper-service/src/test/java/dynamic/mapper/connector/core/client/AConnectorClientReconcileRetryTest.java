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
 */

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
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

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.status.ConnectorStatusEvent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.mapping.MappingService;

/**
 * Unit tests for {@link AConnectorClient#reconcileSubscriptions()}'s deferred-retry path.
 *
 * <p>Before this fix, a {@code reconcileSubscriptions()} call that arrived while the connector
 * wasn't yet connected (e.g. {@code RELOAD_MAPPINGS} or a deployment change racing a just-started
 * service still completing its initial broker handshake) was silently dropped with only a
 * debug-level log line — nothing else re-triggered it, so the mapping stayed un-subscribed
 * until a full connector reconnect. See
 * {@code resources/testing/environments/sparkplug/README.md} for the scenario that surfaced
 * this (a fresh local mapper + immediate deploy/reload against a Sparkplug test connector).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AConnectorClientReconcileRetryTest {

    private static final String TENANT = "test-tenant";
    private static final String CONNECTOR_NAME = "test-connector";
    private static final String CONNECTOR_IDENTIFIER = "conn_1";

    /** Minimal concrete subclass, same pattern as AConnectorClientSubscriptionInitRetryTest. */
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
            return Boolean.TRUE;
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

        connector.initializeManagers();
    }

    @AfterEach
    void tearDown() {
        connector.stopHousekeepingAndClose();
    }

    @Test
    void notConnected_deferersInsteadOfDropping_andSchedulesExactlyOneRetry() throws Exception {
        connector.getConnectionStateManager().setConnected(false);

        connector.reconcileSubscriptions();

        // No immediate reconcile attempt while disconnected...
        verify(mappingService, never()).getCacheInboundMappings(TENANT);
        // ...but a retry must be queued rather than the call being silently dropped.
        assertEquals(1, housekeepingQueueSize(connector), "exactly one retry task must be scheduled");
        assertTrue(retryScheduledFlag(connector));
    }

    @Test
    void secondCallWhileRetryPending_doesNotStackAnotherRetry() throws Exception {
        connector.getConnectionStateManager().setConnected(false);

        connector.reconcileSubscriptions();
        connector.reconcileSubscriptions();

        assertEquals(1, housekeepingQueueSize(connector), "must not stack overlapping retry chains");
    }

    @Test
    void whenConnectedByRetryTime_reconcileRunsAndClearsFlag() throws Exception {
        connector.getConnectionStateManager().setConnected(false);
        connector.reconcileSubscriptions();
        assertEquals(1, housekeepingQueueSize(connector));

        // Simulate the connector finishing its handshake before the retry fires.
        connector.getConnectionStateManager().setConnected(true);
        runPendingHousekeepingTask(connector);

        verify(mappingService, times(1)).getCacheInboundMappings(TENANT);
        verify(mappingService, times(1)).getCacheOutboundMappings(TENANT);
        assertFalse(retryScheduledFlag(connector), "retry-in-flight flag must reset once the reconcile actually runs");
    }

    @Test
    void connected_reconcilesImmediately_noRetryScheduled() throws Exception {
        connector.getConnectionStateManager().setConnected(true);

        connector.reconcileSubscriptions();

        verify(mappingService, times(1)).getCacheInboundMappings(TENANT);
        assertEquals(0, housekeepingQueueSize(connector));
        assertFalse(retryScheduledFlag(connector));
    }

    @Test
    void stillDisconnectedAtRetryTime_reschedulesAgainRatherThanGivingUp() throws Exception {
        connector.getConnectionStateManager().setConnected(false);
        connector.reconcileSubscriptions();

        runPendingHousekeepingTask(connector);

        verify(mappingService, never()).getCacheInboundMappings(TENANT);
        assertEquals(1, housekeepingQueueSize(connector), "must reschedule again, not give up");
        assertTrue(retryScheduledFlag(connector));
    }

    // ── reflection helpers ───────────────────────────────────────────────────────────────

    private static boolean retryScheduledFlag(AConnectorClient connector) throws Exception {
        Field f = AConnectorClient.class.getDeclaredField("reconcileRetryScheduled");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(connector)).get();
    }

    private static int housekeepingQueueSize(AConnectorClient connector) throws Exception {
        Field f = AConnectorClient.class.getDeclaredField("housekeepingExecutor");
        f.setAccessible(true);
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) f.get(connector);
        return executor.getQueue().size();
    }

    /**
     * Runs the single queued housekeeping task synchronously, as if its delay had elapsed.
     * {@code poll()} on a {@link ScheduledThreadPoolExecutor}'s delay-ordered queue returns
     * {@code null} until the task's delay actually expires, so this uses {@code peek()}
     * (which doesn't check delay) to fetch it, runs it directly on the test thread, then
     * removes it so it isn't also picked up later by the executor's own worker thread.
     */
    private static void runPendingHousekeepingTask(AConnectorClient connector) throws Exception {
        Field f = AConnectorClient.class.getDeclaredField("housekeepingExecutor");
        f.setAccessible(true);
        ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) f.get(connector);
        Runnable task = executor.getQueue().peek();
        assertNotNull(task, "expected a pending retry task");
        executor.getQueue().remove(task);
        task.run();
    }
}
