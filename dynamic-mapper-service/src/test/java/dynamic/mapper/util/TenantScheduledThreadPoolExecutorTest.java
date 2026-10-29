package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** A timer has no scope of its own, so a tenant-owned one must give every task the tenant's. */
class TenantScheduledThreadPoolExecutorTest {

    private static TenantScheduledThreadPoolExecutor executor(String tenant) {
        return new TenantScheduledThreadPoolExecutor(tenant, 1, r -> {
            Thread t = new Thread(r, "test-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Test
    void aDelayedTaskRunsUnderTheExecutorsTenantEvenThoughTheSchedulerHadNone() throws Exception {
        TenantScheduledThreadPoolExecutor timer = executor("tenantA");
        AtomicReference<String> seen = new AtomicReference<>("not run");
        CountDownLatch done = new CountDownLatch(1);
        assertNull(TenantLogContext.current(), "the scheduling thread has no scope");

        timer.schedule(() -> {
            seen.set(TenantLogContext.current());
            done.countDown();
        }, 10, TimeUnit.MILLISECONDS);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("tenantA", seen.get());
        timer.shutdownNow();
    }

    @Test
    void aRepeatingTaskKeepsTheTenantOnEveryRunAndLeavesNothingBehind() throws Exception {
        TenantScheduledThreadPoolExecutor timer = executor("tenantB");
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);

        timer.scheduleAtFixedRate(() -> {
            seen.add(TenantLogContext.current());
            three.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS);

        assertTrue(three.await(5, TimeUnit.SECONDS));
        timer.shutdownNow();
        assertTrue(seen.stream().allMatch("tenantB"::equals), seen.toString());

        // execute()/submit() of a ScheduledThreadPoolExecutor go through schedule(), so they are scoped too
        AtomicReference<String> viaExecute = new AtomicReference<>("not run");
        CountDownLatch other = new CountDownLatch(1);
        TenantScheduledThreadPoolExecutor second = executor("tenantC");
        second.execute(() -> {
            viaExecute.set(TenantLogContext.current());
            other.countDown();
        });
        assertTrue(other.await(5, TimeUnit.SECONDS));
        assertEquals("tenantC", viaExecute.get());
        assertNull(TenantLogContext.current(), "the calling thread was never touched");
        second.shutdownNow();
    }
}
