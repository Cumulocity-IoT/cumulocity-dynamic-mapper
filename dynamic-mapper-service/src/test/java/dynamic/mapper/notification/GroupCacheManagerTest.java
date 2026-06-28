/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.notification.GroupCacheManager.CachedGroup;

/**
 * Unit tests for {@link GroupCacheManager}.
 *
 * <p>No Mockito is required here: {@link ManagedObjectRepresentation} is a plain
 * representation object that can be instantiated directly and configured via its
 * setters, and the cache manager has a single {@code String} constructor argument.
 */
class GroupCacheManagerTest {

    private static final String TEST_TENANT = "test-tenant";
    private static final int MAX_CACHE_SIZE = 10000;

    private GroupCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new GroupCacheManager(TEST_TENANT);
    }

    @AfterEach
    void tearDown() {
        // Always shut down the scheduler thread so tests do not leak threads.
        if (cacheManager != null) {
            cacheManager.cleanup();
        }
    }

    private static ManagedObjectRepresentation group(String id) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(id));
        return mo;
    }

    private boolean isGroupPresent(String groupId) {
        return cacheManager.getCache().containsKey(groupId);
    }

    // ---------------------------------------------------------------------
    // H4 — CachedGroup.subscribedDeviceIds: unsynchronized HashSet returned by
    // reference. The contract this test pins is that callers cannot mutate the
    // cached membership through the returned Set, and that concurrent
    // read/write traffic does not raise ConcurrentModificationException.
    // ---------------------------------------------------------------------
    @Test
    void cachedGroup_returnedSubscribedDevicesSet_isIsolated() throws Exception {
        cacheManager.addGroup(group("g1"));

        // Mutate the set handed back to the caller.
        Set<String> firstView = cacheManager.getSubscribedDevices("g1");
        firstView.add("device-injected-by-caller");

        // A subsequent read MUST NOT reflect the caller's mutation. If this
        // assertion fails, GroupCacheManager.getSubscribedDevices is leaking the
        // internal HashSet by reference (the H4 finding); the fix is to return a
        // defensive copy (new HashSet<>(entry.getSubscribedDeviceIds())) or an
        // unmodifiable view.
        Set<String> secondView = cacheManager.getSubscribedDevices("g1");
        assertFalse(secondView.contains("device-injected-by-caller"),
                "getSubscribedDevices leaked the internal HashSet by reference; "
                        + "external mutation should not be visible on a subsequent read");

        // Concurrent readers and writers must not trip a ConcurrentModificationException.
        final int readers = 5;
        final int writers = 5;
        final int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers + writers);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        // Iterate the returned set to surface any CME.
                        for (String ignored : cacheManager.getSubscribedDevices("g1")) {
                            // no-op, just force iteration
                            if (ignored == null) {
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        Set<String> ids = new HashSet<>();
                        ids.add("device-" + writerId + "-" + j);
                        cacheManager.updateSubscribedDevices("g1", ids);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished, "Concurrent read/write workload did not finish in time");
        if (failure.get() != null) {
            fail("Concurrent read/write raised an exception (likely ConcurrentModificationException): "
                    + failure.get());
        }
    }

    // ---------------------------------------------------------------------
    // M5 — the cleanup scheduler currently runs a no-op task per tenant and
    // wastes a thread. cleanup() must shut that scheduler down so the thread
    // is released.
    // ---------------------------------------------------------------------
    @Test
    void cleanup_shutsDownSchedulerThread_noLeak() throws Exception {
        Field executorField = GroupCacheManager.class.getDeclaredField("cacheCleanupExecutor");
        executorField.setAccessible(true);
        ScheduledExecutorService executor = (ScheduledExecutorService) executorField.get(cacheManager);

        assertNotNull(executor, "Scheduler executor should be created on construction");
        assertFalse(executor.isShutdown(), "Scheduler should be running before cleanup()");

        cacheManager.cleanup();

        assertTrue(executor.isShutdown(), "cleanup() must shut down the scheduler thread to avoid a leak");

        // Prevent the @AfterEach double-cleanup from acting on an already-cleaned manager.
        cacheManager = null;
    }

    // ---------------------------------------------------------------------
    // Basic add / present / remove behavior.
    // ---------------------------------------------------------------------
    @Test
    void addAndRemoveGroup_basicOperations_work() {
        ManagedObjectRepresentation g = group("g-basic");

        cacheManager.addGroup(g);
        assertTrue(isGroupPresent("g-basic"), "Group should be present after addGroup");

        cacheManager.removeGroup(g);
        assertFalse(isGroupPresent("g-basic"), "Group should be absent after removeGroup");
    }

    // ---------------------------------------------------------------------
    // cleanupOldestEntries must never compute a negative limit() argument when
    // the cache is filled beyond MAX_CACHE_SIZE (limit() throws
    // IllegalArgumentException for a negative value).
    // ---------------------------------------------------------------------
    @Test
    void cleanupOldestEntries_neverProducesNegativeLimitArgument() throws Exception {
        // Populate the internal cache directly beyond MAX_CACHE_SIZE so we can
        // exercise cleanupOldestEntries without paying addGroup's overhead and
        // without triggering its internal cleanup mid-fill.
        Field cacheField = GroupCacheManager.class.getDeclaredField("groupCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, CachedGroup> cache =
                (java.util.Map<String, CachedGroup>) cacheField.get(cacheManager);

        int total = MAX_CACHE_SIZE + 50;
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < total; i++) {
            // Stagger lastUpdated so the sort in cleanupOldestEntries has a defined order.
            cache.put("dummy-" + i,
                    new CachedGroup(group("dummy-" + i), base.plusSeconds(i), new HashSet<>()));
        }

        Method cleanup = GroupCacheManager.class.getDeclaredMethod("cleanupOldestEntries");
        cleanup.setAccessible(true);

        assertDoesNotThrow(() -> {
            try {
                cleanup.invoke(cacheManager);
            } catch (Exception e) {
                // Unwrap reflection wrapper so the real cause (e.g.
                // IllegalArgumentException from limit()) surfaces in the assertion.
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException(cause);
            }
        }, "cleanupOldestEntries must not pass a negative argument to Stream.limit()");

        // Sanity: cleanup should have shrunk the cache toward the 3/4 target.
        assertTrue(cache.size() < total, "cleanupOldestEntries should remove the oldest entries");
    }
}
