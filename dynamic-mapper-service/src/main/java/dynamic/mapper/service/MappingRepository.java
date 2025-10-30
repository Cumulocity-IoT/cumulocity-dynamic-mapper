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

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingRepresentation;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class MappingRepository {

    private final InventoryFacade inventoryApi;
    private final ConfigurationRegistry configurationRegistry;

    /**
     * Retrieves a single mapping by ID
     */
    public Optional<Mapping> findById(String tenant, String id) {
        try {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id), false);
            if (mo == null) {
                return Optional.empty();
            }

            MappingRepresentation mappingMO = toMappingObject(mo);
            Mapping mapping = mappingMO.getC8yMQTTMapping();
            mapping.setId(mappingMO.getId());
            migrateMapping(mapping);

            log.debug("{} - Found mapping: {}", tenant, mapping.getId());
            return Optional.of(mapping);

        } catch (SDKException e) {
            log.warn("{} - Failed to find managed object for mapping: {}", tenant, id, e);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            log.warn("{} - Failed to convert managed object to mapping: {}", tenant, id, e);
            return Optional.empty();
        }
    }

    /**
     * Retrieves all mappings, optionally filtered by direction
     */
    public List<Mapping> findAll(String tenant, Direction direction) {
        InventoryFilter inventoryFilter = new InventoryFilter();
        inventoryFilter.byType(MappingRepresentation.MAPPING_TYPE);

        ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter, false);

        List<Mapping> mappings = StreamSupport.stream(moc.get().allPages().spliterator(), true)
                .map(mo -> convertToMapping(tenant, mo))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(mapping -> shouldIncludeMapping(mapping, direction))
                .map (mapping -> migrateMapping(mapping))
                .collect(Collectors.toList());

        log.debug("{} - Loaded {} mappings for direction: {}", tenant, mappings.size(), direction);
        return mappings;
    }

    /**
     * Creates a new mapping
     */
    public Mapping create(String tenant, Mapping mapping) {
        // Validation happens in service layer, not here
        MappingRepresentation mr = new MappingRepresentation();
        mapping.setLastUpdate(System.currentTimeMillis());
        mr.setType(MappingRepresentation.MAPPING_TYPE);
        mr.setC8yMQTTMapping(mapping);

        ManagedObjectRepresentation mor = toManagedObject(mr);
        mor = inventoryApi.create(mor, false);

        mapping.setId(mor.getId().getValue());
        mr.getC8yMQTTMapping().setId(mapping.getId());

        mor = toManagedObject(mr);
        mor.setId(GId.asGId(mapping.getId()));
        mor.setName(mapping.getName());
        inventoryApi.update(mor, false);

        log.info("{} - Mapping created: {} [{}]", tenant, mapping.getName(), mapping.getId());
        return mapping;
    }

    public Mapping update(String tenant, Mapping mapping,
            boolean allowUpdateWhenActive, boolean ignoreValidation) {
        // Validation happens in service layer
        if (!allowUpdateWhenActive && mapping.getActive()) {
            throw new IllegalStateException(
                    String.format("Tenant %s - Mapping %s is active, deactivate before updating!",
                            tenant, mapping.getId()));
        }

        MappingRepresentation mr = new MappingRepresentation();
        mapping.setLastUpdate(System.currentTimeMillis());
        mr.setType(MappingRepresentation.MAPPING_TYPE);
        mr.setC8yMQTTMapping(mapping);
        mr.setId(mapping.getId());

        ManagedObjectRepresentation mor = toManagedObject(mr);
        mor.setId(GId.asGId(mapping.getId()));
        mor.setName(mapping.getName());
        inventoryApi.update(mor, false);

        log.info("{} - Mapping updated: {} [{}]", tenant, mapping.getName(), mapping.getId());
        return mapping;
    }

    /**
     * Deletes a mapping
     */
    public void delete(String tenant, String id) {
        Optional<Mapping> mapping = findById(tenant, id);

        if (mapping.isEmpty()) {
            log.warn("{} - Mapping not found for deletion: {}", tenant, id);
            return;
        }

        if (mapping.get().getActive()) {
            throw new IllegalStateException(
                    String.format("Tenant %s - Mapping %s is active, deactivate before deleting!", tenant, id));
        }

        inventoryApi.delete(GId.asGId(id), false);
        log.info("{} - Mapping deleted: {}", tenant, id);
    }

    /**
     * Batch update multiple mappings
     */
    public void updateBatch(String tenant, List<Mapping> mappings) {
        mappings.forEach(mapping -> {
            MappingRepresentation mr = new MappingRepresentation();
            mr.setC8yMQTTMapping(mapping);
            ManagedObjectRepresentation mor = toManagedObject(mr);
            mor.setId(GId.asGId(mapping.getId()));
            inventoryApi.update(mor, false);
        });
        log.debug("{} - Batch updated {} mappings", tenant, mappings.size());
    }

    // Helper methods

    private Optional<Mapping> convertToMapping(String tenant, ManagedObjectRepresentation mo) {
        try {
            MappingRepresentation mappingMO = toMappingObject(mo);
            Mapping mapping = mappingMO.getC8yMQTTMapping();
            mapping.setId(mappingMO.getId());

            if (Direction.INBOUND.equals(mapping.getDirection()) && mapping.getMappingTopic() == null) {
                log.warn("{} - Mapping {} has no mappingTopic, skipping", tenant, mapping.getId());
                return Optional.empty();
            }

            return Optional.of(mapping);
        } catch (IllegalArgumentException e) {
            String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.warn("{} - Failed to convert MO {} to mapping: {}", tenant, mo.getId().getValue(), exceptionMsg);
            return Optional.empty();
        }
    }

    private boolean shouldIncludeMapping(Mapping mapping, Direction direction) {
        return direction == null ||
                Direction.UNSPECIFIED.equals(direction) ||
                mapping.getDirection().equals(direction);
    }

    private ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return configurationRegistry.getObjectMapper().convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return configurationRegistry.getObjectMapper().convertValue(mor, MappingRepresentation.class);
    }

    private Mapping migrateMapping(Mapping mapping) {
        if (mapping.getMappingType() == MappingType.CODE_BASED) {
            mapping.setTransformationType(TransformationType.SUBSTITUTION_AS_CODE);
            mapping.setMappingType(MappingType.JSON);
        }
        return mapping;
    }
}
