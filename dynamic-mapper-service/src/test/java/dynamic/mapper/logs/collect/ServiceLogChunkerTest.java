package dynamic.mapper.logs.collect;

import dynamic.mapper.logs.model.ServiceLogChunk;
import dynamic.mapper.logs.model.ServiceLogPolicy;
import dynamic.mapper.logs.model.ServiceLogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dynamic.mapper.logs.collect.ServiceLogChunker.TenantChunks;

class ServiceLogChunkerTest {

    private static final ServiceLogPolicy WARN = new ServiceLogPolicy("WARN", 500, 65536, List.of("ServiceLogCollector"));

    private static ServiceLogRecord rec(String tenant, String level, int second, String text) {
        return new ServiceLogRecord(Instant.parse("2026-10-15T11:00:00Z").plusSeconds(second), level, "t", tenant,
                "d.m.Foo", text);
    }

    private static String logsOf(TenantChunks chunks) {
        return String.join("\n", chunks.chunks().stream().map(ServiceLogChunk::logs).toList());
    }

    @Test
    void aTenantOnlyEverReceivesRecordsThatNameIt() {
        List<ServiceLogRecord> mixed = List.of(
                rec("tenantA", "WARN", 1, "a1 secret-of-A"),
                rec("tenantB", "ERROR", 2, "b1 secret-of-B"),
                rec("tenantA", "ERROR", 3, "a2"));

        String a = logsOf(ServiceLogChunker.chunkTenant("tenantA", mixed, WARN));
        String b = logsOf(ServiceLogChunker.chunkTenant("tenantB", mixed, WARN));

        assertTrue(a.contains("a1") && a.contains("a2") && !a.contains("secret-of-B"), a);
        assertTrue(b.contains("b1") && !b.contains("secret-of-A") && !b.contains("a2"), b);
    }

    @Test
    void aRecordWithoutATenantOrWithAnotherOneIsNeverPartOfTheTenantBeingStored() {
        List<ServiceLogRecord> records = List.of(
                rec(null, "ERROR", 1, "global error"),
                rec("tenantB", "ERROR", 2, "from another tenant"),
                rec("tenantA", "ERROR", 3, "kept"));

        TenantChunks out = ServiceLogChunker.chunkTenant("tenantA", records, WARN);

        assertEquals("kept", logsOf(out));
        assertTrue(out.deferred().isEmpty(), "they are not deferred either: they simply do not belong");
    }

    @Test
    void recordsBelowTheMinimumLevelAndTheCollectorsOwnLinesAreDropped() {
        ServiceLogRecord info = rec("tenantA", "INFO", 1, "info with device payload");
        ServiceLogRecord own = new ServiceLogRecord(Instant.parse("2026-10-15T11:00:02Z"), "ERROR", "t", "tenantA",
                "d.m.l.ServiceLogCollector", "collector failed");
        ServiceLogRecord warn = rec("tenantA", "WARN", 3, "warn");

        assertEquals("warn", logsOf(ServiceLogChunker.chunkTenant("tenantA", List.of(info, own, warn), WARN)));
    }

    @Test
    void recordsAreStoredInTimeOrderEvenWhenTheyArrivedOutOfOrder() {
        // stamped when logged, but queued by whichever thread got there first
        List<ServiceLogRecord> arrival = List.of(rec("tenantA", "WARN", 5, "newest"), rec("tenantA", "WARN", 1, "oldest"),
                rec("tenantA", "WARN", 3, "middle"));

        ServiceLogChunk chunk = ServiceLogChunker.chunkTenant("tenantA", arrival, WARN).chunks().get(0);

        assertEquals("oldest\nmiddle\nnewest", chunk.logs());
        assertEquals(Instant.parse("2026-10-15T11:00:01Z"), chunk.dateFrom());
        assertEquals(Instant.parse("2026-10-15T11:00:05Z"), chunk.dateTo(), "the chunk ends at its newest record");
    }

    @Test
    void recordsOfTheSameMillisecondKeepTheirArrivalOrder() {
        List<ServiceLogRecord> records = List.of(rec("tenantA", "WARN", 1, "first"), rec("tenantA", "WARN", 1, "second"),
                rec("tenantA", "WARN", 1, "third"));

        assertEquals("first\nsecond\nthird", ServiceLogChunker.chunkTenant("tenantA", records, WARN).chunks().get(0).logs());
    }

