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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dynamic.mapper.logs.read.ServiceLogReader.Page;
import dynamic.mapper.logs.read.ServiceLogReader.StoredChunk;

/** Paging with a mapping filter: the platform selects the chunks, the reader drops the other records inside them. */
class ServiceLogReaderMappingTest {

    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    private record Stored(StoredChunk chunk, Set<String> mappings) {
    }

    /** Like the platform: only chunks carrying the mapping's fragment are returned when a mapping is asked for. */
    static class FakeEvents implements ServiceLogReader.Events {
        final List<Stored> all = new ArrayList<>();
        final List<String> askedFor = new ArrayList<>();

        @Override
        public ServiceLogReader.Batch list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize) {
            return list(tenant, from, to, oldestFirst, page, pageSize, null);
        }

        @Override
        public ServiceLogReader.Batch list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize,
                String mapping) {
            askedFor.add(mapping);
            List<StoredChunk> matching = all.stream()
                    .filter(s -> mapping == null || s.mappings().contains(mapping))
                    .map(Stored::chunk)
                    .filter(c -> (from == null || !c.dateTo().isBefore(from)) && (to == null || !c.dateTo().isAfter(to)))
                    .sorted(oldestFirst ? Comparator.comparing(StoredChunk::dateTo) : Comparator.comparing(StoredChunk::dateTo).reversed())
                    .toList();
            int start = (page - 1) * pageSize;
            return ServiceLogReader.Batch.of(start >= matching.size() ? List.of()
                    : matching.subList(start, Math.min(matching.size(), start + pageSize)));
        }

        /** A chunk at slot {@code index} with one record per given mapping (null = a record outside any mapping). */
        FakeEvents chunk(int index, String... mappings) {
            List<String> lines = new ArrayList<>();
            for (String m : mappings) {
                lines.add("2026-10-15T12:00:%02d.000Z  INFO [t] %sd.m.Foo : c%d-%s".formatted(index % 60,
                        m == null ? "" : "[m:" + m + "] ", index, m));
            }
            String text = String.join("\n", lines);
            all.add(new Stored(new StoredChunk(T0.plusSeconds(2L * index), T0.plusSeconds(2L * index + 1), "pod", lines.size(), 0, text),
                    java.util.Arrays.stream(mappings).filter(m -> m != null).collect(java.util.stream.Collectors.toSet())));
            return this;
        }
    }

    @Test
    void onlyTheLinesOfTheMappingAreReturnedAndTheOthersInTheSameChunkAreDropped() {
        FakeEvents events = new FakeEvents().chunk(0, "aaa", "bbb", null).chunk(1, "bbb").chunk(2, "aaa");

        Page page = new ServiceLogReader(events, 10).read("t", null, null, "aaa");

        assertEquals(2, page.chunks());
        assertEquals(2, page.lines());
        assertTrue(page.logs().contains("c0-aaa") && page.logs().contains("c2-aaa"), page.logs());
        assertFalse(page.logs().contains("bbb") || page.logs().contains("c1"), page.logs());
    }

    @Test
    void noMappingMeansTheUnchangedPlainCall() {
        FakeEvents events = new FakeEvents().chunk(0, "aaa").chunk(1, "bbb");

        Page page = new ServiceLogReader(events, 10).read("t", null, null, null);

        assertEquals(2, page.chunks());
        assertEquals(1, events.askedFor.size() > 0 ? events.askedFor.stream().distinct().count() : 1);
        assertTrue(events.askedFor.stream().allMatch(java.util.Objects::isNull), "no mapping was asked for");
    }

    @Test
    void aPageIsMaxChunksMatchingChunksNoMatterHowManyOthersLieBetween() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 100; i++) {
            events.chunk(i, i % 10 == 0 ? new String[] { "aaa" } : new String[] { "bbb" }); // 10 matching of 100
        }

        Page page = new ServiceLogReader(events, 4).read("t", null, null, "aaa");

        assertEquals(4, page.chunks());
        assertTrue(page.more(), "6 more chunks of the mapping exist");
        assertTrue(page.logs().startsWith("2026-10-15T12:00:"));
        assertTrue(page.logs().contains("c60-aaa") && page.logs().contains("c90-aaa") && !page.logs().contains("c50"), page.logs());
    }

    @Test
    void pagingThroughAMappingsChunksWithNextVisitsEachOnceInOrder() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 30; i++) {
            events.chunk(i, i % 3 == 0 ? new String[] { "aaa" } : new String[] { "bbb" }); // 10 chunks of aaa
        }
        ServiceLogReader reader = new ServiceLogReader(events, 4);

        List<String> seen = new ArrayList<>();
        Page page = reader.read("t", Instant.EPOCH, null, "aaa");
        int guard = 0;
        while (true) {
            for (String line : page.logs().split("\n")) {
                seen.add(line.substring(line.indexOf(" : ") + 3));
            }
            if (!page.more() || guard++ > 10) {
                break;
            }
            page = reader.read("t", page.dateTo(), null, "aaa");
        }

        assertEquals(List.of("c0-aaa", "c3-aaa", "c6-aaa", "c9-aaa", "c12-aaa", "c15-aaa", "c18-aaa", "c21-aaa", "c24-aaa", "c27-aaa"), seen);
    }

    @Test
    void aMappingWithNoLinesGivesAnEmptyPageThatIsNotTheEmptyLog() {
        FakeEvents events = new FakeEvents().chunk(0, "bbb").chunk(1, "bbb");

        Page page = new ServiceLogReader(events, 10).read("t", null, null, "aaa");

        assertEquals("", page.logs());
        assertFalse(page.empty(), "the log exists; it just holds nothing for this mapping");
        assertFalse(page.more());
    }

    @Test
    void aChunkWhoseTextHoldsNoLineOfTheMappingStillAdvancesTheCursors() {
        // The fragment says the mapping is in the chunk, but its line was cut short by the record limit and lost its tag.
        FakeEvents events = new FakeEvents().chunk(0, "aaa").chunk(1, "aaa").chunk(2, "aaa");
        events.all.set(1, new Stored(new StoredChunk(T0.plusSeconds(2), T0.plusSeconds(3), "pod", 1, 0, "2026-10-15T12:00:01.000Z  INFO [t] d.m.Foo : untagged"),
                Set.of("aaa")));

        Page page = new ServiceLogReader(events, 10).read("t", null, null, "aaa");

        assertEquals(3, page.chunks());
        assertEquals(2, page.lines());
        assertEquals(T0, page.dateFrom());
        assertEquals(T0.plusSeconds(5), page.dateTo());
    }

    @Test
    void aSourceThatCannotSelectByMappingRefusesInsteadOfReturningEverything() {
        ServiceLogReader.Events plain = (tenant, from, to, oldestFirst, page, pageSize) -> ServiceLogReader.Batch.of(List.of());

        assertThrows(UnsupportedOperationException.class, () -> new ServiceLogReader(plain, 10).read("t", null, null, "aaa"));
    }
}
