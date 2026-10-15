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
import java.util.List;
import java.util.Map;

/**
 * What one event carries: a contiguous run of one tenant's records.
 *
 * @param dateFrom time of the first record
 * @param dateTo   time of the last (newest) record
 * @param lines    number of log lines (a multi-line record counts every line)
 * @param levels   records per level, e.g. {@code {WARN=2, ERROR=1}}
 * @param omitted  records lost since the previous chunk because the tenant's buffer overflowed
 * @param records  the records this chunk was built from; <b>not stored</b>, it lets the collector put back exactly the
 *                 records that were not stored when a write fails
 */
public record ServiceLogChunk(Instant dateFrom, Instant dateTo, int lines, Map<String, Integer> levels, int omitted,
        String logs, List<ServiceLogRecord> records) {

    public ServiceLogChunk withOmitted(int omitted) {
        return new ServiceLogChunk(dateFrom, dateTo, lines, levels, omitted, logs, records);
    }

    public String summary() {
        StringBuilder s = new StringBuilder().append(lines).append(" line(s)");
        if (!levels.isEmpty()) {
            s.append(" (");
            levels.forEach((level, n) -> s.append(s.charAt(s.length() - 1) == '(' ? "" : ", ").append(n).append(' ').append(level));
            s.append(')');
        }
        if (omitted > 0) {
            s.append(", ").append(omitted).append(" omitted");
        }
        return s.toString();
    }
}
