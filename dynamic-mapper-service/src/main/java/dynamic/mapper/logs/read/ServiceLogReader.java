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

package dynamic.mapper.logs.read;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /**
     * One page of stored events. {@code rawSize} is how many events the platform returned, malformed ones included: the
     * scan must judge "end of the log" by that, not by how many chunks were valid, or a full page holding a few malformed
     * events would look like the last one and hide everything after it.
     */
    public record Batch(List<StoredChunk> chunks, int rawSize) {
        public static Batch of(List<StoredChunk> chunks) {
            return new Batch(chunks, chunks.size());
        }
    }

    /** Access to the stored chunks of one tenant; {@code page} is 1-based. */
    public interface Events {
        Batch list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize);

        /**
         * Only the chunks that contain lines of the mapping with this identifier ({@code null}: all chunks). A source that
         * cannot select by mapping must not pretend to: the default refuses.
         */
        default Batch list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize,
                String mapping) {
            if (mapping != null) {
                throw new UnsupportedOperationException("This source cannot select chunks by mapping");
            }
            return list(tenant, from, to, oldestFirst, page, pageSize);
        }
    }

    /**
     * @param before the cursor for the page before this one: the <em>end</em> time of the page's first chunk. The scan
     *               compares against end times, so the start time would skip a chunk that ends inside the first one
     * @param more  whether more chunks exist beyond this page in the direction that was asked for
     * @param empty no collected log exists at all for this tenant (as opposed to an empty page)
     */
    public record Page(Instant dateFrom, Instant dateTo, Instant before, int chunks, int lines, int omitted, boolean more, boolean empty,
            String logs) {
    }

    static final int PAGE_SIZE = 50;
    /** Most pages walked for one request: bounds the work and memory of a single request. */
    static final int MAX_PAGES = 10;

    private final Events events;
    private final int maxChunks;

    public ServiceLogReader(Events events, int maxChunks) {
        this.events = events;
        this.maxChunks = maxChunks;
    }

    /**
     * Chunks of all instances are returned together, ordered by their end time: the tenant sees its log, not the pods that
     * happened to serve it, and nothing has to be filtered out after it was fetched.
     */
    public Page read(String tenant, Instant dateFrom, Instant dateTo) {
        return read(tenant, dateFrom, dateTo, null);
    }

    /**
     * Same as {@link #read(String, Instant, Instant)}, restricted to the lines of one mapping ({@code mapping} = its
     * identifier, {@code null} = no restriction).
     *
     * <p>The platform returns only the chunks that contain the mapping, so paging is unchanged: a page is
     * {@code maxChunks} such chunks and the cursors are theirs. Inside those chunks the other records are dropped. A
     * chunk whose text then holds no matching record (a line cut short by the record limit lost its tag) still counts for the
     * cursors, so the window can be empty while {@code more} is true — the page must keep paging, not stop.
     * {@code empty} stays false with a mapping: the log exists, it just holds nothing for this mapping.
     */
    public Page read(String tenant, Instant dateFrom, Instant dateTo, String mapping) {
        boolean after = dateFrom != null && dateTo == null;
        boolean range = dateFrom != null && dateTo != null;
        Scan scan = scan(tenant, dateFrom, dateTo, after || range, maxChunks, mapping);
        List<StoredChunk> page = new ArrayList<>(scan.chunks());
        // `more` is also true when the walk gave up because it hit its bound: ending the paging there would hide data.
        boolean more = scan.more() || scan.bounded();
        if (!(after || range)) {
            Collections.reverse(page); // fetched newest first; present oldest first
        }
        if (page.isEmpty()) {
            // Without a window the log itself is empty; with one, the window is merely past its end.
            boolean empty = mapping == null && dateFrom == null && dateTo == null && !more;
            if (more && scan.lastScanned() != null) {
                // The walk gave up at its bound without finding a chunk: hand out how far it got, or the caller (Next /
                // Previous, the export) would have no cursor and could not continue past the stretch it just searched.
                return (after || range) ? new Page(null, scan.lastScanned(), null, 0, 0, 0, true, false, "")
                        : new Page(null, null, scan.lastScanned(), 0, 0, 0, true, false, "");
            }
            return new Page(null, null, null, 0, 0, 0, more, empty, "");
        }
        StringBuilder logs = new StringBuilder();
        int lines = 0;
        int omitted = 0;
        for (StoredChunk c : page) {
            String text = mapping == null ? c.logs() : MappingLogRecords.filter(c.logs(), mapping);
            if (mapping != null && text.isEmpty()) {
                omitted += c.omitted();
                continue; // nothing of this mapping in the chunk's text; its cursors still count (see above)
            }
            if (logs.length() > 0) {
                logs.append('\n');
            }
            logs.append(text);
            lines += mapping == null ? c.lines() : MappingLogRecords.lines(text);
            omitted += c.omitted();
        }
        return new Page(page.get(0).dateFrom(), page.get(page.size() - 1).dateTo(), page.get(0).dateTo(), page.size(), lines, omitted, more,
                false, logs.toString());
    }

    /**
     * @param more    a chunk beyond the page exists
     * @param bounded the walk stopped at {@link #MAX_PAGES} with more events still to look at
     * @param lastScanned end time of the last chunk the walk looked at (the cursor to continue from), or {@code null}
     */
    private record Scan(List<StoredChunk> chunks, boolean more, boolean bounded, Instant lastScanned) {
    }

    /**
     * Walks stored chunks (bounded) and returns those that lie strictly inside {@code (from, to)}, at least {@code limit}.
     * The page is only closed between two different end times: a time is the paging cursor, so chunks that end at the same
     * millisecond must all be on one page, or the next request (strictly after / before it) would skip the ones left over.
     */
    private Scan scan(String tenant, Instant from, Instant to, boolean oldestFirst, int limit, String mapping) {
        List<StoredChunk> out = new ArrayList<>();
        Instant lastScanned = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            // Without a mapping the plain call is made: a source only has to implement the selection it can do.
            Batch batch = mapping == null ? events.list(tenant, from, to, oldestFirst, page, PAGE_SIZE)
                    : events.list(tenant, from, to, oldestFirst, page, PAGE_SIZE, mapping);
            for (StoredChunk c : batch.chunks()) {
                lastScanned = c.dateTo();
                boolean inside = (from == null || c.dateTo().isAfter(from)) && (to == null || c.dateTo().isBefore(to));
                if (!inside) {
                    continue;
                }
                if (out.size() >= limit && !c.dateTo().equals(out.get(out.size() - 1).dateTo())) {
                    return new Scan(out, true, false, lastScanned);
                }
                out.add(c);
            }
            if (batch.rawSize() < PAGE_SIZE) {
                return new Scan(out, false, false, lastScanned); // ran out of events: the end is real
            }
        }
        return new Scan(out, false, true, lastScanned);
    }
}
