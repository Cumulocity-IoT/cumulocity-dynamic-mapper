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

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.mutable.MutableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Direction;
import mqtt.mapping.model.InnerNode;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingRepresentation;
import mqtt.mapping.model.MappingServiceRepresentation;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.ValidationError;

@Slf4j
@Component
public class MappingComponent {

    private Map<String, Map<String, MappingStatus>> tenantStatusMapping = new HashMap<>();

    private Map<String, Set<Mapping>> dirtyMappings = new HashMap<>();

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private HashMap<String, MappingServiceRepresentation> mappingServiceRepresentations = new HashMap<>();
    private HashMap<String, Boolean> initialized = new HashMap<>();

    // cache of inbound mappings stored by mapping.id
    @Getter
    private Map<String, Map<String, Mapping>> cacheMappingInbound = new HashMap<>();

    // cache of outbound mappings stored by mapping.id
    private Map<String, Map<String, Mapping>> cacheMappingOutbound = new HashMap<>();


    // cache of outbound mappings stored by mapping.filterOundbound used for
    // resolving
    private Map<String, Map<String, List<Mapping>>> resolverMappingOutbound = new HashMap<>();

    // cache of inbound mappings stored in a tree used for resolving
    @Getter
    private Map<String, InnerNode> resolverMappingInbound = new HashMap<>();

    public void initializeMappingStatus(String tenant, boolean reset) {
        MappingServiceRepresentation mappingServiceRepresentation = mappingServiceRepresentations.get(tenant);
        if (mappingServiceRepresentation.getMappingStatus() != null && !reset) {
            log.info("Tenant {} - Initializing status: {}, {} ", tenant, mappingServiceRepresentation.getMappingStatus(),
                    (mappingServiceRepresentation.getMappingStatus() == null
                            || mappingServiceRepresentation.getMappingStatus().size() == 0 ? 0
                            : mappingServiceRepresentation.getMappingStatus().size()));
            mappingServiceRepresentation.getMappingStatus().forEach(ms -> {
                Map<String, MappingStatus> mappingStatus = new HashedMap();
                mappingStatus.put(ms.ident, ms);
                tenantStatusMapping.put(tenant, mappingStatus);
            });
        } else {
            tenantStatusMapping.replace(tenant, new HashMap<String, MappingStatus>());
        }
        if (!tenantStatusMapping.get(tenant).containsKey(MappingStatus.IDENT_UNSPECIFIED_MAPPING)) {
            tenantStatusMapping.get(tenant).put(MappingStatus.IDENT_UNSPECIFIED_MAPPING, MappingStatus.UNSPECIFIED_MAPPING_STATUS);
        }
        initialized.put(tenant, true);
        resolverMappingInbound.put(tenant,  InnerNode.createRootNode());
    }

    public void initializeMappingComponent(String tenant, MappingServiceRepresentation mappingServiceRepresentation) {
        this.mappingServiceRepresentations.put(tenant, mappingServiceRepresentation);
        initializeMappingStatus(tenant, false);
    }

