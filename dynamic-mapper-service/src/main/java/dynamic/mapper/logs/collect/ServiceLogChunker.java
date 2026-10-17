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

package dynamic.mapper.logs.collect;

import dynamic.mapper.logs.model.ServiceLogChunk;
import dynamic.mapper.logs.model.ServiceLogPolicy;
import dynamic.mapper.logs.model.ServiceLogRecord;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns captured records into per-tenant event chunks. This is where the isolation rule is enforced: a record reaches a
 * tenant only if its own tenant field names that tenant, the tenant is currently subscribed and the policy admits it.
 * Everything else — unattributed lines, unknown tenants — goes nowhere.
 */
public final class ServiceLogChunker {

    private ServiceLogChunker() {
    }

    /**
     * @param chunks   what to store, in order
     * @param deferred records that did not fit this run's line cap and must be kept for the next run
     */
    public record TenantChunks(List<ServiceLogChunk> chunks, List<ServiceLogRecord> deferred) {
    }

    /**
     * One tenant's records, of which only those that name this tenant and pass the policy are used. They are first put in time order (stably, so records of the same millisecond keep their
     * arrival order): records are stamped when they are logged but reach the buffer in whatever order the threads got
     * there, and a chunk's end time must be its newest record. Then each record is cut to the policy's record size,
     * records are taken up to the per-run line cap, and the rest is returned as {@code deferred}.
     */
    public static TenantChunks chunkTenant(String tenant, List<ServiceLogRecord> records, ServiceLogPolicy policy) {
        // The isolation rule, enforced here for the tenant that is being stored: a record whose own tenant field names
        // another tenant (or none) is never part of it, whatever list it came in.
        List<ServiceLogRecord> ordered = new ArrayList<>();
        for (ServiceLogRecord r : records) {
            if (tenant.equals(r.tenant()) && policy.admits(r)) {
                ordered.add(r);
            }
        }
        ordered.sort(Comparator.comparing(ServiceLogRecord::time));
        List<ServiceLogRecord> accepted = new ArrayList<>();
        List<ServiceLogRecord> deferred = new ArrayList<>();
        int used = 0;
        for (ServiceLogRecord r : ordered) {
            ServiceLogRecord limited = r.withText(policy.limit(r.text()));
            int lines = lineCount(limited.text());
            // The first record is always taken: if it alone exceeded the cap it would otherwise be deferred forever.
            if (deferred.isEmpty() && (accepted.isEmpty() || used + lines <= policy.maxLinesPerTenant())) {
                accepted.add(limited);
                used += lines;
            } else {
                deferred.add(r); // everything after the first deferred record is deferred too: order is kept
            }
        }
        return new TenantChunks(split(accepted, policy.maxChunkBytes()), deferred);
    }

    private static List<ServiceLogChunk> split(List<ServiceLogRecord> records, int maxBytes) {
        List<ServiceLogChunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ServiceLogRecord> inChunk = new ArrayList<>();
        Map<String, Integer> levels = new LinkedHashMap<>();
        Set<String> mappings = new LinkedHashSet<>();
        Instant from = null;
        Instant to = null;
        int lines = 0;
        int bytes = 0;
        for (ServiceLogRecord r : records) {
            int size = r.text().getBytes(StandardCharsets.UTF_8).length + 1;
            if (!inChunk.isEmpty() && bytes + size > maxBytes) {
                chunks.add(new ServiceLogChunk(from, to, lines, levels, 0, text.toString(), inChunk, mappings));
                text = new StringBuilder();
                inChunk = new ArrayList<>();
                levels = new LinkedHashMap<>();
                mappings = new LinkedHashSet<>();
                from = null;
                lines = 0;
                bytes = 0;
            }
            if (!inChunk.isEmpty()) {
                text.append('\n');
            }
            text.append(r.text());
            inChunk.add(r);
            levels.merge(r.level(), 1, Integer::sum);
            if (r.mapping() != null) {
                mappings.add(r.mapping());
            }
            if (from == null) {
                from = r.time();
            }
            to = r.time();
            lines += lineCount(r.text());
            bytes += size;
        }
        if (!inChunk.isEmpty()) {
            chunks.add(new ServiceLogChunk(from, to, lines, levels, 0, text.toString(), inChunk, mappings));
        }
        return chunks;
    }

    private static int lineCount(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
