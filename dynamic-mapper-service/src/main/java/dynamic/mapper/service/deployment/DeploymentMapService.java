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
 package dynamic.mapper.service.deployment;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.MapperServiceRepresentation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages the deployment of mappings to connectors.
 * 
 * <p>This service handles the <strong>deployment configuration</strong> aspect, which determines
 * which mappings are assigned to which connectors. This is one of three conditions that must
 * be met for a mapping to be applied on a connector:
 * 
 * <ol>
 *   <li>The mapping must be <strong>active</strong></li>
 *   <li>The mapping must be <strong>deployed to the connector</strong> (managed by this class)</li>
 *   <li>The mapping must be <strong>compatible</strong> with the connector's capabilities 
 *       (e.g., wildcard support)</li>
 * </ol>
 * 
 * <p>The deployment map maintains a many-to-many relationship between mappings and connectors,
 * stored per tenant and persisted in the Cumulocity inventory.
 * 
 * <p><strong>Note:</strong> The {@code MappingSubscriptionService} evaluates all three conditions
 * to determine if a mapping is actually applied/effective on a connector.
 * 
 * @see dynamic.mapper.service.MappingSubscriptionService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentMapService {

    private final InventoryFacade inventoryApi;
    private final ConfigurationRegistry configurationRegistry;
    private final MicroserviceSubscriptionsService subscriptionsService;

    // Structure: <Tenant, <MappingIdentifier, List<ConnectorIdentifier>>>
    private final Map<String, Map<String, List<String>>> deploymentMaps = new ConcurrentHashMap<>();

    /**
     * Initializes the deployment map for a tenant.
     * 
     * <p>Loads the deployment configuration from the persisted {@link MapperServiceRepresentation}
     * or creates an empty map if none exists or reset is requested.
     * 
     * @param tenant the tenant identifier
     * @param reset if true, creates a new empty deployment map; if false, loads existing configuration
     */
    public void initializeTenantDeploymentMap(String tenant, boolean reset) {
        MapperServiceRepresentation serviceRep = configurationRegistry.getMapperServiceRepresentation(tenant);

        if (serviceRep.getDeploymentMap() != null && !reset) {
            log.debug("{} - Initializing deployment map with {} entries",
                tenant, serviceRep.getDeploymentMap().size());
            // Deep-copy the persisted entries: the outer map must be concurrent and each
            // inner list must be thread-safe, since connector threads read these lists
            // concurrently while the deployment API mutates them.
            Map<String, List<String>> loaded = serviceRep.getDeploymentMap().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new CopyOnWriteArrayList<>(
                                    e.getValue() == null ? List.of() : e.getValue()),
                            (a, b) -> a,
                            ConcurrentHashMap::new));
            deploymentMaps.put(tenant, loaded);
        } else {
            deploymentMaps.put(tenant, new ConcurrentHashMap<>());
        }
        persistDeploymentMap(tenant);
        log.info("{} - Deployment map initialized", tenant);
    }

    /**
     * Removes the deployment map for a tenant from memory.
     * 
     * <p>This does not delete the persisted configuration; it only removes the in-memory cache.
     * Typically called during tenant cleanup or service shutdown.
     * 
     * @param tenant the tenant identifier
     */
    public void removeTenantDeploymentMap(String tenant) {
        deploymentMaps.remove(tenant);
        log.debug("{} - Deployment map removed", tenant);
    }

    /**
     * Gets a copy of the entire deployment map for a tenant.
     * 
     * @param tenant the tenant identifier
     * @return a copy of the deployment map (mapping identifier → list of connector identifiers)
     */
    public Map<String, List<String>> getDeploymentMap(String tenant) {
        // Deep-copy: the inner lists are shared mutable state. Returning the live
        // lists would expose them to concurrent mutation (e.g. while a caller
        // serializes the result) and let callers modify the deployment map.
        return getOrCreateDeploymentMap(tenant).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> (List<String>) new ArrayList<>(e.getValue())));
    }

    /**
     * Gets the list of connectors a specific mapping is deployed to.
     * 
     * @param tenant the tenant identifier
     * @param mappingIdentifier the unique identifier of the mapping
     * @return a copy of the list of connector identifiers; empty list if mapping has no deployments
     */
    public List<String> getDeployedConnectors(String tenant, String mappingIdentifier) {
        // Read-only lookup: must NOT insert an entry. This is called for every mapping
        // on every subscription evaluation; using computeIfAbsent here would pollute the
        // deployment map with empty entries for every queried mapping and persist them.
        List<String> connectors = getOrCreateDeploymentMap(tenant).get(mappingIdentifier);
        return connectors == null ? new ArrayList<>() : new ArrayList<>(connectors);
    }

    /**
     * Updates the complete deployment configuration for a mapping.
     * 
     * <p>Replaces the existing list of deployed connectors with the provided list.
     * Changes are immediately persisted to the Cumulocity inventory.
     * 
     * @param tenant the tenant identifier
     * @param mappingIdentifier the unique identifier of the mapping
     * @param connectors the complete list of connector identifiers to deploy this mapping to
     */
    public void updateDeployment(String tenant, String mappingIdentifier, @Valid List<String> connectors) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        // De-duplicate and store in a thread-safe list (connector threads read it concurrently).
        tenantMap.put(mappingIdentifier,
                new CopyOnWriteArrayList<>(connectors.stream().distinct().collect(Collectors.toList())));
        
        log.debug("{} - Updated deployment for mapping {}: {} connectors", 
            tenant, mappingIdentifier, connectors.size());
        
        persistDeploymentMap(tenant);
    }

    /**
     * Removes a connector from all mapping deployments.
     * 
     * <p>This is typically called when a connector is deleted, ensuring it's removed
     * from all deployment configurations.
     * 
     * @param tenant the tenant identifier
     * @param connectorIdentifier the connector to remove
     * @return true if any mappings were modified; false if the connector wasn't deployed anywhere
     */
    public boolean removeConnectorFromAllMappings(String tenant, String connectorIdentifier) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        boolean modified = false;

        for (Map.Entry<String, List<String>> entry : tenantMap.entrySet()) {
            List<String> connectors = entry.getValue();
            if (connectors.remove(connectorIdentifier)) {
                modified = true;
                log.debug("{} - Removed connector {} from mapping {}", 
                    tenant, connectorIdentifier, entry.getKey());
            }
        }

        if (modified) {
            persistDeploymentMap(tenant);
        }

        return modified;
    }

    /**
     * Removes all deployment configuration for a mapping.
     * 
     * <p>This is typically called when a mapping is deleted, removing it from the deployment map.
     * 
     * @param tenant the tenant identifier
     * @param mappingIdentifier the unique identifier of the mapping
     * @return true if the mapping had deployments and was removed; false if it wasn't in the deployment map
     */
    public boolean removeMappingDeployment(String tenant, String mappingIdentifier) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        List<String> removed = tenantMap.remove(mappingIdentifier);

        // Persist whenever an entry was actually removed from the in-memory map, even if
        // the connector list was empty. Otherwise in-memory and persisted state diverge
        // (the entry is gone from memory but reappears from inventory on restart).
        boolean wasRemoved = removed != null;

        if (wasRemoved) {
            log.debug("{} - Removed deployment for mapping {}", tenant, mappingIdentifier);
            persistDeploymentMap(tenant);
        }

        return wasRemoved;
    }

    /**
     * Adds a single connector to a mapping's deployment configuration.
     * 
     * <p>If the connector is already deployed, no action is taken.
     * 
     * @param tenant the tenant identifier
     * @param mappingIdentifier the unique identifier of the mapping
     * @param connectorIdentifier the connector to add
     */
    public void addConnectorToMapping(String tenant, String mappingIdentifier, String connectorIdentifier) {
        List<String> connectors = getOrCreateDeploymentMap(tenant)
            .computeIfAbsent(mappingIdentifier, k -> new CopyOnWriteArrayList<>());

        if (!connectors.contains(connectorIdentifier)) {
            connectors.add(connectorIdentifier);
            log.debug("{} - Added connector {} to mapping {}", 
                tenant, connectorIdentifier, mappingIdentifier);
            persistDeploymentMap(tenant);
        }
    }

    /**
     * Removes a single connector from a specific mapping's deployment configuration.
     * 
     * @param tenant the tenant identifier
     * @param mappingIdentifier the unique identifier of the mapping
     * @param connectorIdentifier the connector to remove
     * @return true if the connector was found and removed; false otherwise
     */
    public boolean removeConnectorFromMapping(String tenant, String mappingIdentifier, String connectorIdentifier) {
        List<String> connectors = getOrCreateDeploymentMap(tenant).get(mappingIdentifier);
        
        if (connectors != null && connectors.remove(connectorIdentifier)) {
            log.debug("{} - Removed connector {} from mapping {}", 
                tenant, connectorIdentifier, mappingIdentifier);
            persistDeploymentMap(tenant);
            return true;
        }
        
        return false;
    }

    /**
     * Checks if a connector is deployed for a specific mapping.
     * 
     * <p><strong>Note:</strong> This only checks the deployment configuration.
     * A mapping may be deployed but not active/applied if it's inactive or incompatible
     * with the connector's capabilities.
     * 
     * @param tenant the tenant identifier
     * @param mappingIdentifier the unique identifier of the mapping
     * @param connectorIdentifier the connector identifier to check
     * @return true if the connector is in the mapping's deployment list; false otherwise
     */
    public boolean isConnectorDeployed(String tenant, String mappingIdentifier, String connectorIdentifier) {
        List<String> connectors = getOrCreateDeploymentMap(tenant).get(mappingIdentifier);
        return connectors != null && connectors.contains(connectorIdentifier);
    }

    /**
     * Removes connector identifiers that no longer exist from all mapping deployment entries.
     *
     * <p>This is called at startup to clean up stale references left behind if a connector
     * was deleted without going through the normal deletion flow (e.g., direct data manipulation
     * or a service crash).
     *
     * @param tenant                  the tenant identifier
     * @param validConnectorIdentifiers the set of connector identifiers that currently exist
     * @return true if any stale entries were removed and the map was persisted; false otherwise
     */
    public boolean cleanupStaleConnectors(String tenant, Set<String> validConnectorIdentifiers) {
        Map<String, List<String>> tenantMap = getOrCreateDeploymentMap(tenant);
        boolean modified = false;

        for (Map.Entry<String, List<String>> entry : tenantMap.entrySet()) {
            List<String> connectors = entry.getValue();
            boolean removed = connectors.removeIf(id -> !validConnectorIdentifiers.contains(id));
            if (removed) {
                modified = true;
                log.info("{} - Removed stale connector identifiers from mapping {}, remaining: {}",
                        tenant, entry.getKey(), connectors);
            }
        }

        if (modified) {
            persistDeploymentMap(tenant);
        }

        return modified;
    }

    // ========== Private Helper Methods ==========

    /**
     * Gets the deployment map for a tenant, creating it if it doesn't exist.
     */
    private Map<String, List<String>> getOrCreateDeploymentMap(String tenant) {
        return deploymentMaps.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    /**
     * Persists the current in-memory deployment map to the Cumulocity inventory.
     * 
     * <p>Updates the {@link MapperServiceRepresentation} object with the current deployment configuration.
     */
    private void persistDeploymentMap(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            MapperServiceRepresentation serviceRep = configurationRegistry.getMapperServiceRepresentation(tenant);
            
            Map<String, List<String>> deploymentMap = getOrCreateDeploymentMap(tenant);
            
            log.info("{} - Persisting deployment map with {} entries", tenant, deploymentMap.size());

            Map<String, Object> fragment = new ConcurrentHashMap<>();
            fragment.put(MapperServiceRepresentation.DEPLOYMENT_MAP_FRAGMENT, deploymentMap);

            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(serviceRep.getId()));
            updateMor.setAttrs(fragment);

            inventoryApi.update(updateMor, false);
        });
    }
}