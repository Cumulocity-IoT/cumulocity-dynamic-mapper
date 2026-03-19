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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OutputCollector focusing on thread-safety and correct functionality.
 */
class OutputCollectorTest {

    private OutputCollector collector;

    @BeforeEach
    void setUp() {
        collector = new OutputCollector();
    }

    @Test
    void shouldAddRequestAndReturnCorrectIndex() {
        DynamicMapperRequest request1 = new DynamicMapperRequest();
        DynamicMapperRequest request2 = new DynamicMapperRequest();

        int index1 = collector.addRequest(request1);
        int index2 = collector.addRequest(request2);

        assertEquals(0, index1);
        assertEquals(1, index2);
        assertEquals(2, collector.getRequestCount());
    }

    @Test
    void shouldAddAndRetrieveErrors() {
        Exception error1 = new RuntimeException("Error 1");
        Exception error2 = new IllegalArgumentException("Error 2");

        collector.addError(error1);
        collector.addError(error2);

        List<Exception> errors = collector.getErrors();
        assertEquals(2, errors.size());
        assertTrue(collector.hasErrors());
        assertEquals(2, collector.getErrorCount());
    }

    @Test
    void shouldAddAndRetrieveWarnings() {
        collector.addWarning("Warning 1");
        collector.addWarning("Warning 2");

        List<String> warnings = collector.getWarnings();
        assertEquals(2, warnings.size());
        assertTrue(collector.hasWarnings());
        assertEquals(2, collector.getWarningCount());
    }

    @Test
    void shouldAddAndRetrieveLogs() {
        collector.addLog("Log message 1");
        collector.addLog("Log message 2");

        List<String> logs = collector.getLogs();
        assertEquals(2, logs.size());
        assertEquals(2, collector.getLogCount());
    }

    @Test
    void shouldReturnCurrentRequest() {
        assertNull(collector.getCurrentRequest());

        DynamicMapperRequest request1 = new DynamicMapperRequest();
        DynamicMapperRequest request2 = new DynamicMapperRequest();

        collector.addRequest(request1);
        assertEquals(request1, collector.getCurrentRequest());

        collector.addRequest(request2);
        assertEquals(request2, collector.getCurrentRequest());
    }

    @Test
    void shouldReturnImmutableCollections() {
        collector.addRequest(new DynamicMapperRequest());
        collector.addError(new RuntimeException("Test"));
        collector.addWarning("Warning");
        collector.addLog("Log");

        assertThrows(UnsupportedOperationException.class, () ->
            collector.getRequests().add(new DynamicMapperRequest())
        );
        assertThrows(UnsupportedOperationException.class, () ->
            collector.getErrors().add(new RuntimeException())
        );
        assertThrows(UnsupportedOperationException.class, () ->
            collector.getWarnings().add("New warning")
        );
        assertThrows(UnsupportedOperationException.class, () ->
            collector.getLogs().add("New log")
        );
    }

    @Test
    void shouldHandleConcurrentRequestAdds() throws InterruptedException {
        int threadCount = 10;
        int requestsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        collector.addRequest(new DynamicMapperRequest());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * requestsPerThread, collector.getRequestCount());
    }

    @Test
    void shouldHandleConcurrentErrorAdds() throws InterruptedException {
        int threadCount = 10;
        int errorsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < errorsPerThread; j++) {
                        collector.addError(new RuntimeException("Error from thread " + threadId));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * errorsPerThread, collector.getErrorCount());
        assertTrue(collector.hasErrors());
    }

    @Test
    void shouldHandleConcurrentWarningAdds() throws InterruptedException {
        int threadCount = 10;
        int warningsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < warningsPerThread; j++) {
                        collector.addWarning("Warning from thread " + threadId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * warningsPerThread, collector.getWarningCount());
        assertTrue(collector.hasWarnings());
    }

    @Test
    void shouldHandleConcurrentMixedOperations() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        collector.addRequest(new DynamicMapperRequest());
                        collector.addError(new RuntimeException("Error"));
                        collector.addWarning("Warning");
                        collector.addLog("Log");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        int expectedCount = threadCount * operationsPerThread;
        assertEquals(expectedCount, collector.getRequestCount());
        assertEquals(expectedCount, collector.getErrorCount());
        assertEquals(expectedCount, collector.getWarningCount());
        assertEquals(expectedCount, collector.getLogCount());
    }

    @Test
    void shouldClearAllData() {
        collector.addRequest(new DynamicMapperRequest());
        collector.addError(new RuntimeException("Error"));
        collector.addWarning("Warning");
        collector.addLog("Log");

        collector.clear();

        assertEquals(0, collector.getRequestCount());
        assertEquals(0, collector.getErrorCount());
        assertEquals(0, collector.getWarningCount());
        assertEquals(0, collector.getLogCount());
        assertFalse(collector.hasErrors());
        assertFalse(collector.hasWarnings());
        assertNull(collector.getCurrentRequest());
    }

    @Test
    void shouldNotHaveErrorsOrWarningsWhenEmpty() {
        assertFalse(collector.hasErrors());
        assertFalse(collector.hasWarnings());
    }

    @Test
    void shouldProvideSnapshotOfDataNotLiveView() {
        collector.addRequest(new DynamicMapperRequest());

        List<DynamicMapperRequest> snapshot1 = collector.getRequests();
        assertEquals(1, snapshot1.size());

        collector.addRequest(new DynamicMapperRequest());

        // snapshot1 should still show 1 item (it's a snapshot, not live view)
        assertEquals(1, snapshot1.size());

        // New snapshot shows current state
        List<DynamicMapperRequest> snapshot2 = collector.getRequests();
        assertEquals(2, snapshot2.size());
    }
}
