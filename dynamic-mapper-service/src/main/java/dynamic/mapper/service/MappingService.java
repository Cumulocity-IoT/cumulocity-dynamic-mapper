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

package dynamic.mapper.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.*;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.service.cache.MappingCacheManager;
import dynamic.mapper.service.deployment.DeploymentMapService;
import dynamic.mapper.service.resolver.MappingResolverService;
import dynamic.mapper.service.status.MappingStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main orchestrating service for mapping operations.
 * Delegates to specialized services for specific concerns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingService {

    private final MappingRepository mappingRepository;
    private final MappingCacheManager cacheManager;
    private final MappingStatusService statusService;
    private final MappingResolverService resolverService;
    private final DeploymentMapService deploymentMapService;
    private final DeviceToClientMapService deviceToClientMapService;
    private final MappingSnoopService snoopService;
    private final MappingJavaScriptService javaScriptService;
    private final ConfigurationRegistry configurationRegistry;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final MappingValidator mappingValidator;

    // Track dirty mappings that need to be persisted
    private final Map<String, Set<Mapping>> dirtyMappings = new ConcurrentHashMap<>();

    // ========== Resource Lifecycle Management ==========

    /**
     * Creates all necessary resources for a tenant
     */
    public void createResources(String tenant) {
        cacheManager.createTenantCache(tenant);
        statusService.initializeTenantStatus(tenant, false);
        deploymentMapService.initializeTenantDeploymentMap(tenant, false);
        dirtyMappings.put(tenant, ConcurrentHashMap.newKeySet());

        log.info("{} - Resources created", tenant);
    }

    /**
     * Initializes resources by loading mappings from inventory
     */
    public void initializeResources(String tenant) {
        statusService.initializeTenantStatus(tenant, false);
        rebuildMappingCaches(tenant, ConnectorId.INTERNAL);

        log.info("{} - Resources initialized", tenant);
    }

    /**
     * Removes all resources for a tenant
     */
    public void removeResources(String tenant) {
        cacheManager.removeTenantCache(tenant);
        statusService.removeTenantStatus(tenant);
        deploymentMapService.removeTenantDeploymentMap(tenant);
        dirtyMappings.remove(tenant);

        log.info("{} - Resources removed", tenant);
    }

    // ========== Mapping CRUD Operations ==========

    /**
     * Creates a new mapping
     */
    public Mapping createMapping(String tenant, Mapping mapping) {
        // Validate using the validator
        List<ValidationError> errors = mappingValidator.validate(tenant, mapping, null);
        if (!errors.isEmpty()) {
            throw new MappingValidationException(errors);
        }

        // Create (no validation in repository)
        return subscriptionsService.callForTenant(tenant,
                () -> mappingRepository.create(tenant, mapping));
    }

    /**
     * Updates an existing mapping
     */
    public Mapping updateMapping(String tenant, Mapping mapping,
            boolean allowUpdateWhenActive, boolean ignoreValidation) {
        // Validate unless ignoring
        if (!ignoreValidation) {
            List<ValidationError> errors = mappingValidator.validate(tenant, mapping, mapping.getId());
            if (!errors.isEmpty()) {
                throw new MappingValidationException(errors);
            }
        }

        // Update (no validation in repository)
        return subscriptionsService.callForTenant(tenant,
                () -> mappingRepository.update(tenant, mapping, allowUpdateWhenActive, ignoreValidation));
    }

    /**
     * Retrieves a mapping by ID
     */
    public Mapping getMapping(String tenant, String id) {
        return subscriptionsService.callForTenant(tenant,
                () -> mappingRepository.findById(tenant, id).orElse(null));
    }

    /**
     * Retrieves all mappings, optionally filtered by direction
     */
    public List<Mapping> getMappings(String tenant, Direction direction) {
        return subscriptionsService.callForTenant(tenant,
                () -> mappingRepository.findAll(tenant, direction));
    }

    /**
     * Gets all outbound mappings from cache
     */
    public Map<String, Mapping> getCacheOutboundMappings(String tenant) {
        return cacheManager.getAllOutboundMappings(tenant);
    }

    /**
     * Gets all inbound mappings from cache
     */
    public Map<String, Mapping> getCacheInboundMappings(String tenant) {
        return cacheManager.getAllInboundMappings(tenant);
    }

    /**
     * Deletes a mapping
     */
    public Mapping deleteMapping(String tenant, String id) {
        Mapping mapping = subscriptionsService.callForTenant(tenant, () -> {
            Optional<Mapping> found = mappingRepository.findById(tenant, id);
            if (found.isEmpty()) {
                return null;
            }

            mappingRepository.delete(tenant, id);
            return found.get();
        });

        if (mapping != null) {
            cacheManager.removeMapping(tenant, mapping);
            statusService.removeStatus(tenant, mapping.getIdentifier());
            deploymentMapService.removeMappingDeployment(tenant, mapping.getIdentifier());
            javaScriptService.removeCodeFromEngine(tenant, mapping);

            log.info("{} - Mapping deleted: {}", tenant, id);
        }

        return mapping;
    }

    /**
     * Batch saves multiple mappings
     */
    public void saveMappings(String tenant, List<Mapping> mappings) {
        subscriptionsService.runForTenant(tenant, () -> {
            mappingRepository.updateBatch(tenant, mappings);
            log.debug("{} - Batch saved {} mappings", tenant, mappings.size());
        });
    }

    /**
     * Adds an inbound mapping to cache and resolver
     */
    public void addMappingInboundToCache(String tenant, String mappingId, Mapping mapping) {
        cacheManager.addInboundMapping(tenant, mapping);
        log.debug("{} - Added inbound mapping {} to cache", tenant, mappingId);
    }

    /**
     * Removes a mapping from all caches
     * 
     * @param tenant  The tenant
     * @param mapping The mapping to remove
     * @return The removed mapping, or null if not found
     */
    public Mapping removeFromMappingFromCaches(String tenant, Mapping mapping) {
        Optional<Mapping> removed = cacheManager.removeMapping(tenant, mapping);

        if (removed.isPresent()) {
            log.debug("{} - Removed mapping {} from caches", tenant, mapping.getId());
        } else {
            log.warn("{} - Mapping {} not found in caches", tenant, mapping.getId());
        }

        return removed.orElse(null);
    }

    // ========== Mapping State Changes ==========

    /**
     * Activates or deactivates a mapping
     */
    public Mapping setActivationMapping(String tenant, String mappingId, Boolean active) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        // Retrieve current snooped templates
        snoopService.applySnoopedTemplates(tenant, mapping);

        mapping.setActive(active);

        updateMapping(tenant, mapping, true, !active); // ignore validation when deactivating
        updateCacheAfterChange(tenant, mapping);

        if (active) {
            MappingStatus status = statusService.getOrCreateStatus(tenant, mapping);
            statusService.resetFailureCount(tenant, mapping.getIdentifier());
        }

        log.info("{} - Mapping {} set to active={}", tenant, mappingId, active);
        return mapping;
    }

    /**
     * Sets the debug flag for a mapping
     */
    public void setDebugMapping(String tenant, String mappingId, Boolean debug) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        snoopService.applySnoopedTemplates(tenant, mapping);
        mapping.setDebug(debug);

        updateMapping(tenant, mapping, true, true);
        updateCacheAfterChange(tenant, mapping);

        log.info("{} - Mapping {} debug set to {}", tenant, mappingId, debug);
    }

    /**
     * Sets the snoop status for a mapping
     */
    public void setSnoopStatusMapping(String tenant, String mappingId, SnoopStatus snoopStatus) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        snoopService.applySnoopedTemplates(tenant, mapping);
        mapping.setSnoopStatus(snoopStatus);

        updateMapping(tenant, mapping, true, true);
        updateCacheAfterChange(tenant, mapping);

        log.info("{} - Mapping {} snoop status set to {}", tenant, mappingId, snoopStatus);
    }

    /**
     * Updates the filter for a mapping
     */
    public Mapping setFilterMapping(String tenant, String mappingId, String filterMapping) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        snoopService.applySnoopedTemplates(tenant, mapping);
        mapping.setFilterMapping(filterMapping);

        updateMapping(tenant, mapping, true, false);
        updateCacheAfterChange(tenant, mapping);

        log.info("{} - Mapping {} filter updated", tenant, mappingId);
        return mapping;
    }

    /**
     * Updates the source template from snooped templates
     */
    public void updateSourceTemplate(String tenant, String mappingId, Integer templateIndex) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        snoopService.applySnoopedTemplates(tenant, mapping);

        if (templateIndex < 0 || templateIndex >= mapping.getSnoopedTemplates().size()) {
            throw new IllegalArgumentException("Invalid template index: " + templateIndex);
        }

        String newSourceTemplate = mapping.getSnoopedTemplates().get(templateIndex);
        mapping.setSourceTemplate(newSourceTemplate);

        updateMapping(tenant, mapping, true, true);
        updateCacheAfterChange(tenant, mapping);

        log.info("{} - Mapping {} source template updated from snoop index {}", tenant, mappingId, templateIndex);
    }

    /**
     * Resets snooped templates for a mapping
     */
    public void resetSnoop(String tenant, String mappingId) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        mapping.setSnoopedTemplates(new ArrayList<>());

        updateMapping(tenant, mapping, true, true);
        updateCacheAfterChange(tenant, mapping);

        configurationRegistry.getC8yAgent().createOperationEvent(
                "Mapping snoop reset",
                LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);

        log.info("{} - Mapping {} snoop reset", tenant, mappingId);
    }

    // ========== Cache Management ==========

    /**
     * Rebuilds all mapping caches from inventory
     */
    public void rebuildMappingCaches(String tenant, ConnectorId connectorId) {
        List<Mapping> inboundMappings = getMappings(tenant, Direction.INBOUND);
        List<Mapping> outboundMappings = getMappings(tenant, Direction.OUTBOUND);

        cacheManager.rebuildInboundCache(tenant, inboundMappings, connectorId);
        cacheManager.rebuildOutboundCache(tenant, outboundMappings, connectorId);

        log.info("{} - Caches rebuilt by connector: {}", tenant, connectorId.getName());
    }

    /**
     * Updates caches after a mapping change
     */
    private void updateCacheAfterChange(String tenant, Mapping mapping) {
        cacheManager.removeMapping(tenant, mapping);
        cacheManager.addMapping(tenant, mapping);
        removeDirtyMapping(tenant, mapping);
    }

    // ========== Mapping Resolution ==========

    /**
     * Resolves which inbound mappings match a topic
     */
    public List<Mapping> resolveMappingInbound(String tenant, String topic) throws ResolveException {
        return resolverService.resolveInbound(tenant, topic);
    }

    /**
     * Resolves which outbound mappings match a C8Y message
     */
    public List<Mapping> resolveMappingOutbound(String tenant, C8YMessage message,
            ServiceConfiguration serviceConfiguration) throws ResolveException {
        return resolverService.resolveOutbound(tenant, message);
    }

    /**
     * Gets the inbound resolver tree (for debugging/monitoring)
     */
    public MappingTreeNode getResolverMappingInbound(String tenant) {
        return cacheManager.getResolverMappingInbound(tenant);
    }

    // ========== Status Management ==========

    /**
     * Gets or creates status for a mapping
     */
    public MappingStatus getMappingStatus(String tenant, Mapping mapping) {
        return statusService.getOrCreateStatus(tenant, mapping);
    }

    /**
     * Gets all mapping statuses for a tenant
     */
    public List<MappingStatus> getMappingStatus(String tenant) {
        return statusService.getAllStatuses(tenant);
    }

    /**
     * Sends mapping status to inventory
     */
    public void sendMappingStatus(String tenant) {
        statusService.sendStatusToInventory(tenant);
    }

    /**
     * Increments failure count and potentially deactivates mapping
     */
    public void increaseAndHandleFailureCount(String tenant, Mapping mapping, MappingStatus mappingStatus) {
        statusService.incrementFailureCount(tenant, mapping, mappingStatus);

        // If mapping was deactivated, update cache
        if (!mapping.getActive()) {
            cacheManager.removeMapping(tenant, mapping);
        }
    }

    // ========== Deployment Map Operations ==========

    /**
     * Updates deployment for a mapping
     */
    public void updateDeploymentMapEntry(String tenant, String mappingIdentifier, @Valid List<String> deployment) {
        deploymentMapService.updateDeployment(tenant, mappingIdentifier, deployment);
    }

    /**
     * Gets deployment for a mapping
     */
    public List<String> getDeploymentMapEntry(String tenant, String mappingIdentifier) {
        return deploymentMapService.getDeployedConnectors(tenant, mappingIdentifier);
    }

    /**
     * Gets entire deployment map
     */
    public Map<String, List<String>> getDeploymentMap(String tenant) {
        return deploymentMapService.getDeploymentMap(tenant);
    }

    /**
     * Removes a connector from all mappings
     */
    public boolean removeConnectorFromDeploymentMap(String tenant, String connectorIdentifier) {
        return deploymentMapService.removeConnectorFromAllMappings(tenant, connectorIdentifier);
    }

    /**
     * Removes a mapping from deployment map
     */
    public boolean removeMappingFromDeploymentMap(String tenant, String mappingIdentifier) {
        return deploymentMapService.removeMappingDeployment(tenant, mappingIdentifier);
    }

    // ========== Dirty Mapping Management ==========

    /**
     * Adds a mapping to the dirty set (needs to be persisted)
     */
    public void addDirtyMapping(String tenant, Mapping mapping) {
        getDirtySet(tenant).add(mapping);
        log.debug("{} - Mapping {} marked as dirty", tenant, mapping.getId());
    }

    /**
     * Persists all dirty mappings
     */
    public void cleanDirtyMappings(String tenant) {
        Set<Mapping> dirty = getDirtySet(tenant);

        if (dirty.isEmpty()) {
            log.debug("{} - No dirty mappings to clean", tenant);
            return;
        }

        log.info("{} - Cleaning {} dirty mappings", tenant, dirty.size());

        for (Mapping mapping : dirty) {
            updateMapping(tenant, mapping, true, false);
        }

        dirty.clear();

        configurationRegistry.getC8yAgent().createOperationEvent(
                "Mappings updated in backend",
                LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);
    }

    private void removeDirtyMapping(String tenant, Mapping mapping) {
        getDirtySet(tenant).removeIf(m -> m.getId().equals(mapping.getId()));
    }

    private Set<Mapping> getDirtySet(String tenant) {
        return dirtyMappings.computeIfAbsent(tenant, k -> ConcurrentHashMap.newKeySet());
    }

    // ========== Device-to-Client Map ==========

    /**
     * Sends device-to-client map to inventory
     */
    public void sendDeviceToClientMap(String tenant) {
        deviceToClientMapService.sendToInventory(tenant);
    }

    // ========== Utility Methods ==========

    /**
     * Gets all inbound mappings from cache
     */
    public Map<String, Mapping> getCacheMappingInbound(String tenant) {
        return cacheManager.getAllInboundMappings(tenant);
    }

    /**
     * Sends a mapping loading error event
     */
    public void sendMappingLoadingError(String tenant,
            com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation mo, String message) {
        statusService.sendMappingLoadingError(tenant, mo, message);
    }
}