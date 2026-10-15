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

import java.time.Instant;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dynamic.mapper.util.TenantLogContext;

/**
 * Takes each log event of a tenant as it is logged, instead of reading the platform's log afterwards.
 *
 * <p>The tenant comes from the event's MDC — the same source as the {@code [tenant]} field of the console line, so a
 * line can only reach the tenant it was logged for. An event without a tenant is ignored. Events below the policy's
 * minimum level and events of the collection path itself ({@code dynamic.mapper.logs}) are ignored.
 *
 * <p>Runs on the logging thread: it must be quick and must never log (that would recurse); a failure is reported to
 * logback's status system and the event is skipped.
 */
public class ServiceLogCaptureAppender extends AppenderBase<ILoggingEvent> {

    /** The format of a stored line: the console format without process id and tenant (the event carries the tenant). */
    static final String PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC} %5p [%t] %logger{39} : %m%n%ex";

    private static final String OWN_PACKAGE = "dynamic.mapper.logs.";

    private final BoundedServiceLogBuffer buffer;
    private final ServiceLogPolicy policy;
    private PatternLayout layout;

    public ServiceLogCaptureAppender(BoundedServiceLogBuffer buffer, ServiceLogPolicy policy) {
        this.buffer = buffer;
        this.policy = policy;
        setName("ServiceLogCapture");
    }

    @Override
    public void start() {
        PatternLayout l = new PatternLayout();
        l.setContext(getContext());
        l.setPattern(PATTERN);
        l.start();
        this.layout = l;
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            String logger = event.getLoggerName();
            if (logger != null && logger.startsWith(OWN_PACKAGE)) {
                return;
            }
            String tenant = TenantLogContext.tenantOf(event.getMDCPropertyMap());
            if (tenant == null) {
                return;
            }
            ServiceLogRecord record = new ServiceLogRecord(Instant.ofEpochMilli(event.getTimeStamp()),
                    event.getLevel().toString(), event.getThreadName(), tenant, logger, null);
            if (!policy.admits(record)) {
                return; // level, excluded logger: decided before the (costlier) formatting
            }
            String text = layout.doLayout(event);
            buffer.offer(new ServiceLogRecord(record.time(), record.level(), record.thread(), tenant, logger,
                    stripTrailingNewlines(text)));
        } catch (RuntimeException e) {
            addError("Could not capture a log event: " + e);
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
