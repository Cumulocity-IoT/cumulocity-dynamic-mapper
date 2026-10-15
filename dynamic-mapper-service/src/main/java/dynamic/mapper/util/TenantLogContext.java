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
import java.util.function.BiConsumer;

import org.slf4j.MDC;

/**
 * Puts the tenant a unit of work runs for into the logging context, so every log line written by
 * it carries a machine-readable tenant (see {@code logging.pattern.correlation}).
 *
 * <p>Inside a Cumulocity SDK tenant scope the SDK sets its own {@code tenant} key. Threads that
 * never enter such a scope — message workers, connector callbacks — have to set the tenant
 * themselves, because there it travels as a method argument, not as a context. This class does that
 * under a separate key (see {@link #KEY} for why it cannot share the SDK's).
 *
 * <p>A line without the key belongs to no tenant. That is deliberate: it is the safe default for
 * anything that decides who may <em>see</em> a line (see docs/feature/service-logs.md).
 */
public final class TenantLogContext implements AutoCloseable {

    /**
     * Our own key. It must NOT be the SDK's {@code tenant}: {@code ContextServiceImpl.leaveContext()}
     * removes that key unconditionally and restores it only from an enclosing SDK scope, so any
     * {@code callForTenant(...)} made on one of our threads would wipe the tenant for every line
     * logged after it returns. {@link TenantLogConverter} prints the SDK's value when inside an SDK
     * scope and this one otherwise.
     */
    public static final String KEY = "mapperTenant";

    /** The key the Cumulocity microservice SDK sets inside its own tenant scopes. */
    public static final String SDK_KEY = "tenant";

    /**
     * The tenant of a log event's MDC: the SDK's key inside its own scope, else ours, else {@code null}. The same rule as
     * {@link TenantLogConverter}, for code that reads the MDC of an event instead of printing it.
     */
    public static String tenantOf(java.util.Map<String, String> mdc) {
        if (mdc == null) {
            return null;
        }
        String tenant = mdc.get(SDK_KEY);
        if (tenant == null || tenant.isEmpty()) {
            tenant = mdc.get(KEY);
        }
        return tenant == null || tenant.isEmpty() ? null : tenant;
    }

    /** The tenant a line logged right now would be attributed to, or {@code null}. */
    public static String current() {
        String sdk = MDC.get(SDK_KEY);
        return sdk != null ? sdk : MDC.get(KEY);
    }

    private final String previous;

    private TenantLogContext(String tenant) {
        this.previous = MDC.get(KEY);
        if (tenant == null || tenant.isBlank()) {
            MDC.remove(KEY);
        } else {
            MDC.put(KEY, tenant);
        }
    }

    /** Opens a scope for {@code tenant}; close it (try-with-resources) to restore the previous value. */
    public static TenantLogContext open(String tenant) {
        return new TenantLogContext(tenant);
    }

    /** Runs {@code task} with {@code tenant} in the logging context. */
    public static <T> Callable<T> wrap(String tenant, Callable<T> task) {
        return () -> {
            try (TenantLogContext ignored = open(tenant)) {
                return task.call();
            }
        };
    }

    /** Runs {@code task} with {@code tenant} in the logging context. */
    public static Runnable runnable(String tenant, Runnable task) {
        return () -> {
            try (TenantLogContext ignored = open(tenant)) {
                task.run();
            }
        };
    }

    /**
     * Runs {@code task} now with {@code tenant} in the logging context. For callbacks whose
     * functional interface is the client library's own type, so a wrapper cannot be returned:
     * {@code listener(ctx -> TenantLogContext.run(tenant, () -> { ... }))}.
     */
    public static void run(String tenant, Runnable task) {
        try (TenantLogContext ignored = open(tenant)) {
            task.run();
        }
    }

    /** For callbacks invoked on a foreign thread (client libraries) that take two arguments. */
    public static <A, B> BiConsumer<A, B> biConsumer(String tenant, BiConsumer<A, B> task) {
        return (a, b) -> {
            try (TenantLogContext ignored = open(tenant)) {
                task.accept(a, b);
            }
        };
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(KEY);
        } else {
            MDC.put(KEY, previous);
        }
    }
}
