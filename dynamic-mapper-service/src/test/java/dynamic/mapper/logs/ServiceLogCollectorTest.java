package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ServiceLogCollectorTest {

    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    private static String line(String time, String level, String tenant, String text) {
        return "2026-10-15T" + time + "Z " + String.format("%5s", level) + " 16 --- [subscriptions-0] ["
                + String.format("%-11s", tenant) + "] d.m.core.Foo   : " + text;
    }

    /** Serves queued windows and records the cursor each fetch asked for. */
    static class FakeSource implements ServiceLogSource {
        final Deque<Window> windows = new ArrayDeque<>();
        final List<Instant> asked = new ArrayList<>();
        IOException failWith;

        @Override
        public String instance() {
            return "pod-1";
        }

        @Override
        public Window fetch(Instant from) throws IOException {
            asked.add(from);
            if (failWith != null) {
                throw failWith;
            }
            return windows.isEmpty() ? new Window(from, from, "", false) : windows.poll();
        }
    }

    static class FakeSink implements ServiceLogSink {
        final Map<String, List<ServiceLogChunk>> written = new HashMap<>();
        final Map<String, Instant> last = new HashMap<>();
        final Set<String> failing = new HashSet<>();
        final List<String> purged = new ArrayList<>();
        int writes;

        @Override
        public Optional<Instant> lastCollected(String tenant, String instance) {
            return Optional.ofNullable(last.get(tenant));
        }

        @Override
        public void write(String tenant, String instance, ServiceLogChunk chunk) {
            writes++;
            if (failing.contains(tenant)) {
                throw new IllegalStateException("event API down for " + tenant);
            }
            written.computeIfAbsent(tenant, t -> new ArrayList<>()).add(chunk);
            last.put(tenant, chunk.dateTo());
        }

        @Override
        public void purge(String tenant, Instant before) {
            purged.add(tenant);
        }

        String logsOf(String tenant) {
            return written.getOrDefault(tenant, List.of()).stream().map(ServiceLogChunk::logs)
                    .reduce("", (a, b) -> a + b + "\n");
        }
    }

    private final FakeSource source = new FakeSource();
    private final FakeSink sink = new FakeSink();
    private Instant now = T0;
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    };

    private ServiceLogCollector collector(Set<String> subscribed, int maxPages) {
        return new ServiceLogCollector(source, sink, () -> subscribed, "owner",
                new ServiceLogPolicy("WARN", 500, 65536, List.of("ServiceLogCollector")),
                Duration.ofMinutes(10), Duration.ofHours(24), maxPages, clock);
    }

    private static ServiceLogSource.Window window(String logs, String from, String to, boolean truncated) {
        return new ServiceLogSource.Window(Instant.parse("2026-10-15T" + from + "Z"), Instant.parse("2026-10-15T" + to + "Z"),
                logs, truncated);
    }

    @Test
    void eachTenantGetsOnlyItsOwnLinesAndTheOwnerAndUnattributedLinesGetNothing() {
        source.windows.add(window(String.join("\n",
                line("12:00:01.000", "WARN", "tenantA", "a-warning"),
                line("12:00:02.000", "ERROR", "tenantB", "b-error"),
                line("12:00:03.000", "ERROR", "owner", "owner-error"),
                line("12:00:04.000", "ERROR", "-", "global-error"),
                line("12:00:05.000", "INFO", "tenantA", "a-info-with-payload")), "12:00:00", "12:00:06", false));

        collector(Set.of("tenantA", "tenantB", "owner"), 3).collect();

        assertEquals(Set.of("tenantA", "tenantB"), sink.written.keySet());
        assertTrue(sink.logsOf("tenantA").contains("a-warning") && !sink.logsOf("tenantA").contains("b-error"));
        assertTrue(sink.logsOf("tenantB").contains("b-error") && !sink.logsOf("tenantB").contains("a-"));
        assertFalse(sink.logsOf("tenantA").contains("a-info-with-payload"), "below the minimum level");
    }

    @Test
    void theCursorAdvancesAndTheNextRunAsksFromWhereTheLastEnded() {
        source.windows.add(window(line("12:00:01.000", "WARN", "tenantA", "one"), "12:00:00", "12:00:30", false));
        ServiceLogCollector c = collector(Set.of("tenantA"), 3);

        c.collect();
        c.collect();

        assertEquals(T0.minus(Duration.ofMinutes(10)), source.asked.get(0), "first run looks back, never replays history");
        assertEquals(Instant.parse("2026-10-15T12:00:30Z"), source.asked.get(1));
    }

    @Test
    void recordsAlreadyStoredAreNotWrittenAgain() {
        sink.last.put("tenantA", Instant.parse("2026-10-15T12:00:01Z"));
        source.windows.add(window(String.join("\n",
                line("12:00:01.000", "WARN", "tenantA", "already stored"),
                line("12:00:09.000", "WARN", "tenantA", "new")), "12:00:00", "12:00:10", false));

        collector(Set.of("tenantA"), 3).collect();

        assertEquals("new\n", sink.logsOf("tenantA").replaceAll("(?s).*: ", ""));
        assertFalse(sink.logsOf("tenantA").contains("already stored"));
    }

    @Test
    void truncatedWindowsAreFollowedUpToTheLimit() {
        for (int i = 0; i < 5; i++) {
            source.windows.add(window(line("12:0" + i + ":01.000", "WARN", "tenantA", "w" + i), "12:0" + i + ":00",
                    "12:0" + i + ":30", true));
        }

        collector(Set.of("tenantA"), 3).collect();

        assertEquals(3, source.asked.size(), "bounded by max-pages");
    }

    @Test
    void aTenantWhoseStoreFailsHoldsTheCursorAndOthersAreNotDuplicatedOnRetry() {
        sink.failing.add("tenantA");
        String logs = String.join("\n", line("12:00:01.000", "WARN", "tenantA", "a1"),
                line("12:00:02.000", "WARN", "tenantB", "b1"));
        source.windows.add(window(logs, "12:00:00", "12:00:10", false));
        source.windows.add(window(logs, "12:00:00", "12:00:10", false));
        ServiceLogCollector c = collector(Set.of("tenantA", "tenantB"), 3);

        c.collect();
        assertEquals(T0.minus(Duration.ofMinutes(10)), source.asked.get(0));
        sink.failing.clear();
        c.collect();

        assertEquals(T0.minus(Duration.ofMinutes(10)), source.asked.get(1), "cursor was held, window re-read");
        assertTrue(sink.logsOf("tenantA").contains("a1"), "A was retried and stored");
        assertEquals(1, sink.written.get("tenantB").size(), "B was stored once, not twice");
    }

    @Test
    void aSourceFailureDoesNotThrowAndKeepsTheCursor() {
        source.failWith = new IOException("HTTP 503");
        ServiceLogCollector c = collector(Set.of("tenantA"), 3);

        c.collect(); // must not throw

        assertEquals(null, c.cursor());
        assertEquals(0, sink.writes);
    }

    @Test
    void nothingIsReadWhenOnlyTheOwnerIsSubscribed() {
        collector(Set.of("owner"), 3).collect();

        assertTrue(source.asked.isEmpty());
    }

    @Test
    void oldCopiesArePurgedAtMostOncePerHour() {
        ServiceLogCollector c = collector(Set.of("tenantA", "tenantB"), 3);

        c.collect();
        c.collect();
        assertEquals(2, sink.purged.size(), "one purge per tenant, not per run");

        now = now.plus(Duration.ofMinutes(61));
        c.collect();
        assertEquals(4, sink.purged.size());
    }

    @Test
    void theCollectorsOwnLinesAreNeverCopied() {
        String own = "2026-10-15T12:00:01.000Z ERROR 16 --- [scheduling-1] [tenantA    ] d.m.l.ServiceLogCollector   : failed";
        source.windows.add(window(own, "12:00:00", "12:00:10", false));

        collector(Set.of("tenantA"), 3).collect();

        assertNotNull(sink.written);
        assertTrue(sink.written.isEmpty());
    }
}
