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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * Tests the task rules of {@link ConnectorLifecycleExecutor} against a scripted connector: single flight, connect/disconnect mutual exclusion, cancellation of
 * a running connect by a disconnect, the order of the status updates, failure reporting, and the
 * reconnect sequence.
 */
@Timeout(30)
class ConnectorLifecycleExecutorTest {

    private static final long WAIT_SECONDS = 5;

    /** Scripted connector. Each behavior can be replaced per test. */
    static class FakeHost extends AConnectorClient {
        final ConnectionStateManager state = mock(ConnectionStateManager.class);
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger loadConfigurationCalls = new AtomicInteger();
        final AtomicInteger initializeCalls = new AtomicInteger();
        final AtomicInteger connectCalls = new AtomicInteger();
        final AtomicInteger disconnectCalls = new AtomicInteger();

        volatile boolean initializeResult = true;
        volatile boolean configValid = true;
        volatile Runnable onInitialize = () -> {};
        volatile Runnable onConnect = () -> {};
        volatile Runnable onDisconnect = () -> {};

        FakeHost(ExecutorService pool) {
            this.tenant = "t1";
            this.connectorName = "c1";
            this.virtualThreadPool = pool;
            this.connectionStateManager = state;
        }

        @Override
        protected void loadConfiguration() {
            loadConfigurationCalls.incrementAndGet();
            calls.add("loadConfiguration");
        }

        @Override
        public boolean initialize() {
            initializeCalls.incrementAndGet();
            calls.add("initialize");
            onInitialize.run();
            return initializeResult;
        }

        @Override
        public boolean isConfigValid(ConnectorConfiguration configuration) {
            return configValid;
        }

        @Override
        public void connect() {
            connectCalls.incrementAndGet();
            calls.add("connect");
            onConnect.run();
        }

        @Override
        public void disconnect() {
            disconnectCalls.incrementAndGet();
            calls.add("disconnect");
            onDisconnect.run();
        }

        @Override
        public void publishMEAO(ProcessingContext<?> context) {
        }

        @Override
        protected void subscribe(String topic, Qos qos) {
        }

        @Override
        protected void unsubscribe(String topic) {
        }

        @Override
        protected void connectorSpecificHousekeeping(String tenant) {
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

    private ExecutorService pool;
    private FakeHost host;
    private ConnectorLifecycleExecutor lifecycle;

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        host = new FakeHost(pool);
        lifecycle = new ConnectorLifecycleExecutor(host);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "timed out waiting for " + what);
    }

