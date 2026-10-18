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

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs each task with the tenant of the thread that submitted it, so work fanned out to a worker
 * (per-mapping processing, connector callbacks) keeps logging under the tenant it was started for.
 *
 * <p>The tenant and the mapping scope (if the submitter is inside one) are carried, so work a mapping hands to another
 * thread stays attributed to it. A task submitted outside any scope clears the tag, and the per-mapping tasks of the
 * routers set their own. Not the whole MDC: other keys (the SDK's {@code device}) describe
 * the submitting thread's current scope and must not outlive it.
 *
 * <p>Extends {@link AbstractExecutorService}, so {@code submit} still returns a real
 * {@code FutureTask} — callers cancel and interrupt through it (see {@code ProcessingResultWrapper}).
 */
public class MdcPropagatingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    public MdcPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        final String tenant = TenantLogContext.current();
        final String mapping = MappingLogContext.current();
        delegate.execute(() -> {
            try (TenantLogContext ignored = TenantLogContext.open(tenant);
                    MappingLogContext ignoredMapping = MappingLogContext.open(mapping)) {
                command.run();
            }
        });
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
