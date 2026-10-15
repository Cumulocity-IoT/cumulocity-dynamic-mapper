package dynamic.mapper.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ServiceLogRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static final Clock CLOCK = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return NOW; }
    };

    /** Records what each tenant was asked to purge. */
    static class RecordingSink implements ServiceLogSink {
        final Map<String, Instant> purged = new LinkedHashMap<>();
        final Set<String> failing = new java.util.HashSet<>();

        @Override
        public void write(String tenant, String instance, ServiceLogChunk chunk) {
            throw new AssertionError("retention must never write");
        }

        @Override
        public void purge(String tenant, Instant before) {
            if (failing.contains(tenant)) {
                throw new IllegalStateException("event API down");
            }
            purged.put(tenant, before);
        }
    }

    @Test
    void everySubscribedTenantIsPurgedUpToNowMinusTheRetention() {
        RecordingSink sink = new RecordingSink();

        List<String> failed = new ServiceLogRetention(sink, () -> Set.of("tenantA", "tenantB"), Duration.ofHours(24), CLOCK).run();

        assertTrue(failed.isEmpty());
        assertEquals(Set.of("tenantA", "tenantB"), sink.purged.keySet());
        assertTrue(sink.purged.values().stream().allMatch(NOW.minus(Duration.ofHours(24))::equals));
    }

    @Test
    void aTenantThatFailsDoesNotStopTheOthersAndIsReported() {
        RecordingSink sink = new RecordingSink();
        sink.failing.add("tenantA");

        List<String> failed = new ServiceLogRetention(sink, () -> new java.util.LinkedHashSet<>(List.of("tenantA", "tenantB", "tenantC")),
                Duration.ofHours(1), CLOCK).run();

        assertEquals(List.of("tenantA"), failed);
        assertEquals(Set.of("tenantB", "tenantC"), sink.purged.keySet());
    }

    @Test
    void aTenantThatIsNotSubscribedIsNeverTouched() {
        RecordingSink sink = new RecordingSink();

        new ServiceLogRetention(sink, () -> Set.of("tenantA"), Duration.ofHours(24), CLOCK).run();

        assertEquals(Set.of("tenantA"), sink.purged.keySet());
    }

    @Test
    void aFailureToListTheTenantsDoesNotThrow() {
        RecordingSink sink = new RecordingSink();

        List<String> failed = new ServiceLogRetention(sink, () -> {
            throw new IllegalStateException("subscriptions unavailable");
        }, Duration.ofHours(24), CLOCK).run(); // must not throw

        assertTrue(failed.isEmpty());
        assertTrue(sink.purged.isEmpty());
    }

    @Test
    void aRetentionOfZeroIsRejectedBecauseItWouldDeleteEverything() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceLogRetention(new RecordingSink(), () -> Set.of(), Duration.ZERO, CLOCK));
    }
}
