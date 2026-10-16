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

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

/**
 * Wakes the {@link ServiceLogRetention} once a minute; the retention decides per tenant whether that tenant is due, so each
 * tenant's own interval and age limit apply. Always present — also when the service log is switched off — so stored lines
 * never pile up.
 *
 * <p>Every service instance runs it. That is redundant but harmless: deleting events that are already gone is a no-op.
 */
@Component
public class ServiceLogRetentionJob {

    private final ServiceLogRetention retention;

    public ServiceLogRetentionJob(ServiceLogSink sink, MicroserviceSubscriptionsService subscriptionsService,
            ServiceLogSettingsProvider settings) {
        this.retention = new ServiceLogRetention(sink,
                () -> subscriptionsService.getAll().stream().map(MicroserviceCredentials::getTenant)
                        .collect(Collectors.toSet()),
                settings, Clock.systemUTC());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void run() {
        retention.run();
    }
}
