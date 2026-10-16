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

package dynamic.mapper.logs.retention;

import dynamic.mapper.logs.settings.ServiceLogSettingsProvider;
import dynamic.mapper.logs.store.ServiceLogSink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import dynamic.mapper.logs.capture.ServiceLogCaptureAppender;
import dynamic.mapper.configuration.ServiceLogsConfiguration.Settings;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes each subscribed tenant's stored service-log events once they are older than that tenant's retention, so the
 * number of {@code d11r_serviceLog} events cannot grow without bound.
 *
 * <p>Deliberately separate from the collector and independent of whether the tenant has the service log switched on: a
 * tenant that switched it off would otherwise leave its stored lines behind forever. Each tenant's retention, switch and
 * interval are its own settings. A tenant whose purge fails does not stop the others; it is retried on the next pass.
 * Tenants that are not (or no longer) subscribed are not touched — their events belong to their own tenant.
 */
@Slf4j
public class ServiceLogRetention {

    private final ServiceLogSink sink;
    private final Supplier<Set<String>> subscribedTenants;
    private final ServiceLogSettingsProvider settingsProvider;
    private final Clock clock;
    private final Map<String, Instant> lastRun = new HashMap<>();

    public ServiceLogRetention(ServiceLogSink sink, Supplier<Set<String>> subscribedTenants,
            ServiceLogSettingsProvider settingsProvider, Clock clock) {
        this.sink = sink;
        this.subscribedTenants = subscribedTenants;
        this.settingsProvider = settingsProvider;
        this.clock = clock;
    }

    /** One pass. Never throws; returns the tenants whose purge failed (empty when everything worked). */
    public synchronized List<String> run() {
        // The SDK and HTTP client log while deleting; those lines must not be captured and stored in turn.
        try (ServiceLogCaptureAppender.Suppression ignored = ServiceLogCaptureAppender.suppressOnThisThread()) {
            return purgeAll();
        }
    }

    private List<String> purgeAll() {
        List<String> failed = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        try {
            Instant now = clock.instant();
            Set<String> tenants = subscribedTenants.get();
            lastRun.keySet().retainAll(tenants);
            for (String tenant : tenants) {
                Settings settings = settingsProvider.forTenant(tenant);
                Instant last = lastRun.get(tenant);
                if (!settings.retentionEnabled()
                        || (last != null && Duration.between(last, now).toMillis() < settings.retentionIntervalMs())) {
                    continue;
                }
                lastRun.put(tenant, now);
                try {
                    sink.purge(tenant, now.minus(Duration.ofHours(settings.retentionHours())));
                } catch (Exception e) {
                    failed.add(tenant);
                    reasons.add(tenant + ": " + e.getMessage());
                    lastRun.remove(tenant); // retry on the next pass instead of waiting a whole interval
                }
            }
        } catch (Exception e) {
            log.warn("Service log retention failed: {}", e.getMessage());
            return failed;
        }
        if (!failed.isEmpty()) {
            log.warn("Could not delete old service logs, will retry next pass: {}", reasons);
        }
        return failed;
    }
}
