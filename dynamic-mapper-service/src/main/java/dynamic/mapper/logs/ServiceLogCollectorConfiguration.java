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

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one policy the capture and the collector share. Present only when the collector is enabled. */
@Configuration
@ConditionalOnProperty(prefix = "service.logs.collector", name = "enabled", havingValue = "true")
public class ServiceLogCollectorConfiguration {

    /** Class names of everything on the collection path; matched against the end of the logger name. */
    static final List<String> OWN_LOGGERS = List.of("ServiceLogCollector", "ServiceLogCollectorJob", "ServiceLogCapture",
            "ServiceLogCaptureAppender", "EventServiceLogSink", "ServiceLogSourceLocator");

    @Bean
    ServiceLogPolicy serviceLogPolicy(@Value("${service.logs.collector.min-level:WARN}") String minLevel,
            @Value("${service.logs.collector.max-lines-per-tenant:500}") int maxLinesPerTenant,
            @Value("${service.logs.collector.max-chunk-bytes:65536}") int maxChunkBytes) {
        return new ServiceLogPolicy(minLevel, maxLinesPerTenant, maxChunkBytes, OWN_LOGGERS);
    }
}
