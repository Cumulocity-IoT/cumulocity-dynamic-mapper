/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.logs.collect;

import dynamic.mapper.logs.settings.TestSettings;

import dynamic.mapper.logs.capture.ServiceLogCapture;
import dynamic.mapper.logs.retention.ServiceLogRetentionJob;
import dynamic.mapper.logs.settings.ServiceLogSettingsProvider;
import dynamic.mapper.logs.store.ServiceLogSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

/** On unless the operator switches it off; the retention job is there either way. */
class ServiceLogCollectorWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ServiceLogSink.class, () -> mock(ServiceLogSink.class))
            .withBean(ServiceLogSettingsProvider.class, () -> new TestSettings())
            .withBean(MicroserviceSubscriptionsService.class, () -> mock(MicroserviceSubscriptionsService.class))
            .withUserConfiguration(ServiceLogCapture.class, ServiceLogCollectorJob.class, ServiceLogRetentionJob.class);

    @Test
    void isOnWhenNothingIsConfiguredBecauseEveryValueHasADefaultInCode() {
        runner.run(ctx -> {
            assertEquals(1, ctx.getBeansOfType(ServiceLogCapture.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogRetentionJob.class).size());
        });
    }

    @Test
    void theOperatorCanSwitchTheWholeFeatureOffButRetentionStillRunsToCleanUp() {
        runner.withPropertyValues("service.logs.enabled=false").run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(ServiceLogCapture.class).size());
            assertEquals(0, ctx.getBeansOfType(ServiceLogCollectorJob.class).size());
            assertEquals(1, ctx.getBeansOfType(ServiceLogRetentionJob.class).size());
        });
    }
}
