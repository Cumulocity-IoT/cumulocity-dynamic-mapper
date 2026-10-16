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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import dynamic.mapper.configuration.ServiceLogsConfiguration.Settings;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores each subscribed tenant's own log lines, captured inside the process, as that tenant's events, so a tenant that
 * does not own the microservice can read them (docs/feature/service-logs.md).
 *
 * <p>It is woken up often and decides <b>per tenant</b> whether that tenant is due, using the tenant's own settings: its
 * interval, its on/off switch, its limits. One run for a tenant drains its buffered records, lets
 * {@link ServiceLogChunker} apply the isolation rule, and writes one event per chunk. What does not fit the tenant's
 * per-run line cap is kept for the next run; if a store fails, exactly the records that were not stored are put back, in
 * order, and the other tenants are not held up. Buffers of tenants that are not subscribed, or that switched the service
 * log off, are emptied. Lines lost because a tenant's buffer overflowed are reported on that tenant's next chunk.
 *
 * <p>Delivery is near-real-time, not guaranteed: lines still buffered when the process dies are lost.
 */
@Slf4j
public class ServiceLogCollector {

    private final ServiceLogBuffer buffer;
    private final ServiceLogSink sink;
    private final Supplier<Set<String>> subscribedTenants;
    private final ServiceLogSettingsProvider settingsProvider;
    private final String instance;
    private final Clock clock;

    private final Map<String, Instant> lastRun = new HashMap<>();
    private boolean announced;
    private String lastFailure;
    private int repeatedFailures;

    public ServiceLogCollector(ServiceLogBuffer buffer, ServiceLogSink sink, Supplier<Set<String>> subscribedTenants,
            ServiceLogSettingsProvider settingsProvider, String instance, Clock clock) {
        this.buffer = buffer;
        this.sink = sink;
        this.subscribedTenants = subscribedTenants;
        this.settingsProvider = settingsProvider;
        this.instance = instance;
        this.clock = clock;
    }

    /** One pass: every tenant whose interval has elapsed is stored. Never throws. */
    public synchronized void collect() {
        collect(false, null);
    }

    /** Stores every tenant's waiting lines now, whatever their interval (used when the service stops). */
    public synchronized void flush() {
        collect(true, null);
    }

    /**
     * Stores one tenant's waiting lines now, whatever its interval: the "fetch now" button of the service log page. Does
     * nothing for a tenant that is not subscribed or has switched the service log off.
     */
    public synchronized void flushTenant(String tenant) {
        collect(true, tenant);
    }

    private void collect(boolean force, String only) {
        // Whatever this thread logs while storing (SDK, HTTP client) must not be captured and stored in turn.
        try (AutoCloseable ignored = ServiceLogCaptureAppender.suppressOnThisThread()) {
            run(force, only);
        } catch (Exception e) {
            remember("*", e);
        }
    }

    private void run(boolean force, String only) {
        try {
            if (!announced) {
                announced = true;
                log.info("Service log collector running for instance {}", instance);
            }
            Set<String> tenants = new HashSet<>(subscribedTenants.get());
            buffer.retainOnly(tenants);
            lastRun.keySet().retainAll(tenants);
            Instant now = clock.instant();

            Map<String, Settings> due = new HashMap<>();
            for (String tenant : tenants) {
                Settings settings = settingsProvider.forTenant(tenant);
                if (!settings.enabled()) {
                    buffer.discard(tenant); // switched off: nothing may be kept for it
                    continue;
                }
                if (only != null && !only.equals(tenant)) {
                    continue;
                }
                Instant last = lastRun.get(tenant);
                if (force || last == null || Duration.between(last, now).toMillis() >= settings.intervalMs()) {
                    due.put(tenant, settings);
                }
            }
            if (due.isEmpty()) {
                return;
            }

            Map<String, List<ServiceLogRecord>> drained = buffer.drain(due.keySet());
            Map<String, Integer> dropped = buffer.takeDropped(due.keySet());
            if (!dropped.isEmpty()) {
                log.warn("Service log collector dropped records because a tenant's buffer was full: {}", dropped);
            }

            int stored = 0;
            Set<String> written = new HashSet<>();
            boolean failed = false;
            for (Map.Entry<String, Settings> entry : due.entrySet()) {
                String tenant = entry.getKey();
                Settings settings = entry.getValue();
                lastRun.put(tenant, now);
                List<ServiceLogRecord> records = drained.get(tenant);
                if (records == null) {
                    continue;
                }
                ServiceLogChunker.TenantChunks plan = ServiceLogChunker.chunkTenant(tenant, records,
                        ServiceLogPolicy.of(settings));
                List<ServiceLogChunk> chunks = plan.chunks();
                List<ServiceLogRecord> notStored = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    ServiceLogChunk chunk = chunks.get(i);
                    if (i == 0 && dropped.getOrDefault(tenant, 0) > 0) {
                        chunk = chunk.withOmitted(dropped.get(tenant)); // tell the reader lines are missing before this
                    }
                    try {
                        sink.write(tenant, instance, chunk);
                        stored++;
                        written.add(tenant);
                    } catch (Exception e) {
                        failed = true;
                        remember(tenant, e);
                        // This chunk and every later one were not stored; earlier chunks were and must not come back.
                        for (int j = i; j < chunks.size(); j++) {
                            notStored.addAll(chunks.get(j).records());
                        }
                        break; // keep this tenant's chunks in order
                    }
                }
                notStored.addAll(plan.deferred());
                if (!notStored.isEmpty()) {
                    buffer.restore(tenant, notStored, settings.maxBufferedRecords());
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
