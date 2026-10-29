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

package dynamic.mapper.core;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.processor.runtime.PooledGraalContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Real-code memory measurement for the GraalVM context pool — step 1 of
 * {@code docs/planning/IMPLEMENTATION-PLAN-GRAALVM-JS-IMPROVEMENTS.md} §4.
 *
 * <p>Unlike the synthetic probe in {@code resources/testing/performance/graalvm-memory}, this drives the real
 * {@link GraalVMContextService} (same Engine options, host access, shared/system code, pooled
 * context creation) with the shipped Smart Function templates as mappings, and reports what the
 * pool retains: heap and Metaspace after a full GC, per scenario of
 * <em>M distinct mappings × K idle pooled contexts per mapping</em>.
 *
 * <p>Opt-in:
 * <pre>mvn test -Dtest=GraalVMContextMemoryMeasurementTest -Dbenchmark=true</pre>
 * Options: {@code -Dbenchmark.maxContexts=1000} (largest scenario to attempt, default 1000).
 * The report is also written to {@code target/graalvm-memory-measurement.txt}.
 *
 * <p>The class name ends in {@code Test} on purpose: Surefire silently skips other names.
 *
 * <p>Limits: each context's {@code onMessage} is executed once with a minimal message, which is
 * best-effort — real traffic specialises more of the AST. Numbers are per JVM on this machine;
 * use them for ratios and for the per-context cost, then multiply for your tenant/mapping counts.
 */
