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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Attaches the {@link ServiceLogCaptureAppender} to the root logger and exposes what it captured as a
 * {@link ServiceLogBuffer}. Which tenant's lines are captured, and how many, is decided per tenant by its
 * {@link ServiceLogSettingsProvider settings}; the property {@code service.logs.enabled=false} is an operator's switch
 * that turns the whole feature off for every tenant (it is on when the property is absent).
 *
 * <p>This replaces reading the platform's log: measured on a deployed service, no credential the service holds may read
 * its own native log, whereas the logging framework already knows every line's tenant (docs/feature/service-logs.md).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "service.logs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ServiceLogCapture implements ServiceLogBuffer {

    private final BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
    private final ServiceLogCaptureAppender appender;
    private Logger root;

    public ServiceLogCapture(ServiceLogSettingsProvider settings) {
        this.appender = new ServiceLogCaptureAppender(buffer, settings);
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
    public Map<String, List<ServiceLogRecord>> drain(Collection<String> tenants) {
        return buffer.drain(tenants);
    }

    @Override
    public void retainOnly(Set<String> keep) {
        buffer.retainOnly(keep);
    }

    @Override
    public void discard(String tenant) {
        buffer.discard(tenant);
    }

    @Override
    public Map<String, Integer> takeDropped(Collection<String> tenants) {
        return buffer.takeDropped(tenants);
    }

    @Override
    public void restore(String tenant, List<ServiceLogRecord> records, int maxPerTenant) {
        buffer.restore(tenant, records, maxPerTenant);
    }
}
