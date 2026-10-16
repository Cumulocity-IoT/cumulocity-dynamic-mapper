package dynamic.mapper.logs.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import dynamic.mapper.logs.read.ServiceLogReader.Page;
import dynamic.mapper.logs.read.ServiceLogReader.StoredChunk;

class ServiceLogReaderTest {

    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    /** Behaves like the platform: inclusive date bounds, newest first unless asked, 1-based pages. */
    static class FakeEvents implements ServiceLogReader.Events {
        final List<StoredChunk> all = new ArrayList<>();

        @Override
        public ServiceLogReader.Batch list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize) {
            List<StoredChunk> matching = all.stream()
                    .filter(c -> (from == null || !c.dateTo().isBefore(from)) && (to == null || !c.dateTo().isAfter(to)))
                    .sorted(oldestFirst ? Comparator.comparing(StoredChunk::dateTo) : Comparator.comparing(StoredChunk::dateTo).reversed())
                    .toList();
            int start = (page - 1) * pageSize;
            return ServiceLogReader.Batch.of(start >= matching.size() ? List.of()
                    : matching.subList(start, Math.min(matching.size(), start + pageSize)));
        }

        FakeEvents chunk(String instance, int index) {
            // two seconds per chunk: [2i, 2i+1]
            all.add(new StoredChunk(T0.plusSeconds(2L * index), T0.plusSeconds(2L * index + 1), instance, 1, 0,
                    instance + "#" + index));
            return this;
        }
    }

    private static ServiceLogReader reader(FakeEvents events, int max) {
        return new ServiceLogReader(events, max);
    }

    @Test
    void noCollectedLogAtAllIsReportedAsEmptyNotAsAnEmptyPage() {
        Page page = reader(new FakeEvents(), 10).read("t", null, null);

        assertTrue(page.empty());
        assertEquals("", page.logs());
    }

    @Test
    void latestReturnsTheNewestChunksInAscendingOrderAndSaysThereIsMore() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 25; i++) {
            events.chunk("pod-1", i);
        }

        Page page = reader(events, 10).read("t", null, null);

        assertEquals(10, page.chunks());
        assertTrue(page.more());
        assertTrue(page.logs().startsWith("pod-1#15\n"), page.logs());
        assertTrue(page.logs().endsWith("pod-1#24"));
    }

    @Test
    void walkingBackwardsVisitsEveryChunkExactlyOnceInOrder() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 25; i++) {
            events.chunk("pod-1", i);
        }
        ServiceLogReader reader = reader(events, 10);

        List<String> seenNewestPageFirst = new ArrayList<>();
        Page page = reader.read("t", null, null);
        seenNewestPageFirst.add(page.logs());
        while (page.more()) {
            page = reader.read("t", null, page.before());
            seenNewestPageFirst.add(page.logs());
        }

        assertEquals(3, seenNewestPageFirst.size());
        String all = String.join("\n", seenNewestPageFirst.reversed());
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            expected.append(i == 0 ? "" : "\n").append("pod-1#").append(i);
        }
        assertEquals(expected.toString(), all);
    }

    @Test
    void walkingForwardFromTheBeginningVisitsEveryChunkExactlyOnceInOrder() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 25; i++) {
            events.chunk("pod-1", i);
        }
        ServiceLogReader reader = reader(events, 10);

        List<String> pages = new ArrayList<>();
        Page page = reader.read("t", Instant.parse("1970-01-01T00:00:00Z"), null);
        pages.add(page.logs());
        while (page.more()) {
            page = reader.read("t", page.dateTo(), null);
            pages.add(page.logs());
        }

        assertEquals(3, pages.size());
        assertTrue(pages.get(0).startsWith("pod-1#0\n"));
        assertTrue(pages.get(2).endsWith("pod-1#24"));
        assertEquals(25, String.join("\n", pages).split("\n").length);
    }

    @Test
    void theChunksOfAllInstancesAreReturnedTogetherInTimeOrder() {
        FakeEvents events = new FakeEvents().chunk("pod-old", 0).chunk("pod-new", 1).chunk("pod-old", 2).chunk("pod-new", 3);

        Page page = reader(events, 10).read("t", null, null);

        assertEquals("pod-old#0\npod-new#1\npod-old#2\npod-new#3", page.logs());
    }

    @Test
    void aQuietInstanceIsNotHiddenBehindManyNewerChunksOfAnother() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 5; i++) {
            events.chunk("pod-quiet", i);
        }
        for (int i = 5; i < 205; i++) {
            events.chunk("pod-busy", i);
        }

        Page first = reader(events, 10).read("t", Instant.parse("1970-01-01T00:00:00Z"), null);

        assertEquals(10, first.chunks(), "the first page is not consumed by filtering");
        assertTrue(first.logs().startsWith("pod-quiet#0\n"));
    }

    @Test
    void chunksThatEndAtTheSameInstantAreNeverSplitAcrossPages() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 9; i++) {
            events.chunk("pod-1", i);
        }
        // the 10th and 11th chunk end at the same millisecond
        events.all.add(new StoredChunk(T0.plusSeconds(18), T0.plusSeconds(19), "pod-1", 1, 0, "tie-a"));
        events.all.add(new StoredChunk(T0.plusSeconds(18), T0.plusSeconds(19), "pod-2", 1, 0, "tie-b"));
        events.chunk("pod-1", 11);
        ServiceLogReader reader = reader(events, 10);

        List<String> pages = new ArrayList<>();
        Page page = reader.read("t", Instant.parse("1970-01-01T00:00:00Z"), null);
        pages.add(page.logs());
        while (page.more()) {
            page = reader.read("t", page.dateTo(), null);
            pages.add(page.logs());
        }

        String all = String.join("\n", pages);
        assertTrue(all.contains("tie-a") && all.contains("tie-b"), all);
        assertEquals(12, all.split("\n").length);
        assertTrue(pages.get(0).contains("tie-a") && pages.get(0).contains("tie-b"), "the tie stays on one page");
    }

    @Test
    void aWindowBeyondTheLastChunkIsAnEmptyPageNotAnError() {
        FakeEvents events = new FakeEvents().chunk("pod-1", 0);

        Page page = reader(events, 10).read("t", T0.plusSeconds(100), null);

        assertFalse(page.empty());
        assertEquals(0, page.chunks());
        assertFalse(page.more());
    }

    @Test
    void omittedLinesAreSummedSoTheViewCanSayLinesWereLeftOut() {
        FakeEvents events = new FakeEvents();
        events.all.add(new StoredChunk(T0, T0.plusSeconds(1), "pod-1", 3, 7, "x"));
        events.all.add(new StoredChunk(T0.plusSeconds(2), T0.plusSeconds(3), "pod-1", 2, 4, "y"));

        Page page = reader(events, 10).read("t", null, null);

        assertEquals(11, page.omitted());
        assertEquals(5, page.lines());
    }

    @Test
    void aFullPlatformPageWithMalformedEventsDoesNotLookLikeTheEndOfTheLog() {
        // Every platform page holds 50 events, but only 40 of them are valid chunks.
        FakeEvents events = new FakeEvents() {
            @Override
            public ServiceLogReader.Batch list(String tenant, Instant from, Instant to, boolean oldestFirst, int page,
                    int pageSize) {
                ServiceLogReader.Batch b = super.list(tenant, from, to, oldestFirst, page, pageSize);
                return new ServiceLogReader.Batch(b.chunks().subList(0, Math.min(b.chunks().size(), 40)),
                        b.rawSize());
            }
        };
        for (int i = 0; i < 400; i++) {
            events.chunk("pod-1", i);
        }

        Page page = reader(events, 100).read("t", Instant.parse("1970-01-01T00:00:00Z"), null);

        assertTrue(page.chunks() > 40, "the scan went on after a page with fewer valid chunks than events");
    }

    @Test
    void thePreviousPageStartsBeforeTheEndTimeOfTheFirstChunkSoOverlappingChunksOfOtherInstancesAreSeen() {
        FakeEvents events = new FakeEvents();
        // pod-1's chunk spans 12:00-12:30, pod-2's chunk ends at 12:20 (inside it)
        events.all.add(new StoredChunk(T0, T0.plusSeconds(1800), "pod-1", 1, 0, "long"));
        events.all.add(new StoredChunk(T0.plusSeconds(600), T0.plusSeconds(1200), "pod-2", 1, 0, "inner"));

        Page latest = reader(events, 1).read("t", null, null);
        Page previous = reader(events, 1).read("t", null, latest.before());

        assertEquals("long", latest.logs());
        assertEquals("inner", previous.logs());
    }
}
