package dynamic.mapper.processor.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void windowOutsideOneToThreeThousandSixHundredFailsOpen() {
        assertTrue(MessageDeduplicationService.isValidWindow(1));
        assertTrue(MessageDeduplicationService.isValidWindow(3600));
        assertFalse(MessageDeduplicationService.isValidWindow(0));
        assertFalse(MessageDeduplicationService.isValidWindow(3601));
        assertFalse(MessageDeduplicationService.isValidWindow(-5));

        // an out-of-range window is neither checked nor recorded, so the repeat is processed
        assertFalse(service.isDuplicate("t", "m", "fp", 7200));
        assertFalse(service.isDuplicate("t", "m", "fp", 7200));
        assertEquals(0, service.size("t", "m"));
    }

    @Test
    void clearMappingForgetsRecordedKeys() {
        assertFalse(service.isDuplicate("t", "m", "fp", 60));
        assertTrue(service.isDuplicate("t", "m", "fp", 60));
        service.clearMapping("t", "m");
        assertFalse(service.isDuplicate("t", "m", "fp", 60));
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
    }

    @Test
    void fingerprintIs128BitsOfHex() {
        String fp = MessageDeduplicationService.fingerprint("d1|t1");
        assertEquals(32, fp.length());
        assertEquals(fp, MessageDeduplicationService.fingerprint("d1|t1"));
        assertNotEquals(fp, MessageDeduplicationService.fingerprint("d1|t2"));
    }

    @Test
    void keyTextIsTheSameForTheSameValueInBothRuntimes() {
        assertEquals("abc", MessageDeduplicationService.keyText("abc"));
        assertEquals("5", MessageDeduplicationService.keyText(5));
        assertEquals("5", MessageDeduplicationService.keyText(5L));
        assertEquals("5", MessageDeduplicationService.keyText(5.0), "JavaScript prints 5.0 as 5");
        assertEquals("1.5", MessageDeduplicationService.keyText(1.5));
        assertEquals("true", MessageDeduplicationService.keyText(true));
        assertNull(MessageDeduplicationService.keyText(null));
    }

    @Test
    void keyTextOfObjectsDoesNotDependOnMemberOrder() {
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("b", java.util.List.of(1, 2.0));
        first.put("a", "x\"y");
        var second = new java.util.LinkedHashMap<String, Object>();
        second.put("a", "x\"y");
        second.put("b", java.util.List.of(1.0, 2));
        assertEquals("{\"a\":\"x\\\"y\",\"b\":[1,2]}", MessageDeduplicationService.keyText(first));
        assertEquals(MessageDeduplicationService.keyText(first), MessageDeduplicationService.keyText(second));
    }

    @Test
    void fullStoreScansForExpiredKeysAtMostOncePerInterval() {
        for (int i = 0; i < MessageDeduplicationService.MAX_ENTRIES_PER_MAPPING; i++) {
            service.isDuplicate("t", "m", "fp" + i, 1);
        }
        clock.advance(Duration.ofMillis(1500)); // every key has expired
        assertFalse(service.isDuplicate("t", "m", "new1", 60), "the scan frees room, so the key is recorded");
        assertTrue(service.isDuplicate("t", "m", "new1", 60));
        assertEquals(1, service.size("t", "m"));
    }

    @Test
    void concurrentCleanupAndClearNeverLoseARecordedKey() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        pool.submit(() -> {
            while (running.get()) {
                service.evictExpired("t"); // removes the mapping map whenever it is empty
            }
            return null;
        });
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int id = i;
            pool.submit(() -> {
                for (int n = 0; n < 5000; n++) {
                    String fp = "k" + id + "-" + n;
                    service.isDuplicate("t", "m", fp, 600);
                    assertTrue(service.isDuplicate("t", "m", fp, 600), "the key just recorded must be found");
                }
                done.countDown();
                return null;
            });
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        running.set(false);
        pool.shutdown();
    }

    @Test
    void releaseGivesTheKeyBackSoTheRetryPasses() {
        var first = service.check("t", "m", "fp", 60);
        assertFalse(first.duplicate());
        assertTrue(service.isDuplicate("t", "m", "fp", 60));
        first.claim().release();
        assertFalse(service.isDuplicate("t", "m", "fp", 60), "the retry is processed");
    }

    @Test
    void aRepeatHasNoClaimToRelease() {
        service.check("t", "m", "fp", 60);
        assertNull(service.check("t", "m", "fp", 60).claim());
        assertNull(service.check("t", "m", "fp", 7200).claim(), "a failed-open check has none either");
    }

    @Test
    void aLateReleaseDoesNotUndoANewerRecording() {
        var old = service.check("t", "m", "fp", 60).claim();
        service.clearMapping("t", "m");
        clock.advance(Duration.ofMillis(5));
        assertFalse(service.isDuplicate("t", "m", "fp", 60)); // a newer message records the same key
        old.release();
        assertTrue(service.isDuplicate("t", "m", "fp", 60), "the newer recording survives");
    }

    @Test
    void clearAndRemoveTenantDropEverything() {
        service.isDuplicate("t", "m", "fp", 60);
        service.isDuplicate("t", "m", "fp", 60);
        service.clearMapping("t", "m");
        assertEquals(0, service.size("t", "m"));
        service.isDuplicate("t", "m", "fp", 60);
        service.removeTenant("t");
        assertEquals(0, service.size("t", "m"));
    }
}
