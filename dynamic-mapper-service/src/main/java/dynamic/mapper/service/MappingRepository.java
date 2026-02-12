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

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingRepresentation;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Repository responsible for CRUD operations on mappings in the inventory
 */
@Slf4j
@Repository
public class MappingRepository {

    private final ConfigurationRegistry configurationRegistry;
    private final MappingService mappingService;

    public MappingRepository(ConfigurationRegistry configurationRegistry,
                            @Lazy MappingService mappingService) {
        this.configurationRegistry = configurationRegistry;
        this.mappingService = mappingService;
    }

    /**
     * Retrieves a single mapping by ID
     * NOTE: This is a lower-level method that expects inventoryApi to be called from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public Optional<Mapping> findById(String tenant, String id, ManagedObjectRepresentation mo) {
        try {
            if (mo == null) {
                return Optional.empty();
            }

            MappingRepresentation mappingMO = toMappingObject(mo);
            Mapping mapping = mappingMO.getC8yMQTTMapping();
            mapping.setId(mappingMO.getId());

            log.debug("{} - Found mapping: {}", tenant, mapping.getId());
            return Optional.of(mapping);

        } catch (IllegalArgumentException e) {
            log.warn("{} - Failed to convert MO {} to mapping: {}", tenant, id,
                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves all mappings, optionally filtered by direction
     * NOTE: This is a lower-level method that expects inventoryApi to be called from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public List<Mapping> findAll(String tenant, Direction direction, ManagedObjectCollection moc) {
        List<Mapping> mappings = StreamSupport.stream(moc.get().allPages().spliterator(), false)
                .map(mo -> convertToMapping(tenant, mo))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(mapping -> shouldIncludeMapping(mapping, direction))
                .collect(Collectors.toList());

        log.debug("{} - Loaded {} mappings for direction: {}", tenant, mappings.size(), direction);
        return mappings;
    }

    /**
     * Creates a new mapping - only handles conversion, actual persistence is in MappingService
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public Mapping prepareForCreate(String tenant, Mapping mapping) {
        // Apply migrations before creating
        migrateMapping(tenant, mapping);

        MappingRepresentation mr = new MappingRepresentation();
        mapping.setLastUpdate(System.currentTimeMillis());
        mr.setType(MappingRepresentation.MAPPING_TYPE);
        mr.setC8yMQTTMapping(mapping);
        return mapping;
    }

    /**
     * Prepares a mapping for update - only handles conversion logic
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public Mapping prepareForUpdate(String tenant, Mapping mapping,
            boolean allowUpdateWhenActive, boolean ignoreValidation) {
        // Validation happens in service layer
        if (!allowUpdateWhenActive && mapping.getActive()) {
            throw new IllegalStateException(
                    String.format("Tenant %s - Mapping %s is active, deactivate before updating!",
                            tenant, mapping.getId()));
        }

        // Apply migrations before updating
        migrateMapping(tenant, mapping);

        mapping.setLastUpdate(System.currentTimeMillis());
        return mapping;
    }

    /**
     * Deletes a mapping - only validates, actual deletion is in MappingService
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public void prepareForDelete(String tenant, String id, Mapping mapping) {
        if (mapping == null) {
            log.warn("{} - Mapping not found for deletion: {}", tenant, id);
            return;
        }

        if (mapping.getActive()) {
            throw new IllegalStateException(
                    String.format("Tenant %s - Mapping %s is active, deactivate before deleting!", tenant, id));
        }
    }

    /**
     * Batch update multiple mappings - only prepares data, actual persistence is in MappingService
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     */
    public void prepareBatchForUpdate(String tenant, List<Mapping> mappings) {
        mappings.forEach(mapping -> mapping.setLastUpdate(System.currentTimeMillis()));
        log.debug("{} - Prepared {} mappings for batch update", tenant, mappings.size());
    }

    // Helper methods

    /**
     * Applies automatic migrations to a mapping
     * This ensures legacy mappings are migrated to current standards
     */
    private void migrateMapping(String tenant, Mapping mapping) {
        // Migrate legacy JSON mappings without transformationType to JSONATA
        if (MappingType.JSON.equals(mapping.getMappingType()) &&
            (mapping.getTransformationType() == null || TransformationType.DEFAULT.equals(mapping.getTransformationType()))) {

            log.info("{} - Migrating legacy JSON mapping {} to JSONATA transformation",
                    tenant, mapping.getName() != null ? mapping.getName() : mapping.getId());

            mapping.setTransformationType(TransformationType.JSONATA);
        }
    }

