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

import java.io.IOException;
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

import lombok.extern.slf4j.Slf4j;

/**
 * Copies each subscribed tenant's own log lines from the service's native log into that tenant's events,
 * so a tenant that does not own the microservice can see them (docs/feature/service-logs.md).
 *
 * <p>Every instance reads only <b>its own</b> log: no coordination, no duplicates. The cursor is in memory;
 * a restart of the process starts a short look-back before now, and the sink's "newest stored record" per
 * tenant makes any overlap harmless. Delivery is near-real-time, not guaranteed.
 *
 * <p>This class is the orchestration only; reading ({@link ServiceLogSource}), storing
 * ({@link ServiceLogSink}) and the isolation rule ({@link ServiceLogChunker}) are separate so each can be
 * tested alone.
 */
@Slf4j
public class ServiceLogCollector {

    /** After this many runs that failed to store something, the cursor moves on rather than stalling forever. */
    static final int MAX_FAILED_RUNS = 3;

    private final ServiceLogSource source;
    private final ServiceLogSink sink;
    private final Supplier<Set<String>> subscribedTenants;
    private final String ownerTenant;
    private final ServiceLogPolicy policy;
    private final Duration lookBack;
    private final Duration retention;
    private final Duration purgeEvery;
    private final int maxPages;
    private final Clock clock;

    private Instant cursor;
    private Instant lastPurge = Instant.EPOCH;
    private int failedRuns;
    private boolean announced;
    private boolean announcedWaiting;
    private boolean reportedFirstWindow;
    private String lastFailure;
    private int repeatedFailures;

    public ServiceLogCollector(ServiceLogSource source, ServiceLogSink sink, Supplier<Set<String>> subscribedTenants,
            String ownerTenant, ServiceLogPolicy policy, Duration lookBack, Duration retention, int maxPages,
            Clock clock) {
        this.source = source;
        this.sink = sink;
        this.subscribedTenants = subscribedTenants;
        this.ownerTenant = ownerTenant;
        this.policy = policy;
        this.lookBack = lookBack;
        this.retention = retention;
        this.purgeEvery = Duration.ofHours(1);
        this.maxPages = maxPages;
        this.clock = clock;
    }

    /** One collection run. Never throws: a failure is logged (at most once per streak) and retried next run. */
    public synchronized void collect() {
        try {
            if (!announced) {
                announced = true;
                log.info("Service log collector running for instance {} (owner tenant {}, minimum level {}, "
                        + "at most {} line(s) per tenant and run)", source.instance(), ownerTenant, policy.minLevel(),
                        policy.maxLinesPerTenant());
            }
            Set<String> tenants = new HashSet<>(subscribedTenants.get());
            // The owner tenant has the native log; copying it there would only duplicate it.
            tenants.remove(ownerTenant);
            if (tenants.isEmpty()) {
                if (!announcedWaiting) {
                    announcedWaiting = true;
                    log.info("Service log collector: no subscribed tenant other than the owner yet, waiting");
                }
                return;
            }
            announcedWaiting = false;
            boolean allStored = collectWindows(tenants);
            lastFailure = null;
            if (allStored) {
                failedRuns = 0;
            } else if (++failedRuns >= MAX_FAILED_RUNS) {
                log.warn("Service log collection could not store every tenant's lines {} runs in a row; moving on",
                        failedRuns);
                failedRuns = 0;
            }
            // else: the cursor was kept, so the next run re-reads the window; tenants that did succeed are skipped
            purgeIfDue(tenants);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // The same failure every interval is noise: say it once, then remind now and then.
            if (e.getMessage() != null && e.getMessage().equals(lastFailure)) {
                if (++repeatedFailures % 20 == 0) {
                    log.warn("Service log collection still failing ({} runs): {}", repeatedFailures + 1, e.getMessage());
                }
            } else {
                lastFailure = e.getMessage();
                repeatedFailures = 0;
                log.warn("Service log collection failed: {}", e.getMessage());
            }
        }
    }

    private boolean collectWindows(Set<String> tenants) throws IOException, InterruptedException {
        Instant now = clock.instant();
        Instant from = cursor != null ? cursor : now.minus(lookBack);
        Map<String, Instant> collected = new HashMap<>();
        Set<String> asked = new HashSet<>();
        boolean allStored = true;
        int recordsRead = 0;
        int chunksStored = 0;
        Set<String> tenantsWritten = new HashSet<>();
        Instant next = from;
        for (int page = 0; page < maxPages; page++) {
            ServiceLogSource.Window window = source.fetch(next);
            List<ServiceLogRecord> records = ServiceLogParser.parse(window.logs());
            recordsRead += records.size();
            if (!reportedFirstWindow) {
                reportedFirstWindow = true;
                // Where lines drop out, once: read -> attributed to a subscribed tenant -> admitted by the policy.
                long attributed = records.stream().filter(r -> r.isAttributed() && tenants.contains(r.tenant())).count();
                long admitted = records.stream()
                        .filter(r -> tenants.contains(r.tenant()) && policy.admits(r)).count();
                log.info("Service log collector first window {} .. {}: {} record(s) read, {} of subscribed tenants {}, "
                        + "{} at or above level {}{}", window.dateFrom(), window.dateTo(), records.size(), attributed,
                        tenants, admitted, policy.minLevel(), window.truncated() ? " (more follows)" : "");
            }
            for (ServiceLogRecord r : records) {
                if (r.isAttributed() && tenants.contains(r.tenant()) && asked.add(r.tenant())) {
                    sink.lastCollected(r.tenant(), source.instance()).ifPresent(t -> collected.put(r.tenant(), t));
                }
            }
            for (Map.Entry<String, List<ServiceLogChunk>> e
                    : ServiceLogChunker.chunk(records, policy, tenants, collected).entrySet()) {
                for (ServiceLogChunk chunk : e.getValue()) {
                    try {
                        sink.write(e.getKey(), source.instance(), chunk);
                        collected.put(e.getKey(), chunk.dateTo());
                        chunksStored++;
                        tenantsWritten.add(e.getKey());
                    } catch (Exception ex) {
                        allStored = false;
                        log.warn("Could not store service log chunk for tenant {}: {}", e.getKey(), ex.getMessage());
                        break; // keep this tenant's chunks in order
                    }
                }
            }
            boolean progressed = window.dateTo() != null && window.dateTo().isAfter(next);
            if (window.dateTo() != null) {
                next = window.dateTo();
            }
            if (!window.truncated() || !progressed) {
                break;
            }
        }
        if (allStored || failedRuns + 1 >= MAX_FAILED_RUNS) {
            cursor = next;
        }
        if (chunksStored > 0) {
            log.info("Service log collector stored {} chunk(s) for tenant(s) {} from {} record(s) read", chunksStored,
                    tenantsWritten, recordsRead);
        }
        return allStored;
    }

    private void purgeIfDue(Set<String> tenants) {
        Instant now = clock.instant();
        if (Duration.between(lastPurge, now).compareTo(purgeEvery) < 0) {
            return;
        }
        lastPurge = now;
        Instant before = now.minus(retention);
        List<String> failed = new ArrayList<>();
        for (String tenant : tenants) {
            try {
                sink.purge(tenant, before);
            } catch (Exception e) {
                failed.add(tenant);
            }
        }
        if (!failed.isEmpty()) {
            log.warn("Could not purge old service logs for tenants {}", failed);
        }
    }

    Instant cursor() {
        return cursor;
    }
}
