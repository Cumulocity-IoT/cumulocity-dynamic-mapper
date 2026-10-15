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

import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

/**
 * Runs the {@link ServiceLogCollector} on a timer. Off unless {@code service.logs.collector.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "service.logs.collector", name = "enabled", havingValue = "true")
public class ServiceLogCollectorJob {

    private final ServiceLogCollector collector;

    public ServiceLogCollectorJob(ServiceLogBuffer buffer, ServiceLogSink sink,
            MicroserviceSubscriptionsService subscriptionsService, ServiceLogPolicy policy) {
        this.collector = new ServiceLogCollector(buffer, sink,
                () -> subscriptionsService.getAll().stream().map(MicroserviceCredentials::getTenant)
                        .collect(Collectors.toSet()),
                policy, System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "local");
    }

    @Scheduled(fixedDelayString = "${service.logs.collector.interval-ms:30000}",
            initialDelayString = "${service.logs.collector.initial-delay-ms:30000}")
    public void run() {
        collector.collect();
    }
}
