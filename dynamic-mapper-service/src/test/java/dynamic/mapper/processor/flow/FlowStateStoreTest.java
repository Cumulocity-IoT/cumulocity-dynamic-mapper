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

package dynamic.mapper.processor.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * State is shared by every message of a mapping, and messages run in parallel. These tests pin down
 * that a message only ever changes the keys it wrote.
 */
class FlowStateStoreTest {

    private static final String TENANT = "t1";
    private static final String MAPPING = "m1";

    private final FlowStateStore store = new FlowStateStore();

    @Test
    void commitChangesKeepsKeysWrittenByConcurrentMessages() {
        // Both messages load the same (empty) snapshot, each writes its own key.
        Map<String, Object> loadedByA = store.loadState(TENANT, MAPPING);
        Map<String, Object> loadedByB = store.loadState(TENANT, MAPPING);
        assertTrue(loadedByA.isEmpty() && loadedByB.isEmpty());

        store.commitChanges(TENANT, MAPPING, false, Map.of("a", 1), Set.of());
        store.commitChanges(TENANT, MAPPING, false, Map.of("b", 2), Set.of());

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertEquals(1, state.get("a"));
        assertEquals(2, state.get("b"));
    }

    @Test
    void aMessageThatChangedNothingDoesNotRevertOthers() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("count", 1), Set.of());
        store.loadState(TENANT, MAPPING); // slow read-only message took its snapshot here
        store.commitChanges(TENANT, MAPPING, false, Map.of("count", 2), Set.of());

        store.commitChanges(TENANT, MAPPING, false, Map.of(), Set.of()); // read-only message finishes

        assertEquals(2, store.loadState(TENANT, MAPPING).get("count"));
    }

    @Test
    void aNullValueRemovesTheKeyInsteadOfFailing() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("x", 1, "y", 2), Set.of());
        Map<String, Object> upserts = new HashMap<>();
        upserts.put("x", null); // ConcurrentHashMap cannot hold null

        store.commitChanges(TENANT, MAPPING, false, upserts, Set.of());

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertFalse(state.containsKey("x"));
        assertEquals(2, state.get("y"));
    }

    @Test
    void removalsAndWipeWork() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("x", 1, "y", 2), Set.of());
        store.commitChanges(TENANT, MAPPING, false, Map.of(), Set.of("x"));
        assertNull(store.loadState(TENANT, MAPPING).get("x"));

        store.commitChanges(TENANT, MAPPING, true, Map.of("z", 3), Set.of());

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertEquals(Map.of("z", 3), state);
    }

    @Test
    void mappingsDoNotShareState() {
        store.commitChanges(TENANT, "m1", false, Map.of("k", 1), Set.of());
        store.commitChanges(TENANT, "m2", false, Map.of("k", 2), Set.of());

        assertEquals(1, store.loadState(TENANT, "m1").get("k"));
        assertEquals(2, store.loadState(TENANT, "m2").get("k"));
    }

    @Test
    void readersNeverSeeAHalfRebuiltMap() throws Exception {
        Map<String, Object> stable = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            stable.put("stable" + i, i);
        }
        store.commitChanges(TENANT, MAPPING, false, stable, Set.of());

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger torn = new AtomicInteger();
        Thread writer = new Thread(() -> {
            int n = 0;
            while (running.get()) {
                store.commitChanges(TENANT, MAPPING, false, Map.of("volatile", n++), Set.of());
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                if (store.loadState(TENANT, MAPPING).keySet().stream().filter(k -> k.startsWith("stable")).count() != 50) {
                    torn.incrementAndGet();
                }
            }
        });
        writer.start();
        reader.start();
        reader.join();
        running.set(false);
        writer.join();

        assertEquals(0, torn.get(), "a reader saw stable keys missing while another message committed");
    }

    // ---- retention ----

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

    @Test
    void aBusyMappingDoesNotKeepAnIdleOneAlive() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges(TENANT, "idle", false, Map.of("k", 1), Set.of());
        for (int hour = 0; hour < 30; hour++) {
            clock.advance(Duration.ofHours(1));
            s.commitChanges(TENANT, "busy", false, Map.of("k", hour), Set.of());
        }

        s.clearExpiredEntries(TENANT, 1440); // 24 h

        assertTrue(s.loadState(TENANT, "idle").isEmpty(), "idle for 30 h, must have expired");
        assertEquals(29, s.loadState(TENANT, "busy").get("k"));
    }

    @Test
    void aRecentMappingSurvivesWhenOthersExpire() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges(TENANT, "old", false, Map.of("k", 1), Set.of());
        clock.advance(Duration.ofHours(30));
        s.commitChanges(TENANT, "recent", false, Map.of("k", 2), Set.of());

        s.clearExpiredEntries(TENANT, 1440);

        assertTrue(s.loadState(TENANT, "old").isEmpty());
        assertEquals(2, s.loadState(TENANT, "recent").get("k"), "written a moment ago, must not be removed");
    }

    @Test
    void aReadDoesNotRefreshTheRetention() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges(TENANT, MAPPING, false, Map.of("k", 1), Set.of());
        clock.advance(Duration.ofHours(25));
        s.loadState(TENANT, MAPPING);
        s.commitChanges(TENANT, MAPPING, false, Map.of(), Set.of()); // read-only message, writes nothing

        s.clearExpiredEntries(TENANT, 1440);

        assertTrue(s.loadState(TENANT, MAPPING).isEmpty());
    }

    @Test
    void expiryIsLimitedToTheTenantAndCanBeDisabled() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges("t1", MAPPING, false, Map.of("k", 1), Set.of());
        s.commitChanges("t2", MAPPING, false, Map.of("k", 2), Set.of());
        clock.advance(Duration.ofHours(48));

        s.clearExpiredEntries("t1", 0); // 0 disables expiry
        assertEquals(1, s.loadState("t1", MAPPING).get("k"));

        s.clearExpiredEntries("t1", 1440);
        assertTrue(s.loadState("t1", MAPPING).isEmpty());
        assertEquals(2, s.loadState("t2", MAPPING).get("k"), "another tenant is not affected");
    }

    @Test
    void aMappingThatWritesAgainAfterExpiryStartsFresh() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges(TENANT, MAPPING, false, Map.of("old", 1), Set.of());
        clock.advance(Duration.ofHours(25));
        s.clearExpiredEntries(TENANT, 1440);

        s.commitChanges(TENANT, MAPPING, false, Map.of("new", 2), Set.of());
        clock.advance(Duration.ofHours(1));
        s.clearExpiredEntries(TENANT, 1440);

        assertEquals(Map.of("new", 2), s.loadState(TENANT, MAPPING));
    }
}
