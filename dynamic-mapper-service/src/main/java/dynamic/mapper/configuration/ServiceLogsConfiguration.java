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

package dynamic.mapper.configuration;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Per-tenant settings of the service log (docs/feature/service-logs.md). Stored inside the tenant's
 * {@link ServiceConfiguration}, i.e. in the same tenant option, and editable on the Service configuration page.
 *
 * <p>Every value has a default <b>in this class</b>; a tenant that never touched them gets those. Values that are out of
 * range are not rejected but clamped when they are used ({@link #effective()}), because one tenant's settings must never
 * be able to harm the service shared by all tenants: the limits below bound memory, event volume and work per run.
 */
@Data
@Schema(description = "Settings of the service log: which of this tenant's log lines are stored, how many, and for how long")
public class ServiceLogsConfiguration {

    // ---- defaults
    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_MIN_LEVEL = "INFO";
    public static final int DEFAULT_INTERVAL_MS = 30_000;
    public static final int DEFAULT_MAX_LINES_PER_RUN = 500;
    public static final int DEFAULT_MAX_CHUNK_BYTES = 65_536;
    public static final int DEFAULT_MAX_RECORD_BYTES = 8_192;
    public static final int DEFAULT_MAX_BUFFERED_RECORDS = 2_000;
    public static final int DEFAULT_RETENTION_HOURS = 24;
    public static final boolean DEFAULT_RETENTION_ENABLED = true;
    public static final int DEFAULT_RETENTION_INTERVAL_MS = 3_600_000;

    // ---- hard limits
    static final int MIN_INTERVAL_MS = 5_000;
    static final int MAX_INTERVAL_MS = 3_600_000;
    static final int MAX_LINES_PER_RUN = 5_000;
    static final int MIN_CHUNK_BYTES = 1_024;
    static final int MAX_CHUNK_BYTES = 262_144;
    static final int MIN_RECORD_BYTES = 256;
    static final int MAX_RECORD_BYTES = 65_536;
    static final int MIN_BUFFERED_RECORDS = 100;
    static final int MAX_BUFFERED_RECORDS = 10_000;
    /** What one tenant's in-memory backlog may occupy at most, however its record size and count are set. */
    static final long MAX_BUFFER_BYTES = 16L * 1024 * 1024;
    static final int MIN_RETENTION_HOURS = 1;
    static final int MAX_RETENTION_HOURS = 720;
    static final int MIN_RETENTION_INTERVAL_MS = 60_000;
    static final int MAX_RETENTION_INTERVAL_MS = 86_400_000;

    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    @Schema(description = "Store this tenant's log lines so its administrators can read them under Monitoring > Service logs.", example = "true")
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean enabled = DEFAULT_ENABLED;

    @Schema(description = "Lowest level that is stored: TRACE, DEBUG, INFO, WARN or ERROR. INFO lines can contain device payloads.", example = "INFO")
    @JsonSetter(nulls = Nulls.SKIP)
    private String minLevel = DEFAULT_MIN_LEVEL;

    @Schema(description = "How often this tenant's collected lines are stored, in milliseconds (5000 to 3600000).", example = "30000")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer intervalMs = DEFAULT_INTERVAL_MS;

    @Schema(description = "Most lines stored per run; the rest follows in the next run (1 to 5000).", example = "500")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer maxLinesPerRun = DEFAULT_MAX_LINES_PER_RUN;

    @Schema(description = "Size of one stored event in bytes (1024 to 262144).", example = "65536")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer maxChunkBytes = DEFAULT_MAX_CHUNK_BYTES;

    @Schema(description = "A single very long message or stack trace is cut to this size in bytes (256 to 65536, at most the event size).", example = "8192")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer maxRecordBytes = DEFAULT_MAX_RECORD_BYTES;

    @Schema(description = "Lines that may wait in memory between two runs; when full the oldest are dropped (100 to 10000, and at most 16 MB in total).", example = "2000")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer maxBufferedRecords = DEFAULT_MAX_BUFFERED_RECORDS;

    @Schema(description = "How long stored lines are kept, in hours (1 to 720).", example = "24")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer retentionHours = DEFAULT_RETENTION_HOURS;

    @Schema(description = "Delete stored lines once they are older than the retention. Keep on, or they pile up.", example = "true")
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean retentionEnabled = DEFAULT_RETENTION_ENABLED;

    @Schema(description = "How often old lines are deleted, in milliseconds (60000 to 86400000).", example = "3600000")
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer retentionIntervalMs = DEFAULT_RETENTION_INTERVAL_MS;

    /** The values to work with: defaults for anything unset, everything clamped into its safe range. Immutable. */
    public record Settings(boolean enabled, String minLevel, int intervalMs, int maxLinesPerRun, int maxChunkBytes,
            int maxRecordBytes, int maxBufferedRecords, int retentionHours, boolean retentionEnabled,
            int retentionIntervalMs) {
    }

    /** All defaults, for a tenant whose configuration is not known (yet). */
    public static Settings defaults() {
        return new ServiceLogsConfiguration().effective();
    }

    @JsonIgnore
    public Settings effective() {
        int chunk = clamp(maxChunkBytes, DEFAULT_MAX_CHUNK_BYTES, MIN_CHUNK_BYTES, MAX_CHUNK_BYTES);
        int record = Math.min(clamp(maxRecordBytes, DEFAULT_MAX_RECORD_BYTES, MIN_RECORD_BYTES, MAX_RECORD_BYTES), chunk);
        int buffered = clamp(maxBufferedRecords, DEFAULT_MAX_BUFFERED_RECORDS, MIN_BUFFERED_RECORDS, MAX_BUFFERED_RECORDS);
        // Records of 64 KB times 10000 would be 640 MB for one tenant: the backlog is bounded in bytes as well.
        buffered = (int) Math.max(MIN_BUFFERED_RECORDS, Math.min(buffered, MAX_BUFFER_BYTES / record));
        return new Settings(enabled == null ? DEFAULT_ENABLED : enabled, level(minLevel),
                clamp(intervalMs, DEFAULT_INTERVAL_MS, MIN_INTERVAL_MS, MAX_INTERVAL_MS),
                clamp(maxLinesPerRun, DEFAULT_MAX_LINES_PER_RUN, 1, MAX_LINES_PER_RUN), chunk, record, buffered,
                clamp(retentionHours, DEFAULT_RETENTION_HOURS, MIN_RETENTION_HOURS, MAX_RETENTION_HOURS),
                retentionEnabled == null ? DEFAULT_RETENTION_ENABLED : retentionEnabled,
                clamp(retentionIntervalMs, DEFAULT_RETENTION_INTERVAL_MS, MIN_RETENTION_INTERVAL_MS,
                        MAX_RETENTION_INTERVAL_MS));
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        return value == null ? fallback : Math.max(min, Math.min(max, value));
    }

    private static String level(String value) {
        if (value == null) {
            return DEFAULT_MIN_LEVEL;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(upper) ? upper : DEFAULT_MIN_LEVEL;
    }
}
