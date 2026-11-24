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

import dynamic.mapper.processor.model.CollectOptions;
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
    void aReadOfStateRefreshesTheRetention() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges(TENANT, MAPPING, false, Map.of("k", 1), Set.of());
        clock.advance(Duration.ofHours(25));
        s.loadState(TENANT, MAPPING); // a message that only reads: the state is in use

        s.clearExpiredEntries(TENANT, 1440);

        assertEquals(1, s.loadState(TENANT, MAPPING).get("k"));
    }

    @Test
    void stateNobodyReadsOrWritesStillExpires() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges(TENANT, MAPPING, false, Map.of("k", 1), Set.of());
        clock.advance(Duration.ofHours(25));

        s.clearExpiredEntries(TENANT, 1440);

        assertTrue(s.loadState(TENANT, MAPPING).isEmpty());
    }

    @Test
    void aMaxAgeCallDoesNotDropAGroupCollectedWithoutOne() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.collect(TENANT, MAPPING, "A", "x", 1, CollectOptions.count(3)); // no age limit
        clock.advance(Duration.ofSeconds(60));
        s.collect(TENANT, MAPPING, "B", "y", 1, new CollectOptions(3, null, false, 10));

        assertEquals(java.util.List.of("A", "B"), s.openGroups(TENANT, MAPPING, ""));
    }

    @Test
    void aGroupIsStillDroppedByItsOwnMaxAge() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.collect(TENANT, MAPPING, "A", "x", 1, new CollectOptions(3, null, false, 10));
        clock.advance(Duration.ofSeconds(60));
        s.collect(TENANT, MAPPING, "B", "y", 1, new CollectOptions(3, null, false, 10));

        assertEquals(java.util.List.of("B"), s.openGroups(TENANT, MAPPING, ""));
    }

    @Test
    void clearingStateLeavesCollectedGroupsAlone() {
        FlowStateStore s = new FlowStateStore();
        s.commitChanges(TENANT, MAPPING, false, Map.of("k", 1), Set.of());
        s.collect(TENANT, MAPPING, "G", "x", 1, CollectOptions.count(3));

        s.commitChanges(TENANT, MAPPING, true, Map.of(), Set.of());

        assertTrue(s.loadState(TENANT, MAPPING).isEmpty());
        assertEquals(java.util.List.of("G"), s.openGroups(TENANT, MAPPING, ""));
    }

    @Test
    void expiryIsLimitedToTheTenantAndCanBeDisabled() {
        MutableClock clock = new MutableClock();
        FlowStateStore s = new FlowStateStore(clock);
        s.commitChanges("t1", MAPPING, false, Map.of("k", 1), Set.of());
        s.commitChanges("t2", MAPPING, false, Map.of("k", 2), Set.of());
        clock.advance(Duration.ofHours(48));

        s.clearExpiredEntries("t1", 0); // 0 disables expiry
        assertEquals(1, s.loadState("t1", MAPPING).get("k")); // the read itself refreshes the retention
        clock.advance(Duration.ofHours(48));

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

    @Test
    void collectReturnsTheGroupOnceCompleteAndOnlyOnce() {
        assertNull(store.collect(TENANT, MAPPING, "g", "a", 1, 3));
        assertNull(store.collect(TENANT, MAPPING, "g", "b", 2, 3));
        assertNull(store.collect(TENANT, MAPPING, "g", "b", 22, 3)); // repeated item replaces, does not count twice
        assertEquals(Map.of("a", 1, "b", 22, "c", 3), store.collect(TENANT, MAPPING, "g", "c", 3, 3));
        assertNull(store.collect(TENANT, MAPPING, "g", "a", 1, 3)); // a new group starts after hand-out
    }

    @Test
    void collectedGroupsAreInvisibleToStateAndGoWithTheMapping() {
        store.collect(TENANT, MAPPING, "g", "a", 1, 2);
        assertTrue(store.loadState(TENANT, MAPPING).isEmpty());

        store.clearMappingState(TENANT, MAPPING);

        assertNull(store.collect(TENANT, MAPPING, "g", "b", 2, 2)); // "a" is gone, so 1 of 2
    }

    @Test
    void collectDropsTheOldestIncompleteGroupBeyondTheCap() {
        for (int i = 0; i <= FlowStateStore.MAX_OPEN_GROUPS; i++) {
            store.collect(TENANT, MAPPING, "g" + i, "a", 1, 2);
        }
        // g0 was evicted: completing it now finds only one item; the newest group is still open
        assertNull(store.collect(TENANT, MAPPING, "g0", "b", 2, 2));
        assertEquals(Map.of("a", 1, "b", 2),
                store.collect(TENANT, MAPPING, "g" + FlowStateStore.MAX_OPEN_GROUPS, "b", 2, 2));
    }

    private static CollectOptions keys(String... keys) {
        return new CollectOptions(null, Set.of(keys), false, null);
    }

    @Test
    void collectCompletesWhenAllRequiredKeysArePresentAndIgnoresExtras() {
        assertNull(store.collect(TENANT, MAPPING, "g", "other", 0, keys("a", "b")));
        assertNull(store.collect(TENANT, MAPPING, "g", "a", 1, keys("a", "b")));
        assertEquals(Map.of("other", 0, "a", 1, "b", 2), store.collect(TENANT, MAPPING, "g", "b", 2, keys("a", "b")));
    }

    @Test
    void collectCompletesAtOnceOnTheLastItem() {
        store.collect(TENANT, MAPPING, "g", "a", 1, CollectOptions.count(100));
        var last = new CollectOptions(null, null, true, null);
        assertEquals(Map.of("a", 1, "b", 2), store.collect(TENANT, MAPPING, "g", "b", 2, last));
    }

    @Test
    void collectConditionsAreAlternatives() {
        var either = new CollectOptions(3, Set.of("x"), false, null);
        store.collect(TENANT, MAPPING, "g", "a", 1, either);
        assertEquals(Map.of("a", 1, "x", 9), store.collect(TENANT, MAPPING, "g", "x", 9, either)); // keys met first
    }

    @Test
    void takeReturnsAPartialGroupOnceAndLeavesNoGroup() {
        store.collect(TENANT, MAPPING, "g", "a", 1, 3);
        store.collect(TENANT, MAPPING, "g", "b", 2, 3);

        assertEquals(Map.of("a", 1, "b", 2), store.take(TENANT, MAPPING, "g"));
        assertNull(store.take(TENANT, MAPPING, "g"));
        assertNull(store.collect(TENANT, MAPPING, "g", "c", 3, 3)); // starts a new group
        assertNull(store.take(TENANT, "nobody", "g"));
    }

    @Test
    void openGroupsListsIncompleteGroupsByPrefixOldestFirst() {
        store.collect(TENANT, MAPPING, "dev1|t2", "a", 1, 2);
        store.collect(TENANT, MAPPING, "dev1|t1", "a", 1, 2);
        store.collect(TENANT, MAPPING, "dev2|t1", "a", 1, 2);
        store.collect(TENANT, MAPPING, "dev1|t3", "a", 1, 1); // complete: not open

        assertEquals(java.util.List.of("dev1|t2", "dev1|t1"), store.openGroups(TENANT, MAPPING, "dev1|"));
        assertEquals(3, store.openGroups(TENANT, MAPPING, "").size());
        assertTrue(store.openGroups(TENANT, "nobody", "").isEmpty());
    }

    @Test
    void maxAgeDropsStaleGroupsWhenACallWithThatOptionArrives() {
        Instant[] now = { Instant.parse("2026-01-01T00:00:00Z") };
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        FlowStateStore s = new FlowStateStore(clock);
        var aged = new CollectOptions(2, null, false, 60);

        s.collect(TENANT, MAPPING, "old", "a", 1, aged);
        s.collect(TENANT, MAPPING, "same", "a", 1, aged);
        now[0] = now[0].plusSeconds(61);

        // another group's call sweeps "old"; a late value of "same" finds its group stale and starts over
        assertNull(s.collect(TENANT, MAPPING, "other", "a", 1, aged));
        assertEquals(java.util.List.of("other"), s.openGroups(TENANT, MAPPING, ""));
        assertNull(s.collect(TENANT, MAPPING, "same", "b", 2, aged));
        assertEquals(Map.of("b", 2), s.take(TENANT, MAPPING, "same"));
    }
}
