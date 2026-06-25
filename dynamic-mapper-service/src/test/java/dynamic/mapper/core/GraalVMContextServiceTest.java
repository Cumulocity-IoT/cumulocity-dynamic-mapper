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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import lombok.extern.slf4j.Slf4j;

/**
 * Unit tests for {@link GraalVMContextService} covering:
 * <ul>
 *   <li>Count-based engine rotation</li>
 *   <li>Time-based engine rotation</li>
 *   <li>Active-context tracking via {@code engineActiveContexts}</li>
 *   <li>Explicit {@link Engine#close()} after all in-flight contexts drain</li>
 *   <li>Correct behaviour of {@link GraalVMContextService#removeGraalsResources}</li>
 * </ul>
 */
@Slf4j
class GraalVMContextServiceTest {

    private static final String TENANT = "test-tenant";

    private GraalVMContextService service;
    private ServiceConfiguration serviceConfig;

    // Engines created during a test that were NOT closed by the service itself
    // (e.g. the current active engine after a test that doesn't remove resources).
    // We close them in tearDown to avoid resource leaks.
    private Engine engineToCloseInTearDown;

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        service = new GraalVMContextService();
        serviceConfig = buildServiceConfig();
        service.createGraalsResources(TENANT, serviceConfig);
    }

    @AfterEach
    void tearDown() {
        // Try a graceful remove (no-op if already removed by the test)
        try {
            service.removeGraalsResources(TENANT);
        } catch (Exception ignored) {
        }
        if (engineToCloseInTearDown != null) {
            try {
                engineToCloseInTearDown.close();
            } catch (Exception ignored) {
            }
            engineToCloseInTearDown = null;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Reads a private field from {@code target} via reflection. */
    @SuppressWarnings("unchecked")
    private <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private ServiceConfiguration buildServiceConfig() {
        ServiceConfiguration config = new ServiceConfiguration();
        Map<String, CodeTemplate> templates = new HashMap<>();

        CodeTemplate shared = new CodeTemplate();
        shared.setCode(Base64.getEncoder().encodeToString("// shared".getBytes()));
        templates.put(TemplateType.SHARED.name(), shared);

        CodeTemplate system = new CodeTemplate();
        system.setCode(Base64.getEncoder().encodeToString("// system".getBytes()));
        templates.put(TemplateType.SYSTEM.name(), system);

        config.setCodeTemplates(templates);
        // Enable time-based rotation for tests (mirrors ENGINE_MAX_AGE = 24 h)
        config.setEngineMaxAgeMinutes((int) GraalVMContextService.ENGINE_MAX_AGE.toMinutes());
        return config;
    }

    /** Returns the active-context counter for the given engine (via reflection). */
    private AtomicInteger activeCountFor(Engine engine) throws Exception {
        Map<Engine, AtomicInteger> map = field(service, "engineActiveContexts");
        return map.get(engine);
    }

    /** Returns the current engine registered for {@value #TENANT}. */
    private Engine currentEngine() throws Exception {
        Map<String, Engine> engines = field(service, "graalEngines");
        return engines.get(TENANT);
    }

    /** Injects an old creation timestamp so the time-based rotation fires. */
    private void makeEngineLookOld() throws Exception {
        Map<String, Instant> createdAt = field(service, "engineCreatedAt");
        createdAt.put(TENANT, Instant.now().minus(GraalVMContextService.ENGINE_MAX_AGE).minusSeconds(1));
    }

    /** Sets the compilation counter for TENANT to {@code value} via reflection. */
    private void setCompilationCounter(int value) throws Exception {
        Map<String, AtomicInteger> counters = field(service, "compilationCounters");
        counters.get(TENANT).set(value);
    }

    /** Calls recordCompilation with a unique source to increment the compilation counter by 1. */
    private void recordUniqueCompilation(String sourceName) {
        service.recordCompilation(TENANT, sourceName, "function onMessage() { /* " + sourceName + " */ }");
    }

    // -------------------------------------------------------------------------
    // Basic resource management
    // -------------------------------------------------------------------------

    @Test
    void createGraalsResources_initializesEngineAndSources() throws Exception {
        Engine engine = currentEngine();
        assertNotNull(engine, "Engine should be registered for tenant");
        assertNotNull(service.getGraalsSourceShared(TENANT), "Shared source should be cached");
        assertNotNull(service.getGraalsSourceSystem(TENANT), "System source should be cached");

        engineToCloseInTearDown = engine;
        log.info("✅ createGraalsResources initialises engine and sources");
    }

    @Test
    void createGraalsResources_setsActiveContextCountToZero() throws Exception {
        Engine engine = currentEngine();
        AtomicInteger count = activeCountFor(engine);

        assertNotNull(count, "Active-context counter should exist for the new engine");
        assertEquals(0, count.get(), "Fresh engine should start with zero active contexts");

        engineToCloseInTearDown = engine;
        log.info("✅ Active-context counter initialised to zero");
    }

    // -------------------------------------------------------------------------
    // getGraalEngine – active-context tracking
    // -------------------------------------------------------------------------

    @Test
    void getGraalEngine_incrementsActiveContextCount() throws Exception {
        Engine engine = currentEngine();

        service.getGraalEngine(TENANT);
        assertEquals(1, activeCountFor(engine).get(), "Count should be 1 after one getGraalEngine call");

        service.getGraalEngine(TENANT);
        assertEquals(2, activeCountFor(engine).get(), "Count should be 2 after two getGraalEngine calls");

        engineToCloseInTearDown = currentEngine();
        log.info("✅ getGraalEngine increments active-context counter");
    }

    @Test
    void getGraalEngine_returnsNonNullEngine() {
        Engine engine = service.getGraalEngine(TENANT);
        assertNotNull(engine, "getGraalEngine must return a non-null Engine");
        log.info("✅ getGraalEngine returns non-null Engine");
    }

    // -------------------------------------------------------------------------
    // Count-based rotation
    // -------------------------------------------------------------------------

    @Test
    void countBasedRotation_replacesEngineAtThreshold() throws Exception {
        Engine originalEngine = currentEngine();

        // Wind the counter to one below the threshold
        setCompilationCounter(GraalVMContextService.ENGINE_ROTATION_THRESHOLD - 1);

        // Recording one more unique source increments to exactly the threshold and triggers rotation
        recordUniqueCompilation("onMessage_trigger.js");
        Engine newEngine = currentEngine();

        assertNotSame(originalEngine, newEngine, "Engine should have been replaced after threshold");
        log.info("✅ Compilation-based rotation replaces engine at threshold");
    }

    @Test
    void countBasedRotation_resetsCounterAfterRotation() throws Exception {
        setCompilationCounter(GraalVMContextService.ENGINE_ROTATION_THRESHOLD - 1);
        recordUniqueCompilation("onMessage_trigger.js"); // triggers rotation

        Map<String, AtomicInteger> counters = field(service, "compilationCounters");
        // After rotation createGraalsResources resets the counter to 0
        int counterAfter = counters.get(TENANT).get();
        assertTrue(counterAfter < GraalVMContextService.ENGINE_ROTATION_THRESHOLD,
                "Counter should have reset after rotation, got: " + counterAfter);

        log.info("✅ Compilation-based rotation resets compilation counter");
    }

    @Test
    void countBasedRotation_retireOldEngineIntoRetiredSet() throws Exception {
        Engine originalEngine = currentEngine();

        setCompilationCounter(GraalVMContextService.ENGINE_ROTATION_THRESHOLD - 1);
        recordUniqueCompilation("onMessage_trigger.js"); // triggers rotation; old engine had count=0 → closes immediately

        // Old engine had no active contexts before rotation, so it should have been closed
        // (removed from retiredEngines and engineActiveContexts)
        Set<Engine> retired = field(service, "retiredEngines");
        assertFalse(retired.contains(originalEngine),
                "Idle old engine should have been closed and removed from retiredEngines");

        log.info("✅ Idle engine closed immediately on rotation");
    }

    @Test
    void countBasedRotation_keepsRetiredEngineOpenWhileContextsInFlight() throws Exception {
        Engine originalEngine = currentEngine();

        // Two in-flight contexts open BEFORE rotation — each increments the old engine's count
        service.getGraalEngine(TENANT); // old engine count = 1
        service.getGraalEngine(TENANT); // old engine count = 2

        // Trigger rotation via compilation counter
        setCompilationCounter(GraalVMContextService.ENGINE_ROTATION_THRESHOLD - 1);
        recordUniqueCompilation("onMessage_trigger.js"); // triggers rotation; old engine stays at count = 2

        // Old engine is retired but not yet drained
        Set<Engine> retired = field(service, "retiredEngines");
        assertTrue(retired.contains(originalEngine),
                "Engine with open contexts should remain in retiredEngines after rotation");

        // First drain: count 2 → 1 — engine must stay open
        service.releaseEngine(originalEngine);
        assertTrue(retired.contains(originalEngine), "Still one context open — should not be closed yet");

        // Second drain: count 1 → 0 — engine must be closed now
        service.releaseEngine(originalEngine);
        assertFalse(retired.contains(originalEngine),
                "All contexts closed — engine should have been removed from retiredEngines");

        log.info("✅ Retired engine stays open until all in-flight contexts drain");
    }

    // -------------------------------------------------------------------------
    // Time-based rotation
    // -------------------------------------------------------------------------

    @Test
    void timeBasedRotation_replacesEngineWhenOlderThanMaxAge() throws Exception {
        Engine originalEngine = currentEngine();
        makeEngineLookOld();

        Engine returned = service.getGraalEngine(TENANT);
        Engine newEngine = currentEngine();

        assertNotSame(originalEngine, newEngine,
                "Engine older than ENGINE_MAX_AGE should be rotated");
        assertSame(newEngine, returned,
                "getGraalEngine should return the new engine after time-based rotation");

        log.info("✅ Time-based rotation fires when engine exceeds ENGINE_MAX_AGE");
    }

    @Test
    void timeBasedRotation_updatesCreationTimestamp() throws Exception {
        makeEngineLookOld();
        Instant before = Instant.now();

        service.getGraalEngine(TENANT); // triggers time-based rotation

        Map<String, Instant> createdAt = field(service, "engineCreatedAt");
        Instant newTimestamp = createdAt.get(TENANT);

        assertNotNull(newTimestamp, "Creation timestamp must be set after rotation");
        assertFalse(newTimestamp.isBefore(before),
                "New timestamp should be at or after the rotation call");

        log.info("✅ Time-based rotation resets engine creation timestamp");
    }

    // -------------------------------------------------------------------------
    // releaseEngine
    // -------------------------------------------------------------------------

    @Test
    void releaseEngine_closesRetiredEngineWhenFullyDrained() throws Exception {
        Engine engine = currentEngine();

        service.getGraalEngine(TENANT); // count = 1
        assertEquals(1, activeCountFor(engine).get());

        // Retire the engine manually (simulate what rotateEngine does)
        Set<Engine> retired = field(service, "retiredEngines");
        retired.add(engine);
        Map<String, Engine> enginesMap = field(service, "graalEngines");
        enginesMap.remove(TENANT);

        // Drain: after one release the count hits 0 and the engine is in retiredEngines → close
        service.releaseEngine(engine);

        assertFalse(retired.contains(engine),
                "Fully drained retired engine should be removed from retiredEngines");

        Map<Engine, AtomicInteger> activeMap = field(service, "engineActiveContexts");
        assertFalse(activeMap.containsKey(engine),
                "Closed engine should be removed from engineActiveContexts");

        // The engine is closed — creating a new Context from it should fail
        assertThrows(Exception.class, () -> {
            org.graalvm.polyglot.Context.newBuilder("js").engine(engine).build();
        }, "Closed engine should reject new Context creation");

        log.info("✅ releaseEngine closes retired engine when fully drained");
    }

    @Test
    void releaseEngine_doesNotCloseActiveEngine() throws Exception {
        Engine engine = currentEngine();

        service.getGraalEngine(TENANT); // count = 1
        // Engine is NOT in retiredEngines — still active
        service.releaseEngine(engine);

        // Engine should NOT be closed; should still accept new contexts
        assertDoesNotThrow(() -> {
            try (org.graalvm.polyglot.Context ctx = org.graalvm.polyglot.Context.newBuilder("js")
                    .engine(engine)
                    .allowHostAccess(service.getHostAccess())
                    .build()) {
                ctx.eval("js", "1+1");
            }
        }, "Active (non-retired) engine should not be closed by releaseEngine");

        engineToCloseInTearDown = engine;
        log.info("✅ releaseEngine does not close an engine that is not retired");
    }

    @Test
    void releaseEngine_doesNotCloseWhenContextsStillOpen() throws Exception {
        Engine engine = currentEngine();

        service.getGraalEngine(TENANT); // count = 1
        service.getGraalEngine(TENANT); // count = 2

        Set<Engine> retired = field(service, "retiredEngines");
        retired.add(engine);
        Map<String, Engine> enginesMap = field(service, "graalEngines");
        enginesMap.remove(TENANT);

        // First release: count drops to 1 — engine should NOT be closed yet
        service.releaseEngine(engine);
        assertTrue(retired.contains(engine),
                "Engine should remain in retiredEngines while one context is still open");

        // Second release: count drops to 0 — now it should be closed
        service.releaseEngine(engine);
        assertFalse(retired.contains(engine),
                "Engine should be removed from retiredEngines after all contexts drain");

        log.info("✅ releaseEngine waits for all contexts to drain before closing");
    }

    @Test
    void releaseEngine_isIdempotentForUnknownEngine() {
        Engine unknown = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();
        try {
            // Should not throw even though this engine is not tracked
            assertDoesNotThrow(() -> service.releaseEngine(unknown));
        } finally {
            unknown.close();
        }
        log.info("✅ releaseEngine handles unknown engine gracefully");
    }

    // -------------------------------------------------------------------------
    // removeGraalsResources
    // -------------------------------------------------------------------------

    @Test
    void removeGraalsResources_closesEngineImmediatelyWhenIdle() throws Exception {
        Engine engine = currentEngine();
        // No getGraalEngine calls — count stays at 0

        service.removeGraalsResources(TENANT);

        // Engine was idle: should have been closed immediately
        assertThrows(Exception.class, () -> {
            org.graalvm.polyglot.Context.newBuilder("js").engine(engine).build();
        }, "Idle engine should be closed by removeGraalsResources");

        log.info("✅ removeGraalsResources closes idle engine immediately");
    }

    @Test
    void removeGraalsResources_keepsEngineOpenWhileContextsInFlight() throws Exception {
        Engine engine = currentEngine();

        // Simulate one in-flight context
        service.getGraalEngine(TENANT); // count = 1

        service.removeGraalsResources(TENANT);

        // Engine is retired but not yet drained — still usable for in-flight work
        Set<Engine> retired = field(service, "retiredEngines");
        assertTrue(retired.contains(engine),
                "Engine with open contexts should be in retiredEngines after remove");

        // Drain the last context
        service.releaseEngine(engine);
        assertFalse(retired.contains(engine),
                "Engine should be closed and removed from retiredEngines after drain");

        log.info("✅ removeGraalsResources defers close until in-flight contexts drain");
    }

    @Test
    void removeGraalsResources_cleansUpAllTenantState() throws Exception {
        service.removeGraalsResources(TENANT);

        Map<String, Engine> engines = field(service, "graalEngines");
        assertNull(engines.get(TENANT), "graalEngines should not contain tenant after remove");

        Map<String, AtomicInteger> counters = field(service, "compilationCounters");
        assertNull(counters.get(TENANT), "compilationCounters should not contain tenant after remove");

        Map<String, Instant> createdAt = field(service, "engineCreatedAt");
        assertNull(createdAt.get(TENANT), "engineCreatedAt should not contain tenant after remove");

        assertNull(service.getGraalsSourceShared(TENANT), "Shared source should be removed");
        assertNull(service.getGraalsSourceSystem(TENANT), "System source should be removed");

        log.info("✅ removeGraalsResources removes all tenant state");
    }
}
