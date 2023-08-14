/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.API;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingRepresentation;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.model.ValidationError;

@Slf4j
@Component
public class MappingComponent {

    private Map<String, MappingStatus> statusMapping = new HashMap<String, MappingStatus>();

    private Set<Mapping> dirtyMappings = new HashSet<Mapping>();

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired
    private InventoryApi inventoryApi;

    private MappingServiceRepresentation mappingServiceRepresentation;

    private boolean intialized = false;

    @Getter
    @Setter
    private Map<String, Mapping> activeInboundMappings = new HashMap<String, Mapping>();

    @Getter
    @Setter
    private Map<String, Mapping> activeOutboundMappings = new HashMap<String, Mapping>();

    @Getter
    @Setter
    private Map<String, Mapping> mappingCacheOutbound = new HashMap<String, List <Mapping>>();

    @Getter
    @Setter
    private TreeNode mappingTree = InnerNode.createRootNode();

    public void removeStatusMapping(String ident) {
        statusMapping.remove(ident);
    }

    private void initializeMappingStatus() {
        if (mappingServiceRepresentation.getMappingStatus() != null) {
            mappingServiceRepresentation.getMappingStatus().forEach(ms -> {
                statusMapping.put(ms.ident, ms);
            });
        }
        if (!statusMapping.containsKey(MappingStatus.IDENT_UNSPECIFIED_MAPPING)) {
            statusMapping.put(MappingStatus.IDENT_UNSPECIFIED_MAPPING, MappingStatus.UNSPECIFIED_MAPPING_STATUS);
        }
        intialized = true;
    }

    public void initializeMappingComponent(MappingServiceRepresentation mappingServiceRepresentation) {
        this.mappingServiceRepresentation = mappingServiceRepresentation;
        initializeMappingStatus();
    }

    public void sendStatusMapping() {
        // avoid sending empty monitoring events
        if (statusMapping.values().size() > 0 && mappingServiceRepresentation != null && intialized) {
            log.debug("Sending monitoring: {}", statusMapping.values().size());
            Map<String, Object> service = new HashMap<String, Object>();
            MappingStatus[] array = statusMapping.values().toArray(new MappingStatus[0]);
            service.put(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, array);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
            updateMor.setAttrs(service);
            this.inventoryApi.update(updateMor);
        } else {
            log.debug("Ignoring mapping monitoring: {}, intialized: {}", statusMapping.values().size(), intialized);
        }
    }

    public void sendStatusService(ServiceStatus serviceStatus) {
        if ((statusMapping.values().size() > 0) && mappingServiceRepresentation != null) {
            log.debug("Sending status configuration: {}", serviceStatus);
            Map<String, String> entry = Map.of("status", serviceStatus.getStatus().name());
            Map<String, Object> service = new HashMap<String, Object>();
            service.put(MappingServiceRepresentation.SERVICE_STATUS_FRAGMENT, entry);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
            updateMor.setAttrs(service);
            this.inventoryApi.update(updateMor);
        } else {
            log.debug("Ignoring status monitoring: {}", serviceStatus);
        }
    }

    public MappingStatus getMappingStatus(Mapping m) {
        MappingStatus ms = statusMapping.get(m.ident);
        if (ms == null) {
            log.info("Adding: {}", m.ident);
            ms = new MappingStatus(m.id, m.ident, m.subscriptionTopic, m.publishTopic, 0, 0, 0, 0);
            statusMapping.put(m.ident, ms);
        }
        return ms;
    }

    public List<MappingStatus> getMappingStatus() {
        return new ArrayList<MappingStatus>(statusMapping.values());
    }

    public List<MappingStatus> resetMappingStatus() {
        ArrayList<MappingStatus> msl = new ArrayList<MappingStatus>(statusMapping.values());
        msl.forEach(ms -> ms.reset());
        return msl;
    }

    public void setMappingDirty(Mapping mapping) {
        log.debug("Setting dirty: {}", mapping);
        dirtyMappings.add(mapping);
    }

    public void removeMappingFormDirtyMappings(Mapping mapping) {
        for (Mapping m : dirtyMappings) {
            if (m.id.equals(mapping.id)) {
                log.info("Removed mapping form dirty mappings dirty: {} for id: {}", m, mapping.id);
                dirtyMappings.remove(m);
            }
        }
    }

    public Set<Mapping> getMappingDirty() {
        return dirtyMappings;
    }

    public void resetMappingDirty() {
        dirtyMappings = new HashSet<Mapping>();
    }

    public void saveMappings(List<Mapping> mappings) {
        mappings.forEach(m -> {
            MappingRepresentation mr = new MappingRepresentation();
            mr.setC8yMQTTMapping(m);
            ManagedObjectRepresentation mor = toManagedObject(mr);
            mor.setId(GId.asGId(m.id));
            inventoryApi.update(mor);
        });
        log.debug("Saved mappings!");
    }

