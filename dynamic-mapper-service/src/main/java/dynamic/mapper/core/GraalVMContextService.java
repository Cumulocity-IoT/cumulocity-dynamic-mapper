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

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 */
@Slf4j
@Component
public class GraalVMContextService {

    // Structure: < Tenant, Engine >
    private final Map<String, Engine> graalEngines = new ConcurrentHashMap<>();

    // Structure: < Tenant, Source > — pre-compiled shared utility code (globalThis scope)
    private final Map<String, Source> graalSourceShared = new ConcurrentHashMap<>();

    // Structure: < Tenant, Source > — pre-compiled system/built-in code (globalThis scope)
    private final Map<String, Source> graalSourceSystem = new ConcurrentHashMap<>();

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
        Engine eng = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        graalEngines.put(tenant, eng);

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

    public Engine getGraalEngine(String tenant) {
        return graalEngines.get(tenant);
    }

    public void updateGraalsSourceShared(String tenant, String code) {
        Source source = Source.newBuilder("js",
                JavaScriptModuleStripper.toPlainScript(
                        new String(Base64.getDecoder().decode(code))),
                "sharedCode.js")
                .cached(true)
                .buildLiteral();
        graalSourceShared.put(tenant, source);
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

        try (Context warmupCtx = Context.newBuilder("js")
                .engine(eng)
                .allowHostAccess(getHostAccess())
                .allowHostClassLookup(GraalVMContextService::isAllowedHostClass)
                .build()) {

            warmupCtx.eval(graalSourceShared.get(tenant));
            warmupCtx.eval(graalSourceSystem.get(tenant));

            int warmed = 0;
            for (Map.Entry<String, String> entry : sourceCodes.entrySet()) {
                try {
                    Source source = Source.newBuilder("js", entry.getValue(), entry.getKey())
                            .cached(true)
                            .buildLiteral();
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
        graalEngines.remove(tenant);
        graalSourceShared.remove(tenant);
        graalSourceSystem.remove(tenant);
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
