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

import org.apache.commons.lang3.mutable.MutableObject;
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
import mqtt.mapping.model.MappingNode;
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
    // cache of inbound mappings stored by mapping.id
    private Map<String, Mapping> cacheMappingInbound = new HashMap<String, Mapping>();

    @Getter
    @Setter
    // cache of outbound mappings stored by mapping.id
    private Map<String, Mapping> cacheMappingOutbound = new HashMap<String, Mapping>();

    @Getter
    @Setter
    // cache of outbound mappings stored by mapping.filterOundbound used for
    // resolving
    private Map<String, List<Mapping>> resolverMappingOutbound = new HashMap<String, List<Mapping>>();

    @Getter
    @Setter
    // cache of inbound mappings stored in a tree used for resolving
    private TreeNode resolverMappingInbound = InnerNode.createRootNode();

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
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
            if (mo != null) {
                Mapping mt = toMappingObject(mo).getC8yMQTTMapping();
                log.info("Found Mapping: {}", mt.id);
                return mt;
            }
            return null;
        });
        return result;
    }

    public Mapping deleteMapping(String id) {
        // test id the mapping is active, we don't delete or modify active mappings
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
            MappingRepresentation m = toMappingObject(mo);
            if (m.getC8yMQTTMapping().isActive()) {
                throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
            }
            // mapping is deactivated and we can delete it
            inventoryApi.delete(GId.asGId(id));
            deleteMappingStatus(id);
            return m.getC8yMQTTMapping();
        });
        log.info("Deleted Mapping: {}", id);
        return result;
    }

    public List<Mapping> getMappings() {
        List<Mapping> result = subscriptionsService.callForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
            List<Mapping> res = StreamSupport.stream(moc.get().allPages().spliterator(), true)
                    .map(mo -> toMappingObject(mo).getC8yMQTTMapping())
                    .collect(Collectors.toList());
            log.debug("Loaded mappings (inbound & outbound): {}", res.size());
            return res;
        });
        return result;
    }

    public Mapping updateMapping(Mapping mapping, boolean allowUpdateWhenActive) throws Exception {
        // test id the mapping is active, we don't delete or modify active mappings
        MutableObject<Exception> exception = new MutableObject<Exception>(null);
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            // when we do housekeeping tasks we need to update active mapping, e.g. add
            // snooped messages. This is an exception
            if (!allowUpdateWhenActive && mapping.isActive()) {
                throw new IllegalArgumentException("Mapping is still active, deactivate mapping before deleting!");
            }
            // mapping is deactivated and we can delete it
            List<Mapping> mappings = getMappings();
            List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
            if (errors.size() == 0) {
                MappingRepresentation mr = new MappingRepresentation();
                mapping.lastUpdate = System.currentTimeMillis();
                mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
                mr.setC8yMQTTMapping(mapping);
                mr.setId(mapping.id);
                ManagedObjectRepresentation mor = toManagedObject(mr);
                mor.setId(GId.asGId(mapping.id));
                inventoryApi.update(mor);
                return mapping;
            } else {
                String errorList = errors.stream().map(e -> e.toString()).reduce("",
                        (res, error) -> res + "[ " + error + " ]");
                exception.setValue(new RuntimeException("Validation errors:" + errorList));
            }
            return null;
        });

        if (exception.getValue() != null) {
            throw exception.getValue();
        }
        return result;
    }

    public Mapping createMapping(Mapping mapping) {
        List<Mapping> mappings = getMappings();
        List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
        if (errors.size() != 0) {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException("Validation errors:" + errorList);
        }
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            MappingRepresentation mr = new MappingRepresentation();
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
            return mapping;
        });
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

    public void addToCacheMappingInbound(Mapping mapping) {
        try {
            ((InnerNode) getResolverMappingInbound()).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not add mapping {}, ignoring mapping", mapping);
        }
    }

    public void deleteFromCacheMappingInbound(Mapping mapping) {
        try {
            ((InnerNode) getResolverMappingInbound()).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not delete mapping {}, ignoring mapping", mapping);
        }
    }

    public void rebuildMappingOutboundCache() {
        // only add outbound mappings to the cache
        List<Mapping> updatedMappings = getMappings().stream()
                .filter(m -> Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        log.info("Loaded mappings outbound: {} to cache", updatedMappings.size());
        setCacheMappingOutbound(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // setMappingCacheOutbound(updatedMappings.stream()
        // .collect(Collectors.toMap(Mapping::getFilterOutbound, Function.identity())));
        setResolverMappingOutbound(updatedMappings.stream()
                .collect(Collectors.groupingBy(Mapping::getFilterOutbound)));
    }

    public List<Mapping> resolveMappingOutbound(JsonNode message, API api) throws ResolveException {
        // use mappingCacheOutbound and the key filterOutbound to identify the matching
        // mappings.
        // the need to be returend in a list
        List<Mapping> result = new ArrayList<>();
        try {
            for (Mapping m : getCacheMappingOutbound().values()) {
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
            Mapping deletedMapping = getCacheMappingOutbound().remove(mapping.id);
            List<Mapping> cmo = getResolverMappingOutbound().get(mapping.filterOutbound);
            cmo.removeIf(m -> mapping.id.equals(m.id));
            return deletedMapping;
        } else {
            Mapping deletedMapping = getCacheMappingInbound().remove(mapping.id);
            deleteFromCacheMappingInbound(deletedMapping);
            return deletedMapping;
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

    public List<Mapping> rebuildMappingInboundCache(List<Mapping> updatedMappings) {
        log.info("Loaded mappings inbound: {} to cache", updatedMappings.size());
        setCacheMappingInbound(updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        setResolverMappingInbound(rebuildMappingTree(updatedMappings));
        return updatedMappings;
    }

    public List<Mapping> rebuildMappingInboundCache() {
        List<Mapping> updatedMappings = getMappings().stream()
                .filter(m -> !Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        return rebuildMappingInboundCache(updatedMappings);
    }

    public void setActivationMapping(String id, Boolean active) throws Exception {
        // step 1. update activation for mapping
        log.info("Setting active: {} got mapping: {}", id, active);
        Mapping mapping = getMapping(id);
        mapping.setActive(active);
        // step 2. retrieve collected snoopedTemplates
        mapping.setSnoopedTemplates(getCacheMappingInbound().get(id).getSnoopedTemplates());
        // step 3. update mapping in inventory
        updateMapping(mapping, true);
        // step 4. delete mapping from update cache
        removeDirtyMapping(mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache();
        } else {
            deleteFromCacheMappingInbound(mapping);
            addToCacheMappingInbound(mapping);
            getCacheMappingInbound().put(mapping.id, mapping);
        }
    }

    public void cleanDirtyMappings() throws Exception {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        for (Mapping mapping : dirtyMappings) {
            log.info("Found mapping to be saved: {}, {}", mapping.id, mapping.snoopStatus);
            // no reload required
            updateMapping(mapping, true);
        }
        // reset dirtySet
        dirtyMappings = new HashSet<Mapping>();
    }

    private void removeDirtyMapping(Mapping mapping) {
        dirtyMappings.removeIf(m -> m.id.equals(mapping.id));
    }

    public void addDirtyMapping(Mapping mapping) {
        dirtyMappings.add(mapping);
    }

    public List<Mapping> resolveMappingInbound(String topic) throws ResolveException {
        List<TreeNode> resolvedMappings = getResolverMappingInbound()
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic));
        return resolvedMappings.stream().filter(tn -> tn instanceof MappingNode)
                .map(mn -> ((MappingNode) mn).getMapping()).collect(Collectors.toList());
    }

}