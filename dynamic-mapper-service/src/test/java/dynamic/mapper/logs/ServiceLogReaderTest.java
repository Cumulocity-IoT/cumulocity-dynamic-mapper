package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import dynamic.mapper.logs.ServiceLogReader.Page;
import dynamic.mapper.logs.ServiceLogReader.StoredChunk;

class ServiceLogReaderTest {

    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    /** Behaves like the platform: inclusive date bounds, newest first unless asked, 1-based pages. */
    static class FakeEvents implements ServiceLogReader.Events {
        final List<StoredChunk> all = new ArrayList<>();

        @Override
        public List<StoredChunk> list(String tenant, Instant from, Instant to, boolean oldestFirst, int page, int pageSize) {
            List<StoredChunk> matching = all.stream()
                    .filter(c -> (from == null || !c.dateTo().isBefore(from)) && (to == null || !c.dateTo().isAfter(to)))
                    .sorted(oldestFirst ? Comparator.comparing(StoredChunk::dateTo) : Comparator.comparing(StoredChunk::dateTo).reversed())
                    .toList();
            int start = (page - 1) * pageSize;
            return start >= matching.size() ? List.of() : matching.subList(start, Math.min(matching.size(), start + pageSize));
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
        Page page = reader(new FakeEvents(), 10).read("t", null, null, null);

        assertTrue(page.empty());
        assertEquals("", page.logs());
    }

    @Test
    void latestReturnsTheNewestChunksInAscendingOrderAndSaysThereIsMore() {
        FakeEvents events = new FakeEvents();
        for (int i = 0; i < 25; i++) {
            events.chunk("pod-1", i);
        }

        Page page = reader(events, 10).read("t", null, null, null);

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
        Page page = reader.read("t", null, null, null);
        seenNewestPageFirst.add(page.logs());
        while (page.more()) {
            page = reader.read("t", page.instance(), null, page.dateFrom());
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
        Page page = reader.read("t", "pod-1", Instant.parse("1970-01-01T00:00:00Z"), null);
        pages.add(page.logs());
        while (page.more()) {
            page = reader.read("t", "pod-1", page.dateTo(), null);
            pages.add(page.logs());
        }

        assertEquals(3, pages.size());
        assertTrue(pages.get(0).startsWith("pod-1#0\n"));
        assertTrue(pages.get(2).endsWith("pod-1#24"));
        assertEquals(25, String.join("\n", pages).split("\n").length);
    }

    @Test
    void instancesAreNeverMixedAndTheNewestOneIsTheDefault() {
        FakeEvents events = new FakeEvents().chunk("pod-old", 0).chunk("pod-old", 1).chunk("pod-new", 2).chunk("pod-new", 3);

        Page defaulted = reader(events, 10).read("t", null, null, null);
        Page old = reader(events, 10).read("t", "pod-old", null, null);

        assertEquals("pod-new", defaulted.instance());
        assertEquals(List.of("pod-new", "pod-old"), defaulted.instances());
        assertFalse(defaulted.logs().contains("pod-old"));
        assertEquals("pod-old#0\npod-old#1", old.logs());
    }

    @Test
    void aWindowBeyondTheLastChunkIsAnEmptyPageNotAnError() {
        FakeEvents events = new FakeEvents().chunk("pod-1", 0);

        Page page = reader(events, 10).read("t", "pod-1", T0.plusSeconds(100), null);

        assertFalse(page.empty());
        assertEquals(0, page.chunks());
        assertFalse(page.more());
    }

    @Test
    void omittedLinesAreSummedSoTheViewCanSayLinesWereLeftOut() {
        FakeEvents events = new FakeEvents();
        events.all.add(new StoredChunk(T0, T0.plusSeconds(1), "pod-1", 3, 7, "x"));
        events.all.add(new StoredChunk(T0.plusSeconds(2), T0.plusSeconds(3), "pod-1", 2, 4, "y"));

        Page page = reader(events, 10).read("t", null, null, null);

        assertEquals(11, page.omitted());
        assertEquals(5, page.lines());
    }
}