    public Mapping getMapping(String id) {
        Mapping result = null;
        ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
        if (mo != null) {
            result = toMappingObject(mo).getC8yMQTTMapping();
        }
        log.info("Found Mapping: {}", result.id);
        return result;
    }

    public Mapping deleteMapping(String id) {
        // test id the mapping is active, we don't delete or modify active mappings
        ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
        MappingRepresentation m = toMappingObject(mo);
        if (m.getC8yMQTTMapping().isActive()) {
            throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
        }
        // mapping is deactivated and we can delete it
        inventoryApi.delete(GId.asGId(id));
        deleteMappingStatus(id);
        log.info("Deleted Mapping: {}", id);
        return m.getC8yMQTTMapping();
    }

    public List<Mapping> getMappings() {
        InventoryFilter inventoryFilter = new InventoryFilter();
        inventoryFilter.byType(MappingRepresentation.MQTT_MAPPING_TYPE);
        ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
        List<Mapping> result = StreamSupport.stream(moc.get().allPages().spliterator(), true)
                .map(mo -> toMappingObject(mo).getC8yMQTTMapping())
                .collect(Collectors.toList());
        log.debug("Loaded mappings (inbound & outbound): {}", result.size());
        return result;
    }

    public Mapping updateMapping(Mapping mapping, boolean allowUpdateWhenActive) {
        // test id the mapping is active, we don't delete or modify active mappings
        Mapping result = null;
        // when we do housekeeping tasks we need to update active mapping, e.g. add
        // snooped messages
        // this is an exception
        if (!allowUpdateWhenActive) {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(mapping.id));
            if (mapping.isActive()) {
                throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
            }
        }
        // mapping is deactivated and we can delete it
        List<Mapping> mappings = getMappings();
        MappingRepresentation mr = new MappingRepresentation();
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            mapping.lastUpdate = System.currentTimeMillis();
            mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
            mr.setC8yMQTTMapping(mapping);
            mr.setId(mapping.id);
            ManagedObjectRepresentation mor = toManagedObject(mr);
            mor.setId(GId.asGId(mapping.id));
            inventoryApi.update(mor);
            result = mapping;
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        return result;
    }

    public Mapping createMapping(Mapping mapping) {
        List<Mapping> mappings = getMappings();
        MappingRepresentation mr = new MappingRepresentation();
        Mapping result = null;
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            // 1. step create managed object
            mapping.lastUpdate = System.currentTimeMillis();
            mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
            mr.setC8yMQTTMapping(mapping);
            ManagedObjectRepresentation mor = toManagedObject(mr);
            mor = inventoryApi.create(mor);
            // 2. step update mapping.id with if from previously created managedObject
            mapping.id = mor.getId().getValue();
            mr.getC8yMQTTMapping().setId(mapping.id);
            mor = toManagedObject(mr);
            mor.setId(GId.asGId(mapping.id));

            inventoryApi.update(mor);
            log.info("Created mapping: {}", mor);
            result = mapping;
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        return result;
    }

    private ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return objectMapper.convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return objectMapper.convertValue(mor, MappingRepresentation.class);
    }

    private void deleteMappingStatus(String id) {
        statusMapping.remove(id);
    }

    public void initializeCache() {
        activeInboundMappings = new HashMap<String, Mapping>();
        activeOutboundMappings = new HashMap<String, Mapping>();
    }

    public void addToMappingTree(Mapping mapping) {
        try {
            ((InnerNode) getMappingTree()).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not add mapping {}, ignoring mapping", mapping);
        }
    }

    public void deleteFromMappingTree(Mapping mapping) {
        try {
            ((InnerNode) getMappingTree()).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not delete mapping {}, ignoring mapping", mapping);
        }
    }

    public void rebuildOutboundMappingCache(List<Mapping> updatedMappings) {
        // TODO review how to organize the cache efficiently to identify a mapping
        // depending on the payload
        // only add outbound mappings to the cache
        log.info("Loaded mappings outbound: {} to cache", updatedMappings.size());
        setActiveOutboundMappings(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        setMappingCacheOutbound(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getFilterOutbound, Function.identity())));
    }

    public List<Mapping> resolveOutboundMappings(JsonNode message, API api) throws ResolveException {
        // use mappingCacheOutbound and the key filterOutbound to identify the matching
        // mappings.
        // the need to be returend in a list
        List<Mapping> result = new ArrayList<>();

        try {
            for (Mapping m : getActiveOutboundMappings().values()) {
                // test if message has property associated for this mapping, JsonPointer must begin with "/"
                String key = "/" + m.getFilterOutbound().replace('.','/');
                JsonNode testNode = message.at(key);
                if (!testNode.isMissingNode() && m.targetAPI.equals(api)) {
                    log.info("Found mapping key fragment {} in C8Y message {}", key, message.get("id"));
                    result.add(m);
                } else {
                     log.debug("Not matching mapping key fragment {} in C8Y message {}, {}, {}, {}", key, m.getFilterOutbound(), message.get("id"), api , message.toPrettyString());
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ResolveException(e.getMessage());
        }
        return result;
    }
}