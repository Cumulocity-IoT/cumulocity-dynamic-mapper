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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns parsed records into per-tenant event chunks. This is where the isolation rule is enforced: a record
 * reaches a tenant only if its own tenant field names that tenant, the tenant is currently subscribed and
 * the policy admits it. Everything else — unattributed lines, unknown tenants — goes nowhere.
 */
public final class ServiceLogChunker {

    private ServiceLogChunker() {
    }

    /**
     * @param subscribed    tenants that may receive logs; records of any other tenant are dropped
     * @param newerThan     per tenant, records at or before this time were already collected (may be absent)
     */
    public static Map<String, List<ServiceLogChunk>> chunk(List<ServiceLogRecord> records, ServiceLogPolicy policy,
            Set<String> subscribed, Map<String, Instant> newerThan) {
        Map<String, List<ServiceLogRecord>> perTenant = new LinkedHashMap<>();
        Map<String, Integer> omitted = new LinkedHashMap<>();
        for (ServiceLogRecord r : records) {
            if (!policy.admits(r) || !subscribed.contains(r.tenant())) {
                continue;
            }
            Instant seen = newerThan.get(r.tenant());
            if (seen != null && !r.time().isAfter(seen)) {
                continue;
            }
            List<ServiceLogRecord> list = perTenant.computeIfAbsent(r.tenant(), t -> new ArrayList<>());
            int lines = list.stream().mapToInt(x -> lineCount(x.text())).sum();
            if (lines + lineCount(r.text()) > policy.maxLinesPerTenant()) {
                omitted.merge(r.tenant(), 1, Integer::sum);
            } else {
                list.add(r);
            }
        }
        Map<String, List<ServiceLogChunk>> result = new LinkedHashMap<>();
        perTenant.forEach((tenant, list) -> {
            List<ServiceLogChunk> chunks = split(list, policy.maxChunkBytes());
            int dropped = omitted.getOrDefault(tenant, 0);
            if (dropped > 0 && !chunks.isEmpty()) {
                ServiceLogChunk last = chunks.remove(chunks.size() - 1);
                chunks.add(new ServiceLogChunk(last.dateFrom(), last.dateTo(), last.lines(), last.levels(), dropped,
                        last.logs()));
            }
            if (!chunks.isEmpty()) {
                result.put(tenant, chunks);
            }
        });
        return result;
    }

    private static List<ServiceLogChunk> split(List<ServiceLogRecord> records, int maxBytes) {
        List<ServiceLogChunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Map<String, Integer> levels = new LinkedHashMap<>();
        Instant from = null;
        Instant to = null;
        int lines = 0;
        for (ServiceLogRecord r : records) {
            String piece = r.text();
            int size = piece.getBytes(StandardCharsets.UTF_8).length + 1;
            if (text.length() > 0 && text.toString().getBytes(StandardCharsets.UTF_8).length + size > maxBytes) {
                chunks.add(new ServiceLogChunk(from, to, lines, levels, 0, text.toString()));
                text = new StringBuilder();
                levels = new LinkedHashMap<>();
                from = null;
                lines = 0;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(piece);
            levels.merge(r.level(), 1, Integer::sum);
            if (from == null) {
                from = r.time();
            }
            to = r.time();
            lines += lineCount(piece);
        }
        if (text.length() > 0) {
            chunks.add(new ServiceLogChunk(from, to, lines, levels, 0, text.toString()));
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
