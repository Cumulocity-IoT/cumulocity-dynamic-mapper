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

package dynamic.mapper.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MappingStatusTest {

    private MappingStatus status() {
        return new MappingStatus("mo-1", "Mapping", "abc", Direction.INBOUND, "s/+/t", "#", 0, 0, 0, "");
    }

    @Test
    @DisplayName("each call yields its own catch-all status, so tenants never share counters")
    void unspecifiedStatusIsPerTenant() {
        MappingStatus tenantA = MappingStatus.createUnspecified();
        MappingStatus tenantB = MappingStatus.createUnspecified();

        assertNotSame(tenantA, tenantB,
                "a shared instance would report every tenant's unmatched messages to all of them");

        tenantA.incrementErrors();
        tenantA.incrementMessagesReceived();

        assertEquals(1, tenantA.getErrors());
        assertEquals(0, tenantB.getErrors(), "tenant B must not see tenant A's errors");
        assertEquals(0, tenantB.getMessagesReceived());
    }

    @Test
    @DisplayName("the catch-all status has no direction and is recognisable as such")
    void unspecifiedStatusSpansBothDirections() {
        MappingStatus unspecified = MappingStatus.createUnspecified();

        assertTrue(unspecified.isUnspecified());
        assertNull(unspecified.getDirection(),
                "it counts both inbound and outbound; consumers grouping by direction must handle null");
        assertFalse(status().isUnspecified());
    }

    @Test
    @DisplayName("reset clears the failure streak too, not just the lifetime counters")
    void resetClearsEveryCounter() {
        MappingStatus status = status();
        status.incrementMessagesReceived();
        status.incrementErrors();
        status.incrementFailureCount();
        status.setLoadingError("boom");

        status.reset();

        assertEquals(0, status.getMessagesReceived());
        assertEquals(0, status.getErrors());
        assertEquals(0, status.getCurrentFailureCount(),
                "a mapping whose statistics were just reset must not stay one failure from deactivation");
        assertEquals("", status.getLoadingError());
    }

    @Test
    @DisplayName("a successful message clears only the streak, never the lifetime error count")
    void successKeepsLifetimeErrors() {
        MappingStatus status = status();
        status.incrementErrors();
        status.incrementFailureCount();
        status.incrementErrors();
        status.incrementFailureCount();

        status.resetFailureCount();

        assertEquals(0, status.getCurrentFailureCount());
        assertEquals(2, status.getErrors(), "the error rate indicator must survive a recovery");
    }

    @Test
    @DisplayName("a snapshot is an independent copy, so reporting cannot observe a half-updated status")
    void snapshotIsDecoupled() {
        MappingStatus status = status();
        status.incrementMessagesReceived();
        status.incrementErrors();

        MappingStatus snapshot = status.snapshot();
        status.incrementMessagesReceived();
        status.incrementErrors();
        status.incrementFailureCount();

        assertEquals(1, snapshot.getMessagesReceived());
        assertEquals(1, snapshot.getErrors());
        assertEquals(0, snapshot.getCurrentFailureCount());
        assertEquals("abc", snapshot.getIdentifier());
        assertEquals(Direction.INBOUND, snapshot.getDirection());
        assertEquals("#", snapshot.getPublishTopic());
    }

    @Test
    @DisplayName("enriching a snapshot does not write back into the live status")
    void snapshotMutationDoesNotLeakBack() {
        MappingStatus live = status();
        MappingStatus snapshot = live.snapshot();

        // What the reporting path does when it fills in display values.
        snapshot.name = "Renamed by reporting";

        assertEquals("Mapping", live.getName());
    }

    @Test
    @DisplayName("identity is the mapping identifier, which is always populated")
    void identityUsesIdentifier() {
        MappingStatus persisted = status();
        MappingStatus beforePersistence = new MappingStatus(null, "Mapping", "abc", Direction.INBOUND,
                "s/+/t", "#", 0, 0, 0, "");

        assertEquals(persisted, beforePersistence,
                "the same mapping before and after it got a managed-object id");
        assertEquals(persisted.hashCode(), beforePersistence.hashCode());
    }

    @Test
    @DisplayName("concurrent increments do not lose updates")
    @Timeout(30)
    void concurrentIncrementsAreNotLost() throws Exception {
        MappingStatus status = status();
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        status.incrementMessagesReceived();
                        status.incrementErrors();
                        status.incrementFailureCount();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals((long) threads * perThread, status.getMessagesReceived());
        assertEquals((long) threads * perThread, status.getErrors());
        assertEquals((long) threads * perThread, status.getCurrentFailureCount());
    }

    @Test
    @DisplayName("the wire identifier is stable even though the display label is not")
    void identifierIsIndependentOfTheDisplayLabel() {
        MappingStatus unspecified = MappingStatus.createUnspecified();

        // Persisted status fragments and every consumer key off the identifier; the label is
        // display-only and has already been reworded once ("Unspecified" -> "Unmapped messages").
        assertEquals("UNSPECIFIED", unspecified.getIdentifier());
        assertEquals("Unmapped messages", MappingStatus.UNSPECIFIED_DISPLAY_NAME);
        assertTrue(unspecified.isUnspecified(),
                "detection must not depend on the display name");
    }

    @Test
    @DisplayName("the JSON shape is unchanged: a status fragment written by an older release still loads")
    void oldPersistedFragmentStillDeserializes() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // Including snoopedTemplatesTotal, a field removed in 6.4.0 — unknown properties are ignored.
        String persistedByOlderRelease = "{\"id\":\"mo-1\",\"name\":\"Unspecified\","
                + "\"identifier\":\"UNSPECIFIED\",\"direction\":null,\"mappingTopic\":\"#\","
                + "\"publishTopic\":\"#\",\"messagesReceived\":42,\"errors\":7,"
                + "\"currentFailureCount\":2,\"loadingError\":null,\"snoopedTemplatesTotal\":5}";

        MappingStatus restored = mapper.readValue(persistedByOlderRelease, MappingStatus.class);

        assertEquals(42, restored.getMessagesReceived());
        assertEquals(7, restored.getErrors());
        assertEquals(2, restored.getCurrentFailureCount());
        assertTrue(restored.isUnspecified(), "recognised by identifier, not by the stale name");
    }

    @Test
    @DisplayName("no derived property leaks into the persisted status")
    void derivedPredicatesAreNotSerialized() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        String json = mapper.writeValueAsString(MappingStatus.createUnspecified());

        // isUnspecified() is a predicate over the identifier; serializing it would add a field to
        // the d11r_mapping fragment and to every REST payload carrying a status.
        assertFalse(json.contains("\"unspecified\""), "unexpected field in: " + json);
        assertFalse(json.contains("\"snapshot\""), "unexpected field in: " + json);
    }
}
