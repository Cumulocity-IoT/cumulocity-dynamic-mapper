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
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs the {@link ServiceLogRetention} on a timer. On by default and independent of
 * {@code service.logs.collector.enabled}; switch it off with {@code service.logs.retention.enabled=false}.
 *
 * <p>Every service instance runs it. That is redundant but harmless: deleting events that are already gone is a no-op.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "service.logs.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ServiceLogRetentionJob {

    private final ServiceLogRetention retention;

    public ServiceLogRetentionJob(ServiceLogSink sink, MicroserviceSubscriptionsService subscriptionsService,
            @Value("${service.logs.collector.retention-hours:24}") int retentionHours) {
        this.retention = new ServiceLogRetention(sink,
                () -> subscriptionsService.getAll().stream().map(MicroserviceCredentials::getTenant)
                        .collect(Collectors.toSet()),
                Duration.ofHours(retentionHours), Clock.systemUTC());
        log.info("Service log retention: stored lines older than {} hour(s) are deleted", retentionHours);
    }

    @Scheduled(fixedDelayString = "${service.logs.retention.interval-ms:3600000}",
            initialDelayString = "${service.logs.retention.initial-delay-ms:300000}")
    public void run() {
        retention.run();
    }
}
