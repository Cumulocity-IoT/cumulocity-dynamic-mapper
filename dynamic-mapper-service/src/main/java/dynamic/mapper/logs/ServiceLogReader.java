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
     * @param owner the tenant owns the microservice: its lines are never copied (it has the native log), so an
     *              empty result is expected there
     */
    public record Page(List<String> instances, String instance, Instant dateFrom, Instant dateTo, int chunks,
            int lines, int omitted, boolean more, boolean empty, boolean owner, String logs) {
    }

    static final int PAGE_SIZE = 50;
    static final int MAX_PAGES = 5;

    private final Events events;
    private final int maxChunks;
    private final String ownerTenant;

    public ServiceLogReader(Events events, int maxChunks, String ownerTenant) {
        this.events = events;
        this.maxChunks = maxChunks;
        this.ownerTenant = ownerTenant;
    }

    public Page read(String tenant, String instance, Instant dateFrom, Instant dateTo) {
        // Which instances exist, and which one is meant when none is named: the newest chunk's.
        Set<String> instances = new LinkedHashSet<>();
        for (StoredChunk c : scan(tenant, null, null, false, null, Integer.MAX_VALUE, instances)) {
            // only fills `instances`
        }
        if (instances.isEmpty()) {
            return new Page(List.of(), null, null, null, 0, 0, 0, false, true, isOwner(tenant), "");
        }
        String chosen = instance != null && !instance.isBlank() ? instance : instances.iterator().next();

        boolean after = dateFrom != null && dateTo == null;
        boolean range = dateFrom != null && dateTo != null;
        List<StoredChunk> found = scan(tenant, dateFrom, dateTo, after || range, chosen, maxChunks + 1, null);
        boolean more = found.size() > maxChunks;
        List<StoredChunk> page = new ArrayList<>(found.subList(0, Math.min(found.size(), maxChunks)));
        if (!(after || range)) {
            Collections.reverse(page); // fetched newest first; present oldest first
        }
        if (page.isEmpty()) {
            return new Page(new ArrayList<>(instances), chosen, null, null, 0, 0, 0, false, false, isOwner(tenant), "");
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
                page.size(), lines, omitted, more, false, isOwner(tenant), logs.toString());
    }

    private boolean isOwner(String tenant) {
        return ownerTenant != null && !ownerTenant.isBlank() && ownerTenant.equals(tenant);
    }

    /**
     * Walks stored chunks (bounded) and returns those of {@code instance} (all when null) that lie strictly
     * inside {@code (from, to)}; records every instance it sees in {@code seen} when given.
     */
    private List<StoredChunk> scan(String tenant, Instant from, Instant to, boolean oldestFirst, String instance,
            int limit, Set<String> seen) {
        List<StoredChunk> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES && out.size() < limit; page++) {
            List<StoredChunk> batch = events.list(tenant, from, to, oldestFirst, page, PAGE_SIZE);
            for (StoredChunk c : batch) {
                if (seen != null && c.instance() != null) {
                    seen.add(c.instance());
                }
                boolean inside = (from == null || c.dateTo().isAfter(from)) && (to == null || c.dateTo().isBefore(to));
                if (inside && (instance == null || instance.equals(c.instance()))) {
                    out.add(c);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        return out;
    }
}
