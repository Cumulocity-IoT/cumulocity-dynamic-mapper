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

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dynamic.mapper.util.TenantScheduledThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * The periodic housekeeping cycle of one connector client, on its own single-thread executor.
 * <p>
 * Rules:
 * <ul>
 * <li>{@link #start()} is idempotent: while a cycle is already scheduled, it does nothing.</li>
 * <li>A cycle that throws is logged and swallowed. {@code scheduleAtFixedRate} cancels a task that
 * throws, which would silently end housekeeping.</li>
 * <li>{@link #stop()} drops the schedule and waits for a cycle that is already running, so callers can
 * remove the tenant's shared resources right after it returns.</li>
 * </ul>
 * The same executor also runs the subscription retries of {@link SubscriptionRetryScheduler}; stopping
 * housekeeping cancels those too.
 */
@Slf4j
final class ConnectorHousekeeping {

    static final int INTERVAL_SECONDS = 30;
    private static final int STOP_WAIT_SECONDS = 5;

    private final String tenant;
    private final String connectorName;
    private final Runnable cycle;
    private final ScheduledThreadPoolExecutor executor;

    ConnectorHousekeeping(String tenant, String connectorName, String connectorIdentifier, Runnable cycle) {
        this(tenant, connectorName, cycle, new TenantScheduledThreadPoolExecutor(tenant, 1, r -> {
            Thread t = new Thread(r, "housekeeping-" + connectorIdentifier);
            t.setDaemon(true);
            return t;
        }));
    }

    /** For tests, which supply a recording executor. */
    ConnectorHousekeeping(String tenant, String connectorName, Runnable cycle,
            ScheduledThreadPoolExecutor executor) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.cycle = cycle;
        this.executor = executor;
    }

    ScheduledThreadPoolExecutor executor() {
        return executor;
    }

    /** Schedules the cycle every {@value #INTERVAL_SECONDS} seconds, the first run one interval from now. */
    void start() {
        if (!executor.isShutdown() && executor.getQueue().isEmpty()) {
            log.debug("{} - Starting housekeeping for connector: {}", tenant, connectorName);
            executor.scheduleAtFixedRate(this::runCycle, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        } else {
            log.debug("{} - Housekeeping already scheduled, skipping", tenant);
        }
    }

    private void runCycle() {
        try {
            cycle.run();
        } catch (Exception e) {
            if (executor.isShutdown()) {
                // Connector is being torn down (e.g. tenant unsubscribed): its resources may already be gone.
                log.debug("{} - Housekeeping aborted during shutdown: {}", tenant, e.toString());
            } else {
                log.error("{} - Error during housekeeping: {}", tenant, e, e);
            }
        }
    }

    /** Drops the schedule and waits up to {@value #STOP_WAIT_SECONDS}s for a running cycle. */
    void stop() {
        if (executor.isShutdown()) {
            return;
        }
        List<Runnable> stoppedTasks = executor.shutdownNow();
        log.info("{} - Stopped {} housekeeping tasks", tenant, stoppedTasks.size());
        // shutdownNow() only interrupts: a cycle that is already executing keeps going.
        try {
            if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} - Housekeeping still running after {}s for connector: {}", tenant,
                        STOP_WAIT_SECONDS, connectorName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shuts down without waiting, for a client that was only used to test a connection. */
    void discard() {
        executor.shutdownNow();
    }
}
