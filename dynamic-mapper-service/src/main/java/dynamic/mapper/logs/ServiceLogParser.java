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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the platform's log text into {@link ServiceLogRecord}s.
 *
 * <p>The format is the one configured in {@code application.properties}
 * ({@code logging.pattern.correlation=[%-11tenantId] }):
 * <pre>2026-10-15T11:32:35.574Z  INFO 16 --- [subscriptions-0] [t14070519  ] d.m.core.Foo   : message</pre>
 * The tenant is read from its own bracketed field, <b>never from the message text</b> — that is the whole
 * isolation rule of docs/feature/service-logs.md. A line whose field is {@code -} has no tenant.
 *
 * <p>Lines that are not a record header are joined to the previous record (stack traces, multi-line
 * payloads) up to {@link #MAX_CONTINUATION_LINES}, except output that is known not to belong to a record:
 * JVM {@code WARNING:} lines and the pre-configuration / logback status lines. Text before the first
 * header (the platform's start-up banner) is dropped.
 */
public final class ServiceLogParser {

    static final int MAX_CONTINUATION_LINES = 200;

    private static final Pattern HEADER = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))\\s+"
                    + "(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+\\[([^\\]]*)\\]\\s+\\[([^\\]]*)\\]\\s+(\\S+)\\s+:\\s?(.*)$");

    /** Output interleaved into the stream that is not part of any record. */
    private static final Pattern NOT_A_RECORD = Pattern.compile(
            "^(WARNING: .*|\\d{2}:\\d{2}:\\d{2}[.,]\\d{3} (\\[|\\|-).*)$");

    private ServiceLogParser() {
    }

    public static List<ServiceLogRecord> parse(String logs) {
        List<ServiceLogRecord> records = new ArrayList<>();
        if (logs == null || logs.isEmpty()) {
            return records;
        }
        Header current = null;
        StringBuilder text = null;
        int continuation = 0;
        for (String line : logs.split("\\R", -1)) {
            Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                flush(records, current, text);
                current = header(m);
                text = new StringBuilder(line);
                continuation = 0;
            } else if (NOT_A_RECORD.matcher(line).matches()) {
                flush(records, current, text);
                current = null;
                text = null;
            } else if (current != null) {
                if (continuation < MAX_CONTINUATION_LINES && !(line.isEmpty() && endsWithBlank(text))) {
                    text.append('\n').append(line);
                    continuation++;
                }
            }
        }
        flush(records, current, text);
        return records;
    }

    private static boolean endsWithBlank(StringBuilder text) {
        return text.length() > 0 && text.charAt(text.length() - 1) == '\n';
    }

    private record Header(java.time.Instant time, String level, String thread, String tenant, String logger) {
    }

    private static Header header(Matcher m) {
        String tenant = m.group(4).trim();
        return new Header(OffsetDateTime.parse(m.group(1)).toInstant(), m.group(2), m.group(3).trim(),
                tenant.isEmpty() || "-".equals(tenant) ? null : tenant, m.group(5));
    }

    private static void flush(List<ServiceLogRecord> out, Header h, StringBuilder text) {
        if (h != null && text != null) {
            out.add(new ServiceLogRecord(h.time(), h.level(), h.thread(), h.tenant(), h.logger(),
                    stripTrailingNewlines(text)));
        }
    }

    private static String stripTrailingNewlines(StringBuilder s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
