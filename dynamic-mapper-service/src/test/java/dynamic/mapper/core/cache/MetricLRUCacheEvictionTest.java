/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetricLRUCacheEvictionTest {

    private InventoryCache cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
            cache = null;
        }
    }

    private static Map<String, Object> mo(String tag) {
        Map<String, Object> m = new HashMap<>();
        m.put("tag", tag);
        return m;
    }

    @Test
    void evictsEldestAndNotifiesListenerWithEvictedKey() {
        cache = new InventoryCache(1, "t-evict");
        List<String> evicted = new CopyOnWriteArrayList<>();
        cache.setEvictionListener(evicted::add);

        cache.putMO("a", mo("a"));
        cache.putMO("b", mo("b")); // evicts "a"

        assertEquals(List.of("a"), evicted, "listener should be notified with the evicted key");
        assertEquals(1, cache.getCacheSize());
        assertNull(cache.getMOBySource("a"), "evicted entry must be gone");
        assertEquals("b", cache.getMOBySource("b").get("tag"));
    }

    @Test
    void doesNotNotifyWhenBelowCapacity() {
        cache = new InventoryCache(2, "t-no-evict");
        List<String> evicted = new CopyOnWriteArrayList<>();
        cache.setEvictionListener(evicted::add);

        cache.putMO("a", mo("a"));
        cache.putMO("b", mo("b"));

        assertTrue(evicted.isEmpty(), "no eviction expected while within capacity");
        assertEquals(2, cache.getCacheSize());
    }

    /**
     * The eviction listener must run <em>outside</em> the cache lock: while a
     * (deliberately blocking) listener is executing on one thread, another thread
     * must still be able to read and write the cache. With the listener invoked
     * under the synchronized-map monitor this would deadlock/time out.
     */
    @Test
    void listenerRunsOutsideCacheLock() throws Exception {
        cache = new InventoryCache(1, "t-lock");

        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicReference<Throwable> listenerError = new AtomicReference<>();

        cache.setEvictionListener(key -> {
            listenerEntered.countDown();
            try {
                // Simulate a blocking unsubscribe (I/O) inside the listener.
                if (!releaseListener.await(5, TimeUnit.SECONDS)) {
                    listenerError.set(new IllegalStateException("listener was never released"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listenerError.set(e);
            }
        });

        cache.putMO("a", mo("a"));

        // Thread A triggers the eviction of "a"; its listener blocks on releaseListener.
        Thread writer = new Thread(() -> cache.putMO("b", mo("b")), "evicting-writer");
        writer.start();

        // Wait until the listener is actually executing (and thus blocked).
        assertTrue(listenerEntered.await(5, TimeUnit.SECONDS), "listener should have started");

        // While the listener is blocked, the cache must remain usable from another
        // thread. Reads acquire the synchronized-map monitor, so if the writer still
        // held it during the listener call these would block and the join below
        // would time out. (A put is avoided here as it would re-trigger the same
        // blocking listener on this thread.)
        AtomicReference<Throwable> accessError = new AtomicReference<>();
        Thread accessor = new Thread(() -> {
            try {
                cache.getMOBySource("b");
                cache.getCacheSize();
            } catch (Throwable t) {
                accessError.set(t);
            }
        }, "concurrent-accessor");
        accessor.start();
        accessor.join(TimeUnit.SECONDS.toMillis(5));

        assertNull(accessError.get(), "concurrent cache access must not fail");
        assertTrue(!accessor.isAlive(),
                "concurrent access must complete while the listener is blocked (lock not held)");

        // Let the listener finish and the writer complete.
        releaseListener.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(5));
        assertNull(listenerError.get());
    }
}
