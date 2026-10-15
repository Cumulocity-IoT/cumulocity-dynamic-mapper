package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    void drainReturnsEverythingPerTenantOldestFirstAndEmptiesTheBuffer() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer(10);
        buffer.offer(rec("A", 1));
        buffer.offer(rec("B", 2));
        buffer.offer(rec("A", 3));

        Map<String, List<ServiceLogRecord>> drained = buffer.drainAll();

        assertEquals(List.of("A#1", "A#3"), texts(drained.get("A")));
        assertEquals(List.of("B#2"), texts(drained.get("B")));
        assertTrue(buffer.drainAll().isEmpty());
    }

    @Test
    void aFloodingTenantDropsItsOwnOldestRecordsAndNeverAnotherTenantsLines() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer(3);
        buffer.offer(rec("quiet", 0));
        for (int i = 1; i <= 10; i++) {
            buffer.offer(rec("noisy", i));
        }

        Map<String, List<ServiceLogRecord>> drained = buffer.drainAll();

        assertEquals(List.of("noisy#8", "noisy#9", "noisy#10"), texts(drained.get("noisy")));
        assertEquals(List.of("quiet#0"), texts(drained.get("quiet")), "the other tenant lost nothing");
        assertEquals(Map.of("noisy", 7), buffer.takeDropped());
        assertTrue(buffer.takeDropped().isEmpty(), "counts reset when taken");
    }

    @Test
    void restoredRecordsComeBackInFrontOfNewerOnesInTheirOriginalOrder() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer(10);
        buffer.offer(rec("A", 1));
        buffer.offer(rec("A", 2));
        List<ServiceLogRecord> drained = buffer.drainAll().get("A");
        buffer.offer(rec("A", 3)); // logged while the store was failing

        buffer.restore("A", drained);

        assertEquals(List.of("A#1", "A#2", "A#3"), texts(buffer.drainAll().get("A")));
    }

    @Test
    void restoringIntoAFullBufferStillRespectsTheBound() {
        BoundedServiceLogBuffer buffer = new BoundedServiceLogBuffer(3);
        for (int i = 1; i <= 3; i++) {
            buffer.offer(rec("A", i));
        }

        buffer.restore("A", List.of(rec("A", 0)));

        assertEquals(3, buffer.drainAll().get("A").size());
        assertEquals(Map.of("A", 1), buffer.takeDropped());
    }
}