    public void sendStatusMapping(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            boolean initialized = this.initialized.get(tenant);
            Map<String, MappingStatus> statusMapping = tenantStatusMapping.get(tenant);
            MappingServiceRepresentation mappingServiceRepresentation = mappingServiceRepresentations.get(tenant);
            // avoid sending empty monitoring events
            if (statusMapping.values().size() > 0 && mappingServiceRepresentation != null && initialized) {
                log.debug("Sending monitoring: {}", statusMapping.values().size());
                Map<String, Object> service = new HashMap<String, Object>();
                MappingStatus[] ms = statusMapping.values().toArray(new MappingStatus[0]);
                // add current name of mappings to the status messages
                for (int index = 0; index < ms.length; index++) {
                    ms[index].name = "#";
                    if (cacheMappingInbound.get(tenant).containsKey(ms[index].id)) {
                        ms[index].name = cacheMappingInbound.get(tenant).get(ms[index].id).name;
                    } else if (cacheMappingOutbound.get(tenant).containsKey(ms[index].id)) {
                        ms[index].name = cacheMappingOutbound.get(tenant).get(ms[index].id).name;
                    }
                }
                service.put(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT, ms);
                ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
                updateMor.setAttrs(service);
                this.inventoryApi.update(updateMor);
            } else {
                log.debug("Tenant {} - Ignoring mapping monitoring: {}, initialized: {}", tenant, statusMapping.values().size(),
                        initialized);
            }
        });
    }

    public void sendStatusService(String tenant, ServiceStatus serviceStatus) {
        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, MappingStatus> statusMapping = tenantStatusMapping.get(tenant);
            MappingServiceRepresentation mappingServiceRepresentation = mappingServiceRepresentations.get(tenant);
            if ((statusMapping.values().size() > 0) && mappingServiceRepresentation != null) {
                log.debug("Tenant {} - Sending status configuration: {}", tenant, serviceStatus);
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

    public MappingStatus getMappingStatus(String tenant, Mapping m) {
        Map<String, MappingStatus> statusMapping = tenantStatusMapping.get(tenant);
        MappingStatus ms = statusMapping.get(m.ident);
        if (ms == null) {
            log.info("Tenant {} - Adding: {}", tenant, m.ident);
            ms = new MappingStatus(m.id, m.name, m.ident, m.subscriptionTopic, m.publishTopic, 0, 0, 0, 0);
            statusMapping.put(m.ident, ms);
        }
        return ms;
    }

    public List<MappingStatus> getMappingStatus(String tenant) {
        Map<String, MappingStatus> statusMapping = tenantStatusMapping.get(tenant);
        return new ArrayList<MappingStatus>(statusMapping.values());
    }

    public void saveMappings(String tenant, List<Mapping> mappings) {
        subscriptionsService.runForTenant(tenant, () -> {
            mappings.forEach(m -> {
                MappingRepresentation mr = new MappingRepresentation();
                mr.setC8yMQTTMapping(m);
                ManagedObjectRepresentation mor = toManagedObject(mr);
                mor.setId(GId.asGId(m.id));
                inventoryApi.update(mor);
            });
            log.debug("Tenant {} - Saved mappings!", tenant);
        });
    }

    public Mapping getMapping(String tenant, String id) {
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
            if (mo != null) {
                Mapping mt = toMappingObject(mo).getC8yMQTTMapping();
                log.info("Tenant {} - Found Mapping: {}", tenant, mt.id);
                return mt;
            }
            return null;
        });
        return result;
    }

    public Mapping deleteMapping(String tenant, String id) {
        // test id the mapping is active, we don't delete or modify active mappings
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mo = inventoryApi.get(GId.asGId(id));
            MappingRepresentation m = toMappingObject(mo);
            if (m.getC8yMQTTMapping().isActive()) {
                throw new IllegalArgumentException("Tenant "+tenant+" - Mapping "+id+" is still active, deactivate mapping before deleting!");
            }
            // mapping is deactivated and we can delete it
            inventoryApi.delete(GId.asGId(id));
            deleteMappingStatus(tenant, id);
            return m.getC8yMQTTMapping();
        });
        log.info("Tenant {} - Deleted Mapping: {}", tenant, id);
        return result;
    }

    public List<Mapping> getMappings(String tenant) {
        List<Mapping> result = subscriptionsService.callForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingRepresentation.MQTT_MAPPING_TYPE);
            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
            List<Mapping> res = StreamSupport.stream(moc.get().allPages().spliterator(), true)
                    .map(mo -> toMappingObject(mo).getC8yMQTTMapping())
                    .collect(Collectors.toList());
            log.debug("Tenant {} - Loaded mappings (inbound & outbound): {}", tenant, res.size());
            return res;
        });
        return result;
    }

    public Mapping updateMapping(String tenant, Mapping mapping, boolean allowUpdateWhenActive, boolean ignoreValidation)
            throws Exception {
        // test id the mapping is active, we don't delete or modify active mappings
        MutableObject<Exception> exception = new MutableObject<Exception>(null);
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            // when we do housekeeping tasks we need to update active mapping, e.g. add
            // snooped messages. This is an exception
            if (!allowUpdateWhenActive && mapping.isActive()) {
                throw new IllegalArgumentException("Tenant "+tenant+" - Mapping "+mapping.id+" is still active, deactivate mapping before deleting!");
            }
            // mapping is deactivated and we can delete it
            List<Mapping> mappings = getMappings(tenant);
            List<ValidationError> errors = MappingRepresentation.isMappingValid(mappings, mapping);
            if (errors.size() == 0 || ignoreValidation) {
                MappingRepresentation mr = new MappingRepresentation();
                mapping.lastUpdate = System.currentTimeMillis();
                mr.setType(MappingRepresentation.MQTT_MAPPING_TYPE);
                mr.setC8yMQTTMapping(mapping);
                mr.setId(mapping.id);
                ManagedObjectRepresentation mor = toManagedObject(mr);
                mor.setId(GId.asGId(mapping.id));
                mor.setName(mapping.name);
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

    public Mapping createMapping(String tenant, Mapping mapping) {
        List<Mapping> mappings = getMappings(tenant);
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
            mor.setName(mapping.name);
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

    private void deleteMappingStatus(String tenant, String id) {
        tenantStatusMapping.get(tenant).remove(id);
    }

    public void addToCacheMappingInbound(String tenant, Mapping mapping) {
        try {
            ((InnerNode) resolverMappingInbound.get(tenant)).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not add mapping {}, ignoring mapping", mapping);
        }
    }

    public void deleteFromCacheMappingInbound(String tenant, Mapping mapping) {
        try {
            ((InnerNode) resolverMappingInbound.get(tenant)).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Could not delete mapping {}, ignoring mapping", mapping);
        }
    }

    public void rebuildMappingOutboundCache(String tenant) {
        // only add outbound mappings to the cache
        List<Mapping> updatedMappings = getMappings(tenant).stream()
                .filter(m -> Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        log.info("Loaded mappings outbound: {} to cache", updatedMappings.size());
        cacheMappingOutbound.replace(tenant,updatedMappings.stream()
                        .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        resolverMappingOutbound.replace(tenant, updatedMappings.stream()
                        .collect(Collectors.groupingBy(Mapping::getFilterOutbound)));
    }

    public List<Mapping> resolveMappingOutbound(String tenant, JsonNode message, API api) throws ResolveException {
        // use mappingCacheOutbound and the key filterOutbound to identify the matching
        // mappings.
        // the need to be returend in a list
        List<Mapping> result = new ArrayList<>();
        try {
            for (Mapping m : cacheMappingOutbound.get(tenant).values()) {
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

    public Mapping deleteFromMappingCache(String tenant, Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            Mapping deletedMapping = cacheMappingOutbound.get(tenant).remove(mapping.id);
            List<Mapping> cmo = resolverMappingOutbound.get(tenant).get(mapping.filterOutbound);
            cmo.removeIf(m -> mapping.id.equals(m.id));
            return deletedMapping;
        } else {
            Mapping deletedMapping = cacheMappingInbound.get(tenant).remove(mapping.id);
            deleteFromCacheMappingInbound(tenant, deletedMapping);
            return deletedMapping;
        }
    }

    public InnerNode rebuildMappingTree(List<Mapping> mappings) {
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

    public List<Mapping> rebuildMappingInboundCache(String tenant, List<Mapping> updatedMappings) {
        log.info("Loaded mappings inbound: {} to cache", updatedMappings.size());
        cacheMappingInbound.replace(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        resolverMappingInbound.replace(tenant, rebuildMappingTree(updatedMappings));
        return updatedMappings;
    }

    public List<Mapping> rebuildMappingInboundCache(String tenant) {
        List<Mapping> updatedMappings = getMappings(tenant).stream()
                .filter(m -> !Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        return rebuildMappingInboundCache(tenant, updatedMappings);
    }

    public void setActivationMapping(String tenant, String id, Boolean active) throws Exception {
        // step 1. update activation for mapping
        log.info("Setting active: {} got mapping: {}", id, active);
        Mapping mapping = getMapping(tenant, id);
        mapping.setActive(active);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingInbound.get(tenant).get(id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        boolean ignoreValidation = !active;
        updateMapping(tenant, mapping, true, ignoreValidation);
        // step 4. delete mapping from update cache
        removeDirtyMapping(tenant, mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache(tenant);
        } else {
            deleteFromCacheMappingInbound(tenant, mapping);
            addToCacheMappingInbound(tenant, mapping);
            cacheMappingInbound.get(tenant).put(mapping.id, mapping);
        }
    }

    public void cleanDirtyMappings(String tenant) throws Exception {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");

        for (Mapping mapping : dirtyMappings.get(tenant)) {
            log.info("Found mapping to be saved: {}, {}", mapping.id, mapping.snoopStatus);
            // no reload required
            updateMapping(tenant, mapping, true, false);
        }
        // reset dirtySet
        dirtyMappings.replace(tenant, new HashSet<Mapping>());
    }

    private void removeDirtyMapping(String tenant, Mapping mapping) {
        dirtyMappings.get(tenant).removeIf(m -> m.id.equals(mapping.id));
    }

    public void addDirtyMapping(String tenant, Mapping mapping) {
        dirtyMappings.get(tenant).add(mapping);
    }

    public List<Mapping> resolveMappingInbound(String tenant, String topic) throws ResolveException {
        List<InnerNode> resolvedMappings = getResolverMappingInbound().get(tenant)
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(topic));
        return resolvedMappings.stream().filter(tn -> tn.isMappingNode())
                .map(mn -> mn.getMapping()).collect(Collectors.toList());
    }

}