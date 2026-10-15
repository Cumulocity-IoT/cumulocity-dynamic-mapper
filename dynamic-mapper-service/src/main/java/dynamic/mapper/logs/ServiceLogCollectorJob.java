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
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import jakarta.annotation.PreDestroy;

/**
 * Wakes the {@link ServiceLogCollector} every few seconds. The collector decides per tenant whether that tenant is due, so
 * each tenant's own interval applies; the tick only sets the finest granularity (the smallest interval a tenant can have
 * is 5 seconds). Present unless the operator set {@code service.logs.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "service.logs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ServiceLogCollectorJob {

    private final ServiceLogCollector collector;

    public ServiceLogCollectorJob(ServiceLogBuffer buffer, ServiceLogSink sink,
            MicroserviceSubscriptionsService subscriptionsService, ServiceLogSettingsProvider settings) {
        this.collector = new ServiceLogCollector(buffer, sink,
                () -> subscriptionsService.getAll().stream().map(MicroserviceCredentials::getTenant)
                        .collect(Collectors.toSet()),
                settings, System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "local", Clock.systemUTC());
    }

    /** Stores what is still waiting in memory when the service stops, so a restart does not lose the last interval. */
    @PreDestroy
    void flush() {
        collector.flush();
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void run() {
        collector.collect();
    }
}
