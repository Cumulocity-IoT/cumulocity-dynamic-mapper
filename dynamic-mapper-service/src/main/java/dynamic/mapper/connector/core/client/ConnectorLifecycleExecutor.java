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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;

import dynamic.mapper.model.status.ConnectorStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the initialize, connect, disconnect and reconnect tasks of one connector client, extracted out of
 * {@link AConnectorClient} so the concurrency rules can be tested without a full connector subclass.
 * <p>
 * Rules this class enforces:
 * <ul>
 * <li><b>Single flight:</b> each of the four tasks is deduplicated. A call made while the previous task of
 * the same kind is still running returns that task's future instead of starting a second one.</li>
 * <li><b>Mutual exclusion:</b> the bodies of {@code connect()} and {@code disconnect()} never run at the
 * same time. A {@link ReentrantLock} is used, not {@code synchronized}, because the lock is held across
 * blocking network I/O and {@code synchronized} would pin the virtual thread's carrier thread.</li>
 * <li><b>Disconnect cancels connect:</b> a disconnect request cancels a connect task that is still
 * running.</li>
 * <li><b>Status reporting:</b> every transition and every failure is reported to the
 * {@link ConnectionStateManager}.</li>
 * </ul>
 * One instance belongs to one connector client.
 */
@Slf4j
final class ConnectorLifecycleExecutor {

    private final AConnectorClient client;

    // Guards the "check if done, else create" logic below: the submit methods are called from several
    // unsynchronized call sites (scheduled health checks, user operations, connection-lost callbacks).
    private final Object lifecycleLock = new Object();
    private final ReentrantLock connectDisconnectExecutionLock = new ReentrantLock();

    private volatile CompletableFuture<Void> initializeTask;
    private volatile CompletableFuture<Void> connectTask;
    private volatile CompletableFuture<Void> disconnectTask;
    private volatile CompletableFuture<Void> reconnectTask;

    /**
     * The client's fields are read when a task runs, not here, so this can be created before the
     * client's constructor has assigned them.
     */
    ConnectorLifecycleExecutor(AConnectorClient client) {
        this.client = client;
    }

    /** Runs {@code action} while holding the lock that excludes connect and disconnect bodies. */
    void runExclusive(Runnable action) {
        connectDisconnectExecutionLock.lock();
        try {
            action.run();
        } finally {
            connectDisconnectExecutionLock.unlock();
        }
    }

    CompletableFuture<Void> submitInitialize() {
        synchronized (lifecycleLock) {
            if (initializeTask == null || initializeTask.isDone()) {
                log.debug("{} - Initializing connector: {}", client.getTenant(), client.getConnectorName());
                initializeTask = CompletableFuture.runAsync(this::runInitialize, client.getVirtualThreadPool());
            }
            return initializeTask;
        }
    }

    private void runInitialize() {
        try {
            if (!client.initialize()) {
                throw new ConnectorException("Initialization failed");
            }
            if (client.isConfigValid(client.getConnectorConfiguration())) {
                client.getConnectionStateManager().updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            log.debug("{} - Connector initialized successfully", client.getTenant());
        } catch (Exception e) {
            log.error("{} - Initialization failed: {}", client.getTenant(), e.getMessage(), e);
            client.getConnectionStateManager().updateStatusWithError(e);
            throw new CompletionException("Initialization failed for connector " + client.getConnectorName(), e);
        }
    }

    CompletableFuture<Void> submitConnect() {
        client.loadConfiguration();

        synchronized (lifecycleLock) {
            if (connectTask == null || connectTask.isDone()) {
                log.debug("{} - Connecting connector: {}", client.getTenant(), client.getConnectorName());
                connectTask = CompletableFuture.runAsync(this::runConnect, client.getVirtualThreadPool());
            }
            return connectTask;
        }
    }

    private void runConnect() {
        connectDisconnectExecutionLock.lock();
        try {
            client.getConnectionStateManager().updateStatus(ConnectorStatus.CONNECTING, true, true);
            client.connect();
            log.debug("{} - Connector connected successfully", client.getTenant());
        } catch (Exception e) {
            log.error("{} - Connection failed: {}", client.getTenant(), e.getMessage(), e);
            client.getConnectionStateManager().updateStatusWithError(e);
            throw new CompletionException("Connection failed for connector " + client.getConnectorName(), e);
        } finally {
            connectDisconnectExecutionLock.unlock();
        }
    }

    CompletableFuture<Void> submitDisconnect() {
        synchronized (lifecycleLock) {
            if (connectTask != null && !connectTask.isDone()) {
                log.debug("{} - Cancelling ongoing connection", client.getTenant());
                connectTask.cancel(true);
            }

            if (disconnectTask == null || disconnectTask.isDone()) {
                log.debug("{} - Disconnecting connector: {}", client.getTenant(), client.getConnectorName());
                // DISCONNECTING is set synchronously, before the async task runs. Otherwise
                // stopHousekeepingAndClose() -> close() -> disconnect() could finish synchronously
                // (setting DISCONNECTED) before the task starts, and the task would then overwrite
                // DISCONNECTED with DISCONNECTING.
                client.getConnectionStateManager().updateStatus(ConnectorStatus.DISCONNECTING, true, true);
                disconnectTask = CompletableFuture.runAsync(this::runDisconnect, client.getVirtualThreadPool());
            }
            return disconnectTask;
        }
    }

    private void runDisconnect() {
        connectDisconnectExecutionLock.lock();
        try {
            client.disconnect();
            client.getConnectionStateManager().setConnected(false);
            log.debug("{} - Connector disconnected successfully", client.getTenant());
        } catch (Exception e) {
            log.error("{} - Disconnection failed: {}", client.getTenant(), e.getMessage(), e);
            client.getConnectionStateManager().updateStatusWithError(e);
            throw new CompletionException("Disconnection failed for connector " + client.getConnectorName(), e);
        } finally {
            connectDisconnectExecutionLock.unlock();
        }
    }

    /**
     * Disconnect, initialize, connect in sequence. Single flight: a call made while a reconnect is
     * running joins that reconnect. Never returns {@code null}.
     */
    CompletableFuture<Void> reconnect() {
        synchronized (lifecycleLock) {
            if (reconnectTask != null && !reconnectTask.isDone()) {
                log.debug("{} - Reconnection already in progress for {}, joining existing attempt",
                        client.getTenant(), client.getConnectorName());
                return reconnectTask;
            }
            log.info("{} - Starting reconnection sequence for {}", client.getTenant(), client.getConnectorName());
            reconnectTask = CompletableFuture.runAsync(this::runReconnect, client.getVirtualThreadPool());
            return reconnectTask;
        }
    }

    private void runReconnect() {
        try {
            submitDisconnect().get();
            submitInitialize().get();
            submitConnect().get();
            log.info("{} - Reconnection completed successfully for {}", client.getTenant(), client.getConnectorName());
        } catch (CancellationException e) {
            // A newer request cancelled a step: expected when a CONNECT operation arrives while a
            // previous reconnect is still in flight.
            log.debug("{} - Reconnection for {} was superseded by a newer reconnect request",
                    client.getTenant(), client.getConnectorName());
        } catch (Exception e) {
            log.error("{} - Reconnection failed for {}: {}", client.getTenant(), client.getConnectorName(),
                    e.getMessage(), e);
            client.getConnectionStateManager().updateStatusWithError(e);
            throw new CompletionException(e);
        }
    }
}
