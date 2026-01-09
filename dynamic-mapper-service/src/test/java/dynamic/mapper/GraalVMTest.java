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

package dynamic.mapper;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class GraalVMTest {

    private static final Logger log = LoggerFactory.getLogger("GraalVMTest");

    private final String scriptFibonacci = """
                function fibonacci(n) {
                    if (n <= 1) return n;
                    return fibonacci(n - 1) + fibonacci(n - 2);
                }
                fibonacci(20);
            """;

    public static void main(String[] args) {
        GraalVMTest test = new GraalVMTest();
        test.runTestNewContext();
        test.runTestSingleContext();
        test.runTestPooledContext();
    }

    // ========================================
    // Test 1: New Context per Iteration
    // ========================================

    private void runTestNewContext() {
        log.info("Starting GraalVM performance test with new Context per iteration");

        // Create a single Engine instance to be reused
        long engineStartTime = System.nanoTime();
        Engine engine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        long engineBuildTime = System.nanoTime() - engineStartTime;
        log.info("Engine build time:                   {} ms", String.format("%10.3f", engineBuildTime / 1_000_000.0));

        // Run the test 100 times
        int iterations = 100;
        long totalContextBuildTime = 0;
        long totalScriptExecutionTime = 0;
        Value lastResult = null;

        for (int i = 0; i < iterations; i++) {
            // Measure Context build time
            long contextStartTime = System.nanoTime();
            Context context = Context.newBuilder("js")
                    .engine(engine)
                    .option("js.strict", "true")
                    .build();
            long contextBuildTime = System.nanoTime() - contextStartTime;
            totalContextBuildTime += contextBuildTime;

            // Measure script execution time
            long scriptStartTime = System.nanoTime();
            lastResult = context.eval("js", scriptFibonacci);
            long scriptExecutionTime = System.nanoTime() - scriptStartTime;
            totalScriptExecutionTime += scriptExecutionTime;

            // Close the context
            context.close();

            // Log progress every 100 iterations
            if ((i + 1) % 100 == 0) {
                log.info("Completed {} iterations", i + 1);
            }
        }

        // Close the engine
        engine.close();

        // Calculate averages and totals
        double avgContextBuildTime = totalContextBuildTime / (double) iterations / 1_000_000.0;
        double avgScriptExecutionTime = totalScriptExecutionTime / (double) iterations / 1_000_000.0;
        double totalAvgTime = avgContextBuildTime + avgScriptExecutionTime;
        double totalTime = (totalContextBuildTime + totalScriptExecutionTime) / 1_000_000.0;
        double aggregatedContextBuildTime = totalContextBuildTime / 1_000_000.0;
        double aggregatedScriptExecutionTime = totalScriptExecutionTime / 1_000_000.0;

        // Log results
        log.info("=== GraalVM Performance Test Results ===");
        log.info("Iterations:                          {}", String.format("%10d", iterations));
        log.info("Fibonacci result:                    {}", String.format("%10d", lastResult != null ? lastResult.asInt() : 0));
        log.info("Average Context build time:          {} ms", String.format("%10.3f", avgContextBuildTime));
        log.info("Average Script execution time:       {} ms", String.format("%10.3f", avgScriptExecutionTime));
        log.info("Average total time per iteration:    {} ms", String.format("%10.3f", totalAvgTime));
        log.info("Aggregated Context build time:       {} ms", String.format("%10.3f", aggregatedContextBuildTime));
        log.info("Aggregated Script execution time:    {} ms", String.format("%10.3f", aggregatedScriptExecutionTime));
        log.info("Total test time:                     {} ms", String.format("%10.3f", totalTime));
    }

    // ========================================
    // Test 2: Single Reused Context
    // ========================================

    private void runTestSingleContext() {
        log.info("\n");
        log.info("Starting GraalVM performance test with single reused Context");

        // Create a single Engine instance to be reused
        long engineStartTime = System.nanoTime();
        Engine engine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        long engineBuildTime = System.nanoTime() - engineStartTime;
        log.info("Engine build time:                   {} ms", String.format("%10.3f", engineBuildTime / 1_000_000.0));

        // Create a single Context instance to be reused
        long contextStartTime = System.nanoTime();
        Context context = Context.newBuilder("js")
                .engine(engine)
                .option("js.strict", "true")
                .build();
        long contextBuildTime = System.nanoTime() - contextStartTime;
        log.info("Context build time:                  {} ms", String.format("%10.3f", contextBuildTime / 1_000_000.0));

        // Run the test 100 times with the same context
        int iterations = 100;
        long totalScriptExecutionTime = 0;
        Value lastResult = null;

        for (int i = 0; i < iterations; i++) {
            // Measure script execution time
            long scriptStartTime = System.nanoTime();
            lastResult = context.eval("js", scriptFibonacci);
            long scriptExecutionTime = System.nanoTime() - scriptStartTime;
            totalScriptExecutionTime += scriptExecutionTime;

            // Log progress every 100 iterations
            if ((i + 1) % 100 == 0) {
                log.info("Completed {} iterations", i + 1);
            }
        }

        // Close the context and engine
        context.close();
        engine.close();

        // Calculate averages and totals
        double avgScriptExecutionTime = totalScriptExecutionTime / (double) iterations / 1_000_000.0;
        double aggregatedScriptExecutionTime = totalScriptExecutionTime / 1_000_000.0;

        // Log results
        log.info("=== GraalVM Performance Test Results (Single Context) ===");
        log.info("Iterations:                          {}", String.format("%10d", iterations));
        log.info("Fibonacci result:                    {}", String.format("%10d", lastResult != null ? lastResult.asInt() : 0));
        log.info("Average Script execution time:       {} ms", String.format("%10.3f", avgScriptExecutionTime));
        log.info("Aggregated Script execution time:    {} ms", String.format("%10.3f", aggregatedScriptExecutionTime));
    }

    // ========================================
    // Test 3: Pooled Context
    // ========================================

    private void runTestPooledContext() {
        log.info("\n");
        log.info("Starting GraalVM performance test with pooled Context");

        // Create a single Engine instance to be reused
        long engineStartTime = System.nanoTime();
        Engine engine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        long engineBuildTime = System.nanoTime() - engineStartTime;
        log.info("Engine build time:                   {} ms", String.format("%10.3f", engineBuildTime / 1_000_000.0));

        // Create a pool of Context instances
        int poolSize = 10;
        BlockingQueue<Context> contextPool = new ArrayBlockingQueue<>(poolSize);
        long totalPoolCreationTime = 0;

        log.info("Creating context pool with size:     {}", String.format("%10d", poolSize));
        for (int i = 0; i < poolSize; i++) {
            long contextStartTime = System.nanoTime();
            Context context = Context.newBuilder("js")
                    .engine(engine)
                    .option("js.strict", "true")
                    .build();
            long contextBuildTime = System.nanoTime() - contextStartTime;
            totalPoolCreationTime += contextBuildTime;
            contextPool.offer(context);
        }
        log.info("Pool creation time:                  {} ms", String.format("%10.3f", totalPoolCreationTime / 1_000_000.0));

        // Run the test 100 times using contexts from the pool
        int iterations = 100;
        long totalContextAcquisitionTime = 0;
        long totalScriptExecutionTime = 0;
        long totalContextReturnTime = 0;
        Value lastResult = null;

        for (int i = 0; i < iterations; i++) {
            try {
                // Measure time to acquire context from pool
                long acquireStartTime = System.nanoTime();
                Context context = contextPool.take();
                long acquireTime = System.nanoTime() - acquireStartTime;
                totalContextAcquisitionTime += acquireTime;

                // Measure script execution time
                long scriptStartTime = System.nanoTime();
                lastResult = context.eval("js", scriptFibonacci);
                long scriptExecutionTime = System.nanoTime() - scriptStartTime;
                totalScriptExecutionTime += scriptExecutionTime;

                // Measure time to return context to pool
                long returnStartTime = System.nanoTime();
                contextPool.put(context);
                long returnTime = System.nanoTime() - returnStartTime;
                totalContextReturnTime += returnTime;

                // Log progress every 100 iterations
                if ((i + 1) % 100 == 0) {
                    log.info("Completed {} iterations", i + 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Thread interrupted", e);
                break;
            }
        }

        // Close all contexts in the pool and the engine
        for (Context context : contextPool) {
            context.close();
        }
        engine.close();

        // Calculate averages and totals
        double avgContextAcquisitionTime = totalContextAcquisitionTime / (double) iterations / 1_000_000.0;
        double avgScriptExecutionTime = totalScriptExecutionTime / (double) iterations / 1_000_000.0;
        double avgContextReturnTime = totalContextReturnTime / (double) iterations / 1_000_000.0;
        double avgTotalTime = avgContextAcquisitionTime + avgScriptExecutionTime + avgContextReturnTime;
        double aggregatedScriptExecutionTime = totalScriptExecutionTime / 1_000_000.0;

        // Log results
        log.info("=== GraalVM Performance Test Results (Pooled Context) ===");
        log.info("Pool size:                           {}", String.format("%10d", poolSize));
        log.info("Iterations:                          {}", String.format("%10d", iterations));
        log.info("Fibonacci result:                    {}", String.format("%10d", lastResult != null ? lastResult.asInt() : 0));
        log.info("Average Context acquisition time:    {} ms", String.format("%10.3f", avgContextAcquisitionTime));
        log.info("Average Script execution time:       {} ms", String.format("%10.3f", avgScriptExecutionTime));
        log.info("Average Context return time:         {} ms", String.format("%10.3f", avgContextReturnTime));
        log.info("Average total time per iteration:    {} ms", String.format("%10.3f", avgTotalTime));
        log.info("Aggregated Script execution time:    {} ms", String.format("%10.3f", aggregatedScriptExecutionTime));
    }
}