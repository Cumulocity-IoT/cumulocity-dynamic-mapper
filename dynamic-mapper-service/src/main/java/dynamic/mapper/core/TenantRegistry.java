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
import dynamic.mapper.model.DeviceToClientMapRepresentation;
import dynamic.mapper.model.MapperServiceRepresentation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    // Structure: < tenant|internalC8YId, tenant|externalIdType|externalId > — reverse index
    // enabling eviction of a stale externalIdCache entry when only the internal id (e.g. from a
    // deleted managed object) is known.
    private final Map<String, String> externalIdCacheReverse = new ConcurrentHashMap<>();

    // Structure: < tenant|externalIdType|externalId, lock object > — per-ID monitor for double-check locking during implicit device creation
    private final Map<String, Object> externalIdLocks = new ConcurrentHashMap<>();

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
        String tenant = cacheKey.substring(0, cacheKey.indexOf('|'));
        externalIdCacheReverse.put(tenant + "|" + internalId, cacheKey);
    }

    /**
     * Removes a single entry from the external-ID cache and its associated lock.
     * Used after a 422 response to force re-resolution on the next message.
     */
    public void removeFromExternalIdCache(String tenant, ID identity) {
        String cacheKey = tenant + "|" + identity.getType() + "|" + identity.getValue();
        String internalId = externalIdCache.remove(cacheKey);
        externalIdLocks.remove(cacheKey);
        if (internalId != null) {
            externalIdCacheReverse.remove(tenant + "|" + internalId);
        }
        log.debug("{} - Removed external ID from cache: {}", tenant, cacheKey);
    }

    /**
     * Removes the external-ID cache entry that resolves to {@code internalId}, if any.
     * Used when a cached device id is discovered to no longer exist upstream (e.g. the managed
     * object was deleted from inventory) so the next message for the same external ID
     * re-triggers resolution instead of indefinitely reusing the stale, deleted id.
     */
    public void removeFromExternalIdCacheByInternalId(String tenant, String internalId) {
        if (internalId == null) {
            return;
        }
        String cacheKey = externalIdCacheReverse.remove(tenant + "|" + internalId);
        if (cacheKey != null) {
            externalIdCache.remove(cacheKey);
            externalIdLocks.remove(cacheKey);
            log.debug("{} - Removed stale external ID cache entry for deleted device {}: {}",
                    tenant, internalId, cacheKey);
        }
    }

    /**
     * Clears the external-ID cache. If {@code tenant} is non-null, only entries
     * for that tenant are removed (prefix match). If null, the entire cache is
     * cleared.
     */
    public void clearExternalIdCache(String tenant) {
        if (tenant == null) {
            externalIdCache.clear();
            externalIdCacheReverse.clear();
            log.info("Cleared entire external ID cache");
        } else {
            externalIdCache.entrySet().removeIf(entry -> entry.getKey().startsWith(tenant + "|"));
            externalIdCacheReverse.entrySet().removeIf(entry -> entry.getKey().startsWith(tenant + "|"));
            log.debug("{} - Cleared external ID cache", tenant);
        }
    }

}