@Slf4j
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class GraalVMContextMemoryMeasurementTest {

    private static final String TENANT = "memtenant";
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** {mappings, contextsPerMapping} */
    private static final int[][] SCENARIOS = {
            { 1, 1 }, { 1, 20 }, { 10, 1 }, { 10, 4 }, { 10, 20 }, { 50, 1 }, { 50, 4 }, { 50, 20 } };

    private record Sample(double heapMb, double metaspaceMb) {}

    private record Result(int mappings, int perMapping, int contexts, double idleHeapMb, double idleMetaMb,
            double executedHeapMb, int executeFailures, double releasedHeapMb) {}

    @Test
    void measurePoolRetention() throws IOException {
        int maxContexts = Integer.getInteger("benchmark.maxContexts", 1000);
        List<String> realMappings = loadMappingTemplates();
        String shared = read("template-SHARED.js");
        String system = read("template-SYSTEM.js");

        StringBuilder report = new StringBuilder();
        report.append("\nGraalVM context pool memory — real service code, shipped templates as mappings\n");
        report.append("JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append(", max heap ")
                .append(Runtime.getRuntime().maxMemory() / 1048576).append(" MB; ")
                .append(realMappings.size()).append(" distinct real templates, varied per mapping\n");
        report.append(String.format("%-10s %-9s %-9s | %10s %10s %9s | %10s %9s | %9s%n",
                "mappings", "ctx/map", "contexts", "idle heap", "KB/ctx", "meta MB",
                "exec heap", "KB/ctx", "freed MB"));
        report.append("-".repeat(100)).append('\n');

        List<Result> results = new ArrayList<>();
        for (int[] scenario : SCENARIOS) {
            int mappings = scenario[0];
            int perMapping = scenario[1];
            if (mappings * perMapping > maxContexts) {
                report.append(String.format("%-10d %-9d %-9d | skipped (> benchmark.maxContexts=%d)%n",
                        mappings, perMapping, mappings * perMapping, maxContexts));
                continue;
            }
            Result result = runScenario(shared, system, realMappings, mappings, perMapping);
            results.add(result);
            report.append(row(result));
            if (result.executedHeapMb() > Runtime.getRuntime().maxMemory() / 1048576.0 * 0.6) {
                report.append("stopping: heap use above 60% of max heap, results beyond this would be GC-skewed\n");
                break;
            }
        }

        report.append("\nReading the table:\n");
        report.append("  idle heap  = heap retained by the idle pooled contexts (after full GC) minus the heap\n");
        report.append("               before any context existed (Engine + warm-up already in place)\n");
        report.append("  exec heap  = same, after executing onMessage once in every context\n");
        report.append("  freed MB   = heap released again by invalidating the tenant's pool\n");
        appendExtrapolation(report, results);

        System.out.println(report);
        log.info(report.toString());
        Files.writeString(Path.of("target/graalvm-memory-measurement.txt"), report.toString());
    }

    // ── scenario ──────────────────────────────────────────────────────────

    private Result runScenario(String shared, String system, List<String> realMappings,
            int mappings, int perMapping) {
        GraalVMContextService service = new GraalVMContextService();
        ServiceConfiguration config = serviceConfig(shared, system);
        config.setContextPoolSize(Math.max(perMapping, 1));
        service.createGraalsResources(TENANT, config);
        Engine engine = service.peekGraalEngine(TENANT);
        Source sharedSource = service.getGraalsSourceShared(TENANT);
        Source systemSource = service.getGraalsSourceSystem(TENANT);

        try {
            Sample before = sample();

            // Borrow perMapping contexts per mapping at once (forces that many creations), return all.
            Map<String, List<PooledGraalContext>> held = new HashMap<>();
            for (int m = 0; m < mappings; m++) {
                String code = variant(realMappings.get(m % realMappings.size()), m);
                String encoded = Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
                String poolKey = TENANT + ":mapping-" + m + ":" + Integer.toHexString(code.hashCode());
                List<PooledGraalContext> borrowed = new ArrayList<>();
                for (int k = 0; k < perMapping; k++) {
                    borrowed.add(service.borrowOrCreateContext(poolKey, TENANT, engine, false,
                            sharedSource, systemSource, encoded, "mapping-" + m));
                }
                for (PooledGraalContext pooled : borrowed) {
                    service.returnContext(poolKey, pooled, engine);
                }
                held.put(poolKey, borrowed);
            }
            Sample idle = sample();

            // Execute onMessage once in every pooled context (best-effort specialisation). The
            // message and the context are permissive stubs: every method of the context exists and
            // returns null, the payload is empty. Templates that need real payload fields still throw
            // part-way; they have then run their prologue, which is what is being specialised.
            int failures = 0;
            for (List<PooledGraalContext> contextsOfMapping : held.values()) {
                for (PooledGraalContext pooled : contextsOfMapping) {
                    try {
                        Value message = pooled.getGraalContext().eval("js",
                                "({payload: {}, topic: 'a/b', clientId: 'c', transportFields: {}})");
                        Value context = pooled.getGraalContext().eval("js",
                                "new Proxy({}, {get: function(t, k) { return function() { return null; }; }})");
                        pooled.getOnMessageFunction().execute(message, context);
                    } catch (Exception e) {
                        failures++;
                    }
                }
            }
            Sample executed = sample();

            // Drop our own references first, otherwise nothing can be collected after the pool closes.
            held.clear();
            invalidatePool(service);
            Sample released = sample();

            int contexts = mappings * perMapping;
            return new Result(mappings, perMapping, contexts,
                    idle.heapMb() - before.heapMb(), idle.metaspaceMb() - before.metaspaceMb(),
                    executed.heapMb() - before.heapMb(), failures,
                    executed.heapMb() - released.heapMb());
        } finally {
            service.removeGraalsResources(TENANT);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** {@code invalidateTenantPool} is private (it is part of rotation/removal); call it as they do. */
    private static void invalidatePool(GraalVMContextService service) {
        try {
            java.lang.reflect.Method method =
                    GraalVMContextService.class.getDeclaredMethod("invalidateTenantPool", String.class);
            method.setAccessible(true);
            method.invoke(service, TENANT);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String row(Result r) {
        return String.format("%-10d %-9d %-9d | %10.1f %10.0f %9.1f | %10.1f %9.0f | %9.1f%s%n",
                r.mappings(), r.perMapping(), r.contexts(),
                r.idleHeapMb(), r.idleHeapMb() * 1024 / r.contexts(), r.idleMetaMb(),
                r.executedHeapMb(), r.executedHeapMb() * 1024 / r.contexts(), r.releasedHeapMb(),
                r.executeFailures() > 0 ? "   (" + r.executeFailures() + " onMessage calls threw part-way: stub payload does not fit the template)" : "");
    }

    private static void appendExtrapolation(StringBuilder report, List<Result> results) {
        results.stream().filter(r -> r.contexts() >= 20).max((a, b) -> Integer.compare(a.contexts(), b.contexts()))
                .ifPresent(r -> {
                    double kbPerContext = r.executedHeapMb() * 1024 / r.contexts();
                    report.append(String.format("%nExtrapolation from the %d-context row (%.0f KB per executed context):%n",
                            r.contexts(), kbPerContext));
                    for (int[] shape : new int[][] { { 50, 20, 1 }, { 50, 20, 5 }, { 100, 20, 10 } }) {
                        report.append(String.format(
                                "  %3d mappings x %2d pooled contexts x %2d tenants = %,6d contexts -> ~%.0f MB%n",
                                shape[0], shape[1], shape[2], shape[0] * shape[1] * shape[2],
                                shape[0] * shape[1] * shape[2] * kbPerContext / 1024));
                    }
                    report.append("  (DEFAULT_POOL_SIZE = 20 idle contexts per mapping; the pool only grows to what\n"
                            + "   concurrency actually asked for, so the real figure sits between the 1x and 20x rows.)\n");
                });
    }

    private static ServiceConfiguration serviceConfig(String shared, String system) {
        ServiceConfiguration config = new ServiceConfiguration();
        Map<String, CodeTemplate> templates = new HashMap<>();
        CodeTemplate sharedTemplate = new CodeTemplate();
        sharedTemplate.setCode(Base64.getEncoder().encodeToString(shared.getBytes(StandardCharsets.UTF_8)));
        templates.put(TemplateType.SHARED.name(), sharedTemplate);
        CodeTemplate systemTemplate = new CodeTemplate();
        systemTemplate.setCode(Base64.getEncoder().encodeToString(system.getBytes(StandardCharsets.UTF_8)));
        templates.put(TemplateType.SYSTEM.name(), systemTemplate);
        config.setCodeTemplates(templates);
        return config;
    }

    private static List<String> loadMappingTemplates() throws IOException {
        List<String> codes = new ArrayList<>();
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("template-SMART-INBOUND-") || name.startsWith("template-SMART-OUTBOUND-")) {
                    codes.add(Files.readString(file));
                }
            }
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("No Smart Function templates found in " + TEMPLATES.toAbsolutePath());
        }
        return codes;
    }

    private static String read(String templateFile) throws IOException {
        return Files.readString(TEMPLATES.resolve(templateFile));
    }

    /** Makes the source of mapping {@code index} distinct (own hash/Source) without changing behaviour.
     * The marker goes first: the module stripper expects the template's trailing {@code export} last. */
    private static String variant(String code, int index) {
        return "// variant " + index + "\n" + code;
    }

    private static Sample sample() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime rt = Runtime.getRuntime();
        double heap = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        double metaspace = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Metaspace") || pool.getName().equals("Compressed Class Space")) {
                metaspace += pool.getUsage().getUsed() / 1048576.0;
            }
        }
        return new Sample(heap, metaspace);
    }
}
