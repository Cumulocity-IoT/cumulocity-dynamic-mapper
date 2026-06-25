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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.processor.util.JavaScriptModuleStripper;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages per-tenant GraalVM {@link Engine} instances and pre-compiled
 * JavaScript {@link Source} objects.
 *
 * <p>Extracted from {@link TenantRegistry} so that JS-engine lifecycle
 * (create, warm-up, update, remove) lives in a dedicated component. All state
 * is keyed by tenant and held in {@link ConcurrentHashMap}s.
 *
 * <p>This component has no Spring service dependencies and can therefore be
 * injected anywhere without risk of circular dependencies.
 *
 * <h2>Why Metaspace matters</h2>
 *
 * <p>Every JavaScript source compiled through a GraalVM {@link Engine} causes the Truffle
 * interpreter and Graal JIT to emit native machine code (Truffle ASTs, Graal IR graphs,
 * compiled stubs). This data is stored in JVM <em>Metaspace</em> — a native-memory region
 * that is <strong>separate from the Java heap</strong> and is only partially bounded by
 * {@code -XX:MaxMetaspaceSize}. Inside a container the effective ceiling is typically around
 * 10 % of the container's RAM limit (≈ 184 MB for a 2 GB deployment). Once a tenant's Engine
 * has been used long enough and accumulated enough distinct compiled sources, Metaspace fills
 * up and the JVM throws {@code OutOfMemoryError: Metaspace}.
 *
 * <p>Metaspace data is tied to the {@link Engine} that produced it. It can only be reclaimed
 * after the Engine itself is garbage-collected <em>and</em> the GC collects the native frames —
 * which does not happen predictably. Calling {@link Engine#close()} forces an immediate release.
 *
 * <h2>Engine rotation strategies</h2>
 *
 * <p>Two complementary strategies prevent unbounded Metaspace growth:
 *
 * <ol>
 *   <li><b>Count-based rotation</b> ({@link #ENGINE_ROTATION_THRESHOLD}): After every
 *       {@value #ENGINE_ROTATION_THRESHOLD} {@link Context} creations for a tenant the current
 *       Engine is retired and replaced with a fresh one. This caps the number of compiled
 *       sources that accumulate per Engine lifecycle. High-traffic tenants that process many
 *       messages per hour will rotate frequently; the threshold should be tuned so that the
 *       expected Metaspace footprint per Engine (number of distinct mapping sources × compiled
 *       size) stays safely below the container budget. Lower values rotate more often, giving
 *       GC more opportunities to reclaim memory, but incur a JIT warm-up penalty on the first
 *       few requests after each rotation (~1–7 s for the first message, dropping rapidly as the
 *       JIT re-profiles the new Engine). Higher values amortise the warm-up cost but risk
 *       larger Metaspace spikes.</li>
 *
 *   <li><b>Time-based rotation</b> ({@link #ENGINE_MAX_AGE}): Even if a tenant is idle or
 *       processes fewer than {@value #ENGINE_ROTATION_THRESHOLD} messages in
 *       {@value #ENGINE_MAX_AGE} hours, the Engine is rotated once it exceeds that wall-clock
 *       age. This bounds worst-case memory retention for low-traffic or bursty tenants that
 *       would otherwise never reach the count threshold. The two strategies are independent and
 *       OR-ed: whichever condition fires first triggers the rotation.</li>
 * </ol>
 *
 * <h2>Explicit Engine closing and active-context tracking</h2>
 *
 * <p>Simply dereferencing a retired Engine is not sufficient — the JVM GC is not guaranteed
 * to collect it promptly, leaving Metaspace allocated for an unbounded period. Instead, this
 * service tracks every open {@link Context} via {@code engineActiveContexts}. When the last
 * in-flight Context belonging to a retired Engine is closed, {@link #releaseEngine(Engine)}
 * calls {@link Engine#close()} explicitly, triggering an immediate Metaspace release.
 *
 * <p>The lifecycle for a retired Engine is:
 * <ol>
 *   <li>Rotation/removal: the Engine is added to {@code retiredEngines} and removed from
 *       the tenant lookup map. No new Contexts are created against it.</li>
 *   <li>Drain: existing in-flight Contexts continue to run to completion. Each Context's
 *       {@code close()} triggers the {@code engineReleaseAction} callback set on
 *       {@link dynamic.mapper.processor.model.ProcessingContext}, which calls
 *       {@link #releaseEngine(Engine)}.</li>
 *   <li>Close: when the active-context counter reaches zero and the Engine is still in
 *       {@code retiredEngines}, {@link Engine#close()} is called and the Engine is removed
 *       from all tracking maps.</li>
 * </ol>
 */
@Slf4j
@Component
public class GraalVMContextService {

    /**
     * Number of GraalVM {@link Context} creations after which the shared {@link Engine}
     * is rotated for a tenant. Tune this based on the expected Metaspace budget:
     * lower = more frequent GC opportunities, higher = better JIT warm-up reuse.
     */
    // static final int ENGINE_ROTATION_THRESHOLD = Integer.MAX_VALUE; // set to e.g. 100 to enable rotation
    static final int ENGINE_ROTATION_THRESHOLD = 100;

    /**
     * Maximum wall-clock age of a tenant's {@link Engine} before it is rotated,
     * regardless of the context-creation count.  Prevents very-low-traffic tenants
     * from accumulating unbounded Metaspace over days.
     */
    static final Duration ENGINE_MAX_AGE = Duration.ofHours(24);

    // Prefix added by buildMappingCodeMap() to source names: "onMessage_<identifier>.js"
    private static final String SMART_FUNCTION_PREFIX = "onMessage_";

    // Structure: < Tenant, Engine >
    private final Map<String, Engine> graalEngines = new ConcurrentHashMap<>();

    // Structure: < Tenant, Source > — pre-compiled shared utility code (globalThis scope)
    private final Map<String, Source> graalSourceShared = new ConcurrentHashMap<>();

    // Structure: < Tenant, Source > — pre-compiled system/built-in code (globalThis scope)
    private final Map<String, Source> graalSourceSystem = new ConcurrentHashMap<>();

    // How many contexts have been created per tenant since the last Engine rotation
    private final Map<String, AtomicInteger> contextCounters = new ConcurrentHashMap<>();

    // Stored ServiceConfiguration per tenant — needed to recreate Engine on rotation
    private final Map<String, ServiceConfiguration> tenantServiceConfigs = new ConcurrentHashMap<>();

    // Number of in-flight Contexts per Engine (keyed by Engine identity, not by tenant)
    private final Map<Engine, AtomicInteger> engineActiveContexts = new ConcurrentHashMap<>();

    // Engines that have been retired (rotated out or removed) but still have open Contexts
    private final Set<Engine> retiredEngines = ConcurrentHashMap.newKeySet();

    // When each tenant's current Engine was created (for time-based rotation)
    private final Map<String, Instant> engineCreatedAt = new ConcurrentHashMap<>();

    // Optional per-tenant supplier of mapping JS code — called after each Engine rotation
    // so the new Engine's JIT is primed with all active mapping code before the first message.
    // Registered by BootstrapService during tenant initialisation.
    private final Map<String, java.util.function.Supplier<Map<String, String>>> mappingCodeSuppliers =
            new ConcurrentHashMap<>();

    /** Lazily initialised; the same HostAccess config is shared across all tenants and contexts. */
    private HostAccess hostAccess;

    /**
     * Returns the shared {@link HostAccess} configuration used by all GraalVM
     * contexts. Lazily initialised on first call.
     */
    public HostAccess getHostAccess() {
        if (hostAccess == null) {
            hostAccess = HostAccess.newBuilder()
                    .allowPublicAccess(true)
                    .allowArrayAccess(true)
                    .allowListAccess(true)
                    .allowMapAccess(true)
                    .build();
        }
        return hostAccess;
    }

    /**
     * Creates and warms up the GraalVM {@link Engine} and pre-compiled shared/system
     * {@link Source} objects for the given tenant.
     *
     * @param tenant               tenant identifier
     * @param serviceConfiguration the tenant's service configuration (provides code templates)
     */
    public void createGraalsResources(String tenant, ServiceConfiguration serviceConfiguration) {
        tenantServiceConfigs.put(tenant, serviceConfiguration);
        contextCounters.put(tenant, new AtomicInteger(0));

        Engine eng = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        graalEngines.put(tenant, eng);
        engineActiveContexts.put(eng, new AtomicInteger(0));
        engineCreatedAt.put(tenant, Instant.now());
        log.info("{} - GraalVM Engine created — baseline {}", tenant, metaspaceUsageSummary());

        boolean supportESM = Boolean.TRUE.equals(serviceConfiguration.getSupportESM());

        // Shared / system code is always evaluated as a plain script (.js) so that
        // every top-level declaration lands on globalThis and is visible to all mapping
        // modules running in the same GraalVM context. Only per-mapping code is loaded
        // as an ES module (.mjs) when supportESM is true.
        String sharedCode = serviceConfiguration.getCodeTemplates()
                .get(TemplateType.SHARED.name()).getCode();
        Source sharedSource = Source.newBuilder("js",
                JavaScriptModuleStripper.toPlainScript(
                        new String(Base64.getDecoder().decode(sharedCode))),
                "sharedCode.js")
                .cached(true)
                .buildLiteral();

        String systemCode = serviceConfiguration.getCodeTemplates()
                .get(TemplateType.SYSTEM.name()).getCode();
        Source systemSource = Source.newBuilder("js",
                JavaScriptModuleStripper.toPlainScript(
                        new String(Base64.getDecoder().decode(systemCode))),
                "systemCode.js")
                .cached(true)
                .buildLiteral();

        graalSourceShared.put(tenant, sharedSource);
        graalSourceSystem.put(tenant, systemSource);

        // Warm up the GraalVM JIT by running a throw-away Context through the
        // shared/system sources and a trivial onMessage stub. This triggers Graal's
        // JIT compiler at startup so the first real mapping executes in ~1 s instead
        // of ~7 s.
        Context.Builder warmupBuilder = Context.newBuilder("js")
                .engine(eng)
                .allowHostAccess(getHostAccess())
                .allowHostClassLookup(GraalVMContextService::isAllowedHostClass);
        if (supportESM) {
            warmupBuilder.allowIO(IOAccess.ALL)
                    .allowExperimentalOptions(true)
                    .option("js.esm-eval-returns-exports", "true");
        }
        try (Context warmupCtx = warmupBuilder.build()) {
            warmupCtx.eval(sharedSource);
            warmupCtx.eval(systemSource);
            warmupCtx.eval(Source.newBuilder("js",
                    "function __warmup__(msg, ctx) { return []; } __warmup__({}, null);",
                    "__warmup__.js").buildLiteral());
            log.info("{} - GraalVM JIT warm-up complete", tenant);
        } catch (Exception e) {
            log.warn("{} - GraalVM warm-up failed (non-fatal): {}", tenant, e.getMessage());
        }

        log.info("{} - Created cached GraalVM sources for shared and system code", tenant);
    }

    /**
     * Returns the shared GraalVM {@link Engine} for the tenant, rotating it when the
     * context-creation count reaches {@link #ENGINE_ROTATION_THRESHOLD}.
     *
     * <p>Only the thread whose increment lands exactly on the threshold triggers the
     * rotation, avoiding concurrent double-rotations.
     */
    public Engine getGraalEngine(String tenant) {
        AtomicInteger counter = contextCounters.get(tenant);
        Engine currentEngine = graalEngines.get(tenant);

        // Count-based rotation: only the thread whose increment lands on the threshold triggers it
        boolean atThreshold = counter != null && counter.incrementAndGet() == ENGINE_ROTATION_THRESHOLD;

        // Time-based rotation: rotate if the Engine is older than ENGINE_MAX_AGE
        Instant createdAt = engineCreatedAt.get(tenant);
        boolean tooOld = createdAt != null
                && Duration.between(createdAt, Instant.now()).compareTo(ENGINE_MAX_AGE) > 0;

        if (atThreshold || tooOld) {
            rotateEngine(tenant);
            currentEngine = graalEngines.get(tenant);
        }

        // Track this in-flight Context so we know when the Engine can be safely closed
        AtomicInteger activeCount = engineActiveContexts.get(currentEngine);
        if (activeCount != null) {
            activeCount.incrementAndGet();
        }
        return currentEngine;
    }

    /**
     * Replaces the tenant's {@link Engine} with a fresh instance so that the old
     * Engine's Metaspace (JIT-compiled JS code) can be reclaimed by the GC once
     * every in-flight {@link Context} referencing it has been closed.
     */
    private void rotateEngine(String tenant) {
        ServiceConfiguration config = tenantServiceConfigs.get(tenant);
        if (config == null) {
            log.warn("{} - Cannot rotate GraalVM Engine: ServiceConfiguration not cached; skipping rotation", tenant);
            return;
        }
        log.info("{} - Rotating GraalVM Engine to release Metaspace ({} retired engine(s) pending drain) — {}",
                tenant, retiredEngines.size(), metaspaceUsageSummary());
        // Retire the current Engine before creating the replacement.
        Engine oldEngine = graalEngines.get(tenant);
        if (oldEngine != null) {
            retiredEngines.add(oldEngine);
        }
        // createGraalsResources puts a fresh Engine into graalEngines and resets contextCounters.
        createGraalsResources(tenant, config);
        // Close the old Engine immediately if it has no open Contexts; otherwise the last
        // in-flight Context's engineReleaseAction callback will drain the count to zero and
        // call closeEngineIfDrained() for us.
        if (oldEngine != null) {
            closeEngineIfDrained(oldEngine);
        }
        // Re-warm all active mapping codes on the new Engine so the first real message after
        // rotation does not pay the full JIT cold-start penalty.
        java.util.function.Supplier<Map<String, String>> codeSupplier = mappingCodeSuppliers.get(tenant);
        if (codeSupplier != null) {
            try {
                warmupMappingCodes(tenant, codeSupplier.get());
            } catch (Exception e) {
                log.warn("{} - Failed to re-warm mapping codes after Engine rotation: {}", tenant, e.getMessage());
            }
        }
    }

    private String metaspaceUsageSummary() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().contains("Metaspace")) {
                MemoryUsage usage = pool.getUsage();
                long usedMB = usage.getUsed() / (1024 * 1024);
                long maxMB = usage.getMax();
                if (maxMB > 0) {
                    long pct = usedMB * 100 / (maxMB / (1024 * 1024));
                    return String.format("Metaspace %d MB / %d MB (%d%%)", usedMB, maxMB / (1024 * 1024), pct);
                }
                return String.format("Metaspace %d MB (no max set)", usedMB);
            }
        }
        return "Metaspace pool not found";
    }

    /**
     * Registers a supplier of mapping JavaScript source code for the given tenant.
     * The supplier is called after every Engine rotation so the replacement Engine
     * is pre-compiled with all active mapping code before the first real message arrives.
     *
     * @param tenant   tenant identifier
     * @param supplier returns a {@code sourceName → plainJS} map for all active SmartFunction mappings
     */
    public void setMappingCodeSupplier(String tenant,
            java.util.function.Supplier<Map<String, String>> supplier) {
        mappingCodeSuppliers.put(tenant, supplier);
    }

    /** Removes the mapping-code supplier registered for {@code tenant}. */
    public void removeMappingCodeSupplier(String tenant) {
        mappingCodeSuppliers.remove(tenant);
    }

    /**
     * Signals that one in-flight GraalVM {@link Context} has been closed.  When the
     * retired engine's active count drops to zero it is explicitly closed so the JVM
     * can reclaim its Metaspace rather than waiting for GC.
     *
     * <p>Called from {@link dynamic.mapper.processor.model.ProcessingContext#close()}
     * via a callback set by {@link dynamic.mapper.processor.AbstractEnrichmentProcessor}.
     *
     * @param engine the Engine that backed the just-closed Context
     */
    public void releaseEngine(Engine engine) {
        AtomicInteger count = engineActiveContexts.get(engine);
        if (count == null) return;
        count.decrementAndGet();
        closeEngineIfDrained(engine);
    }

    /**
     * Closes the engine if it is retired and has no more in-flight Contexts.
     * Safe to call from both {@link #releaseEngine(Engine)} (per-context drain) and
     * from {@link #rotateEngine}/{@link #removeGraalsResources} (idle-at-retirement
     * fast path).  The {@code retiredEngines.remove()} CAS ensures only one caller
     * wins the close race.
     */
    private void closeEngineIfDrained(Engine engine) {
        AtomicInteger count = engineActiveContexts.get(engine);
        if (count == null || count.get() > 0) return;
        if (!retiredEngines.contains(engine)) return;
        // Atomic: only the thread that removes the engine from retiredEngines closes it
        if (retiredEngines.remove(engine)) {
            engineActiveContexts.remove(engine);
            try {
                engine.close();
                log.info("Retired GraalVM Engine closed ({} retired engine(s) still pending drain)",
                        retiredEngines.size());
            } catch (Exception e) {
                log.warn("Error closing retired GraalVM Engine: {}", e.getMessage());
            }
        }
    }

    public void updateGraalsSourceShared(String tenant, String code) {
        Source source = Source.newBuilder("js",
                JavaScriptModuleStripper.toPlainScript(
                        new String(Base64.getDecoder().decode(code))),
                "sharedCode.js")
                .cached(true)
                .buildLiteral();
        graalSourceShared.put(tenant, source);
        // Keep tenantServiceConfigs in sync so Engine rotation uses the latest templates
        ServiceConfiguration config = tenantServiceConfigs.get(tenant);
        if (config != null && config.getCodeTemplates() != null) {
            config.getCodeTemplates().computeIfPresent(TemplateType.SHARED.name(),
                    (k, t) -> { t.setCode(code); return t; });
        }
        log.info("{} - Updated cached shared code source", tenant);
    }

    public Source getGraalsSourceShared(String tenant) {
        return graalSourceShared.get(tenant);
    }

    public void updateGraalsSourceSystem(String tenant, String code) {
        Source source = Source.newBuilder("js",
                JavaScriptModuleStripper.toPlainScript(
                        new String(Base64.getDecoder().decode(code))),
                "systemCode.js")
                .cached(true)
                .buildLiteral();
        graalSourceSystem.put(tenant, source);
        // Keep tenantServiceConfigs in sync so Engine rotation uses the latest templates
        ServiceConfiguration config = tenantServiceConfigs.get(tenant);
        if (config != null && config.getCodeTemplates() != null) {
            config.getCodeTemplates().computeIfPresent(TemplateType.SYSTEM.name(),
                    (k, t) -> { t.setCode(code); return t; });
        }
        log.info("{} - Updated cached system code source", tenant);
    }

    public Source getGraalsSourceSystem(String tenant) {
        return graalSourceSystem.get(tenant);
    }

    /**
     * Pre-compiles mapping-specific JavaScript into the Engine's Source cache.
     * Call this after mappings are loaded so the first test for each existing
     * mapping hits the cache instead of paying the full parse+compile cost.
     *
     * @param tenant      the tenant identifier
     * @param sourceCodes map of source name (e.g. "onMessage_&lt;id&gt;.js") →
     *                    decoded+adapted JS code
     */
    public void warmupMappingCodes(String tenant, Map<String, String> sourceCodes) {
        Engine eng = graalEngines.get(tenant);
        if (eng == null || sourceCodes.isEmpty()) return;

        ServiceConfiguration config = tenantServiceConfigs.get(tenant);
        boolean supportESM = config != null && Boolean.TRUE.equals(config.getSupportESM());

        // The warmup context mirrors the non-ESM runtime context. ESM mode uses allowIO
        // which is not needed here — mapping codes evaluated below are stripped of exports.
        try (Context warmupCtx = Context.newBuilder("js")
                .engine(eng)
                .allowHostAccess(getHostAccess())
                .allowHostClassLookup(GraalVMContextService::isAllowedHostClass)
                .build()) {

            warmupCtx.eval(graalSourceShared.get(tenant));
            warmupCtx.eval(graalSourceSystem.get(tenant));

            int warmed = 0;
            for (Map.Entry<String, String> entry : sourceCodes.entrySet()) {
                // The key from buildMappingCodeMap() is "onMessage_<identifier>.js".
                // Strip the prefix so the Source name matches what AbstractFlowProcessor
                // builds at runtime — this ensures GraalVM's source cache is actually hit
                // on the first real message execution.
                String key = entry.getKey();
                String runtimeName = key.startsWith(SMART_FUNCTION_PREFIX)
                        ? key.substring(SMART_FUNCTION_PREFIX.length())
                        : key;

                try {
                    Source source;
                    if (supportESM) {
                        // ESM path: raw code evaluated as .mjs, matching the runtime source.
                        String msjName = runtimeName.endsWith(".js")
                                ? runtimeName.substring(0, runtimeName.length() - 3) + ".mjs"
                                : runtimeName;
                        source = Source.newBuilder("js", entry.getValue(), msjName)
                                .cached(true)
                                .buildLiteral();
                    } else {
                        // Non-ESM path: strip ES module export/import statements and wrap in an
                        // IIFE — identical to AbstractFlowProcessor. This prevents top-level
                        // declarations in the mapping code (e.g. `const globalConfig` from a
                        // bundled Zod library) from colliding with the same declarations in
                        // shared.js or in preceding mapping evals within the same warmup context.
                        // It also ensures the cached Source content matches the runtime source.
                        String code = JavaScriptModuleStripper.toPlainScript(entry.getValue());
                        String wrapped = "(function() {\n" + code + "\n"
                                + "globalThis['onMessage'] = onMessage;\n"
                                + "})();";
                        source = Source.newBuilder("js", wrapped, runtimeName)
                                .cached(true)
                                .buildLiteral();
                    }
                    warmupCtx.eval(source);
                    warmed++;
                } catch (Exception e) {
                    log.warn("{} - Failed to pre-compile mapping {}: {}", tenant, entry.getKey(),
                            e.getMessage());
                }
            }
            log.info("{} - GraalVM pre-compiled {} mapping JavaScript source(s)", tenant, warmed);
        } catch (Exception e) {
            log.warn("{} - Mapping code warm-up failed (non-fatal): {}", tenant, e.getMessage());
        }
    }

    public void removeGraalsResources(String tenant) {
        Engine eng = graalEngines.remove(tenant);
        if (eng != null) {
            // Retire the engine; close immediately if idle, or let the last in-flight
            // Context's engineReleaseAction drain it to zero and close it.
            retiredEngines.add(eng);
            closeEngineIfDrained(eng);
        }
        graalSourceShared.remove(tenant);
        graalSourceSystem.remove(tenant);
        contextCounters.remove(tenant);
        tenantServiceConfigs.remove(tenant);
        engineCreatedAt.remove(tenant);
        mappingCodeSuppliers.remove(tenant);
        log.info("{} - Removed GraalVM engine and cached sources", tenant);
    }

    /**
     * Host-class allow-list shared by all GraalVM context builders in this service.
     * Kept in one place so that {@link #createGraalsResources} and
     * {@link #warmupMappingCodes} stay consistent.
     */
    private static boolean isAllowedHostClass(String className) {
        return className.equals("dynamic.mapper.processor.model.SubstitutionContext")
                || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                || className.equals("dynamic.mapper.processor.model.RepairStrategy")
                || className.equals("java.nio.charset.StandardCharsets")
                || className.equals("java.util.Base64")
                || className.equals("java.lang.String")
                || className.equals("java.util.ArrayList")
                || className.equals("java.util.Arrays")
                || className.equals("java.util.HashMap")
                || className.equals("java.util.HashSet");
    }
}
