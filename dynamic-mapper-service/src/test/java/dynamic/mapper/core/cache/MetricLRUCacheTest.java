/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MetricLRUCacheTest {

    private static class TestCache extends MetricLRUCache<String, String> {
        TestCache(int size, long ttl, String tenant) {
            super(size, tenant, "test_cache_size", ttl);
        }

        String get(String k) { return getEntry(k); }
        void put(String k, String v) { putEntry(k, v); }
    }

    @Test
    void evictsLeastRecentlyUsedNotOldestInserted() {
        try (TestCache c = new TestCache(2, 0, "lru")) {
            c.put("a", "1");
            c.put("b", "2");
            c.get("a"); // touch a, so b is now the least recently used
            c.put("c", "3");

            assertEquals("1", c.get("a"));
            assertNull(c.get("b"));
            assertEquals("3", c.get("c"));
        }
    }

    @Test
    void expiredEntryIsMissingAndReportedToListener() throws InterruptedException {
        try (TestCache c = new TestCache(10, 20, "ttl")) {
            List<String> evicted = new ArrayList<>();
            c.setEvictionListener(evicted::add);
            c.put("a", "1");
            assertEquals("1", c.get("a"));

            Thread.sleep(40);

            assertNull(c.get("a"));
            assertEquals(List.of("a"), evicted);
            assertEquals(0, c.getCacheSize());
        }
    }

    @Test
    void ttlZeroDisablesExpiry() throws InterruptedException {
        try (TestCache c = new TestCache(10, 0, "nottl")) {
            c.put("a", "1");
            Thread.sleep(20);
            assertEquals("1", c.get("a"));
        }
    }
}
