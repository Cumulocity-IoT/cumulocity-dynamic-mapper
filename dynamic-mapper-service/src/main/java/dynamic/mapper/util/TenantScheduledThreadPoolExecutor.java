/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A scheduled executor whose tasks run in a tenant's logging scope.
 *
 * <p>{@link MdcPropagatingExecutorService} carries the <em>submitter's</em> tenant, which is useless for a
 * timer: a task scheduled once and fired later (or repeatedly) runs on the timer thread, which has no scope. This
 * executor belongs to one tenant, so every task it runs is wrapped in that tenant's scope.
 */
public class TenantScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {

    private final String tenant;

    public TenantScheduledThreadPoolExecutor(String tenant, int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
        this.tenant = tenant;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return super.schedule(TenantLogContext.runnable(tenant, command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return super.schedule(TenantLogContext.wrap(tenant, callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return super.scheduleAtFixedRate(TenantLogContext.runnable(tenant, command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return super.scheduleWithFixedDelay(TenantLogContext.runnable(tenant, command), initialDelay, delay, unit);
    }
}
