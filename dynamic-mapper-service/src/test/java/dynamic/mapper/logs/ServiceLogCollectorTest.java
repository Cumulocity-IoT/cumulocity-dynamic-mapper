package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
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
        int lastRestoreBound = -1;

        FakeBuffer add(ServiceLogRecord r) {
            pending.computeIfAbsent(r.tenant(), t -> new ArrayList<>()).add(r);
            return this;
        }

        @Override
        public Map<String, List<ServiceLogRecord>> drain(Collection<String> tenants) {
            if (failDrain != null) {
                throw failDrain;
            }
            Map<String, List<ServiceLogRecord>> out = new LinkedHashMap<>();
            for (String tenant : tenants) {
                List<ServiceLogRecord> list = pending.remove(tenant);
                if (list != null) {
                    out.put(tenant, list);
                }
            }
            return out;
        }

        @Override
        public void retainOnly(Set<String> keep) {
            pending.keySet().removeIf(t -> !keep.contains(t));
        }

        @Override
        public void discard(String tenant) {
            pending.remove(tenant);
        }

        @Override
        public void restoreDropped(String tenant, int count) {
            if (count > 0) {
                dropped.merge(tenant, count, Integer::sum);
            }
        }

        @Override
        public Map<String, Integer> takeDropped(Collection<String> tenants) {
            Map<String, Integer> out = new HashMap<>();
            for (String tenant : tenants) {
                Integer n = dropped.remove(tenant);
                if (n != null) {
                    out.put(tenant, n);
                }
            }
            return out;
        }

        @Override
        public void restore(String tenant, List<ServiceLogRecord> records, int maxPerTenant) {
            lastRestoreBound = maxPerTenant;
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
            // not the collector's job: see ServiceLogRetention
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

    private ServiceLogCollector collector(Set<String> subscribed, TestSettings settings) {
        return new ServiceLogCollector(buffer, sink, () -> subscribed, settings, "pod-1", clock);
    }

    private ServiceLogCollector collector(Set<String> subscribed) {
        return collector(subscribed, new TestSettings());
    }

    @Test
    void eachTenantGetsOnlyItsOwnLines() {
        buffer.add(rec("tenantA", "WARN", 1, "a-warning secret-of-A")).add(rec("tenantB", "ERROR", 2, "b-error secret-of-B"))
                .add(rec("tenantA", "ERROR", 3, "a-error"));

        collector(Set.of("tenantA", "tenantB")).collect();

        assertTrue(sink.logsOf("tenantA").contains("a-warning") && sink.logsOf("tenantA").contains("a-error"));
        assertFalse(sink.logsOf("tenantA").contains("secret-of-B"));
        assertTrue(sink.logsOf("tenantB").contains("b-error"));
        assertFalse(sink.logsOf("tenantB").contains("secret-of-A"));
    }

    @Test
    void theBufferOfATenantThatIsNotSubscribedIsEmptiedNotStored() {
        buffer.add(rec("gone", "ERROR", 1, "from an unsubscribed tenant")).add(rec("tenantA", "WARN", 2, "kept"));

        collector(Set.of("tenantA")).collect();

        assertEquals(Set.of("tenantA"), sink.written.keySet());
        assertTrue(buffer.pending.isEmpty(), "dropped, not kept for later");
    }

    @Test
    void theOwnerTenantIsTreatedLikeAnyOther() {
        buffer.add(rec("owner", "ERROR", 1, "owner line"));

        collector(Set.of("owner", "tenantA")).collect();

        assertTrue(sink.logsOf("owner").contains("owner line"));
    }

    @Test
    void aTenantThatSwitchedTheServiceLogOffGetsNothingStoredAndItsBufferIsEmptied() {
        buffer.add(rec("tenantA", "WARN", 1, "a")).add(rec("tenantB", "WARN", 2, "b"));
        TestSettings settings = new TestSettings().with("tenantA", c -> c.setEnabled(false));

        collector(Set.of("tenantA", "tenantB"), settings).collect();

        assertEquals(Set.of("tenantB"), sink.written.keySet());
        assertTrue(buffer.pending.isEmpty());
    }

    @Test
    void eachTenantIsStoredOnItsOwnInterval() {
        TestSettings settings = new TestSettings(c -> c.setMinLevel("WARN"))
                .with("slow", c -> {
                    c.setMinLevel("WARN");
                    c.setIntervalMs(60_000);
                })
                .with("fast", c -> {
                    c.setMinLevel("WARN");
                    c.setIntervalMs(5_000);
                });
        ServiceLogCollector c = collector(Set.of("slow", "fast"), settings);
        c.collect(); // first pass: both are due (nothing was stored before)
        buffer.add(rec("slow", "WARN", 1, "slow 1")).add(rec("fast", "WARN", 1, "fast 1"));

        now = T0.plusSeconds(10);
        c.collect();
        assertEquals(Set.of("fast"), sink.written.keySet(), "10 s: only the 5 s tenant is due");
        assertEquals(1, buffer.pending.get("slow").size(), "the other tenant's line waits");

        now = T0.plusSeconds(61);
        c.collect();
        assertTrue(sink.logsOf("slow").contains("slow 1"));
    }

    @Test
    void flushStoresEveryTenantNowWhateverItsInterval() {
        TestSettings settings = new TestSettings().with("tenantA", c -> {
            c.setMinLevel("WARN");
            c.setIntervalMs(3_600_000);
        });
        ServiceLogCollector c = collector(Set.of("tenantA"), settings);
        c.collect();
        buffer.add(rec("tenantA", "WARN", 1, "waiting"));
        now = T0.plusSeconds(10);
        c.collect();
        assertTrue(sink.written.isEmpty(), "not due yet");

        c.flush(); // the service is stopping

        assertTrue(sink.logsOf("tenantA").contains("waiting"));
    }

    @Test
    void flushTenantStoresOnlyThatTenantNowAndLeavesTheOthersWaiting() {
        TestSettings settings = new TestSettings(c -> {
            c.setMinLevel("WARN");
            c.setIntervalMs(3_600_000);
        });
        ServiceLogCollector c = collector(Set.of("tenantA", "tenantB"), settings);
        c.collect();
        buffer.add(rec("tenantA", "WARN", 1, "a waiting"));
        buffer.add(rec("tenantB", "WARN", 1, "b waiting"));
        now = T0.plusSeconds(10);

        c.flushTenant("tenantA");

        assertTrue(sink.logsOf("tenantA").contains("a waiting"));
        assertTrue(sink.logsOf("tenantB").isEmpty(), "another tenant is not stored");
        c.flush();
        assertTrue(sink.logsOf("tenantB").contains("b waiting"), "and its lines were not lost");
    }

    @Test
    void flushTenantDoesNothingForATenantThatSwitchedTheServiceLogOff() {
        TestSettings settings = new TestSettings().with("tenantA", c -> c.setEnabled(false));
        ServiceLogCollector c = collector(Set.of("tenantA"), settings);
        buffer.add(rec("tenantA", "WARN", 1, "x"));

        c.flushTenant("tenantA");

        assertTrue(sink.written.isEmpty());
    }

    @Test
    void aFailedStoreIsRetriedNextRunWithoutHoldingUpOrDuplicatingOtherTenants() {
        sink.failing.add("tenantA");
        buffer.add(rec("tenantA", "WARN", 1, "a1")).add(rec("tenantB", "WARN", 2, "b1"));
        TestSettings fast = new TestSettings(c -> {
            c.setMinLevel("WARN");
            c.setIntervalMs(5_000);
        });
        ServiceLogCollector c = collector(Set.of("tenantA", "tenantB"), fast);

        c.collect();
        assertTrue(sink.logsOf("tenantB").contains("b1"), "B was not held up by A");
        assertEquals(1, buffer.pending.get("tenantA").size(), "A's record was put back");
        assertFalse(buffer.pending.containsKey("tenantB"));

        sink.failing.clear();
        buffer.add(rec("tenantA", "WARN", 5, "a2"));
        now = T0.plusSeconds(6);
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
        TestSettings tiny = new TestSettings(c -> {
            c.setMinLevel("WARN");
            c.setMaxChunkBytes(1024);
            c.setMaxRecordBytes(1024);
        });

        collector(Set.of("tenantA"), tiny).collect();

        int storedLines = sink.written.get("tenantA").stream().mapToInt(ServiceLogChunk::lines).sum();
        int restored = buffer.pending.get("tenantA").size();
        assertEquals(30, storedLines + restored, "nothing lost and nothing stored twice");
        assertTrue(storedLines > 0 && restored > 0);
    }

    @Test
    void theRestoreUsesThatTenantsOwnBufferBound() {
        sink.failing.add("tenantA");
        buffer.add(rec("tenantA", "WARN", 1, "a1"));
        TestSettings settings = new TestSettings().with("tenantA", c -> {
            c.setMinLevel("WARN");
            c.setMaxBufferedRecords(300);
        });

        collector(Set.of("tenantA"), settings).collect();

        assertEquals(300, buffer.lastRestoreBound);
    }

    @Test
    void whatDoesNotFitTheTenantsLineCapWaitsForTheNextRunInsteadOfBeingDropped() {
        TestSettings settings = new TestSettings(c -> {
            c.setMinLevel("WARN");
            c.setMaxLinesPerRun(3);
            c.setIntervalMs(5_000);
        });
        ServiceLogCollector c = collector(Set.of("tenantA"), settings);
        for (int i = 0; i < 5; i++) {
            buffer.add(rec("tenantA", "WARN", i, "line " + i));
        }

        c.collect();
        assertEquals(3, sink.written.get("tenantA").stream().mapToInt(ServiceLogChunk::lines).sum());
        assertEquals(2, buffer.pending.get("tenantA").size(), "the newest two are kept");

        now = T0.plusSeconds(6);
        c.collect();
        assertTrue(sink.logsOf("tenantA").contains("line 3") && sink.logsOf("tenantA").contains("line 4"));
        assertTrue(buffer.pending.isEmpty());
    }

    @Test
    void recordsThatReachedTheBufferOutOfOrderAreStoredInTimeOrder() {
        buffer.add(rec("tenantA", "WARN", 9, "newest")).add(rec("tenantA", "WARN", 2, "oldest"));

        collector(Set.of("tenantA")).collect();

        ServiceLogChunk chunk = sink.written.get("tenantA").get(0);
        assertEquals("oldest\nnewest", chunk.logs());
        assertEquals(T0.plusSeconds(9), chunk.dateTo(), "the chunk ends at its newest record");
    }

    @Test
    void linesLostToABufferOverflowAreReportedOnTheTenantsNextChunk() {
        buffer.add(rec("tenantA", "WARN", 1, "after the gap"));
        buffer.dropped.put("tenantA", 7);

        collector(Set.of("tenantA")).collect();

        assertEquals(7, sink.written.get("tenantA").get(0).omitted());
    }

    @Test
    void theOverflowCountIsKeptWhenTheChunkThatWouldCarryItCouldNotBeStored() {
        buffer.add(rec("tenantA", "WARN", 1, "after the gap"));
        buffer.dropped.put("tenantA", 7);
        sink.failing.add("tenantA");
        ServiceLogCollector c = collector(Set.of("tenantA"));

        c.collect();
        assertEquals(7, buffer.dropped.get("tenantA"), "not lost with the failed write");

        sink.failing.clear();
        now = T0.plusSeconds(60);
        c.collect();
        assertEquals(7, sink.written.get("tenantA").get(0).omitted(), "reported once storing works again");
        assertTrue(buffer.dropped.isEmpty());
    }

    @Test
    void theOverflowCountWaitsForTheNextChunkWhenNothingIsBufferedToCarryIt() {
        buffer.dropped.put("tenantA", 4);

        collector(Set.of("tenantA")).collect();

        assertEquals(4, buffer.dropped.get("tenantA"));
    }

    @Test
    void aHugeRecordIsCutAndStoredInsteadOfBlockingTheTenantForGood() {
        buffer.add(rec("tenantA", "ERROR", 1, "payload " + "x".repeat(2_000_000)));

        collector(Set.of("tenantA")).collect();

        assertEquals(1, sink.written.get("tenantA").size());
        assertTrue(sink.written.get("tenantA").get(0).logs().length() <= 8192);
        assertTrue(buffer.pending.isEmpty());
    }

    @Test
    void theCollectorsOwnLinesAreNeverStored() {
        buffer.add(new ServiceLogRecord(T0, "ERROR", "t", "tenantA", "d.m.l.ServiceLogCollector", "collector failed"));

        collector(Set.of("tenantA")).collect();

        assertTrue(sink.written.isEmpty());
    }

    @Test
    void aFailingBufferDoesNotThrow() {
        buffer.failDrain = new IllegalStateException("boom");

        collector(Set.of("tenantA")).collect(); // must not throw

        assertEquals(0, sink.writes);
    }
}
