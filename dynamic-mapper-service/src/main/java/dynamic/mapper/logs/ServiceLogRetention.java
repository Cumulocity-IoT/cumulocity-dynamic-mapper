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
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Deletes each subscribed tenant's stored service-log events once they are older than the retention period, so the number
 * of {@code d11r_serviceLog} events cannot grow without bound.
 *
 * <p>Deliberately separate from the collector and independent of whether collection is switched on: a service that stopped
 * collecting would otherwise leave its stored lines behind forever. A tenant whose purge fails does not stop the others;
 * it is retried on the next run. Tenants that are not (or no longer) subscribed are not touched — their events belong to
 * their own tenant and retention.
 */
@Slf4j
public class ServiceLogRetention {

    private final ServiceLogSink sink;
    private final Supplier<Set<String>> subscribedTenants;
    private final Duration retention;
    private final Clock clock;

    public ServiceLogRetention(ServiceLogSink sink, Supplier<Set<String>> subscribedTenants, Duration retention,
            Clock clock) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.sink = sink;
        this.subscribedTenants = subscribedTenants;
        this.retention = retention;
        this.clock = clock;
    }

    /** One run. Never throws; returns the tenants whose purge failed (empty when everything worked). */
    public List<String> run() {
        List<String> failed = new ArrayList<>();
        try {
            Instant before = clock.instant().minus(retention);
            for (String tenant : subscribedTenants.get()) {
                try {
                    sink.purge(tenant, before);
                } catch (Exception e) {
                    failed.add(tenant);
                }
            }
        } catch (Exception e) {
            log.warn("Service log retention failed: {}", e.getMessage());
            return failed;
        }
        if (!failed.isEmpty()) {
            log.warn("Could not delete old service logs for tenant(s) {}; will retry", failed);
        }
        return failed;
    }
}
