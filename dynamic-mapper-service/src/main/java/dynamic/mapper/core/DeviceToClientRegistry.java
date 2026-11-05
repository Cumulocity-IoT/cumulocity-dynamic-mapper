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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dynamic.mapper.model.device.DeviceToClientMapRepresentation;
import lombok.extern.slf4j.Slf4j;

/**
 * Which connector client each device belongs to, per tenant.
 * <p>
 * Used by the device-isolation feature of the MQTT Service: a client relation is recorded when a device
 * first sends a message through a client, and the whole map is persisted to the inventory. This class holds
 * the in-memory map, the changes made on this instance that are not yet persisted, and the id of the
 * persisted map object. It holds no other tenant state.
 */
@Slf4j
@Component
public class DeviceToClientRegistry {

    // ─── Device → connector-client mapping ───────────────────────────────────

    // Structure: < Tenant, < DeviceId, ConnectorClientId > >
    private final Map<String, Map<String, String>> deviceToClientPerTenant = new ConcurrentHashMap<>();
    /**
     * Relation changes made on this instance and not yet persisted: device to client for an upsert,
     * device to {@code null} for a removal. The startup snapshot loaded from the inventory is not in
     * here, so a sync never replays what another instance wrote. Guarded by its own monitor, which is
     * also held while the relation map is mutated, so a snapshot of the changes is consistent with it.
     */
    private final Map<String, Map<String, String>> pendingClientChangesPerTenant = new ConcurrentHashMap<>();

    // Structure: < Tenant, ManagedObject Id > — C8Y inventory ID of the persisted device-to-client map
    private final Map<String, String>              deviceToClientMapIds    = new ConcurrentHashMap<>();


    // =========================================================================
    // Device → connector-client mapping
    // =========================================================================

    /**
     * Loads the persisted {@link DeviceToClientMapRepresentation} into the in-memory map of a tenant.
     * Relations already collected in memory win over persisted ones (they are newer), and persisted
     * relations this instance removed since are not restored. The loaded entries are not recorded as
     * pending changes.
     */
    public void initializeDeviceToClientMap(String tenant,
            DeviceToClientMapRepresentation representation) {
        deviceToClientMapIds.put(tenant, representation.getId());
        Map<String, String> clientMap = deviceToClientPerTenant.computeIfAbsent(tenant,
                k -> new ConcurrentHashMap<>());
        if (representation.getDeviceToClientMap() != null) {
            log.debug("{} - Initializing Device To Client Map with {} entries", tenant,
                    representation.getDeviceToClientMap().size());
            Map<String, String> pending = pendingChanges(tenant);
            synchronized (pending) {
                representation.getDeviceToClientMap().forEach((deviceId, clientId) -> {
                    if (!pending.containsKey(deviceId)) {
                        clientMap.putIfAbsent(deviceId, clientId);
                    }
                });
            }
        }
    }

    public String getDeviceToClientMapId(String tenant) {
        return deviceToClientMapIds.get(tenant);
    }

    public void addOrUpdateClientRelation(String tenant, String clientId, String deviceId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.computeIfAbsent(tenant,
                k -> new ConcurrentHashMap<>());
        Map<String, String> pending = pendingChanges(tenant);
        synchronized (pending) {
            tenantMappings.put(deviceId, clientId);
            pending.put(deviceId, clientId);
        }
        log.debug("Added client mapping for tenant {}: device {} -> client {}", tenant, deviceId, clientId);
    }

    public void addOrUpdateClientRelations(String tenant, String clientId, List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            log.debug("No device IDs provided for tenant {}, client {}", tenant, clientId);
            return;
        }
        Map<String, String> tenantMappings = deviceToClientPerTenant
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
        Map<String, String> pending = pendingChanges(tenant);
        synchronized (pending) {
            deviceIds.forEach(deviceId -> {
                tenantMappings.put(deviceId, clientId);
                pending.put(deviceId, clientId);
            });
        }
        log.debug("Added {} client mappings for tenant {}: devices {} -> client {}",
                deviceIds.size(), tenant, deviceIds, clientId);
    }

    public void removeClientRelation(String tenant, String deviceId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        if (tenantMappings != null) {
            Map<String, String> pending = pendingChanges(tenant);
            String removedClientId;
            synchronized (pending) {
                removedClientId = tenantMappings.remove(deviceId);
                if (removedClientId != null) {
                    pending.put(deviceId, null);
                }
            }
            if (removedClientId != null) {
                log.debug("Removed client mapping for tenant {}: device {} (was mapped to client {})",
                        tenant, deviceId, removedClientId);
            }
        }
    }

    public void removeClientById(String tenant, String clientId) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.get(tenant);
        if (tenantMappings != null) {
            Map<String, String> pending = pendingChanges(tenant);
            List<String> toRemove;
            synchronized (pending) {
                toRemove = tenantMappings.entrySet().stream()
                        .filter(e -> clientId.equals(e.getValue()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
                toRemove.forEach(deviceId -> {
                    tenantMappings.remove(deviceId);
                    pending.put(deviceId, null);
                });
            }
            log.debug("Removed {} device mappings for client {} in tenant {}",
                    toRemove.size(), clientId, tenant);
        }
    }

    /**
     * Drops all in-memory device-to-client state of a tenant (relations, pending changes, persisted
     * map id). Unlike {@link #clearCacheDeviceToClient} it records no pending changes, since the
     * tenant is gone and nothing must be persisted for it afterwards.
     */
    public void removeDeviceToClientState(String tenant) {
        deviceToClientPerTenant.remove(tenant);
        pendingClientChangesPerTenant.remove(tenant);
        deviceToClientMapIds.remove(tenant);
    }

    public void clearCacheDeviceToClient(String tenant) {
        Map<String, String> tenantMappings = deviceToClientPerTenant.computeIfAbsent(tenant,
                k -> new ConcurrentHashMap<>());
        Map<String, String> pending = pendingChanges(tenant);
        synchronized (pending) {
            tenantMappings.keySet().forEach(deviceId -> pending.put(deviceId, null));
            tenantMappings.clear();
        }
        log.debug("Cleared all client mappings for tenant {}", tenant);
    }

    private Map<String, String> pendingChanges(String tenant) {
        return pendingClientChangesPerTenant.computeIfAbsent(tenant, k -> new HashMap<>());
    }

    /**
     * The relation changes made on this instance since they were last persisted: device to client, or
     * device to {@code null} for a removal.
     */
    public Map<String, String> getPendingClientChanges(String tenant) {
        Map<String, String> pending = pendingChanges(tenant);
        synchronized (pending) {
            return new HashMap<>(pending);
        }
    }

    /**
     * Forgets the changes that are now persisted. A change made again after the snapshot was taken
     * stays pending.
     */
    public void acknowledgeClientChanges(String tenant, Map<String, String> persisted) {
        Map<String, String> pending = pendingChanges(tenant);
        synchronized (pending) {
            persisted.forEach((deviceId, clientId) -> {
                if (pending.containsKey(deviceId) && java.util.Objects.equals(pending.get(deviceId), clientId)) {
                    pending.remove(deviceId);
                }
            });
        }
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
}
