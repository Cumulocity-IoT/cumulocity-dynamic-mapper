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

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Benchmark test comparing memory usage between monolithic ProcessingContext
 * and focused context classes.
 *
 * This benchmark demonstrates the memory savings achieved by the context refactoring.
 *
 * Run with: mvn test -Dtest=ContextMemoryBenchmark
 */
class ContextMemoryBenchmark {

    private static final int ITERATIONS = 10000;

    @Test
    void benchmarkProcessingContextMemory() {
        System.out.println("\n=== Context Memory Benchmark ===\n");

        // Warm up JVM
        for (int i = 0; i < 1000; i++) {
            createProcessingContext();
        }

        System.gc();
        pauseForGC();

        long memoryBefore = getUsedMemory();

        // Create many ProcessingContext instances
        List<ProcessingContext<?>> contexts = new ArrayList<>(ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            contexts.add(createProcessingContext());
        }

        long memoryAfter = getUsedMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        long perContext = memoryUsed / ITERATIONS;

        System.out.println("Monolithic ProcessingContext (40 fields):");
        System.out.println("  Total memory used: " + formatBytes(memoryUsed));
        System.out.println("  Per context: " + formatBytes(perContext));
        System.out.println("  Iterations: " + ITERATIONS);

        contexts.clear();
        contexts = null;
    }

    @Test
    void benchmarkFocusedContextsMemory() {
        System.out.println("\n=== Focused Contexts Memory Benchmark ===\n");

        // Warm up
        for (int i = 0; i < 1000; i++) {
            createFocusedContexts();
        }

        System.gc();
        pauseForGC();

        long memoryBefore = getUsedMemory();

        // Create many focused context instances
        List<Object> contexts = new ArrayList<>(ITERATIONS * 3);
        for (int i = 0; i < ITERATIONS; i++) {
            var focused = createFocusedContexts();
            contexts.add(focused[0]); // RoutingContext
            contexts.add(focused[1]); // DeviceContext
            contexts.add(focused[2]); // OutputCollector
        }

        long memoryAfter = getUsedMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        long perSet = memoryUsed / ITERATIONS;

        System.out.println("Focused Contexts (RoutingContext + DeviceContext + OutputCollector):");
        System.out.println("  Total memory used: " + formatBytes(memoryUsed));
        System.out.println("  Per context set: " + formatBytes(perSet));
        System.out.println("  Iterations: " + ITERATIONS);

        contexts.clear();
        contexts = null;
    }

    @Test
    void benchmarkMethodCallOverhead() {
        System.out.println("\n=== Method Call Overhead Benchmark ===\n");

        ProcessingContext<?> fullContext = createProcessingContext();
        RoutingContext routing = fullContext.getRoutingContext();

        int warmupIterations = 100000;
        int benchmarkIterations = 1000000;

        // Warm up
        for (int i = 0; i < warmupIterations; i++) {
            processWithFullContext(fullContext);
            processWithFocusedContext(routing);
        }

        // Benchmark full context
        long startFull = System.nanoTime();
        for (int i = 0; i < benchmarkIterations; i++) {
            processWithFullContext(fullContext);
        }
        long endFull = System.nanoTime();
        long durationFull = (endFull - startFull) / 1_000_000; // Convert to ms

        // Benchmark focused context
        long startFocused = System.nanoTime();
        for (int i = 0; i < benchmarkIterations; i++) {
            processWithFocusedContext(routing);
        }
        long endFocused = System.nanoTime();
        long durationFocused = (endFocused - startFocused) / 1_000_000; // Convert to ms

        System.out.println("Method Call Performance (1M iterations):");
        System.out.println("  Full context (40 fields): " + durationFull + " ms");
        System.out.println("  Focused context (6 fields): " + durationFocused + " ms");
        System.out.println("  Improvement: " + ((durationFull - durationFocused) * 100 / durationFull) + "%");
    }