    private static Runnable blockUntil(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // a cancelled connect keeps running: CompletableFuture.cancel does not interrupt
            }
        };
    }

    /** The CompletionException a failed task completes with (join, unlike get, keeps its message). */
    private static CompletionException failureOf(CompletableFuture<Void> future) {
        return assertThrows(CompletionException.class, future::join);
    }

    // ── initialize ───────────────────────────────────────────────────────

    @Test
    void initialize_success_setsConfigured() throws Exception {
        lifecycle.submitInitialize().get(WAIT_SECONDS, TimeUnit.SECONDS);

        verify(host.state).updateStatus(ConnectorStatus.CONFIGURED, true, true);
        verify(host.state, never()).updateStatusWithError(any());
    }

    @Test
    void initialize_invalidConfiguration_doesNotSetConfigured() throws Exception {
        host.configValid = false;

        lifecycle.submitInitialize().get(WAIT_SECONDS, TimeUnit.SECONDS);

        verify(host.state, never()).updateStatus(eq(ConnectorStatus.CONFIGURED), any(Boolean.class),
                any(Boolean.class));
    }

    @Test
    void initialize_returnsFalse_reportsErrorAndFails() {
        host.initializeResult = false;

        CompletableFuture<Void> future = lifecycle.submitInitialize();

        CompletionException failure = failureOf(future);
        assertTrue(failure.getMessage().contains("c1"), "message names the connector: " + failure.getMessage());
        assertInstanceOf(ConnectorException.class, failure.getCause());
        verify(host.state).updateStatusWithError(any(ConnectorException.class));
        verify(host.state, never()).updateStatus(eq(ConnectorStatus.CONFIGURED), any(Boolean.class),
                any(Boolean.class));
    }

    @Test
    void initialize_throws_reportsErrorAndFails() {
        host.onInitialize = () -> { throw new IllegalStateException("boom"); };

        CompletionException failure = failureOf(lifecycle.submitInitialize());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        verify(host.state).updateStatusWithError(any(IllegalStateException.class));
    }

    @Test
    void initialize_isSingleFlightWhileRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onInitialize = blockUntil(entered, release);

        CompletableFuture<Void> first = lifecycle.submitInitialize();
        await(entered, "initialize to start");
        CompletableFuture<Void> second = lifecycle.submitInitialize();

        assertSame(first, second);
        release.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, host.initializeCalls.get());
    }

    @Test
    void initialize_afterCompletion_startsNewTask() throws Exception {
        CompletableFuture<Void> first = lifecycle.submitInitialize();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Void> second = lifecycle.submitInitialize();
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertNotSame(first, second);
        assertEquals(2, host.initializeCalls.get());
    }

    @Test
    void initialize_afterFailure_startsNewTask() throws Exception {
        host.initializeResult = false;
        failureOf(lifecycle.submitInitialize());

        host.initializeResult = true;
        lifecycle.submitInitialize().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, host.initializeCalls.get());
    }

    // ── connect ──────────────────────────────────────────────────────────

    @Test
    void connect_reportsConnectingBeforeCallingConnect() throws Exception {
        lifecycle.submitConnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        InOrder order = inOrder(host.state);
        order.verify(host.state).updateStatus(ConnectorStatus.CONNECTING, true, true);
        assertEquals(1, host.connectCalls.get());
    }

    @Test
    void connect_reloadsConfigurationBeforeTheTask() throws Exception {
        lifecycle.submitConnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of("loadConfiguration", "connect"), host.calls);
    }

    @Test
    void connect_failure_reportsErrorAndFails() {
        host.onConnect = () -> { throw new IllegalStateException("refused"); };

        CompletionException failure = failureOf(lifecycle.submitConnect());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("c1"));
        verify(host.state).updateStatusWithError(any(IllegalStateException.class));
    }

    @Test
    void connect_isSingleFlight_butStillReloadsConfigurationEachCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onConnect = blockUntil(entered, release);

        CompletableFuture<Void> first = lifecycle.submitConnect();
        await(entered, "connect to start");
        CompletableFuture<Void> second = lifecycle.submitConnect();

        assertSame(first, second);
        release.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, host.connectCalls.get());
        assertEquals(2, host.loadConfigurationCalls.get());
    }

    @Test
    void connect_afterFailure_canBeRetried() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        host.onConnect = () -> {
            if (fail.get()) {
                throw new IllegalStateException("first attempt fails");
            }
        };
        failureOf(lifecycle.submitConnect());

        fail.set(false);
        lifecycle.submitConnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, host.connectCalls.get());
        verify(host.state, times(1)).updateStatusWithError(any());
    }

    // ── disconnect ───────────────────────────────────────────────────────

    @Test
    void disconnect_setsDisconnectingSynchronously_beforeTheTaskRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onDisconnect = blockUntil(entered, release);

        CompletableFuture<Void> future = lifecycle.submitDisconnect();

        // The status is already set when submitDisconnect returns, although the body is still blocked.
        verify(host.state).updateStatus(ConnectorStatus.DISCONNECTING, true, true);
        await(entered, "disconnect to start");
        verify(host.state, never()).setConnected(false);
        release.countDown();
        future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        verify(host.state).setConnected(false);
    }

    @Test
    void disconnect_marksNotConnectedAfterDisconnectReturns() throws Exception {
        lifecycle.submitDisconnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        InOrder order = inOrder(host.state);
        order.verify(host.state).updateStatus(ConnectorStatus.DISCONNECTING, true, true);
        order.verify(host.state).setConnected(false);
        verify(host.state, never()).updateStatusWithError(any());
    }

    @Test
    void disconnect_failure_reportsErrorAndDoesNotMarkNotConnected() {
        host.onDisconnect = () -> { throw new IllegalStateException("broker hangs"); };

        CompletionException failure = failureOf(lifecycle.submitDisconnect());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("c1"));
        verify(host.state).updateStatusWithError(any(IllegalStateException.class));
        verify(host.state, never()).setConnected(false);
    }

    @Test
    void disconnect_isSingleFlightWhileRunning_andReportsDisconnectingOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onDisconnect = blockUntil(entered, release);

        CompletableFuture<Void> first = lifecycle.submitDisconnect();
        await(entered, "disconnect to start");
        CompletableFuture<Void> second = lifecycle.submitDisconnect();

        assertSame(first, second);
        release.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, host.disconnectCalls.get());
        verify(host.state, times(1)).updateStatus(ConnectorStatus.DISCONNECTING, true, true);
    }

    @Test
    void disconnect_cancelsARunningConnectTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onConnect = blockUntil(entered, release);
        CompletableFuture<Void> connect = lifecycle.submitConnect();
        await(entered, "connect to start");

        CompletableFuture<Void> disconnect = lifecycle.submitDisconnect();

        assertTrue(connect.isCancelled(), "a disconnect request cancels the running connect");
        release.countDown();
        disconnect.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void disconnect_doesNotCancelAFinishedConnectTask() throws Exception {
        CompletableFuture<Void> connect = lifecycle.submitConnect();
        connect.get(WAIT_SECONDS, TimeUnit.SECONDS);

        lifecycle.submitDisconnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertFalse(connect.isCancelled());
    }

    // ── mutual exclusion of connect and disconnect bodies ────────────────

    @Test
    void disconnectBody_waitsUntilARunningConnectBodyFinishes() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        host.onConnect = blockUntil(connectEntered, releaseConnect);
        lifecycle.submitConnect();
        await(connectEntered, "connect to start");

        CompletableFuture<Void> disconnect = lifecycle.submitDisconnect();

        // Give the disconnect task every chance to run; it must stay blocked on the execution lock.
        Thread.sleep(300);
        assertEquals(0, host.disconnectCalls.get(), "disconnect body must not overlap connect body");
        assertFalse(disconnect.isDone());

        releaseConnect.countDown();
        disconnect.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of("loadConfiguration", "connect", "disconnect"), host.calls);
    }

    @Test
    void connectBody_waitsUntilARunningDisconnectBodyFinishes() throws Exception {
        CountDownLatch disconnectEntered = new CountDownLatch(1);
        CountDownLatch releaseDisconnect = new CountDownLatch(1);
        host.onDisconnect = blockUntil(disconnectEntered, releaseDisconnect);
        lifecycle.submitDisconnect();
        await(disconnectEntered, "disconnect to start");

        CompletableFuture<Void> connect = lifecycle.submitConnect();

        Thread.sleep(300);
        assertEquals(0, host.connectCalls.get(), "connect body must not overlap disconnect body");
        assertFalse(connect.isDone());

        releaseDisconnect.countDown();
        connect.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, host.connectCalls.get());
    }

    @Test
    void runExclusive_blocksWhileAConnectBodyRuns_andRunsAfter() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        host.onConnect = blockUntil(connectEntered, releaseConnect);
        lifecycle.submitConnect();
        await(connectEntered, "connect to start");

        AtomicBoolean ran = new AtomicBoolean();
        CompletableFuture<Void> exclusive = CompletableFuture.runAsync(
                () -> lifecycle.runExclusive(() -> ran.set(true)), pool);

        Thread.sleep(300);
        assertFalse(ran.get(), "runExclusive must wait for the connect body");

        releaseConnect.countDown();
        exclusive.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ran.get());
    }

    @Test
    void runExclusive_releasesTheLockWhenTheActionThrows() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> lifecycle.runExclusive(() -> { throw new IllegalStateException("x"); }));

        // If the lock leaked, this connect would hang until the test timeout.
        lifecycle.submitConnect().get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void connectFailure_releasesTheExecutionLock() throws Exception {
        host.onConnect = () -> { throw new IllegalStateException("fail"); };
        failureOf(lifecycle.submitConnect());
        host.onConnect = () -> {};

        lifecycle.submitDisconnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, host.disconnectCalls.get());
    }

    @Test
    void disconnectFailure_releasesTheExecutionLock() throws Exception {
        host.onDisconnect = () -> { throw new IllegalStateException("fail"); };
        failureOf(lifecycle.submitDisconnect());
        host.onDisconnect = () -> {};

        lifecycle.submitConnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, host.connectCalls.get());
    }

    // ── reconnect ────────────────────────────────────────────────────────

    @Test
    void reconnect_runsDisconnectInitializeConnectInOrder() throws Exception {
        lifecycle.reconnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of("disconnect", "initialize", "loadConfiguration", "connect"), host.calls);
        verify(host.state, never()).updateStatusWithError(any());
    }

    @Test
    void reconnect_isSingleFlightWhileRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onInitialize = blockUntil(entered, release);

        CompletableFuture<Void> first = lifecycle.reconnect();
        await(entered, "reconnect to reach initialize");
        CompletableFuture<Void> second = lifecycle.reconnect();

        assertSame(first, second);
        release.countDown();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, host.disconnectCalls.get());
        assertEquals(1, host.initializeCalls.get());
        assertEquals(1, host.connectCalls.get());
    }

    @Test
    void reconnect_afterCompletion_startsNewSequence() throws Exception {
        CompletableFuture<Void> first = lifecycle.reconnect();
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Void> second = lifecycle.reconnect();
        second.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertNotSame(first, second);
        assertEquals(2, host.connectCalls.get());
    }

    @Test
    void reconnect_disconnectFailure_stopsSequenceAndReportsError() {
        host.onDisconnect = () -> { throw new IllegalStateException("cannot disconnect"); };

        CompletionException failure = failureOf(lifecycle.reconnect());

        assertEquals(0, host.initializeCalls.get(), "initialize must not run after a failed disconnect");
        assertEquals(0, host.connectCalls.get());
        // once by the failed disconnect task, once by the reconnect wrapper
        verify(host.state, times(2)).updateStatusWithError(any());
    }

    @Test
    void reconnect_initializeFailure_doesNotConnect() {
        host.initializeResult = false;

        CompletionException failure = failureOf(lifecycle.reconnect());

        assertEquals(0, host.connectCalls.get(), "connect must not run after a failed initialize");
        verify(host.state, never()).updateStatus(ConnectorStatus.CONNECTING, true, true);
    }

    @Test
    void reconnect_connectFailure_reportsErrorAndFails() {
        host.onConnect = () -> { throw new IllegalStateException("refused"); };

        CompletionException failure = failureOf(lifecycle.reconnect());

        verify(host.state, times(2)).updateStatusWithError(any());
    }

    @Test
    void reconnect_failedSequence_canBeRetried() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        host.onConnect = () -> {
            if (fail.get()) {
                throw new IllegalStateException("down");
            }
        };
        failureOf(lifecycle.reconnect());

        fail.set(false);
        lifecycle.reconnect().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, host.connectCalls.get());
    }

    @Test
    void reconnect_connectCancelledByDisconnect_endsQuietlyWithoutErrorStatus() throws Exception {
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        host.onConnect = blockUntil(connectEntered, releaseConnect);

        CompletableFuture<Void> reconnect = lifecycle.reconnect();
        await(connectEntered, "reconnect to reach connect");

        // A newer request disconnects while the reconnect's connect step is running: that cancels it.
        CompletableFuture<Void> disconnect = lifecycle.submitDisconnect();
        releaseConnect.countDown();

        reconnect.get(WAIT_SECONDS, TimeUnit.SECONDS);
        disconnect.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(reconnect.isCompletedExceptionally(), "a superseded reconnect is not a failure");
        verify(host.state, never()).updateStatusWithError(any());
    }

    @Test
    void cancelledConnectFuture_isReportedAsCancellationToCallers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        host.onConnect = blockUntil(entered, release);
        CompletableFuture<Void> connect = lifecycle.submitConnect();
        await(entered, "connect to start");

        lifecycle.submitDisconnect();
        release.countDown();

        assertThrows(CancellationException.class, () -> connect.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }
}
