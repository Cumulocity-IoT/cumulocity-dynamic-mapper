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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.util.CumulocityErrors;
import lombok.extern.slf4j.Slf4j;

/**
 * Retries the subscription work of a connector that failed for a transient reason, on the connector's
 * housekeeping executor.
 * <p>
 * Two independent retry chains exist, each allowed at most once at a time:
 * <ul>
 * <li><b>Initialization after connect:</b> when the platform answers with a transient error (for example a
 * 502, 503, or 504 during a rollout), the connector is marked {@link ConnectorStatus#RETRYING} and the
 * initialization is retried with a doubling delay, capped at {@value #INIT_RETRY_MAX_DELAY_SECONDS}
 * seconds. The chain ends on success, on a non-retryable error, or when the connector disconnects.</li>
 * <li><b>Reconcile while not connected:</b> a reconcile that arrives before the connection is up is
 * retried once after {@value #RECONCILE_RETRY_DELAY_SECONDS} seconds, so a deployment change is not lost.</li>
 * </ul>
 * Pending retries are cancelled when the connector disconnects, because the executor is shut down then.
 * One instance belongs to one connector client.
 */
@Slf4j
final class SubscriptionRetryScheduler {

    static final long INIT_RETRY_INITIAL_DELAY_SECONDS = 10L;
    static final long INIT_RETRY_MAX_DELAY_SECONDS = 300L;
    static final long RECONCILE_RETRY_DELAY_SECONDS = 5L;

    private final String tenant;
    private final String connectorName;
    private final ConnectionStateManager connectionStateManager;
    private final Supplier<? extends ScheduledExecutorService> executor;
    private final BooleanSupplier connected;
    private final Runnable initializeSubscriptions;
    private final Runnable reconcileSubscriptions;

    // Guards against overlapping retry chains
    private final AtomicBoolean initRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean reconcileRetryScheduled = new AtomicBoolean(false);
    // When the current init retry chain started, so the success log can report how long the connector
    // spent in RETRYING before recovering.
    private volatile long initRetryStartedAtMs;

    SubscriptionRetryScheduler(String tenant, String connectorName, ConnectionStateManager connectionStateManager,
            Supplier<? extends ScheduledExecutorService> executor, BooleanSupplier connected,
            Runnable initializeSubscriptions, Runnable reconcileSubscriptions) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.connectionStateManager = connectionStateManager;
        this.executor = executor;
        this.connected = connected;
        this.initializeSubscriptions = initializeSubscriptions;
        this.reconcileSubscriptions = reconcileSubscriptions;
    }

    /** True if the error (or one of its causes) is a platform error known to be transient. */
    static boolean isRetryable(Throwable t) {
        return CumulocityErrors.isTransientPlatformError(t);
    }

    /** Resets the guards, so a reused instance is not stuck with a flag left at {@code true}. */
    void reset() {
        initRetryScheduled.set(false);
    }

    /**
     * Marks the connector {@link ConnectorStatus#RETRYING} and schedules the first retry of the
     * subscription initialization. Does nothing if a chain is already running.
     */
    void scheduleInitRetry(long delaySeconds, Exception lastError) {
        if (!initRetryScheduled.compareAndSet(false, true)) {
            // A retry chain is already in flight for this connector; let it continue.
            return;
        }
        initRetryStartedAtMs = System.currentTimeMillis();
        connectionStateManager.updateStatusRetrying(lastError, delaySeconds);
        if (!scheduleOnExecutor(() -> runInitRetry(delaySeconds), delaySeconds)) {
            initRetryScheduled.set(false);
        }
    }

    void runInitRetry(long previousDelaySeconds) {
        if (!connected.getAsBoolean()) {
            log.debug("{} - Connector {} no longer connected, abandoning subscription-init retry",
                    tenant, connectorName);
            initRetryScheduled.set(false);
            return;
        }
        try {
            initializeSubscriptions.run();
            long elapsedSeconds = (System.currentTimeMillis() - initRetryStartedAtMs) / 1000;
            log.info("{} - Subscription initialization succeeded after retry for connector {} (after {}s)",
                    tenant, connectorName, elapsedSeconds);
            initRetryScheduled.set(false);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);
        } catch (Exception e) {
            if (!isRetryable(e)) {
                log.error("{} - Non-retryable error re-initializing subscriptions for connector {}: {}",
                        tenant, connectorName, e.getMessage(), e);
                initRetryScheduled.set(false);
                connectionStateManager.updateStatusWithError(e);
                return;
            }
            long nextDelaySeconds = Math.min(previousDelaySeconds * 2, INIT_RETRY_MAX_DELAY_SECONDS);
            log.warn("{} - Retry of subscription initialization failed for connector {}, next attempt in {}s: {}",
                    tenant, connectorName, nextDelaySeconds, e.getMessage());
            connectionStateManager.updateStatusRetrying(e, nextDelaySeconds);
            if (!scheduleOnExecutor(() -> runInitRetry(nextDelaySeconds), nextDelaySeconds)) {
                initRetryScheduled.set(false);
            }
        }
    }

    /**
     * Schedules a single retry of the reconcile. At most one retry is in flight; a reconcile that arrives
     * while one is pending relies on that retry re-checking the current state.
     */
    void scheduleReconcileRetry() {
        if (!reconcileRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = scheduleOnExecutor(() -> {
            reconcileRetryScheduled.set(false);
            reconcileSubscriptions.run();
        }, RECONCILE_RETRY_DELAY_SECONDS);
        if (!scheduled) {
            reconcileRetryScheduled.set(false);
        }
    }

    /** @return {@code false} if the executor is gone or shut down, so nothing was scheduled */
    private boolean scheduleOnExecutor(Runnable task, long delaySeconds) {
        ScheduledExecutorService service = executor.get();
        if (service == null || service.isShutdown()) {
            return false;
        }
        service.schedule(task, delaySeconds, TimeUnit.SECONDS);
        return true;
    }
}
