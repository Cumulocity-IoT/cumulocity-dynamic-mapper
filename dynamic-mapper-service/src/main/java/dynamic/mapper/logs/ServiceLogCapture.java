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
import java.util.Map;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Attaches the {@link ServiceLogCaptureAppender} to the root logger and exposes what it captured as a
 * {@link ServiceLogBuffer}. Present only when {@code service.logs.collector.enabled=true}.
 *
 * <p>This replaces reading the platform's log: measured on a deployed service, no credential the service holds may
 * read its own native log, whereas the logging framework already knows every line's tenant (docs/feature/service-logs.md).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "service.logs.collector", name = "enabled", havingValue = "true")
public class ServiceLogCapture implements ServiceLogBuffer {

    private final BoundedServiceLogBuffer buffer;
    private final ServiceLogCaptureAppender appender;
    private Logger root;

    public ServiceLogCapture(ServiceLogPolicy policy,
            @Value("${service.logs.collector.max-buffered-records-per-tenant:2000}") int maxBufferedPerTenant) {
        this.buffer = new BoundedServiceLogBuffer(maxBufferedPerTenant);
        this.appender = new ServiceLogCaptureAppender(buffer, policy);
    }

    @PostConstruct
    void attach() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            log.warn("Service log capture needs logback; the logging framework is {}", factory.getClass().getName());
            return;
        }
        appender.setContext(context);
        appender.start();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        log.info("Service log capture attached");
    }

    @PreDestroy
    void detach() {
        if (root != null) {
            root.detachAppender(appender);
        }
        appender.stop();
    }

    @Override
    public Map<String, List<ServiceLogRecord>> drainAll() {
        return buffer.drainAll();
    }

    @Override
    public void restore(String tenant, List<ServiceLogRecord> records) {
        buffer.restore(tenant, records);
    }

    @Override
    public Map<String, Integer> takeDropped() {
        return buffer.takeDropped();
    }
}
