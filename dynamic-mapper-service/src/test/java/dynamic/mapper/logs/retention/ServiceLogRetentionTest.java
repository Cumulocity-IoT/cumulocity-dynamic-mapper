package dynamic.mapper.logs.retention;

import dynamic.mapper.logs.settings.TestSettings;

import dynamic.mapper.logs.model.ServiceLogChunk;
import dynamic.mapper.logs.store.ServiceLogSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static ServiceLogRetention retention(RecordingSink sink, Set<String> tenants, TestSettings settings, Clock clock) {
        return new ServiceLogRetention(sink, () -> tenants, settings, clock);
    }

    private static class Ticking extends Clock {
        Instant now = NOW;

        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void everySubscribedTenantIsPurgedUpToNowMinusItsOwnRetention() {
        RecordingSink sink = new RecordingSink();
        TestSettings settings = new TestSettings().with("tenantB", c -> c.setRetentionHours(2));

        List<String> failed = retention(sink, Set.of("tenantA", "tenantB"), settings, CLOCK).run();

        assertTrue(failed.isEmpty());
        assertEquals(NOW.minus(Duration.ofHours(24)), sink.purged.get("tenantA"), "the default");
        assertEquals(NOW.minus(Duration.ofHours(2)), sink.purged.get("tenantB"), "its own");
    }

    @Test
    void aTenantThatSwitchedRetentionOffIsLeftAlone() {
        RecordingSink sink = new RecordingSink();
        TestSettings settings = new TestSettings().with("tenantA", c -> c.setRetentionEnabled(false));

        retention(sink, Set.of("tenantA", "tenantB"), settings, CLOCK).run();

        assertEquals(Set.of("tenantB"), sink.purged.keySet());
    }

    @Test
    void retentionStillRunsForATenantThatSwitchedTheServiceLogOffSoOldLinesDoNotStayForever() {
        RecordingSink sink = new RecordingSink();
        TestSettings settings = new TestSettings().with("tenantA", c -> c.setEnabled(false));

        retention(sink, Set.of("tenantA"), settings, CLOCK).run();

        assertEquals(Set.of("tenantA"), sink.purged.keySet());
    }

    @Test
    void eachTenantIsPurgedOnItsOwnIntervalNotOnEveryPass() {
        RecordingSink sink = new RecordingSink();
        Ticking clock = new Ticking();
        ServiceLogRetention retention = retention(sink, Set.of("tenantA"),
                new TestSettings().with("tenantA", c -> c.setRetentionIntervalMs(600_000)), clock);

        retention.run();
        sink.purged.clear();
        clock.now = NOW.plus(Duration.ofMinutes(5));
        retention.run();
        assertTrue(sink.purged.isEmpty(), "not due after 5 minutes of a 10 minute interval");

        clock.now = NOW.plus(Duration.ofMinutes(11));
        retention.run();
        assertEquals(Set.of("tenantA"), sink.purged.keySet());
    }

    @Test
    void aTenantThatFailsDoesNotStopTheOthersAndIsRetriedOnTheNextPass() {
        RecordingSink sink = new RecordingSink();
        sink.failing.add("tenantA");
        Ticking clock = new Ticking();
        ServiceLogRetention retention = retention(sink,
                new java.util.LinkedHashSet<>(List.of("tenantA", "tenantB", "tenantC")), new TestSettings(), clock);

        List<String> failed = retention.run();

        assertEquals(List.of("tenantA"), failed);
        assertEquals(Set.of("tenantB", "tenantC"), sink.purged.keySet());

        sink.failing.clear();
        clock.now = NOW.plusSeconds(60); // far less than the hourly interval, yet A is due again
        retention.run();
        assertTrue(sink.purged.containsKey("tenantA"), "a failed tenant is retried at once, not after a whole interval");
    }

    @Test
    void aFailureToListTheTenantsDoesNotThrow() {
        RecordingSink sink = new RecordingSink();

        List<String> failed = new ServiceLogRetention(sink, () -> {
            throw new IllegalStateException("subscriptions unavailable");
        }, new TestSettings(), CLOCK).run(); // must not throw

        assertTrue(failed.isEmpty());
        assertTrue(sink.purged.isEmpty());
    }
}
