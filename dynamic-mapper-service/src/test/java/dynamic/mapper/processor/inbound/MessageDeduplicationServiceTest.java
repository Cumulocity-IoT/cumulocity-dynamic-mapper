package dynamic.mapper.processor.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MessageDeduplicationServiceTest {

    /** A clock the test can move. */
    private static final class MovingClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();
    private final MessageDeduplicationService service = new MessageDeduplicationService(clock);

    @Test
    void secondCopyInsideTheWindowIsADuplicate() {
        assertFalse(service.isDuplicate("t", "m", "fp", 60));
        assertTrue(service.isDuplicate("t", "m", "fp", 60));
        assertEquals(1, service.getDroppedCount("t", "m"));
    }

    @Test
    void copyAfterTheWindowIsProcessedAgain() {
        assertFalse(service.isDuplicate("t", "m", "fp", 10));
        clock.advance(Duration.ofSeconds(11));
        assertFalse(service.isDuplicate("t", "m", "fp", 10));
    }

    @Test
    void windowDoesNotSlide() {
        assertFalse(service.isDuplicate("t", "m", "fp", 10));
        clock.advance(Duration.ofSeconds(6));
        assertTrue(service.isDuplicate("t", "m", "fp", 10));
        clock.advance(Duration.ofSeconds(6)); // 12 s after the first copy
        assertFalse(service.isDuplicate("t", "m", "fp", 10));
    }

    @Test
    void mappingsAndTenantsAreIndependent() {
        assertFalse(service.isDuplicate("t1", "m1", "fp", 60));
        assertFalse(service.isDuplicate("t1", "m2", "fp", 60));
        assertFalse(service.isDuplicate("t2", "m1", "fp", 60));
    }

    @Test
    void windowOfZeroDisablesTheCheck() {
        assertFalse(service.isDuplicate("t", "m", "fp", 0));
        assertFalse(service.isDuplicate("t", "m", "fp", 0));
        assertEquals(0, service.size("t", "m"));
    }

    @Test
    void windowIsClampedToOneHour() {
        assertEquals(3600, MessageDeduplicationService.clampWindowSeconds(99_999));
        assertEquals(0, MessageDeduplicationService.clampWindowSeconds(-5));
        assertEquals(1, MessageDeduplicationService.clampWindowSeconds(1));
    }

    @Test
    void evictExpiredRemovesOldEntriesAndEmptyMappings() {
        service.isDuplicate("t", "m", "a", 5);
        service.isDuplicate("t", "m", "b", 600);
        clock.advance(Duration.ofSeconds(10));
        service.evictExpired("t");
        assertEquals(1, service.size("t", "m"));
        clock.advance(Duration.ofSeconds(700));
        service.evictExpired("t");
        assertEquals(0, service.size("t", "m"));
    }

    @Test
    void fullStoreFailsOpen() {
        for (int i = 0; i < MessageDeduplicationService.MAX_ENTRIES_PER_MAPPING; i++) {
            service.isDuplicate("t", "m", "fp" + i, 60);
        }
        assertFalse(service.isDuplicate("t", "m", "overflow", 60));
        assertFalse(service.isDuplicate("t", "m", "overflow", 60), "overflow is not recorded");
        assertTrue(service.isDuplicate("t", "m", "fp0", 60), "recorded fingerprints still match");
    }

    @Test
    void concurrentCopiesOfOneMessageLetExactlyOnePass() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                if (!service.isDuplicate("t", "m", "same", 60)) {
                    passed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, passed.get());
        assertEquals(threads - 1, service.getDroppedCount("t", "m"));
    }

    @Test
    void fingerprintSeparatesTopicFromPayload() {
        assertNotEquals(
                MessageDeduplicationService.fingerprint("a", "bc".getBytes()),
                MessageDeduplicationService.fingerprint("ab", "c".getBytes()));
        assertEquals(
                MessageDeduplicationService.fingerprint("a", "bc".getBytes()),
                MessageDeduplicationService.fingerprint("a", "bc".getBytes()));
    }

    @Test
    void clearAndRemoveTenantDropEverything() {
        service.isDuplicate("t", "m", "fp", 60);
        service.isDuplicate("t", "m", "fp", 60);
        service.clearMapping("t", "m");
        assertEquals(0, service.size("t", "m"));
        assertEquals(0, service.getDroppedCount("t", "m"));
        service.isDuplicate("t", "m", "fp", 60);
        service.removeTenant("t");
        assertEquals(0, service.size("t", "m"));
    }
}
