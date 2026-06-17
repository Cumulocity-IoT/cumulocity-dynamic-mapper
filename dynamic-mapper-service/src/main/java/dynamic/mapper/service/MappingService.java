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
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.*;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.service.cache.FlowStateStore;
import dynamic.mapper.service.cache.MappingCacheManager;
import dynamic.mapper.service.deployment.DeploymentMapService;
import dynamic.mapper.service.resolver.MappingResolverService;
import dynamic.mapper.service.status.MappingStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main orchestrating service for mapping operations.
 * Delegates to specialized services for specific concerns.
 */
@Slf4j
@Validated
@Service
@RequiredArgsConstructor
public class MappingService {

    private final InventoryFacade inventoryApi;
    private final MappingRepository mappingRepository;
    private final MappingCacheManager cacheManager;
    private final MappingStatusService statusService;
    private final MappingResolverService resolverService;
    private final DeploymentMapService deploymentMapService;
    private final DeviceToClientMapService deviceToClientMapService;
    private final ConfigurationRegistry configurationRegistry;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final MappingValidator mappingValidator;
    private final FlowStateStore flowStateStore;
    private final MappingVersionService mappingVersionService;

    // Track dirty mappings that need to be persisted
    private final Map<String, Set<Mapping>> dirtyMappings = new ConcurrentHashMap<>();

    // Per-line locks guaranteeing the version-activation swap (read -> copy -> persist ->
    // cache) runs atomically, so concurrent activations on the same mapping line cannot
    // interleave (NFR-2 / C-1). Keyed by "tenant:mappingId".
    private final Map<String, java.util.concurrent.locks.ReentrantLock> activationLocks = new ConcurrentHashMap<>();

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
        flowStateStore.clearTenantState(tenant);
        mappingRepository.clearReportedWarnings(tenant);
        dirtyMappings.remove(tenant);
        activationLocks.keySet().removeIf(key -> key.startsWith(tenant + ":"));

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

