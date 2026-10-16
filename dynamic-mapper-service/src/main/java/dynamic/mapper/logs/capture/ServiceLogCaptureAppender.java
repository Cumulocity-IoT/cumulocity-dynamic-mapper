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

package dynamic.mapper.logs.capture;

import dynamic.mapper.logs.model.ServiceLogPolicy;
import dynamic.mapper.logs.model.ServiceLogRecord;
import dynamic.mapper.logs.settings.ServiceLogSettingsProvider;

import java.time.Instant;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dynamic.mapper.util.TenantLogContext;

/**
 * Takes each log event of a tenant as it is logged, instead of reading the platform's log afterwards.
 *
 * <p>The tenant comes from the event's MDC — the same source as the {@code [tenant]} field of the console line, so a
 * line can only reach the tenant it was logged for. An event without a tenant is ignored. Events of a tenant that switched
 * the service log off, below that tenant's minimum level, or from the collection path itself
 * ({@code dynamic.mapper.logs}) are ignored.
 *
 * <p>Runs on the logging thread: it must be quick and must never log (that would recurse); a failure is reported to
 * logback's status system and the event is skipped.
 */
public class ServiceLogCaptureAppender extends AppenderBase<ILoggingEvent> {

    /** The format of a stored line: the console format without process id and tenant (the event carries the tenant). */
    static final String PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC} %5p [%t] %logger{39} : %m%n%ex";

    private static final String OWN_PACKAGE = "dynamic.mapper.logs.";

    /**
     * While set on a thread, nothing that thread logs is captured. The collector sets it around its own run: the SDK and
     * the HTTP client log while it stores a tenant's lines, and at a low minimum level those lines would be captured and
     * stored in turn, forever.
     */
    private static final ThreadLocal<Boolean> SUPPRESSED = new ThreadLocal<>();

    /** Suppresses capture on the calling thread until the returned handle is closed. */
    public static AutoCloseable suppressOnThisThread() {
        Boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        return () -> {
            if (previous == null) {
                SUPPRESSED.remove();
            }
        };
    }

    private final BoundedServiceLogBuffer buffer;
    private final ServiceLogSettingsProvider settings;
    private PatternLayout layout;

    public ServiceLogCaptureAppender(BoundedServiceLogBuffer buffer, ServiceLogSettingsProvider settings) {
        this.buffer = buffer;
        this.settings = settings;
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
            if (SUPPRESSED.get() != null) {
                return;
            }
            String logger = event.getLoggerName();
            if (logger != null && logger.startsWith(OWN_PACKAGE)) {
                return;
            }
            String tenant = TenantLogContext.tenantOf(event.getMDCPropertyMap());
            if (tenant == null) {
                return;
            }
            // The tenant's own settings, as they are right now: a change on the Service configuration page applies to the
            // next line. A tenant that switched the service log off costs only this lookup.
            dynamic.mapper.configuration.ServiceLogsConfiguration.Settings s = settings.forTenant(tenant);
            if (!s.enabled() || ServiceLogPolicy.rank(event.getLevel().toString()) < ServiceLogPolicy.rank(s.minLevel())) {
                return;
            }
            for (String own : ServiceLogPolicy.OWN_LOGGERS) {
                if (logger != null && logger.endsWith(own)) {
                    return;
                }
            }
            String text = ServiceLogPolicy.limit(stripTrailingNewlines(layout.doLayout(event)), s.maxRecordBytes());
            buffer.offer(new ServiceLogRecord(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
                    event.getThreadName(), tenant, logger, text), s.maxBufferedRecords());
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
