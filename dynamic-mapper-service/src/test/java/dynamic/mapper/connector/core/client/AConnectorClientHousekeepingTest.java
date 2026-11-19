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

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.status.ConnectorStatus;

/**
 * Tests the housekeeping schedule of {@link AConnectorClient}: the timing handed to the executor, the
 * idempotence of {@code submitHousekeeping()}, and what one housekeeping cycle does. The 30 second interval
 * is not waited for: a recording executor captures the {@code scheduleAtFixedRate} arguments and the
 * scheduled cycle, which the tests then run directly.
 */
class AConnectorClientHousekeepingTest {

    /** One recorded scheduleAtFixedRate call. */
    record Schedule(long initialDelay, long period, TimeUnit unit) {
    }

    /** Executor that records what is scheduled on it. The real schedule still applies (it is far in the future). */
    static class RecordingExecutor extends ScheduledThreadPoolExecutor {
        final List<Schedule> schedules = Collections.synchronizedList(new ArrayList<>());
        final List<Runnable> cycles = Collections.synchronizedList(new ArrayList<>());

        RecordingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                TimeUnit unit) {
            schedules.add(new Schedule(initialDelay, period, unit));
            cycles.add(command);
            return super.scheduleAtFixedRate(command, initialDelay, period, unit);
        }
    }

    /** Connector that counts what a housekeeping cycle calls and can be told to fail. */
    static class CountingConnector extends AConnectorClientSubscriptionInitRetryTest.TestableConnector {
        final AtomicInteger specificHousekeeping = new AtomicInteger();
        final AtomicInteger monitored = new AtomicInteger();
        volatile boolean configValid = true;
        volatile RuntimeException failure;

        @Override
        public boolean isConfigValid(ConnectorConfiguration configuration) {
            return configValid;
        }

        @Override
        protected void connectorSpecificHousekeeping(String tenant) {
            specificHousekeeping.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void monitorSubscriptions() {
            monitored.incrementAndGet();
        }
    }

    private RecordingExecutor executor;
    private ConnectionStateManager state;
    private MappingService mappingService;
    private CountingConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        executor = new RecordingExecutor();
        state = mock(ConnectionStateManager.class);
        when(state.getCurrentStatus()).thenReturn(ConnectorStatus.CONNECTED);
        mappingService = mock(MappingService.class);

        connector = new CountingConnector();
        connector.tenant = "t1";
        connector.connectorName = "c1";
        connector.connectionStateManager = state;
        connector.mappingService = mappingService;
        Field f = AConnectorClient.class.getDeclaredField("housekeeping");
        f.setAccessible(true);
        f.set(connector, new ConnectorHousekeeping("t1", "c1", connector::performHousekeeping, executor));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ── schedule ─────────────────────────────────────────────────────────

    @Test
    void submitHousekeeping_schedulesAtFixedRate_withTheConfiguredIntervalAfterTheSameDelay() {
        connector.submitHousekeeping();

        assertEquals(1, executor.schedules.size());
        Schedule schedule = executor.schedules.get(0);
        assertEquals(30, AConnectorClient.HOUSEKEEPING_INTERVAL_SECONDS, "documented interval");
        assertEquals(AConnectorClient.HOUSEKEEPING_INTERVAL_SECONDS, schedule.initialDelay());
        assertEquals(AConnectorClient.HOUSEKEEPING_INTERVAL_SECONDS, schedule.period());
        assertEquals(TimeUnit.SECONDS, schedule.unit());
    }

    @Test
    void submitHousekeeping_doesNotRunACycleImmediately() {
        connector.submitHousekeeping();

        assertEquals(0, connector.specificHousekeeping.get(), "first cycle is one interval away");
    }

    @Test
    void submitHousekeeping_isIdempotent() {
        connector.submitHousekeeping();
        connector.submitHousekeeping();
        connector.submitHousekeeping();

        assertEquals(1, executor.schedules.size(), "a second call must not stack another schedule");
        assertEquals(1, executor.getQueue().size());
    }

    @Test
    void submitHousekeeping_afterShutdown_schedulesNothingAndDoesNotThrow() {
        executor.shutdownNow();

        assertDoesNotThrow(() -> connector.submitHousekeeping());

        assertTrue(executor.schedules.isEmpty());
    }

    @Test
    void submitHousekeeping_afterStopHousekeepingAndClose_isNoOp() {
        connector.submitHousekeeping();

        connector.stopHousekeepingAndClose();
        assertDoesNotThrow(() -> connector.submitHousekeeping());

        assertEquals(1, executor.schedules.size(), "no new schedule once the connector is stopped");
        assertTrue(executor.isShutdown());
    }

    @Test
    void stopHousekeepingAndClose_cancelsThePendingCycle() {
        connector.submitHousekeeping();
        assertEquals(1, executor.getQueue().size());

        connector.stopHousekeepingAndClose();

        assertTrue(executor.getQueue().isEmpty(), "the scheduled cycle is dropped on stop");
        assertEquals(0, connector.specificHousekeeping.get());
    }

    @Test
    void rescheduleIsPossibleAfterTheQueueDrained() {
        connector.submitHousekeeping();
        executor.getQueue().clear();

        connector.submitHousekeeping();

        assertEquals(2, executor.schedules.size());
    }

    // ── what one cycle does ──────────────────────────────────────────────

    @Test
    void cycle_runsSpecificHousekeepingThenCleansMappingsThenReportsStatusThenMonitors() {
        connector.submitHousekeeping();

        executor.cycles.get(0).run();

        assertEquals(1, connector.specificHousekeeping.get());
        InOrder order = inOrder(mappingService);
        order.verify(mappingService).cleanDirtyMappings("t1");
        order.verify(mappingService).sendMappingStatus("t1");
        assertEquals(1, connector.monitored.get());
    }

    @Test
    void cycle_movesUnknownToConfigured_whenConfigurationIsValid() {
        when(state.getCurrentStatus()).thenReturn(ConnectorStatus.UNKNOWN);
        connector.submitHousekeeping();

        executor.cycles.get(0).run();

        verify(state).updateStatus(ConnectorStatus.CONFIGURED, true, true);
    }

    @Test
    void cycle_movesDisconnectedToConfigured_whenConfigurationIsValid() {
        when(state.getCurrentStatus()).thenReturn(ConnectorStatus.DISCONNECTED);
        connector.submitHousekeeping();

        executor.cycles.get(0).run();

        verify(state).updateStatus(ConnectorStatus.CONFIGURED, true, true);
    }

    @Test
    void cycle_keepsStatus_whenConfigurationIsInvalid() {
        when(state.getCurrentStatus()).thenReturn(ConnectorStatus.UNKNOWN);
        connector.configValid = false;
        connector.submitHousekeeping();

        executor.cycles.get(0).run();

        verify(state, never()).updateStatus(any(), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void cycle_doesNotTouchStatus_forConnectedOrErrorStates() {
        for (ConnectorStatus status : List.of(ConnectorStatus.CONNECTED, ConnectorStatus.CONNECTING,
                ConnectorStatus.FAILED, ConnectorStatus.RETRYING)) {
            when(state.getCurrentStatus()).thenReturn(status);
            connector.performHousekeeping();
        }

        verify(state, never()).updateStatus(any(), any(Boolean.class), any(Boolean.class));
        assertEquals(4, connector.specificHousekeeping.get(), "the rest of the cycle still runs");
    }

    // ── failure handling ─────────────────────────────────────────────────

    @Test
    void cycle_swallowsAFailure_soTheScheduleKeepsRunning() {
        connector.failure = new IllegalStateException("broker unreachable");
        connector.submitHousekeeping();
        Runnable cycle = executor.cycles.get(0);

        assertDoesNotThrow(cycle::run);
        assertDoesNotThrow(cycle::run);

        // scheduleAtFixedRate cancels itself after an exception, so swallowing is what keeps it alive.
        assertEquals(2, connector.specificHousekeeping.get());
    }

    @Test
    void cycle_afterAFailure_recoversOnTheNextRun() {
        connector.failure = new IllegalStateException("transient");
        connector.submitHousekeeping();
        Runnable cycle = executor.cycles.get(0);
        cycle.run();
        verify(mappingService, never()).cleanDirtyMappings("t1");

        connector.failure = null;
        cycle.run();

        verify(mappingService).cleanDirtyMappings("t1");
        verify(mappingService).sendMappingStatus("t1");
    }

    @Test
    void cycle_failureDuringShutdown_isSwallowed() {
        connector.failure = new IllegalStateException("resources already gone");
        connector.submitHousekeeping();
        Runnable cycle = executor.cycles.get(0);
        executor.shutdownNow();

        assertDoesNotThrow(cycle::run);
    }

    @Test
    void cycle_mappingServiceFailure_isSwallowed() {
        org.mockito.Mockito.doThrow(new RejectedExecutionException("pool closed"))
                .when(mappingService).cleanDirtyMappings("t1");
        connector.submitHousekeeping();

        assertDoesNotThrow(executor.cycles.get(0)::run);

        verify(mappingService, never()).sendMappingStatus("t1");
        assertFalse(executor.isShutdown());
    }
}
