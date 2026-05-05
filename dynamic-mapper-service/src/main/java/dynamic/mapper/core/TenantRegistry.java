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

package dynamic.mapper.core;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.model.ID;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.model.DeviceToClientMapRepresentation;
import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.processor.util.JavaScriptModuleStripper;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry holding all per-tenant runtime state.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>GraalVM engine and compiled JS sources (one engine per tenant)</li>
 *   <li>Microservice credentials per tenant</li>
 *   <li>Active service configuration per tenant</li>
 *   <li>Mapper service managed-object reference per tenant</li>
 *   <li>Device-to-connector-client mapping cache per tenant</li>
 *   <li>External-ID resolution cache and per-ID creation locks</li>
 * </ul>
 *
 * <p>All state is keyed by tenant. Thread-safety at the map level is guaranteed
 * by {@link ConcurrentHashMap}. Individual values are not shared across tenants.
 *
 * <p>This class intentionally has no Spring service dependencies so that it can
 * be injected into low-level components without creating circular dependencies.
 * Methods that require C8Y API calls (e.g. device creation) remain in
 * {@link ConfigurationRegistry}, which calls back into this registry for
 * cache and lock access.
 */
@Slf4j
@Component
public class TenantRegistry {

    // ─── GraalVM ──────────────────────────────────────────────────────────────

    // Structure: < Tenant, Engine >
    private final Map<String, Engine>  graalEngines      = new ConcurrentHashMap<>();

    // Structure: < Tenant, Source > — pre-compiled shared utility code (globalThis scope)
    private final Map<String, Source>  graalSourceShared = new ConcurrentHashMap<>();

    // Structure: < Tenant, Source > — pre-compiled system/built-in code (globalThis scope)
    private final Map<String, Source>  graalSourceSystem = new ConcurrentHashMap<>();

    // Structure: < Tenant, Boolean > — true when ESM (.mjs) is enabled for per-mapping code
    private final Map<String, Boolean> tenantESMFlags    = new ConcurrentHashMap<>();

    /** Lazily initialised; the same HostAccess config is shared across all tenants and contexts. */
    private HostAccess hostAccess;

    // ─── Credentials & configuration ─────────────────────────────────────────

    // Structure: < Tenant, MicroserviceCredentials >
    private final Map<String, MicroserviceCredentials>     microserviceCredentials      = new ConcurrentHashMap<>();

    // Structure: < Tenant, ServiceConfiguration >
    private final Map<String, ServiceConfiguration>        serviceConfigurations        = new ConcurrentHashMap<>();

    // Structure: < Tenant, MapperServiceRepresentation >
    private final Map<String, MapperServiceRepresentation> mapperServiceRepresentations = new ConcurrentHashMap<>();

    // ─── Device → connector-client mapping ───────────────────────────────────

    // Structure: < Tenant, < DeviceId, ConnectorClientId > >
    private final Map<String, Map<String, String>> deviceToClientPerTenant = new ConcurrentHashMap<>();

    // Structure: < Tenant, ManagedObject Id > — C8Y inventory ID of the persisted device-to-client map
    private final Map<String, String>              deviceToClientMapIds    = new ConcurrentHashMap<>();

    // ─── External-ID resolution cache & per-ID creation locks ────────────────

    // Structure: < tenant|externalIdType|externalId, internalC8YId >
    private final Map<String, String> externalIdCache = new ConcurrentHashMap<>();

    // Structure: < tenant|externalIdType|externalId, lock object > — per-ID monitor for double-check locking during implicit device creation
    private final Map<String, Object> externalIdLocks = new ConcurrentHashMap<>();