    @Test
    void whatDoesNotFitTheLineCapIsDeferredNotDropped() {
        ServiceLogPolicy small = new ServiceLogPolicy("WARN", 3, 65536, List.of());
        List<ServiceLogRecord> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(rec("tenantA", "WARN", i, "line " + i));
        }

        TenantChunks result = ServiceLogChunker.chunkTenant("tenantA", records, small);

        assertEquals(3, result.chunks().stream().mapToInt(ServiceLogChunk::lines).sum());
        assertEquals(List.of("line 3", "line 4"), result.deferred().stream().map(ServiceLogRecord::text).toList(),
                "the NEWEST lines wait for the next run, in order");
    }

    @Test
    void aMultiLineRecordCountsEveryLineAgainstTheCap() {
        ServiceLogPolicy small = new ServiceLogPolicy("WARN", 4, 65536, List.of());
        List<ServiceLogRecord> records = List.of(rec("tenantA", "ERROR", 1, "boom\n\tat a\n\tat b"),
                rec("tenantA", "ERROR", 2, "second\n\tat c\n\tat d"));

        TenantChunks result = ServiceLogChunker.chunkTenant("tenantA", records, small);

        assertEquals(3, result.chunks().get(0).lines());
        assertEquals(1, result.deferred().size());
    }

    @Test
    void aRecordLongerThanTheCapIsStillTakenOtherwiseItWouldWaitForever() {
        ServiceLogPolicy small = new ServiceLogPolicy("WARN", 2, 65536, List.of());
        ServiceLogRecord longTrace = rec("tenantA", "ERROR", 1, "boom\n\tat a\n\tat b\n\tat c\n\tat d");

        TenantChunks result = ServiceLogChunker.chunkTenant("tenantA", List.of(longTrace), small);

        assertEquals(1, result.chunks().size());
        assertTrue(result.deferred().isEmpty());
    }

    @Test
    void aSingleHugeRecordIsCutSoItCanNeverMakeAnEventTheServerRejects() {
        ServiceLogPolicy policy = new ServiceLogPolicy("WARN", 500, 65536, 1024, List.of());
        ServiceLogRecord huge = rec("tenantA", "ERROR", 1, "payload " + "x".repeat(5_000_000));

        ServiceLogChunk chunk = ServiceLogChunker.chunkTenant("tenantA", List.of(huge), policy).chunks().get(0);

        assertTrue(chunk.logs().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024, "size " + chunk.logs().length());
        assertTrue(chunk.logs().endsWith("[truncated]"));
    }

    @Test
    void largeRunsAreSplitIntoContiguousChunksBelowTheByteLimit() {
        ServiceLogPolicy tiny = new ServiceLogPolicy("WARN", 500, 1024, List.of());
        List<ServiceLogRecord> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            records.add(rec("tenantA", "WARN", i, "x".repeat(100) + i));
        }

        List<ServiceLogChunk> chunks = ServiceLogChunker.chunkTenant("tenantA", records, tiny).chunks();

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.logs().getBytes().length <= 1024));
        assertEquals(30, chunks.stream().mapToInt(ServiceLogChunk::lines).sum());
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).dateFrom().isAfter(chunks.get(i - 1).dateTo()), "chunks are contiguous and ordered");
        }
    }

    @Test
    void everyChunkRemembersExactlyTheRecordsItWasBuiltFrom() {
        ServiceLogPolicy tiny = new ServiceLogPolicy("WARN", 500, 1024, List.of());
        List<ServiceLogRecord> records = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            records.add(rec("tenantA", "WARN", i, "x".repeat(200) + i));
        }

        List<ServiceLogChunk> chunks = ServiceLogChunker.chunkTenant("tenantA", records, tiny).chunks();

        assertEquals(12, chunks.stream().mapToInt(c -> c.records().size()).sum());
        for (ServiceLogChunk c : chunks) {
            assertEquals(c.records().size(), c.levels().values().stream().mapToInt(Integer::intValue).sum());
        }
    }

    @Test
    void thePolicyRejectsNonsense() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceLogPolicy("LOUD", 10, 4096, List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceLogPolicy("WARN", 0, 4096, List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ServiceLogPolicy("WARN", 10, 4096, 8192, List.of()), "a record cannot exceed a chunk");
    }

    @Test
    void limitNeverCutsInsideAMultiByteCharacter() {
        ServiceLogPolicy policy = new ServiceLogPolicy("WARN", 500, 65536, 300, List.of());
        String text = "ä".repeat(1000); // two bytes each

        String limited = policy.limit(text);

        assertTrue(limited.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 300);
        assertFalse(limited.contains("�"), "no broken character");
    }
}