    /**
     * Converts a ManagedObjectRepresentation to a Mapping, handling deprecated CODE_BASED migration
     * This is now called within MappingService with proper tenant scope active
     */
    private Optional<Mapping> convertToMapping(String tenant, ManagedObjectRepresentation mo) {
        try {
            MappingRepresentation mappingMO = toMappingObject(mo);
            Mapping mapping = mappingMO.getC8yMQTTMapping();
            mapping.setId(mappingMO.getId());

            // Migrate deprecated CODE_BASED mappings to JSON with SMART_FUNCTION transformation
            if (MappingType.CODE_BASED.equals(mapping.getMappingType())) {
                String moId = mo.getId().getValue();
                log.info("{} - Migrating deprecated CODE_BASED mapping {} to JSON with SMART_FUNCTION transformation",
                        tenant, moId);

                mapping.setMappingType(MappingType.JSON);
                mapping.setTransformationType(TransformationType.SUBSTITUTION_AS_CODE);

                try {
                    // Persist the migrated mapping - now through MappingService with proper scope
                    mappingService.updateMapping(tenant, mapping, true, true);

                    // Notify about the automatic migration
                    String migrationMsg = String.format(
                            "Mapping %s was automatically migrated from deprecated CODE_BASED to JSON with SMART_FUNCTION transformation",
                            moId);
                    mappingService.sendMappingLoadingError(tenant, mo, migrationMsg);
                } catch (Exception updateEx) {
                    log.warn("{} - Failed to persist migrated mapping {}: {}", tenant, moId, updateEx.getMessage());
                }
            }

            // Migrate legacy JSON mappings without transformationType to JSONATA
            if (MappingType.JSON.equals(mapping.getMappingType()) &&
                (mapping.getTransformationType() == null || TransformationType.DEFAULT.equals(mapping.getTransformationType()))) {
                String moId = mo.getId().getValue();
                log.info("{} - Migrating legacy JSON mapping {} to JSONATA transformation",
                        tenant, moId);

                mapping.setTransformationType(TransformationType.JSONATA);

                try {
                    // Persist the migrated mapping - now through MappingService with proper scope
                    mappingService.updateMapping(tenant, mapping, true, true);

                    // Notify about the automatic migration using MAPPING_MIGRATION_EVENT_TYPE
                    String migrationMsg = String.format(
                            "Mapping %s [%s] was automatically migrated: transformationType set to JSONATA for legacy JSON mapping",
                            mapping.getName(), moId);
                    configurationRegistry.getC8yAgent().createOperationEvent(
                            migrationMsg,
                            LoggingEventType.MAPPING_MIGRATION_EVENT_TYPE,
                            DateTime.now(),
                            tenant,
                            null);
                } catch (Exception updateEx) {
                    log.warn("{} - Failed to persist migrated mapping {}: {}", tenant, moId, updateEx.getMessage());
                }
            }

            if (Direction.INBOUND.equals(mapping.getDirection()) && mapping.getMappingTopic() == null) {
                log.warn("{} - Mapping {} has no mappingTopic, skipping", tenant, mapping.getId());
                return Optional.empty();
            }

            return Optional.of(mapping);
        } catch (IllegalArgumentException e) {
            String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            String moId = mo.getId().getValue();

            // Combine context information with exception details for comprehensive error notification
            String detailedErrorMsg = String.format("Failed to convert MO %s to mapping in tenant %s: %s",
                    moId, tenant, exceptionMsg);

            log.warn("{} - Failed to convert MO {} to mapping: {}", tenant, moId, exceptionMsg);
            try {
                mappingService.sendMappingLoadingError(tenant, mo, detailedErrorMsg);
            } catch (Exception notifyEx) {
                log.warn("{} - Failed to send mapping loading error for MO {}: {}", tenant, moId, notifyEx.getMessage());
            }
            return Optional.empty();
        }
    }

    private Boolean shouldIncludeMapping(Mapping mapping, Direction direction) {
        return direction == null ||
                Direction.UNSPECIFIED.equals(direction) ||
                mapping.getDirection().equals(direction);
    }

    // Helper methods - these are used by MappingService for conversion

    public ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return configurationRegistry.getObjectMapper().convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return configurationRegistry.getObjectMapper().convertValue(mor, MappingRepresentation.class);
    }

}
