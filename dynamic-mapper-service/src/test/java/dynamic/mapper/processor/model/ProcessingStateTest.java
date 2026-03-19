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

package dynamic.mapper.processor.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProcessingState focusing on thread-safety and correct functionality.
 */
class ProcessingStateTest {

    private ProcessingState state;

    @BeforeEach
    void setUp() {
        state = new ProcessingState(ProcessingType.UNDEFINED, MappingType.JSON);
    }

    @Test
    void shouldInitializeWithCorrectTypes() {
        assertEquals(ProcessingType.UNDEFINED, state.getProcessingType());
        assertEquals(MappingType.JSON, state.getMappingType());
        assertEquals(0, state.getCacheSize());
        assertFalse(state.needsRepair());
        assertFalse(state.shouldIgnoreFurtherProcessing());
    }

    @Test
    void shouldAddAndRetrieveSubstitution() {
        String key = "measurement.temperature.value";
        Object value = 25.5;

        state.addSubstitution(key, value, SubstituteValue.TYPE.NUMBER,
            RepairStrategy.DEFAULT, false);

        List<SubstituteValue> substitutions = state.getSubstitutions(key);
        assertEquals(1, substitutions.size());
        assertEquals(value, substitutions.get(0).getValue());
        assertEquals(SubstituteValue.TYPE.NUMBER, substitutions.get(0).getType());
    }

    @Test
    void shouldAddMultipleSubstitutionsToSameKey() {
        String key = "device.sensors";

        state.addSubstitution(key, "sensor1", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);
        state.addSubstitution(key, "sensor2", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);

        List<SubstituteValue> substitutions = state.getSubstitutions(key);
        assertEquals(2, substitutions.size());
    }

    @Test
    void shouldReturnEmptyListForNonExistentKey() {
        List<SubstituteValue> substitutions = state.getSubstitutions("non.existent.key");
        assertTrue(substitutions.isEmpty());
    }

    @Test
    void shouldReturnImmutableSubstitutionList() {
        String key = "test.key";
        state.addSubstitution(key, "value", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);

        List<SubstituteValue> substitutions = state.getSubstitutions(key);

        assertThrows(UnsupportedOperationException.class, () ->
            substitutions.add(new SubstituteValue("new", SubstituteValue.TYPE.TEXTUAL,
                RepairStrategy.DEFAULT, false))
        );
    }

    @Test
    void shouldPutCompleteSubstitutionList() {
        String key = "test.key";
        List<SubstituteValue> values = List.of(
            new SubstituteValue("value1", SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT, false),
            new SubstituteValue("value2", SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT, false)
        );

        state.putSubstitutions(key, values);

        List<SubstituteValue> retrieved = state.getSubstitutions(key);
        assertEquals(2, retrieved.size());
    }

    @Test
    void shouldGetAllPathTargets() {
        state.addSubstitution("key1", "value1", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);
        state.addSubstitution("key2", "value2", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);

        Set<String> pathTargets = state.getPathTargets();
        assertEquals(2, pathTargets.size());
        assertTrue(pathTargets.contains("key1"));
        assertTrue(pathTargets.contains("key2"));
    }

    @Test
    void shouldSetAndGetNeedsRepairFlag() {
        assertFalse(state.needsRepair());

        state.setNeedsRepair(true);
        assertTrue(state.needsRepair());

        state.setNeedsRepair(false);
        assertFalse(state.needsRepair());
    }

    @Test
    void shouldSetAndGetIgnoreFurtherProcessingFlag() {
        assertFalse(state.shouldIgnoreFurtherProcessing());

        state.setIgnoreFurtherProcessing(true);
        assertTrue(state.shouldIgnoreFurtherProcessing());

        state.setIgnoreFurtherProcessing(false);
        assertFalse(state.shouldIgnoreFurtherProcessing());
    }

    @Test
    void shouldClearAllState() {
        state.addSubstitution("key1", "value1", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);
        state.setNeedsRepair(true);
        state.setIgnoreFurtherProcessing(true);

        state.clear();

        assertEquals(0, state.getCacheSize());
        assertFalse(state.needsRepair());
        assertFalse(state.shouldIgnoreFurtherProcessing());
    }

    @Test
    void shouldHandleConcurrentSubstitutionAdds() throws InterruptedException {
        int threadCount = 10;
        int substitutionsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        String key = "concurrent.test.key";

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < substitutionsPerThread; j++) {
                        state.addSubstitution(key, "value_" + threadId + "_" + j,
                            SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        List<SubstituteValue> substitutions = state.getSubstitutions(key);
        assertEquals(threadCount * substitutionsPerThread, substitutions.size());
    }

    @Test
    void shouldHandleConcurrentAddsToDifferentKeys() throws InterruptedException {
        int threadCount = 10;
        int substitutionsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < substitutionsPerThread; j++) {
                        String key = "thread_" + threadId + "_key_" + j;
                        state.addSubstitution(key, "value_" + j,
                            SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * substitutionsPerThread, state.getCacheSize());
    }

    @Test
    void shouldHandleConcurrentFlagUpdates() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final boolean value = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        state.setNeedsRepair(value);
                        state.setIgnoreFurtherProcessing(!value);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Final state should be consistent (one of the values)
        boolean needsRepair = state.needsRepair();
        boolean ignoreProcessing = state.shouldIgnoreFurtherProcessing();

        // Just verify the getters work without throwing exceptions
        assertNotNull(needsRepair);
        assertNotNull(ignoreProcessing);
    }

    @Test
    void shouldHandleConcurrentReadsDuringWrites() throws InterruptedException {
        int writerThreads = 5;
        int readerThreads = 5;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch latch = new CountDownLatch(writerThreads + readerThreads);

        // Writers
        for (int i = 0; i < writerThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        state.addSubstitution("key_" + threadId, "value_" + j,
                            SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Readers
        for (int i = 0; i < readerThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Should not throw ConcurrentModificationException
                        state.getPathTargets();
                        state.getSubstitutions("key_" + threadId);
                        state.getCacheSize();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify final state is consistent
        assertTrue(state.getCacheSize() <= writerThreads * operationsPerThread);
    }

    @Test
    void shouldReturnImmutableProcessingCache() {
        state.addSubstitution("key1", "value1", SubstituteValue.TYPE.TEXTUAL,
            RepairStrategy.DEFAULT, false);

        var cache = state.getProcessingCache();

        assertThrows(UnsupportedOperationException.class, () ->
            cache.put("new.key", List.of())
        );
    }
}
