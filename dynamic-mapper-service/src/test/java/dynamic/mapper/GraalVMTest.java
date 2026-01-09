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

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;

@Slf4j
public class GraalVMTest {
    String scriptFibonacci = """
                function fibonacci(n) {
                    if (n <= 1) return n;
                    return fibonacci(n - 1) + fibonacci(n - 2);
                }
                fibonacci(20);
            """;

    public static void main(String[] args) {
        GraalVMTest test = new GraalVMTest();
        test.runTest();
    }

    private void runTest() {
        log.info("Starting GraalVM performance test");

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
}