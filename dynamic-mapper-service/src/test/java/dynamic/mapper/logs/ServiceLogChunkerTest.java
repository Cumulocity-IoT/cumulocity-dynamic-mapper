package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ServiceLogChunkerTest {

    private static final ServiceLogPolicy WARN = new ServiceLogPolicy("WARN", 500, 65536, List.of("ServiceLogCollector"));

    private static ServiceLogRecord rec(String tenant, String level, int second, String text) {
        return new ServiceLogRecord(Instant.parse("2026-10-15T11:00:00Z").plusSeconds(second), level, "t", tenant,
                "d.m.Foo", text);
    }

    @Test
    void eachTenantOnlyEverReceivesItsOwnRecords() {
        List<ServiceLogRecord> records = List.of(
                rec("tenantA", "WARN", 1, "a1 secret-of-A"),
                rec("tenantB", "ERROR", 2, "b1 secret-of-B"),
                rec("tenantA", "ERROR", 3, "a2"));

        Map<String, List<ServiceLogChunk>> out = ServiceLogChunker.chunk(records, WARN,
                Set.of("tenantA", "tenantB"), Map.of());

        String a = out.get("tenantA").get(0).logs();
        String b = out.get("tenantB").get(0).logs();
        assertTrue(a.contains("a1") && a.contains("a2") && !a.contains("secret-of-B"), a);
        assertTrue(b.contains("b1") && !b.contains("secret-of-A") && !b.contains("a2"), b);
    }

    @Test
    void unattributedAndUnsubscribedRecordsGoNowhere() {
        List<ServiceLogRecord> records = List.of(
                rec(null, "ERROR", 1, "global error"),
                rec("gone", "ERROR", 2, "from an unsubscribed tenant"),
                rec("tenantA", "ERROR", 3, "kept"));

        Map<String, List<ServiceLogChunk>> out = ServiceLogChunker.chunk(records, WARN, Set.of("tenantA"), Map.of());

        assertEquals(Set.of("tenantA"), out.keySet());
        assertFalse(out.get("tenantA").get(0).logs().contains("global"));
    }

    @Test
    void recordsBelowTheMinimumLevelAndTheCollectorsOwnLinesAreDropped() {
        ServiceLogRecord info = rec("tenantA", "INFO", 1, "info with device payload");
        ServiceLogRecord own = new ServiceLogRecord(Instant.parse("2026-10-15T11:00:02Z"), "ERROR", "t", "tenantA",
                "d.m.l.ServiceLogCollector", "collector failed");
        ServiceLogRecord warn = rec("tenantA", "WARN", 3, "warn");

        Map<String, List<ServiceLogChunk>> out = ServiceLogChunker.chunk(List.of(info, own, warn), WARN,
                Set.of("tenantA"), Map.of());

        String logs = out.get("tenantA").get(0).logs();
        assertEquals("warn", logs);
    }

    @Test
    void alreadyCollectedRecordsAreSkippedPerTenant() {
        List<ServiceLogRecord> records = List.of(rec("tenantA", "WARN", 1, "old"), rec("tenantA", "WARN", 5, "new"),
                rec("tenantB", "WARN", 1, "b-first-time"));

        Map<String, List<ServiceLogChunk>> out = ServiceLogChunker.chunk(records, WARN, Set.of("tenantA", "tenantB"),
                Map.of("tenantA", Instant.parse("2026-10-15T11:00:01Z")));

        assertEquals("new", out.get("tenantA").get(0).logs());
        assertEquals("b-first-time", out.get("tenantB").get(0).logs());
    }

    @Test
    void theLineCapOmitsTheRestAndSaysSo() {
        ServiceLogPolicy small = new ServiceLogPolicy("WARN", 3, 65536, List.of());
        List<ServiceLogRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(rec("tenantA", "WARN", i, "line " + i));
        }

        ServiceLogChunk chunk = ServiceLogChunker.chunk(records, small, Set.of("tenantA"), Map.of()).get("tenantA").get(0);

        assertEquals(3, chunk.lines());
        assertEquals(2, chunk.omitted());
        assertTrue(chunk.summary().contains("2 omitted"), chunk.summary());
    }

    @Test
    void largeRunsAreSplitIntoContiguousChunksBelowTheByteLimit() {
        ServiceLogPolicy tiny = new ServiceLogPolicy("WARN", 500, 1024, List.of());
        List<ServiceLogRecord> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            records.add(rec("tenantA", "WARN", i, "x".repeat(100) + i));
        }

        List<ServiceLogChunk> chunks = ServiceLogChunker.chunk(records, tiny, Set.of("tenantA"), Map.of()).get("tenantA");

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.logs().getBytes().length <= 1024));
        assertEquals(30, chunks.stream().mapToInt(ServiceLogChunk::lines).sum());
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).dateFrom().isAfter(chunks.get(i - 1).dateTo()), "chunks are contiguous and ordered");
        }
    }

    @Test
    void aMultiLineRecordCountsEveryLineAgainstTheCap() {
        ServiceLogPolicy small = new ServiceLogPolicy("WARN", 4, 65536, List.of());
        List<ServiceLogRecord> records = List.of(rec("tenantA", "ERROR", 1, "boom\n\tat a\n\tat b"),
                rec("tenantA", "ERROR", 2, "second\n\tat c\n\tat d"));

        ServiceLogChunk chunk = ServiceLogChunker.chunk(records, small, Set.of("tenantA"), Map.of()).get("tenantA").get(0);

        assertEquals(3, chunk.lines());
        assertEquals(1, chunk.omitted());
    }

    @Test
    void thePolicyRejectsNonsense() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceLogPolicy("LOUD", 10, 4096, List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceLogPolicy("WARN", 0, 4096, List.of()));
    }
}