    // =========================================================================
    // GraalVM
    // =========================================================================

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
        tenantESMFlags.put(tenant, supportESM);

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
                .allowHostClassLookup(TenantRegistry::isAllowedHostClass);
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
                .allowHostClassLookup(TenantRegistry::isAllowedHostClass)
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
        tenantESMFlags.remove(tenant);
        log.info("{} - Removed GraalVM engine and cached sources", tenant);
    }

    // =========================================================================
    // Credentials
    // =========================================================================

    public MicroserviceCredentials getMicroserviceCredential(String tenant) {
        return microserviceCredentials.get(tenant);
    }

    public void addMicroserviceCredentials(String tenant, MicroserviceCredentials credentials) {
        microserviceCredentials.put(tenant, credentials);
    }

    public void removeMicroserviceCredentials(String tenant) {
        microserviceCredentials.remove(tenant);
    }

    // =========================================================================
    // Service configuration
    // =========================================================================

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        return serviceConfigurations.get(tenant);
    }

    public void addServiceConfiguration(String tenant, ServiceConfiguration configuration) {
        serviceConfigurations.put(tenant, configuration);
    }

    public void removeServiceConfiguration(String tenant) {
        serviceConfigurations.remove(tenant);
    }

    // =========================================================================
    // Mapper service managed-object reference
    // =========================================================================

    public void addMapperServiceRepresentation(String tenant, MapperServiceRepresentation repr) {
        mapperServiceRepresentations.put(tenant, repr);
    }

    public MapperServiceRepresentation getMapperServiceRepresentation(String tenant) {
        return mapperServiceRepresentations.get(tenant);
    }

    public void removeMapperServiceRepresentation(String tenant) {
        mapperServiceRepresentations.remove(tenant);
    }

    // =========================================================================
    // Device → connector-client mapping
    // =========================================================================

    /**
     * Initialises the device-to-client map for a tenant from the persisted
     * {@link DeviceToClientMapRepresentation}. Replaces any existing in-memory map.
     */
    public void initializeDeviceToClientMap(String tenant,
            DeviceToClientMapRepresentation representation) {
        deviceToClientMapIds.put(tenant, representation.getId());
        Map<String, String> clientMap = new ConcurrentHashMap<>();
        if (representation.getDeviceToClientMap() != null) {
            log.debug("{} - Initializing Device To Client Map with {} entries", tenant,
                    representation.getDeviceToClientMap().size());
            clientMap.putAll(representation.getDeviceToClientMap());
        }
        deviceToClientPerTenant.put(tenant, clientMap);
    }

    public String getDeviceToClientMapId(String tenant) {
        return deviceToClientMapIds.get(tenant);
    }

    public void addOrUpdateClientRelation(String tenant, String clientId, String deviceId) {
        deviceToClientPerTenant.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(deviceId, clientId);
        log.debug("Added client mapping for tenant {}: device {} -> client {}", tenant, deviceId, clientId);
    }

    public void addOrUpdateClientRelations(String tenant, String clientId, List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            log.debug("No device IDs provided for tenant {}, client {}", tenant, clientId);
            return;
        }
        Map<String, String> tenantMappings = deviceToClientPerTenant
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
        deviceIds.forEach(deviceId -> tenantMappings.put(deviceId, clientId));
        log.debug("Added {} client mappings for tenant {}: devices {} -> client {}",
                deviceIds.size(), tenant, deviceIds, clientId);
    }

    public void removeClientRelation(String tenant, String deviceId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        if (tenantMappings != null) {
            String removedClientId = tenantMappings.remove(deviceId);
            if (removedClientId != null) {
                log.debug("Removed client mapping for tenant {}: device {} (was mapped to client {})",
                        tenant, deviceId, removedClientId);
            }
        }
    }

    public void removeClientById(String tenant, String clientId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        if (tenantMappings != null) {
            List<String> toRemove = tenantMappings.entrySet().stream()
                    .filter(e -> clientId.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            toRemove.forEach(tenantMappings::remove);
            log.debug("Removed {} device mappings for client {} in tenant {}",
                    toRemove.size(), clientId, tenant);
        }
    }

    public void clearCacheDeviceToClient(String tenant) {
        deviceToClientPerTenant.put(tenant, new ConcurrentHashMap<>());
        log.debug("Cleared all client mappings for tenant {}", tenant);
    }

    public String resolveDeviceToClient(String tenant, String deviceId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        return tenantMappings != null ? tenantMappings.get(deviceId) : null;
    }

    public Map<String, String> getAllClientRelations(String tenant) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        return tenantMappings != null ? new HashMap<>(tenantMappings) : new HashMap<>();
    }

    public List<String> getDevicesForClient(String tenant, String clientId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        if (tenantMappings == null) return new ArrayList<>();
        return tenantMappings.entrySet().stream()
                .filter(e -> clientId.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public List<String> getAllClients(String tenant) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        if (tenantMappings == null) return new ArrayList<>();
        return tenantMappings.values().stream()
                .distinct().sorted().collect(Collectors.toList());
    }

    public int getClientRelationCount(String tenant) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        return tenantMappings != null ? tenantMappings.size() : 0;
    }

    public boolean hasClientRelation(String tenant, String deviceId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        return tenantMappings != null && tenantMappings.containsKey(deviceId);
    }

    // =========================================================================
    // External-ID resolution cache and per-ID creation locks
    // =========================================================================

    /**
     * Returns the lock object for the given cache key, creating one atomically if
     * absent. Callers use this for the double-check locking pattern when creating
     * implicit devices.
     */
    public Object getOrCreateExternalIdLock(String cacheKey) {
        return externalIdLocks.computeIfAbsent(cacheKey, k -> new Object());
    }

    public String getCachedExternalId(String cacheKey) {
        return externalIdCache.get(cacheKey);
    }

    public void cacheExternalId(String cacheKey, String internalId) {
        externalIdCache.put(cacheKey, internalId);
    }

    /**
     * Removes a single entry from the external-ID cache and its associated lock.
     * Used after a 422 response to force re-resolution on the next message.
     */
    public void removeFromExternalIdCache(String tenant, ID identity) {
        String cacheKey = tenant + "|" + identity.getType() + "|" + identity.getValue();
        externalIdCache.remove(cacheKey);
        externalIdLocks.remove(cacheKey);
        log.debug("{} - Removed external ID from cache: {}", tenant, cacheKey);
    }

    /**
     * Clears the external-ID cache. If {@code tenant} is non-null, only entries
     * for that tenant are removed (prefix match). If null, the entire cache is
     * cleared.
     */
    public void clearExternalIdCache(String tenant) {
        if (tenant == null) {
            externalIdCache.clear();
            log.info("Cleared entire external ID cache");
        } else {
            externalIdCache.entrySet().removeIf(entry -> entry.getKey().startsWith(tenant + "|"));
            log.debug("{} - Cleared external ID cache", tenant);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Host-class allow-list shared by all GraalVM context builders in this registry.
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
