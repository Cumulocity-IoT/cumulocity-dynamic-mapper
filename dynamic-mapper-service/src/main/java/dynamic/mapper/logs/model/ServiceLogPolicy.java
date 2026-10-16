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

package dynamic.mapper.logs.model;

import dynamic.mapper.logs.capture.ServiceLogCapture;
import dynamic.mapper.logs.capture.ServiceLogCaptureAppender;
import dynamic.mapper.logs.collect.ServiceLogCollector;
import dynamic.mapper.logs.collect.ServiceLogCollectorJob;
import dynamic.mapper.logs.retention.ServiceLogRetention;
import dynamic.mapper.logs.retention.ServiceLogRetentionJob;
import dynamic.mapper.logs.store.EventServiceLogSink;
import dynamic.mapper.logs.store.ServiceLogSourceLocator;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * What may be copied to a tenant, and how much.
 *
 * @param minLevel          lines below it are never copied (the service itself runs at TRACE/INFO, and INFO
 *                          lines can carry device payloads — see docs/feature/service-logs.md)
 * @param maxLinesPerTenant per collection run; the rest is <b>deferred to the next run</b>, not dropped
 * @param maxChunkBytes     size of one event's log text, well below the platform's event size limit
 * @param maxRecordBytes    size of one record: a single huge message or stack trace is cut to this, because a chunk
 *                          cannot be split inside a record and an oversized event would be rejected again and again
 * @param excludedLoggers   loggers whose lines are never copied (the collector's own, to avoid a feedback
 *                          loop); matched against the end of the logger name
 */
public record ServiceLogPolicy(String minLevel, int maxLinesPerTenant, int maxChunkBytes, int maxRecordBytes,
        List<String> excludedLoggers) {

    static final int DEFAULT_MAX_RECORD_BYTES = 8192;

    /** Class names of everything on the collection path; matched against the end of the logger name. */
    public static final List<String> OWN_LOGGERS = List.of("ServiceLogCollector", "ServiceLogCollectorJob",
            "ServiceLogCapture", "ServiceLogCaptureAppender", "EventServiceLogSink", "ServiceLogSourceLocator",
            "ServiceLogRetention", "ServiceLogRetentionJob");
    private static final String TRUNCATED = " … [truncated]";
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    public ServiceLogPolicy {
        if (rank(minLevel) < 0) {
            throw new IllegalArgumentException("Unknown minimum level: " + minLevel);
        }
        if (maxLinesPerTenant < 1 || maxChunkBytes < 1024) {
            throw new IllegalArgumentException("maxLinesPerTenant must be >= 1 and maxChunkBytes >= 1024");
        }
        if (maxRecordBytes < 256 || maxRecordBytes > maxChunkBytes) {
            throw new IllegalArgumentException("maxRecordBytes must be between 256 and maxChunkBytes");
        }
        excludedLoggers = excludedLoggers == null ? List.of() : List.copyOf(excludedLoggers);
    }

    public ServiceLogPolicy(String minLevel, int maxLinesPerTenant, int maxChunkBytes, List<String> excludedLoggers) {
        this(minLevel, maxLinesPerTenant, maxChunkBytes, Math.min(maxChunkBytes, DEFAULT_MAX_RECORD_BYTES), excludedLoggers);
    }

    /** The policy of one tenant, from its current settings. */
    public static ServiceLogPolicy of(dynamic.mapper.configuration.ServiceLogsConfiguration.Settings settings) {
        return new ServiceLogPolicy(settings.minLevel(), settings.maxLinesPerRun(), settings.maxChunkBytes(),
                settings.maxRecordBytes(), OWN_LOGGERS);
    }

    public static int rank(String level) {
        if (level == null) {
            return -1;
        }
        // Called for every captured event: no upper-cased copy of the level name.
        for (int i = 0; i < LEVELS.size(); i++) {
            if (LEVELS.get(i).equalsIgnoreCase(level)) {
                return i;
            }
        }
        return -1;
    }

    public boolean admits(ServiceLogRecord record) {
        if (!record.isAttributed() || rank(record.level()) < rank(minLevel)) {
            return false;
        }
        for (String excluded : excludedLoggers) {
            if (record.logger() != null && record.logger().endsWith(excluded)) {
                return false;
            }
        }
        return true;
    }

    /** The text, cut to {@link #maxRecordBytes()} (never inside a multi-byte character) with a marker if it was cut. */
    public String limit(String text) {
        return limit(text, maxRecordBytes);
    }

    public static String limit(String text, int maxBytes) {
        // UTF-8 needs at most 3 bytes per UTF-16 char: a short text is within the limit without encoding it. This runs
        // for every captured event, and nearly all lines are short.
        if ((long) text.length() * 3 <= maxBytes) {
            return text;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int cut = maxBytes - TRUNCATED.getBytes(StandardCharsets.UTF_8).length;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8) + TRUNCATED;
    }
}
