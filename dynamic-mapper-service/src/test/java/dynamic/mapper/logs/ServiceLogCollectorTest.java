package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ServiceLogCollectorTest {

    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    private static ServiceLogRecord rec(String tenant, String level, int second, String text) {
        return new ServiceLogRecord(T0.plusSeconds(second), level, "t", tenant, "d.m.core.Foo", text);
    }

    static class FakeBuffer implements ServiceLogBuffer {
        Map<String, List<ServiceLogRecord>> pending = new LinkedHashMap<>();
        Map<String, Integer> dropped = new HashMap<>();
        RuntimeException failDrain;

        FakeBuffer add(ServiceLogRecord r) {
            pending.computeIfAbsent(r.tenant(), t -> new ArrayList<>()).add(r);
            return this;
        }

        @Override
        public Map<String, List<ServiceLogRecord>> drainAll() {
            if (failDrain != null) {
                throw failDrain;
            }
            Map<String, List<ServiceLogRecord>> out = pending;
            pending = new LinkedHashMap<>();
            return out;
        }

        @Override
        public Map<String, Integer> takeDropped() {
            Map<String, Integer> out = dropped;
            dropped = new HashMap<>();
            return out;
        }

        @Override
        public void restore(String tenant, List<ServiceLogRecord> records) {
            List<ServiceLogRecord> merged = new ArrayList<>(records);
            merged.addAll(pending.getOrDefault(tenant, List.of()));
            if (!merged.isEmpty()) {
                pending.put(tenant, merged);
            }
        }
    }

    static class FakeSink implements ServiceLogSink {
        final Map<String, List<ServiceLogChunk>> written = new HashMap<>();
        final Set<String> failing = new HashSet<>();
        int failOnWriteNumber = -1;
        int writes;
        final List<String> purged = new ArrayList<>();

        @Override
        public void write(String tenant, String instance, ServiceLogChunk chunk) {
            writes++;
            if (failing.contains(tenant) || writes == failOnWriteNumber) {
                throw new IllegalStateException("event API down");
            }
            written.computeIfAbsent(tenant, t -> new ArrayList<>()).add(chunk);
        }

        @Override
        public void purge(String tenant, Instant before) {
            purged.add(tenant);
        }

        String logsOf(String tenant) {
            return written.getOrDefault(tenant, List.of()).stream().map(ServiceLogChunk::logs).reduce("", (a, b) -> a + b + "\n");
        }
    }

    private final FakeBuffer buffer = new FakeBuffer();
    private final FakeSink sink = new FakeSink();
    private Instant now = T0;
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    };

    private ServiceLogCollector collector(Set<String> subscribed, int maxChunkBytes) {
        return new ServiceLogCollector(buffer, sink, () -> subscribed,
                new ServiceLogPolicy("WARN", 500, maxChunkBytes, List.of("ServiceLogCollector")), "pod-1",
                Duration.ofHours(24), clock);
    }

    @Test
    void eachTenantGetsOnlyItsOwnLines() {
        buffer.add(rec("tenantA", "WARN", 1, "a-warning secret-of-A")).add(rec("tenantB", "ERROR", 2, "b-error secret-of-B"))
                .add(rec("tenantA", "ERROR", 3, "a-error"));

        collector(Set.of("tenantA", "tenantB"), 65536).collect();

        assertTrue(sink.logsOf("tenantA").contains("a-warning") && sink.logsOf("tenantA").contains("a-error"));
        assertFalse(sink.logsOf("tenantA").contains("secret-of-B"));
        assertTrue(sink.logsOf("tenantB").contains("b-error"));
        assertFalse(sink.logsOf("tenantB").contains("secret-of-A"));
    }

    @Test
    void recordsOfATenantThatIsNotSubscribedAreDroppedNotStored() {
        buffer.add(rec("gone", "ERROR", 1, "from an unsubscribed tenant")).add(rec("tenantA", "WARN", 2, "kept"));

        collector(Set.of("tenantA"), 65536).collect();

        assertEquals(Set.of("tenantA"), sink.written.keySet());
        assertTrue(buffer.pending.isEmpty(), "dropped, not kept for later");
    }

    @Test
    void theOwnerTenantIsTreatedLikeAnyOther() {
        buffer.add(rec("owner", "ERROR", 1, "owner line"));

        collector(Set.of("owner", "tenantA"), 65536).collect();

        assertTrue(sink.logsOf("owner").contains("owner line"));
    }

    @Test
    void aFailedStoreIsRetriedNextRunWithoutHoldingUpOrDuplicatingOtherTenants() {
        sink.failing.add("tenantA");
        buffer.add(rec("tenantA", "WARN", 1, "a1")).add(rec("tenantB", "WARN", 2, "b1"));
        ServiceLogCollector c = collector(Set.of("tenantA", "tenantB"), 65536);

        c.collect();
        assertTrue(sink.logsOf("tenantB").contains("b1"), "B was not held up by A");
        assertEquals(1, buffer.pending.get("tenantA").size(), "A's record was put back");
        assertFalse(buffer.pending.containsKey("tenantB"));

        sink.failing.clear();
        buffer.add(rec("tenantA", "WARN", 5, "a2"));
        c.collect();

        assertTrue(sink.logsOf("tenantA").contains("a1") && sink.logsOf("tenantA").contains("a2"));
        assertTrue(sink.logsOf("tenantA").indexOf("a1") < sink.logsOf("tenantA").indexOf("a2"), "order kept");
        assertEquals(1, sink.written.get("tenantB").size(), "B was stored exactly once");
    }

    @Test
    void whenALaterChunkFailsOnlyTheUnstoredRecordsComeBack() {
        for (int i = 0; i < 30; i++) {
            buffer.add(rec("tenantA", "WARN", i, "x".repeat(100) + i));
        }
        sink.failOnWriteNumber = 2; // the first chunk is stored, the second fails
        ServiceLogCollector c = collector(Set.of("tenantA"), 1024);

        c.collect();

        int storedLines = sink.written.get("tenantA").stream().mapToInt(ServiceLogChunk::lines).sum();
        int restored = buffer.pending.get("tenantA").size();
        assertEquals(30, storedLines + restored, "nothing lost and nothing stored twice");
        assertTrue(storedLines > 0 && restored > 0);
    }

    @Test
    void theCollectorsOwnLinesAreNeverStored() {
        buffer.add(new ServiceLogRecord(T0, "ERROR", "t", "tenantA", "d.m.l.ServiceLogCollector", "collector failed"));

        collector(Set.of("tenantA"), 65536).collect();

        assertTrue(sink.written.isEmpty());
    }

    @Test
    void aFailingBufferDoesNotThrow() {
        buffer.failDrain = new IllegalStateException("boom");

        collector(Set.of("tenantA"), 65536).collect(); // must not throw

        assertEquals(0, sink.writes);
    }

    @Test
    void oldCopiesArePurgedAtMostOncePerHour() {
        ServiceLogCollector c = collector(Set.of("tenantA", "tenantB"), 65536);

        c.collect();
        c.collect();
        assertEquals(2, sink.purged.size(), "one purge per tenant, not per run");

        now = now.plus(Duration.ofMinutes(61));
        c.collect();
        assertEquals(4, sink.purged.size());
    }
}
