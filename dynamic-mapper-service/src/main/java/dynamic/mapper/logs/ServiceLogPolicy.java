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
import java.util.Locale;

/**
 * What may be copied to a tenant, and how much.
 *
 * @param minLevel         lines below it are never copied (the service itself runs at TRACE/INFO, and INFO
 *                         lines can carry device payloads — see docs/feature/service-logs.md)
 * @param maxLinesPerTenant per collection run; the rest is counted as omitted, not silently lost
 * @param maxChunkBytes    size of one event's log text, well below the platform's event size limit
 * @param excludedLoggers  loggers whose lines are never copied (the collector's own, to avoid a feedback
 *                         loop); matched against the end of the logger field, which logback abbreviates
 */
public record ServiceLogPolicy(String minLevel, int maxLinesPerTenant, int maxChunkBytes,
        List<String> excludedLoggers) {

    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    public ServiceLogPolicy {
        if (rank(minLevel) < 0) {
            throw new IllegalArgumentException("Unknown minimum level: " + minLevel);
        }
        if (maxLinesPerTenant < 1 || maxChunkBytes < 1024) {
            throw new IllegalArgumentException("maxLinesPerTenant must be >= 1 and maxChunkBytes >= 1024");
        }
        excludedLoggers = excludedLoggers == null ? List.of() : List.copyOf(excludedLoggers);
    }

    static int rank(String level) {
        return level == null ? -1 : LEVELS.indexOf(level.toUpperCase(Locale.ROOT));
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
}
