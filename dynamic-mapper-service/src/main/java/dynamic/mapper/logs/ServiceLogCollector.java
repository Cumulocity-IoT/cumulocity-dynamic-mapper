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

package dynamic.mapper.logs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Stores each subscribed tenant's own log lines, captured inside the process, as that tenant's events, so a tenant that
 * does not own the microservice can read them (docs/feature/service-logs.md).
 *
 * <p>One run drains the {@link ServiceLogBuffer}, lets {@link ServiceLogChunker} apply the isolation rule (a record
 * reaches a tenant only if its own tenant field names it, the tenant is subscribed and the policy admits it), and writes
 * one event per chunk. A tenant whose store fails gets its unstored records put back, so the next run retries them
 * in order; the other tenants are not held up by it. Records of tenants that are not subscribed are dropped.
 *
 * <p>Delivery is near-real-time, not guaranteed: lines still buffered when the process dies are lost.
 */
@Slf4j
public class ServiceLogCollector {

    private final ServiceLogBuffer buffer;
    private final ServiceLogSink sink;
    private final Supplier<Set<String>> subscribedTenants;
    private final ServiceLogPolicy policy;
    private final String instance;

    private boolean announced;
    private String lastFailure;
    private int repeatedFailures;

    public ServiceLogCollector(ServiceLogBuffer buffer, ServiceLogSink sink, Supplier<Set<String>> subscribedTenants,
            ServiceLogPolicy policy, String instance) {
        this.buffer = buffer;
        this.sink = sink;
        this.subscribedTenants = subscribedTenants;
        this.policy = policy;
        this.instance = instance;
    }

    /** One collection run. Never throws: a failure is logged (once per streak) and retried next run. */
    public synchronized void collect() {
        try {
            if (!announced) {
                announced = true;
                log.info("Service log collector running for instance {} (minimum level {}, at most {} line(s) per tenant "
                        + "and run)", instance, policy.minLevel(), policy.maxLinesPerTenant());
            }
            Set<String> tenants = new HashSet<>(subscribedTenants.get());
            Map<String, List<ServiceLogRecord>> drained = buffer.drainAll();
            reportDropped();
            int stored = 0;
            Set<String> written = new HashSet<>();
            boolean failed = false;
            for (Map.Entry<String, List<ServiceLogRecord>> entry : drained.entrySet()) {
                String tenant = entry.getKey();
                if (!tenants.contains(tenant)) {
                    continue; // not (or no longer) subscribed: nothing may be stored for it
                }
                List<ServiceLogChunk> chunks = ServiceLogChunker
                        .chunk(entry.getValue(), policy, tenants, Map.of()).getOrDefault(tenant, List.of());
                Instant watermark = null;
                for (ServiceLogChunk chunk : chunks) {
                    try {
                        sink.write(tenant, instance, chunk);
                        watermark = chunk.dateTo();
                        stored++;
                        written.add(tenant);
                    } catch (Exception e) {
                        failed = true;
                        remember(tenant, e);
                        restoreUnstored(tenant, entry.getValue(), watermark);
                        break; // keep this tenant's chunks in order
                    }
                }
            }
            if (stored > 0) {
                log.info("Service log collector stored {} chunk(s) for tenant(s) {}", stored, written);
            }
            if (!failed) {
                lastFailure = null;
            }
        } catch (Exception e) {
            remember("*", e);
        }
    }

    private void restoreUnstored(String tenant, List<ServiceLogRecord> records, Instant storedUpTo) {
        List<ServiceLogRecord> unstored = new ArrayList<>();
        for (ServiceLogRecord r : records) {
            if (storedUpTo == null || r.time().isAfter(storedUpTo)) {
                unstored.add(r);
            }
        }
        buffer.restore(tenant, unstored);
    }

    private void reportDropped() {
        Map<String, Integer> dropped = buffer.takeDropped();
        if (!dropped.isEmpty()) {
            log.warn("Service log collector dropped records because a tenant's buffer was full: {}", dropped);
        }
    }

    /** The same failure every interval is noise: say it once, then remind now and then. */
    private void remember(String tenant, Exception e) {
        String message = tenant + ": " + e.getMessage();
        if (message.equals(lastFailure)) {
            if (++repeatedFailures % 20 == 0) {
                log.warn("Service log collection still failing ({} runs): {}", repeatedFailures + 1, message);
            }
        } else {
            lastFailure = message;
            repeatedFailures = 0;
            log.warn("Service log collection failed for {}", message);
        }
    }
}
