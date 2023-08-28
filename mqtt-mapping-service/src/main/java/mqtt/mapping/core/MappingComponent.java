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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
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
import mqtt.mapping.model.Direction;
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

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private MappingServiceRepresentation mappingServiceRepresentation;

    private boolean intialized = false;

    @Getter
    @Setter
    private String tenant = null;

    @Getter
    @Setter
    // cache of active inbound mappings stored by mapping.id
    private Map<String, Mapping> activeMappingInbound = new HashMap<String, Mapping>();

    @Getter
    @Setter
    // cache of active outbound mappings stored by mapping.id
    private Map<String, Mapping> activeMappingOutbound = new HashMap<String, Mapping>();

    @Getter
    @Setter
    // cache of active outbound mappings stored by mapping.filterOundbound
    private Map<String, List<Mapping>> cacheMappingOutbound = new HashMap<String, List<Mapping>>();

    @Getter
    @Setter
    // cache of active inbound mappings stored in a tree
    private TreeNode cacheMappingInbound = InnerNode.createRootNode();

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
        subscriptionsService.runForTenant(tenant, () -> {
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
        });
    }

    public void sendStatusService(ServiceStatus serviceStatus) {
        subscriptionsService.runForTenant(tenant, () -> {
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
        });
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
        subscriptionsService.runForTenant(tenant, () -> {
            mappings.forEach(m -> {
                MappingRepresentation mr = new MappingRepresentation();
                mr.setC8yMQTTMapping(m);
                ManagedObjectRepresentation mor = toManagedObject(mr);
                mor.setId(GId.asGId(m.id));
                inventoryApi.update(mor);
            });
            log.debug("Saved mappings!");
        });

    }

    public Mapping getMapping(String id) {
        Mapping[] result = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
            if (mo != null) {
                result[0] = toMappingObject(mo).getC8yMQTTMapping();
                log.info("Found Mapping: {}", result[0].id);
            }
        });
        return result[0];
    }

    public Mapping deleteMapping(String id) throws Exception {
        Mapping[] result = { null };
        // test id the mapping is active, we don't delete or modify active mappings
        Exception[] exceptions = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
                MappingRepresentation m = toMappingObject(mo);
                if (m.getC8yMQTTMapping().isActive()) {
                    throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
                }
                // mapping is deactivated and we can delete it
                inventoryApi.delete(GId.asGId(id));
                result[0] = m.getC8yMQTTMapping();
                deleteMappingStatus(id);
            } catch (Exception e) {
                exceptions[0] = e;
            }
        });
        if (exceptions[0] != null) {
            throw exceptions[0];
        }
        log.info("Deleted Mapping: {}", id);

        return result[0];
    }

    public List<Mapping> getMappings() {
        List<Mapping> result = new ArrayList<Mapping>();
        subscriptionsService.runForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
            result.addAll(StreamSupport.stream(moc.get().allPages().spliterator(), true)
                    .map(mo -> toMappingObject(mo).getC8yMQTTMapping())
                    .collect(Collectors.toList()));
            log.debug("Loaded mappings (inbound & outbound): {}", result.size());
        });
        return result;
    }

    public Mapping updateMapping(Mapping mapping, boolean allowUpdateWhenActive) throws Exception {
        // test id the mapping is active, we don't delete or modify active mappings
        Mapping[] result = { null };
        Exception[] exceptions = { null };
        subscriptionsService.runForTenant(tenant, () -> {
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
                result[0] = mapping;
            } else {
                String errorList = errors.stream().map(e -> e.toString()).reduce("",
                        (res, error) -> res + "[ " + error + " ]");
                exceptions[0] = new RuntimeException("Validation errors:" + errorList);
            }
        });

        if (exceptions[0] != null) {
            throw exceptions[0];
        }
        return result[0];
    }

    public Mapping createMapping(Mapping mapping) {

        List<Mapping> mappings = getMappings();
        MappingRepresentation mr = new MappingRepresentation();
        Mapping[] result = { null };
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() == 0) {
            subscriptionsService.runForTenant(tenant, () -> {
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
                result[0] = mapping;
            });
        } else {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        return result[0];

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


    public void addToCacheMappingInbound(Mapping mapping) {
        try {
            ((InnerNode) getCacheMappingInbound()).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not add mapping {}, ignoring mapping", mapping);
        }
    }

    public void deleteFromCacheMappingInbound(Mapping mapping) {
        try {
            ((InnerNode) getCacheMappingInbound()).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not delete mapping {}, ignoring mapping", mapping);
        }
    }

    public void rebuildOutboundMappingCache() {
        List<Mapping> updatedMappings = getMappings().stream()
                .filter(m -> Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        // TODO review how to organize the cache efficiently to identify a mapping
        // depending on the payload
        // only add outbound mappings to the cache
        log.info("Loaded mappings outbound: {} to cache", updatedMappings.size());
        setActiveMappingOutbound(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // setMappingCacheOutbound(updatedMappings.stream()
        // .collect(Collectors.toMap(Mapping::getFilterOutbound, Function.identity())));
        setCacheMappingOutbound(updatedMappings.stream()
                .collect(Collectors.groupingBy(Mapping::getFilterOutbound)));
    }

    public List<Mapping> resolveOutboundMappings(JsonNode message, API api) throws ResolveException {
        // use mappingCacheOutbound and the key filterOutbound to identify the matching
        // mappings.
        // the need to be returend in a list
        List<Mapping> result = new ArrayList<>();

        try {
            for (Mapping m : getActiveMappingOutbound().values()) {
                // test if message has property associated for this mapping, JsonPointer must
                // begin with "/"
                String key = "/" + m.getFilterOutbound().replace('.', '/');
                JsonNode testNode = message.at(key);
                if (!testNode.isMissingNode() && m.targetAPI.equals(api)) {
                    log.info("Found mapping key fragment {} in C8Y message {}", key, message.get("id"));
                    result.add(m);
                } else {
                    log.debug("Not matching mapping key fragment {} in C8Y message {}, {}, {}, {}", key,
                            m.getFilterOutbound(), message.get("id"), api, message.toPrettyString());
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ResolveException(e.getMessage());
        }
        return result;
    }

    public Mapping deleteFromMappingCache(Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            // TODO update activeOutboundMapping
            Optional<Mapping> activeOutboundMapping = getActiveMappingOutbound().values().stream()
                    .filter(m -> m.id.equals(mapping.id))
                    .findFirst();
            if (!activeOutboundMapping.isPresent()) {
                return null;
            }

            getActiveMappingOutbound().remove(mapping.id);
            List<Mapping> mappingCacheOutbound = getCacheMappingOutbound().get(mapping.filterOutbound);
            Iterator<Mapping> it = mappingCacheOutbound.iterator();
            while (it.hasNext()) {
                Mapping m = it.next();
                if (m.id.equals(mapping.id)) {
                    it.remove();
                    return m;
                }
            }
            return null;

        } else {
            // find mapping for given id to work with the subscriptionTopic of the mapping
            Optional<Mapping> activeInboundMapping = getActiveMappingInbound().values().stream()
                    .filter(m -> m.id.equals(mapping.id)).findFirst();
            if (!activeInboundMapping.isPresent()) {
                return null;
            }
            Mapping existingMapping = activeInboundMapping.get();
            deleteFromCacheMappingInbound(existingMapping);
            return existingMapping;
        }
    }

    public TreeNode rebuildMappingTree(List<Mapping> mappings) {
        InnerNode in = InnerNode.createRootNode();
        mappings.forEach(m -> {
            try {
                in.addMapping(m);
            } catch (ResolveException e) {
                log.error("Could not add mapping {}, ignoring mapping", m);
            }
        });
        return in;
    }

    public void rebuildInboundMappingCache(List<Mapping> updatedMappings) {
        log.info("Loaded mappings inbound: {} to cache", updatedMappings.size());
        setActiveMappingInbound(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        setCacheMappingInbound(rebuildMappingTree(updatedMappings));
    }

    public void rebuildInboundMappingCache() {
        List<Mapping> updatedMappings = getMappings().stream()
                .filter(m -> !Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        setActiveMappingInbound(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        setCacheMappingInbound(rebuildMappingTree(updatedMappings));
    }



    public void setActivationMapping(Map<String, String> parameter) throws Exception {
        // step 1. update activation for mapping
        String id = parameter.get("id");
        String active = parameter.get("active");
        Boolean activeBoolean = Boolean.parseBoolean(active);
        log.info("Setting active: {} got mapping: {}, {}", id, active, activeBoolean);
        Mapping mapping = getMapping(id);
        mapping.setActive(activeBoolean);
        // step 2. retrieve collected snoopedTemplates
        getActiveMappingInbound().values().forEach(m -> {
            if (m.id == id) {
                mapping.setSnoopedTemplates(m.getSnoopedTemplates());
            }
        });
        // step 3. update mapping in inventory
        updateMapping(mapping, true);
        // step 4. delete mapping from update cache
        removeMappingFormDirtyMappings(mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildOutboundMappingCache();
        } else {
            deleteFromCacheMappingInbound(mapping);
            addToCacheMappingInbound(mapping);
            getActiveMappingInbound().put(mapping.id, mapping);
        }
    }

    public void cleanDirtyMappings() throws Exception {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        for (Mapping mapping : getMappingDirty()) {
            log.info("Found mapping to be saved: {}, {}", mapping.id, mapping.snoopStatus);
            // no reload required
            updateMapping(mapping, true);
        }
        // reset dirtySet
        resetMappingDirty();
    }

}