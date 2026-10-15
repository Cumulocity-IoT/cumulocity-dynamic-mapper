package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BoundedServiceLogBufferTest {

    private static ServiceLogRecord rec(String tenant, int n) {
        return new ServiceLogRecord(Instant.parse("2026-10-15T12:00:00Z").plusSeconds(n), "WARN", "t", tenant, "d.m.Foo",
                tenant + "#" + n);
    }

    private static List<String> texts(List<ServiceLogRecord> records) {
        return records.stream().map(ServiceLogRecord::text).toList();
    }

    @Test
    void drainReturnsWhatWaitsForTheAskedTenantsOldestFirstAndLeavesTheOthers() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        buffer.offer(rec("A", 1), 10);
        buffer.offer(rec("B", 2), 10);
        buffer.offer(rec("A", 3), 10);

        Map<String, List<ServiceLogRecord>> drained = buffer.drain(List.of("A"));

        assertEquals(List.of("A#1", "A#3"), texts(drained.get("A")));
        assertEquals(Set.of("A"), drained.keySet());
        assertEquals(List.of("B#2"), texts(buffer.drain(List.of("B")).get("B")), "B was not touched");
        assertTrue(buffer.drain(List.of("A", "B")).isEmpty());
    }

    @Test
    void aFloodingTenantDropsItsOwnOldestRecordsAndNeverAnotherTenantsLines() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        buffer.offer(rec("quiet", 0), 3);
        for (int i = 1; i <= 10; i++) {
            buffer.offer(rec("noisy", i), 3);
        }

        Map<String, List<ServiceLogRecord>> drained = buffer.drain(List.of("noisy", "quiet"));

        assertEquals(List.of("noisy#8", "noisy#9", "noisy#10"), texts(drained.get("noisy")));
        assertEquals(List.of("quiet#0"), texts(drained.get("quiet")), "the other tenant lost nothing");
        assertEquals(Map.of("noisy", 7), buffer.takeDropped(List.of("noisy", "quiet")));
        assertTrue(buffer.takeDropped(List.of("noisy")).isEmpty(), "counts reset when taken");
    }

    @Test
    void eachTenantHasItsOwnBound() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        for (int i = 0; i < 5; i++) {
            buffer.offer(rec("small", i), 2);
            buffer.offer(rec("large", i), 100);
        }

        Map<String, List<ServiceLogRecord>> drained = buffer.drain(List.of("small", "large"));

        assertEquals(2, drained.get("small").size());
        assertEquals(5, drained.get("large").size());
    }

    @Test
    void droppedCountsOfTenantsThatAreNotAskedForAreKept() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        for (int i = 0; i < 4; i++) {
            buffer.offer(rec("A", i), 2);
        }

        assertTrue(buffer.takeDropped(List.of("B")).isEmpty());
        assertEquals(Map.of("A", 2), buffer.takeDropped(List.of("A")));
    }

    @Test
    void restoredRecordsComeBackInFrontOfNewerOnesInTheirOriginalOrder() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        buffer.offer(rec("A", 1), 10);
        buffer.offer(rec("A", 2), 10);
        List<ServiceLogRecord> drained = buffer.drain(List.of("A")).get("A");
        buffer.offer(rec("A", 3), 10); // logged while the store was failing

        buffer.restore("A", drained, 10);

        assertEquals(List.of("A#1", "A#2", "A#3"), texts(buffer.drain(List.of("A")).get("A")));
    }

    @Test
    void restoringIntoAFullBufferStillRespectsTheBound() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        for (int i = 1; i <= 3; i++) {
            buffer.offer(rec("A", i), 3);
        }

        buffer.restore("A", List.of(rec("A", 0)), 3);

        assertEquals(3, buffer.drain(List.of("A")).get("A").size());
        assertEquals(Map.of("A", 1), buffer.takeDropped(List.of("A")));
    }

    @Test
    void tenantsThatAreGoneAreForgottenSoTheyDoNotHoldMemory() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        buffer.offer(rec("stays", 1), 10);
        buffer.offer(rec("gone", 2), 10);

        buffer.retainOnly(Set.of("stays"));

        assertEquals(Set.of("stays"), buffer.drain(List.of("stays", "gone")).keySet());
    }

    @Test
    void discardingATenantRemovesItsRecordsAndItsDropCounts() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer();
        for (int i = 0; i < 5; i++) {
            buffer.offer(rec("A", i), 2);
        }

        buffer.discard("A");

        assertTrue(buffer.drain(List.of("A")).isEmpty());
        assertTrue(buffer.takeDropped(List.of("A")).isEmpty());
    }
}
