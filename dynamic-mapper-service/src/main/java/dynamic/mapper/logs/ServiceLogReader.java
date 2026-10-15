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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a tenant's collected log back and assembles it into the same window-and-paging shape the platform's
 * native log has, so one page can show either.
 *
 * <ul>
 *   <li>neither bound: the <b>latest</b> chunks</li>
 *   <li>{@code dateFrom} only: the chunks <b>after</b> it, oldest first (next page; {@code 1970-01-01} = first page)</li>
 *   <li>{@code dateTo} only: the chunks <b>before</b> it (previous page)</li>
 * </ul>
 * Both bounds are exclusive and refer to a chunk's {@code dateTo}. The result is always in ascending order.
 */
public class ServiceLogReader {

    /** A stored chunk as the reader needs it. */
    public record StoredChunk(Instant dateFrom, Instant dateTo, String instance, int lines, int omitted, String logs) {
    }

    /** Access to the stored chunks of one tenant; {@code page} is 1-based. */
    public interface Events {
        List<StoredChunk> list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize);
    }

    /**
     * @param more  whether more chunks exist beyond this page in the direction that was asked for
     * @param empty no collected log exists at all for this tenant (as opposed to an empty page)
     */
    public record Page(List<String> instances, String instance, Instant dateFrom, Instant dateTo, int chunks,
            int lines, int omitted, boolean more, boolean empty, String logs) {
    }

    /** Newest events looked at to learn which instances have stored lines; only their names are kept. */
    static final int DISCOVERY_SIZE = 20;
    static final int PAGE_SIZE = 50;
    /** Most pages walked to find one instance's chunks: bounds the work and memory of a single request. */
    static final int MAX_PAGES = 10;

    private final Events events;
    private final int maxChunks;

    public ServiceLogReader(Events events, int maxChunks) {
        this.events = events;
        this.maxChunks = maxChunks;
    }

    public Page read(String tenant, String instance, Instant dateFrom, Instant dateTo) {
        // Which instances have stored lines, and which one is meant when none is named: the newest chunk's. One small page,
        // and only the instance names are kept — the chunks themselves can be tens of kilobytes each.
        Set<String> instances = new LinkedHashSet<>();
        for (StoredChunk c : events.list(tenant, null, null, false, 1, DISCOVERY_SIZE)) {
            if (c.instance() != null) {
                instances.add(c.instance());
            }
        }
        if (instances.isEmpty()) {
            return new Page(List.of(), null, null, null, 0, 0, 0, false, true, "");
        }
        String chosen = instance != null && !instance.isBlank() ? instance : instances.iterator().next();
        instances.add(chosen); // a requested instance stays selectable even if it was quiet recently

        boolean after = dateFrom != null && dateTo == null;
        boolean range = dateFrom != null && dateTo != null;
        Scan scan = scan(tenant, dateFrom, dateTo, after || range, chosen, maxChunks + 1);
        List<StoredChunk> found = scan.chunks();
        // `more` is also true when the walk gave up because it hit its bound: ending the paging there would hide data.
        boolean more = found.size() > maxChunks || scan.bounded();
        List<StoredChunk> page = new ArrayList<>(found.subList(0, Math.min(found.size(), maxChunks)));
        if (!(after || range)) {
            Collections.reverse(page); // fetched newest first; present oldest first
        }
        if (page.isEmpty()) {
            return new Page(new ArrayList<>(instances), chosen, null, null, 0, 0, 0, more, false, "");
        }
        StringBuilder logs = new StringBuilder();
        int lines = 0;
        int omitted = 0;
        for (StoredChunk c : page) {
            if (logs.length() > 0) {
                logs.append('\n');
            }
            logs.append(c.logs());
            lines += c.lines();
            omitted += c.omitted();
        }
        return new Page(new ArrayList<>(instances), chosen, page.get(0).dateFrom(), page.get(page.size() - 1).dateTo(),
                page.size(), lines, omitted, more, false, logs.toString());
    }

    /** @param bounded the walk stopped at {@link #MAX_PAGES} with more events still to look at */
    private record Scan(List<StoredChunk> chunks, boolean bounded) {
    }

    /**
     * Walks stored chunks (bounded) and returns those of {@code instance} that lie strictly inside {@code (from, to)}.
     */
    private Scan scan(String tenant, Instant from, Instant to, boolean oldestFirst, String instance, int limit) {
        List<StoredChunk> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<StoredChunk> batch = events.list(tenant, from, to, oldestFirst, page, PAGE_SIZE);
            for (StoredChunk c : batch) {
                boolean inside = (from == null || c.dateTo().isAfter(from)) && (to == null || c.dateTo().isBefore(to));
                if (inside && instance.equals(c.instance())) {
                    out.add(c);
                    if (out.size() >= limit) {
                        return new Scan(out, false);
                    }
                }
            }
            if (batch.size() < PAGE_SIZE) {
                return new Scan(out, false); // ran out of events: the end is real
            }
        }
        return new Scan(out, true);
    }
}