        // Create with proper tenant context
        return subscriptionsService.callForTenant(tenant, () -> {
            mappingRepository.prepareForCreate(tenant, mapping);

            MappingRepresentation mr = new MappingRepresentation();
            mr.setType(MappingRepresentation.MAPPING_TYPE);
            mr.setC8yMQTTMapping(mapping);

            ManagedObjectRepresentation mor = mappingRepository.toManagedObject(mr);
            mor = inventoryApi.create(mor, false);

            mapping.setId(mor.getId().getValue());
            mr.getC8yMQTTMapping().setId(mapping.getId());

            mor = mappingRepository.toManagedObject(mr);
            mor.setId(GId.asGId(mapping.getId()));
            mor.setName(mapping.getName());
            inventoryApi.update(mor, false);

            configurationRegistry.getC8yAgent().createOperationEvent(
                    String.format("Mapping created: %s [%s]", mapping.getName(), mapping.getId()),
                    LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                    DateTime.now(),
                    tenant,
                    null);

            log.info("{} - Mapping created: {} [{}]", tenant, mapping.getName(), mapping.getId());
            return mapping;
        });
    }

    /**
     * Updates an existing mapping
     */
    public Mapping updateMapping(String tenant, Mapping mapping,
            boolean allowUpdateWhenActive, boolean ignoreValidation) {
        return updateMapping(tenant, mapping, allowUpdateWhenActive, ignoreValidation, true);
    }

    /**
     * Updates an existing mapping with optional event logging
     */
    private Mapping updateMapping(String tenant, Mapping mapping,
            boolean allowUpdateWhenActive, boolean ignoreValidation, boolean logEvent) {
        // Validate unless ignoring
        if (!ignoreValidation) {
            List<ValidationError> errors = mappingValidator.validate(tenant, mapping, mapping.getId());
            if (!errors.isEmpty()) {
                throw new MappingValidationException(errors);
            }
        }

        // Update with proper tenant scope
        return subscriptionsService.callForTenant(tenant, () -> {
            mappingRepository.prepareForUpdate(tenant, mapping, allowUpdateWhenActive, ignoreValidation);

            MappingRepresentation mr = new MappingRepresentation();
            mr.setType(MappingRepresentation.MAPPING_TYPE);
            mr.setC8yMQTTMapping(mapping);
            mr.setId(mapping.getId());

            ManagedObjectRepresentation mor = mappingRepository.toManagedObject(mr);
            mor.setId(GId.asGId(mapping.getId()));
            mor.setName(mapping.getName());
            inventoryApi.update(mor, false);

            if (logEvent) {
                configurationRegistry.getC8yAgent().createOperationEvent(
                        String.format("Mapping updated: %s [%s]", mapping.getName(), mapping.getId()),
                        LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                        DateTime.now(),
                        tenant,
                        null);
            }

            log.info("{} - Mapping updated: {} [{}]", tenant, mapping.getName(), mapping.getId());
            return mapping;
        });
    }

    /**
     * Retrieves a mapping by ID
     */
    public Mapping getMapping(String tenant, String id) {
        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id), false);
                return mappingRepository.findById(tenant, id, mo).orElse(null);
            } catch (SDKException e) {
                log.warn("{} - Failed to find managed object for mapping: {}", tenant, id, e);
                return null;
            }
        });
    }

    /**
     * Retrieves all mappings, optionally filtered by direction
     */
    public List<Mapping> getMappings(String tenant, Direction direction) {
        return subscriptionsService.callForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingRepresentation.MAPPING_TYPE);

            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter, false);
            return mappingRepository.findAll(tenant, direction, moc);
        });
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
            try {
                ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id), false);
                Optional<Mapping> found = mappingRepository.findById(tenant, id, mo);
                if (found.isEmpty()) {
                    return null;
                }

                Mapping m = found.get();
                mappingRepository.prepareForDelete(tenant, id, m);
                // Remove the line's version records too so they do not leak (FR-18).
                mappingVersionService.deleteAllVersions(tenant, m.getIdentifier());
                inventoryApi.delete(GId.asGId(id), false);

                log.info("{} - Mapping deleted from Inventory: {}", tenant, id);
                return m;
            } catch (SDKException e) {
                log.warn("{} - Failed to find managed object for mapping: {}", tenant, id, e);
                return null;
            }
        });

        if (mapping != null) {
            cacheManager.removeMapping(tenant, mapping);
            statusService.removeStatus(tenant, mapping.getIdentifier());
            deploymentMapService.removeMappingDeployment(tenant, mapping.getIdentifier());
            flowStateStore.clearMappingState(tenant, mapping.getIdentifier());

            configurationRegistry.getC8yAgent().createOperationEvent(
                    String.format("Mapping deleted: %s [%s]", mapping.getName(), id),
                    LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                    DateTime.now(),
                    tenant,
                    null);

            log.info("{} - Mapping deleted from Service: {}", tenant, id);
        }

        return mapping;
    }

    /**
     * Batch saves multiple mappings
     */
    public void saveMappings(String tenant, List<Mapping> mappings) {
        subscriptionsService.runForTenant(tenant, () -> {
            mappingRepository.prepareBatchForUpdate(tenant, mappings);
            mappings.forEach(mapping -> {
                MappingRepresentation mr = new MappingRepresentation();
                mr.setC8yMQTTMapping(mapping);
                ManagedObjectRepresentation mor = mappingRepository.toManagedObject(mr);
                mor.setId(GId.asGId(mapping.getId()));
                inventoryApi.update(mor, false);
            });
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
     * Activates or deactivates a mapping (current version).
     */
    public Mapping setActivationMapping(String tenant, String mappingId, Boolean active) throws Exception {
        return setActivationMapping(tenant, mappingId, active, null);
    }

    /**
     * Activates or deactivates a mapping, optionally switching the active version.
     *
     * <p>When {@code active} is true and {@code versionNumber} names a different
     * version than the one currently running, the corresponding stored snapshot is
     * copied into the runnable mapping (rollback or roll-forward). Because a mapping
     * line is a single managed object, exactly one version is ever active (C-1).
     * The whole read -> copy -> persist -> cache sequence runs under a per-line lock
     * so concurrent activations cannot interleave (NFR-2); validation happens before
     * any persistence, so a failed activation leaves the running version unchanged
     * (FR-9).
     *
     * @param versionNumber version to activate, or {@code null} to keep the current one
     */
    public Mapping setActivationMapping(String tenant, String mappingId, Boolean active, Integer versionNumber)
            throws Exception {
        java.util.concurrent.locks.ReentrantLock lock = activationLockFor(tenant, mappingId);
        lock.lock();
        try {
            Mapping mapping = getMapping(tenant, mappingId);
            if (mapping == null) {
                throw new IllegalArgumentException("Mapping not found: " + mappingId);
            }

            // Capture the currently active configuration as a version if the line has
            // none yet (legacy backfill, NFR-1a). Does not change what is running.
            mappingVersionService.ensureBackfilled(tenant, mapping);

            try {
                Mapping toPersist = mapping;
                boolean versionSwitched = false;
                if (Boolean.TRUE.equals(active) && versionNumber != null
                        && versionNumber != mapping.getVersionNumber()) {
                    toPersist = applyVersion(tenant, mapping, versionNumber);
                    versionSwitched = true;
                }
                toPersist.setActive(active);

                // Validate when activating (unless we just materialized an already-published
                // snapshot); never validate when deactivating.
                boolean ignoreValidation = versionSwitched || !active;
                updateMapping(tenant, toPersist, true, ignoreValidation);
                updateCacheAfterChange(tenant, toPersist);

                if (active) {
                    statusService.resetFailureCount(tenant, toPersist.getIdentifier());
                }

                String versionInfo = versionSwitched ? String.format(" (version %d)", toPersist.getVersionNumber())
                        : "";
                configurationRegistry.getC8yAgent().createOperationEvent(
                        String.format("Mapping %s [%s] %s%s", toPersist.getName(), mappingId,
                                active ? "activated" : "deactivated", versionInfo),
                        LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                        DateTime.now(),
                        tenant,
                        null);

                log.info("{} - Mapping {} set to active={}{}", tenant, mappingId, active, versionInfo);
                return toPersist;
            } catch (Exception e) {
                configurationRegistry.getC8yAgent().createOperationEvent(
                        String.format("Failed to %s mapping %s [%s]: %s",
                                active ? "activate" : "deactivate", mapping.getName(), mappingId, e.getMessage()),
                        LoggingEventType.MAPPING_ACTIVATION_ERROR_EVENT_TYPE,
                        DateTime.now(),
                        tenant,
                        null);
                log.error("{} - Failed to set activation for mapping {}", tenant, mappingId, e);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Builds the runnable mapping for a target version by copying the stored
     * snapshot, preserving the line's identity ({@code id}/{@code identifier}) and
     * its line-level draft flag. The returned mapping is a fresh deep copy, so the
     * stored version record is never aliased.
     */
    private Mapping applyVersion(String tenant, Mapping runnable, int versionNumber) {
        dynamic.mapper.model.MappingVersion version = mappingVersionService.getVersion(tenant,
                runnable.getIdentifier(), versionNumber);
        if (version == null || version.getSnapshot() == null) {
            throw new IllegalArgumentException(String.format(
                    "Tenant %s - No version %d found for mapping %s [%s]", tenant, versionNumber,
                    runnable.getIdentifier(), runnable.getId()));
        }
        Mapping snapshot = copyOf(version.getSnapshot());
        snapshot.setId(runnable.getId());
        snapshot.setIdentifier(runnable.getIdentifier());
        snapshot.setVersionNumber(version.getVersionNumber());
        snapshot.setVersionNote(version.getNote());
        snapshot.setDraftDirty(runnable.isDraftDirty());
        return snapshot;
    }

    private Mapping copyOf(Mapping mapping) {
        return configurationRegistry.getObjectMapper().convertValue(mapping, Mapping.class);
    }

    private java.util.concurrent.locks.ReentrantLock activationLockFor(String tenant, String mappingId) {
        return activationLocks.computeIfAbsent(tenant + ":" + mappingId,
                k -> new java.util.concurrent.locks.ReentrantLock());
    }

    /**
     * Sets the debug flag for a mapping
     */
    public void setDebugMapping(String tenant, String mappingId, Boolean debug) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        mapping.setDebug(debug);

        updateMapping(tenant, mapping, true, true);
        updateCacheAfterChange(tenant, mapping);

        configurationRegistry.getC8yAgent().createOperationEvent(
                String.format("Mapping %s [%s] debug mode %s", mapping.getName(), mappingId,
                        debug ? "enabled" : "disabled"),
                LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);

        log.info("{} - Mapping {} debug set to {}", tenant, mappingId, debug);
    }

    /**
     * Updates the filter for a mapping
     */
    public Mapping setFilterMapping(String tenant, String mappingId, String filterMapping) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        mapping.setFilterMapping(filterMapping);

        updateMapping(tenant, mapping, true, false);
        updateCacheAfterChange(tenant, mapping);

        configurationRegistry.getC8yAgent().createOperationEvent(
                String.format("Mapping %s [%s] filter updated", mapping.getName(), mappingId),
                LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);

        log.info("{} - Mapping {} filter updated", tenant, mappingId);
        return mapping;
    }

    /**
     * Updates the code for a mapping
     */
    public Mapping setCodeMapping(String tenant, String mappingId, String code) throws Exception {
        Mapping mapping = getMapping(tenant, mappingId);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + mappingId);
        }

        mapping.setCode(code);

        updateMapping(tenant, mapping, true, false);
        updateCacheAfterChange(tenant, mapping);

        configurationRegistry.getC8yAgent().createOperationEvent(
                String.format("Mapping %s [%s] code updated", mapping.getName(), mappingId),
                LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);

        log.info("{} - Mapping {} code updated", tenant, mappingId);
        return mapping;
    }

    // ========== Draft Editing ==========

    /**
     * Returns the current draft (working copy) for a mapping line, or {@code null}
     * if none. Identified by the runnable mapping's managed-object id.
     */
    public Mapping getDraftMapping(String tenant, String id) {
        Mapping runnable = getMapping(tenant, id);
        if (runnable == null) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        dynamic.mapper.model.MappingVersion draft = mappingVersionService.getDraft(tenant, runnable.getIdentifier());
        return draft != null ? draft.getSnapshot() : null;
    }

    /**
     * Saves edits into the mapping line's draft without touching the running/active
     * configuration (FR-1 / D-8). The runnable mapping is resolved by its
     * managed-object id; the draft is keyed by the line's functional identifier.
     */
    public Mapping saveDraftMapping(String tenant, String id, Mapping edits) {
        java.util.concurrent.locks.ReentrantLock lock = activationLockFor(tenant, id);
        lock.lock();
        try {
            Mapping runnable = getMapping(tenant, id);
            if (runnable == null) {
                throw new IllegalArgumentException("Mapping not found: " + id);
            }
            edits.setId(id);
            edits.setIdentifier(runnable.getIdentifier());
            dynamic.mapper.model.MappingVersion draft = mappingVersionService.saveDraft(tenant,
                    runnable.getIdentifier(), edits);

            // Mark the line as having unpublished changes so the grid can flag it. Persisting
            // under the per-line lock keeps this consistent with the version-activation swap.
            if (!runnable.isDraftDirty()) {
                runnable.setDraftDirty(true);
                updateMapping(tenant, runnable, true, true);
                updateCacheAfterChange(tenant, runnable);
            }
            log.info("{} - Saved draft for mapping {} [{}]", tenant, runnable.getIdentifier(), id);
            return draft.getSnapshot();
        } finally {
            lock.unlock();
        }
    }

    // ========== Version Management ==========

    /**
     * Publishes the mapping line's current draft as a new immutable version. The
     * currently active configuration is first captured as a version if the line has
     * none yet (NFR-1a), then the draft snapshot becomes the next version and the
     * draft is cleared. Does not activate the new version.
     */
    public dynamic.mapper.model.MappingVersion publishDraft(String tenant, String id, String note) {
        java.util.concurrent.locks.ReentrantLock lock = activationLockFor(tenant, id);
        lock.lock();
        try {
            Mapping runnable = getMapping(tenant, id);
            if (runnable == null) {
                throw new IllegalArgumentException("Mapping not found: " + id);
            }
            String identifier = runnable.getIdentifier();

            // Preserve the currently active config in history before publishing a new version.
            mappingVersionService.ensureBackfilled(tenant, runnable);

            dynamic.mapper.model.MappingVersion draft = mappingVersionService.getDraft(tenant, identifier);
            if (draft == null || draft.getSnapshot() == null) {
                throw new IllegalStateException(
                        String.format("Tenant %s - No draft to publish for mapping %s [%s]", tenant, identifier, id));
            }

            String effectiveNote = note != null ? note : draft.getSnapshot().getVersionNote();
            dynamic.mapper.model.MappingVersion version = mappingVersionService.publish(tenant, draft.getSnapshot(),
                    effectiveNote, runnable.getVersionNumber());

            // The draft's content now lives in an immutable version; clear the working copy
            // and the line's draft flag.
            mappingVersionService.deleteDraft(tenant, identifier);
            if (runnable.isDraftDirty()) {
                runnable.setDraftDirty(false);
                updateMapping(tenant, runnable, true, true);
                updateCacheAfterChange(tenant, runnable);
            }

            log.info("{} - Published draft of mapping {} [{}] as version {}", tenant, identifier, id,
                    version.getVersionNumber());
            return version;
        } finally {
            lock.unlock();
        }
    }

    /** Lists all published versions of a mapping line, identified by its managed-object id. */
    public List<dynamic.mapper.model.MappingVersion> listVersions(String tenant, String id) {
        Mapping runnable = getMapping(tenant, id);
        if (runnable == null) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        // Lazily create the v1 record for imported / pre-versioning mappings that have
        // never been published or activated through the versioning flow.
        mappingVersionService.ensureBackfilled(tenant, runnable);
        return mappingVersionService.listVersions(tenant, runnable.getIdentifier());
    }

    /** Returns a single published version of a mapping line, or {@code null} if not found. */
    public dynamic.mapper.model.MappingVersion getVersion(String tenant, String id, int versionNumber) {
        Mapping runnable = getMapping(tenant, id);
        if (runnable == null) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        return mappingVersionService.getVersion(tenant, runnable.getIdentifier(), versionNumber);
    }

    /** Updates the change note of a published version (note is the only mutable field). */
    public dynamic.mapper.model.MappingVersion updateVersionNote(String tenant, String id, int versionNumber,
            String note) {
        Mapping runnable = getMapping(tenant, id);
        if (runnable == null) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        return mappingVersionService.updateNote(tenant, runnable.getIdentifier(), versionNumber, note);
    }

    /** Deletes an inactive published version; the active version cannot be deleted (FR-17). */
    public void deleteVersion(String tenant, String id, int versionNumber) {
        Mapping runnable = getMapping(tenant, id);
        if (runnable == null) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        mappingVersionService.deleteVersion(tenant, runnable.getIdentifier(), versionNumber,
                runnable.getVersionNumber());
    }

    public void deleteDraftMapping(String tenant, String id) {
        Mapping runnable = getMapping(tenant, id);
        if (runnable == null) {
            throw new IllegalArgumentException("Mapping not found: " + id);
        }
        mappingVersionService.deleteDraft(tenant, runnable.getIdentifier());
        if (runnable.isDraftDirty()) {
            runnable.setDraftDirty(false);
            updateMapping(tenant, runnable, true, true);
            updateCacheAfterChange(tenant, runnable);
        }
        log.info("{} - Discarded draft of mapping {}", tenant, id);
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
     * Removes stale connector identifiers (those no longer configured) from all deployment entries.
     */
    public boolean cleanupStaleDeploymentConnectors(String tenant, Set<String> validConnectorIdentifiers) {
        return deploymentMapService.cleanupStaleConnectors(tenant, validConnectorIdentifiers);
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
            updateMapping(tenant, mapping, true, false, false); // Don't log individual updates in batch
        }

        dirty.clear();

        configurationRegistry.getC8yAgent().createOperationEvent(
                "Mappings updated in backend, dirty mappings cleaned!",
                LoggingEventType.MAPPING_CHANGED_EVENT_TYPE,
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