    @Test
    void benchmarkParallelProcessing() {
        System.out.println("\n=== Parallel Processing Benchmark ===\n");

        // Create test data
        List<Integer> data = new ArrayList<>(10000);
        for (int i = 0; i < 10000; i++) {
            data.add(i);
        }

        ProcessingContext<?> sharedContext = createProcessingContext();
        RoutingContext routing = sharedContext.getRoutingContext();
        OutputCollector output = new OutputCollector();

        // Warm up
        data.stream().limit(1000).forEach(i -> processItem(routing, output, i));

        // Benchmark sequential with unsafe context
        long startSeq = System.nanoTime();
        data.forEach(i -> processItemUnsafe(sharedContext, i));
        long endSeq = System.nanoTime();
        long durationSeq = (endSeq - startSeq) / 1_000_000;

        // Benchmark parallel with safe contexts
        long startPar = System.nanoTime();
        data.parallelStream().forEach(i -> processItem(routing, output, i));
        long endPar = System.nanoTime();
        long durationPar = (endPar - startPar) / 1_000_000;

        System.out.println("Processing 10,000 items:");
        System.out.println("  Sequential (unsafe): " + durationSeq + " ms");
        System.out.println("  Parallel (thread-safe): " + durationPar + " ms");
        System.out.println("  Speedup: " + (durationSeq * 100 / durationPar) + "%");
        System.out.println("  Note: Parallel processing is now SAFE with focused contexts!");
    }

    // Helper methods

    private ProcessingContext<?> createProcessingContext() {
        Mapping mapping = new Mapping();
        mapping.setName("test-mapping");

        return ProcessingContext.builder()
                .mapping(mapping)
                .topic("test/topic")
                .clientId("client123")
                .api(API.MEASUREMENT)
                .qos(Qos.AT_LEAST_ONCE)
                .resolvedPublishTopic("test/resolved")
                .payload(new Object())
                .rawPayload(new byte[100])
                .requests(new ArrayList<>())
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .logs(new ArrayList<>())
                .processingType(ProcessingType.UNDEFINED)
                .mappingType(MappingType.JSON)
                .processingCache(new TreeMap<>())
                .sendPayload(false)
                .testing(false)
                .needsRepair(false)
                .retain(false)
                .tenant("t12345")
                .serviceConfiguration(new ServiceConfiguration())
                .ignoreFurtherProcessing(false)
                .sourceId("source123")
                .externalId("ext123")
                .alarms(new HashSet<>())
                .processingMode(com.cumulocity.sdk.client.ProcessingMode.PERSISTENT)
                .deviceName("Test Device")
                .deviceType("c8y_Device")
                .binaryInfo(new BinaryInfo())
                .flowState(new HashMap<>())
                .build();
    }

    private Object[] createFocusedContexts() {
        RoutingContext routing = RoutingContext.builder()
                .topic("test/topic")
                .clientId("client123")
                .api(API.MEASUREMENT)
                .qos(Qos.AT_LEAST_ONCE)
                .resolvedPublishTopic("test/resolved")
                .tenant("t12345")
                .build();

        DeviceContext device = DeviceContext.builder()
                .sourceId("source123")
                .externalId("ext123")
                .deviceName("Test Device")
                .deviceType("c8y_Device")
                .build();

        OutputCollector output = new OutputCollector();

        return new Object[]{routing, device, output};
    }

    private void processWithFullContext(ProcessingContext<?> context) {
        // Simulate method that only needs routing info but receives full context
        String topic = context.getTopic();
        String tenant = context.getTenant();
        Qos qos = context.getQos();
        // Do some work
        if (topic != null && tenant != null && qos != null) {
            // Process
        }
    }

    private void processWithFocusedContext(RoutingContext routing) {
        // Same method but with focused context
        String topic = routing.getTopic();
        String tenant = routing.getTenant();
        Qos qos = routing.getQos();
        // Do some work
        if (topic != null && tenant != null && qos != null) {
            // Process
        }
    }

    private void processItem(RoutingContext routing, OutputCollector output, Integer item) {
        // Simulate processing with thread-safe contexts
        if (item % 2 == 0) {
            // Even items create a request
            DynamicMapperRequest req = new DynamicMapperRequest();
            output.addRequest(req);
        }
    }

    private void processItemUnsafe(ProcessingContext<?> context, Integer item) {
        // Simulate processing with potentially unsafe context
        // NOTE: This would NOT be safe in parallel stream!
        if (item % 2 == 0) {
            context.addRequest(new DynamicMapperRequest());
        }
    }

    private long getUsedMemory() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        return heapUsage.getUsed();
    }

    private void pauseForGC() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
