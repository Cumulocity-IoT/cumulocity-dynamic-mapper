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

package dynamic.mapping.core;

import static java.util.Map.entry;
import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapping.model.MappingSubstitution.toPrettyJsonString;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.validation.Valid;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableObject;
import org.graalvm.polyglot.Context;
import org.joda.time.DateTime;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.dashjoin.jsonata.json.Json;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.API;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.LoggingEventType;
import dynamic.mapping.model.MappingTreeNode;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingRepresentation;
import dynamic.mapping.model.MappingServiceRepresentation;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.model.ResolveException;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.model.ValidationError;

@Slf4j
@Component
public class MappingComponent {

    private static final Handler GRAALJS_LOG_HANDLER = new SLF4JBridgeHandler();

    // structure: <tenant, < mappingIdent , status>>
    private Map<String, Map<String, MappingStatus>> tenantMappingStatus = new HashMap<>();

    // // structure: <tenant, < id , status>>
    // private Map<String, Map<String, MappingStatus>> tenantMappingLoadingError =
    // new HashMap<>();

    // structure: <tenant, < mappingId ,list of connectorId>>
    private Map<String, Map<String, List<String>>> tenantDeploymentMap = new HashMap<>();

    private Map<String, Set<Mapping>> dirtyMappings = new HashMap<>();

    @Autowired
    ConfigurationRegistry configurationRegistry;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    // structure: <tenant, initialized>
    private Map<String, Boolean> initializedMappingStatus = new HashMap<>();

    // structure: <tenant, < connectorIdentifier , <connectorProperty ,
    // connectorValue>>>
    @Getter
    private Map<String, Map<String, Map<String, String>>> consolidatedConnectorStatus = new HashMap<>();

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
    private Map<String, MappingTreeNode> resolverMappingInbound = new HashMap<>();

    public void initializeMappingCaches(String tenant) {
        cacheMappingInbound.put(tenant, new HashMap<>());
        cacheMappingOutbound.put(tenant, new HashMap<>());
        resolverMappingOutbound.put(tenant, new HashMap<>());
        resolverMappingInbound.put(tenant, MappingTreeNode.createRootNode(tenant));
    }

    public void initializeMappingStatus(String tenant, boolean reset) {
        MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                .getMappingServiceRepresentations().get(tenant);
        // tenantMappingLoadingError.put(tenant, new HashMap<String, MappingStatus>());
        if (mappingServiceRepresentation.getMappingStatus() != null && !reset) {
            log.debug("Tenant {} - Initializing status: {}, {} ", tenant,
                    mappingServiceRepresentation.getMappingStatus(),
                    (mappingServiceRepresentation.getMappingStatus() == null
                            || mappingServiceRepresentation.getMappingStatus().size() == 0 ? 0
                                    : mappingServiceRepresentation.getMappingStatus().size()));
            Map<String, MappingStatus> mappingStatus = new HashMap<>();
            mappingServiceRepresentation.getMappingStatus().forEach(ms -> {
                mappingStatus.put(ms.identifier, ms);
            });
            tenantMappingStatus.put(tenant, mappingStatus);
        } else {
            tenantMappingStatus.put(tenant, new HashMap<String, MappingStatus>());

        }
        if (!tenantMappingStatus.get(tenant).containsKey(MappingStatus.IDENT_UNSPECIFIED_MAPPING)) {
            tenantMappingStatus.get(tenant).put(MappingStatus.UNSPECIFIED_MAPPING_STATUS.identifier,
                    MappingStatus.UNSPECIFIED_MAPPING_STATUS);
        }
        initializedMappingStatus.put(tenant, true);
        resolverMappingInbound.put(tenant, MappingTreeNode.createRootNode(tenant));
        if (cacheMappingInbound.get(tenant) == null)
            cacheMappingInbound.put(tenant, new HashMap<>());
        if (cacheMappingOutbound.get(tenant) == null)
            cacheMappingOutbound.put(tenant, new HashMap<>());
    }

    public void initializeDeploymentMap(String tenant, boolean reset) {
        MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                .getMappingServiceRepresentations().get(tenant);
        if (mappingServiceRepresentation.getDeploymentMap() != null && !reset) {
            log.debug("Tenant {} - Initializing deploymentMap: {}, {} ", tenant,
                    mappingServiceRepresentation.getDeploymentMap(),
                    (mappingServiceRepresentation.getDeploymentMap() == null
                            || mappingServiceRepresentation.getDeploymentMap().size() == 0 ? 0
                                    : mappingServiceRepresentation.getDeploymentMap().size()));
            tenantDeploymentMap.put(tenant, mappingServiceRepresentation.getDeploymentMap());
        } else {
            tenantDeploymentMap.put(tenant, new HashMap<>());
        }
    }

    public void cleanMappingStatus(String tenant) {
        resolverMappingInbound.remove(tenant);
        tenantMappingStatus.remove(tenant);
    }

    public void sendMappingStatus(String tenant) {
        if (configurationRegistry.getServiceConfigurations().get(tenant).sendMappingStatus) {
            subscriptionsService.runForTenant(tenant, () -> {
                boolean initialized = this.initializedMappingStatus.get(tenant);
                Map<String, MappingStatus> statusMapping = tenantMappingStatus.get(tenant);
                MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                        .getMappingServiceRepresentations().get(tenant);
                // avoid sending empty monitoring events
                if (statusMapping.values().size() > 0 && mappingServiceRepresentation != null && initialized) {
                    log.debug("Tenant {} - Sending monitoring: {}", tenant, statusMapping.values().size());
                    Map<String, Object> service = new HashMap<String, Object>();
                    MappingStatus[] ms = statusMapping.values().toArray(new MappingStatus[0]);
                    // add current name of mappings to the status messages
                    for (int index = 0; index < ms.length; index++) {
                        ms[index].name = "UNSPECIFIED".equals(ms[index].id) ? "Unspecified" : "Mapping deleted";
                        if (cacheMappingInbound.get(tenant).containsKey(ms[index].id)) {
                            ms[index].name = cacheMappingInbound.get(tenant).get(ms[index].id).name;
                        } else if (cacheMappingOutbound.get(tenant).containsKey(ms[index].id)) {
                            ms[index].name = cacheMappingOutbound.get(tenant).get(ms[index].id).name;
                        }
                    }
                    service.put(C8YAgent.MAPPING_FRAGMENT, ms);
                    ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                    updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
                    updateMor.setAttrs(service);
                    this.inventoryApi.update(updateMor);
                } else {
                    log.debug("Tenant {} - Ignoring mapping monitoring: {}, initialized: {}", tenant,
                            statusMapping.values().size(),
                            initialized);
                }
            });
        }
    }

    public MappingStatus getMappingStatus(String tenant, Mapping m) {
        // log.info("Tenant {} - get MappingStatus: {}", tenant, m.identifier);
        Map<String, MappingStatus> mappingStatus = tenantMappingStatus.get(tenant);
        MappingStatus ms = mappingStatus.get(m.identifier);
        if (ms == null) {
            log.info("Tenant {} - Adding: {}", tenant, m.identifier);
            ms = new MappingStatus(m.id, m.name, m.identifier, m.direction, m.mappingTopic, m.publishTopic, 0, 0,
                    0, 0, null);
            mappingStatus.put(m.identifier, ms);
        }
        return ms;
    }

    public void sendMappingLoadingError(String tenant, ManagedObjectRepresentation mo, String message) {

        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date now = new Date();
        String date = dateFormat.format(now);
        Map<String, String> stMap = Map.ofEntries(
                entry("message", message),
                entry("id", mo.getId().getValue()),
                entry("date", date));
        configurationRegistry.getC8yAgent().createEvent(message,
                LoggingEventType.MAPPING_LOADING_ERROR_EVENT_TYPE,
                DateTime.now(), configurationRegistry.getMappingServiceRepresentations().get(tenant), tenant, stMap);
    }

    // ic List<MappingStatus> getMappingLoadingError(String tenant) {
    // // log.info("Tenant {} - get MappingStatus: {
    // ", tenant, m.identifier);
    // Map<String, MappingStatus> mappingLoadError
    // = tenantMappingLoadingError.get(tenant);
    // List<MappingStatus> mappingLoadErrorList =
    // mappingLoadError.values().stream().collect(Collectors.toList());
    // return mappingLoadErrorList;
    // }

    public List<MappingStatus> getMappingStatus(String tenant) {
        Map<String, MappingStatus> statusMapping = tenantMappingStatus.get(tenant);
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
                try {
                    MappingRepresentation mappingMO = toMappingObject(mo);
                    Mapping mapping = mappingMO.getC8yMQTTMapping();
                    mapping.setId(mappingMO.getId());
                    log.debug("Tenant {} - Found Mapping: {}", tenant, mapping.id);
                    return mapping;
                } catch (IllegalArgumentException e) {
                    log.warn("Failed to convert managed object to mapping: {}", mo.getId(), e);
                    return null;
                }
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
            if (m.getC8yMQTTMapping().getActive()) {
                throw new IllegalArgumentException(String.format(
                        "Tenant %s - Mapping %s is still active, deactivate mapping before deleting!", tenant, id));
            }
            // mapping is deactivated and we can delete it
            inventoryApi.delete(GId.asGId(id));
            deleteMappingStatus(tenant, id);
            return m.getC8yMQTTMapping();
        });
        if (result != null) {
            removeMappingFromDeploymentMap(tenant, result.identifier);
            removeCodeFromEngine(result);
        }
        // log.info("Tenant {} - Deleted Mapping: {}", tenant, id);

        return result;
    }

    private void removeCodeFromEngine(Mapping mapping) {
        if (mapping.code != null) {
            String globalIdentifier = "delete globalThis" + Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
            try (Context context = Context.newBuilder("js")
                    .engine(configurationRegistry.getGraalsEngine())
                    .logHandler(GRAALJS_LOG_HANDLER)
                    .allowAllAccess(true)
                    .option("js.strict", "true")
                    .build()) {

                // Before closing the context, clean up the members
                context.getBindings("js").removeMember(globalIdentifier);
            }
        }
    }

    public List<Mapping> getMappings(String tenant, Direction direction) {
        List<Mapping> result = subscriptionsService.callForTenant(tenant, () -> {
            InventoryFilter inventoryFilter = new InventoryFilter();
            inventoryFilter.byType(MappingRepresentation.MAPPING_TYPE);
            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(inventoryFilter);
            List<Mapping> res = StreamSupport.stream(moc.get().allPages().spliterator(), true)
                    .map(mo -> {
                        try {
                            MappingRepresentation mappingMO = toMappingObject(mo);
                            Mapping mapping = mappingMO.getC8yMQTTMapping();
                            mapping.setId(mappingMO.getId());
                            return Optional.of(mapping);
                        } catch (IllegalArgumentException e) {
                            String exceptionMsg = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
                            String msg = String.format("Failed to convert managed object %s to mapping. Error: %s",
                                    mo.getId().getValue(),
                                    exceptionMsg);
                            log.warn(msg);
                            sendMappingLoadingError(tenant, mo, msg);
                            return Optional.<Mapping>empty();
                        }
                    })
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(mapping -> direction == null | Direction.UNSPECIFIED.equals(direction) ? true
                            : mapping.direction.equals(direction))
                    .collect(Collectors.toList());
            log.debug("Tenant {} - Loaded mappings (inbound & outbound): {}", tenant, res.size());
            return res;
        });
        return result;
    }

    public Mapping updateMapping(String tenant, Mapping mapping, boolean allowUpdateWhenActive,
            boolean ignoreValidation)
            throws Exception {
        // test id the mapping is active, we don't delete or modify active mappings
        MutableObject<Exception> exception = new MutableObject<Exception>(null);
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            // when we do housekeeping tasks we need to update active mapping, e.g. add
            // snooped messages. This is an exception
            if (!allowUpdateWhenActive && mapping.getActive()) {
                throw new IllegalArgumentException(
                        String.format("Tenant %s - Mapping %s is still active, deactivate mapping before updating!",
                                tenant, mapping.id));
            }
            // mapping is deactivated and we can delete it
            List<Mapping> mappings = getMappings(tenant, Direction.UNSPECIFIED);
            List<ValidationError> errors = Mapping.isMappingValid(mappings, mapping);
            
            // remove potentially obsolete javascript code from engine cache
            removeCodeFromEngine(mapping);

            if (errors.size() == 0 || ignoreValidation) {
                MappingRepresentation mr = new MappingRepresentation();
                mapping.lastUpdate = System.currentTimeMillis();
                mr.setType(MappingRepresentation.MAPPING_TYPE);
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
        List<Mapping> mappings = getMappings(tenant, Direction.UNSPECIFIED);
        List<ValidationError> errors = Mapping.isMappingValid(mappings, mapping);
        if (errors.size() != 0) {
            String errorList = errors.stream().map(e -> e.toString()).reduce("",
                    (res, error) -> res + "[ " + error + " ]");
            throw new RuntimeException(String.format("Validation errors: %s", errorList));
        }
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            MappingRepresentation mr = new MappingRepresentation();
            // 1. step create managed object
            mapping.lastUpdate = System.currentTimeMillis();
            mr.setType(MappingRepresentation.MAPPING_TYPE);
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
            log.info("Tenant {} - Mapping created: {}", tenant, mor.getName());
            log.debug("Tenant {} - Mapping created: {}", tenant, mor);
            return mapping;
        });
        return result;
    }

    private ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return configurationRegistry.getObjectMapper().convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return configurationRegistry.getObjectMapper().convertValue(mor, MappingRepresentation.class);
    }

    private void deleteMappingStatus(String tenant, String id) {
        tenantMappingStatus.get(tenant).remove(id);
    }

    public void addToCacheMappingInbound(String tenant, Mapping mapping) {
        try {
            resolverMappingInbound.get(tenant).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("Tenant {} - Could not add mapping {}, ignoring mapping", tenant, mapping, e);
        }
    }

    public void deleteFromCacheMappingInbound(String tenant, Mapping mapping) {
        try {
            resolverMappingInbound.get(tenant).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("Tenant {} - Could not delete mapping {}, ignoring mapping", tenant, mapping, e);
        }
    }

    public List<Mapping> rebuildMappingOutboundCache(String tenant) {
        // only add outbound mappings to the cache
        List<Mapping> updatedMappings = getMappings(tenant, Direction.OUTBOUND).stream()
                .filter(m -> Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        log.info("Tenant {} - Loaded mappings outbound: {} to cache", tenant, updatedMappings.size());

        cacheMappingOutbound.replace(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));

        resolverMappingOutbound.replace(tenant, updatedMappings.stream()
                .filter(m -> {
                    if (m.getFilterMapping() == null) {
                        log.warn("Tenant {} - Mapping with ID {} has null filterMapping, ignoring for resolver",
                                tenant, m.getId());
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.groupingBy(Mapping::getFilterMapping)));

        return updatedMappings;
    }

    public List<Mapping> resolveMappingOutbound(String tenant, String message, API api) throws ResolveException {
        // use mappingCacheOutbound and the key filterMapping to identify the matching
        // mappings.
        // the need to be returned in a list
        List<Mapping> result = new ArrayList<>();
        try {
            Map messageAsMap = (Map) Json.parseJson(message);
            for (Mapping m : cacheMappingOutbound.get(tenant).values()) {
                // test if message has property associated for this mapping, JsonPointer must
                // begin with "/"
                var expression = jsonata(m.getFilterMapping());
                Object extractedContent = expression.evaluate(messageAsMap);
                //Only add mappings where the filter is "true".
                if(extractedContent != null  && isNodeTrue(extractedContent) && m.targetAPI.equals(api)) {
                    log.info("Tenant {} - Found valid mapping for filter {} in C8Y message {}", tenant,
                            m.getFilterMapping(),
                            messageAsMap.get("id"));
                    result.add(m);
                } else {
                    log.debug("Tenant {} - Not matching mapping key fragment {} in C8Y message {}, {}, {}, {}", tenant,
                            m.getFilterMapping(),
                            m.getFilterMapping(), messageAsMap.get("id"), api, toPrettyJsonString(message));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ResolveException(e.getMessage());
        }
        return result;
    }

    private boolean isNodeTrue(Object node) {
        // Case 1: Direct boolean value check
        if (node instanceof Boolean) {
            return (Boolean) node;
        }

        // Case 2: String value that can be converted to boolean
        if (node instanceof String) {
            String text = ((String) node).trim().toLowerCase();
            return "true".equals(text) || "1".equals(text) || "yes".equals(text);
            // Add more string variations if needed
        }
        return false;
    }

    public Mapping deleteFromMappingCache(String tenant, Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            Mapping deletedMapping = cacheMappingOutbound.get(tenant).remove(mapping.id);
            log.info("Tenant {} - Preparing to delete {} {}", tenant, resolverMappingOutbound.get(tenant),
                    mapping.filterMapping);

            List<Mapping> cmo = resolverMappingOutbound.get(tenant).get(mapping.filterMapping);
            cmo.removeIf(m -> mapping.id.equals(m.id));
            return deletedMapping;
        } else {
            Mapping deletedMapping = cacheMappingInbound.get(tenant).remove(mapping.id);
            deleteFromCacheMappingInbound(tenant, deletedMapping);
            return deletedMapping;
        }
    }

    public MappingTreeNode rebuildMappingTree(List<Mapping> mappings, String tenant) {
        MappingTreeNode in = MappingTreeNode.createRootNode(tenant);
        mappings.forEach(m -> {
            try {
                in.addMapping(m);
            } catch (ResolveException e) {
                log.error("Tenant {} - Could not add mapping {}, ignoring mapping", tenant, m, e);
            }
        });
        return in;
    }

    private List<Mapping> rebuildMappingInboundCache(String tenant, List<Mapping> updatedMappings) {
        log.info("Tenant {} - Loaded mappings inbound: {} to cache", tenant, updatedMappings.size());
        cacheMappingInbound.replace(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        resolverMappingInbound.replace(tenant, rebuildMappingTree(updatedMappings, tenant));
        return updatedMappings;
    }

    public List<Mapping> rebuildMappingInboundCache(String tenant) {
        List<Mapping> updatedMappings = getMappings(tenant, Direction.INBOUND).stream()
                .filter(m -> !Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        return rebuildMappingInboundCache(tenant, updatedMappings);
    }

    public Mapping setActivationMapping(String tenant, String mappingId, Boolean active) throws Exception {
        // step 1. update activation for mapping
        log.debug("Tenant {} - Setting active: {} got mapping: {}", tenant, active, mappingId);
        Mapping mapping = getMapping(tenant, mappingId);
        mapping.setActive(active);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingInbound.get(tenant).get(mappingId).getSnoopedTemplates());
        } else {
            mapping.setSnoopedTemplates(cacheMappingOutbound.get(tenant).get(mappingId).getSnoopedTemplates());
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
        return mapping;
    }

    public Mapping setFilterMapping(String tenant, String mappingId, String filterMapping) throws Exception {
        // step 1. update activation for mapping
        log.debug("Tenant {} - Setting filterMapping: {} got mapping: {}", tenant, filterMapping, mappingId);
        Mapping mapping = getMapping(tenant, mappingId);
        mapping.setFilterMapping(filterMapping);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingInbound.get(tenant).get(mappingId).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, false);
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
        return mapping;
    }

    public void updateSourceTemplate(String tenant, String id, Integer index) throws Exception {
        // step 1. update debug for mapping
        Mapping mapping = getMapping(tenant, id);
        String newSourceTemplate = mapping.snoopedTemplates.get(index);
        log.info("Tenant {} - Setting sourceTemplate for mapping: {} to: {}", tenant, id, newSourceTemplate);
        mapping.setSourceTemplate(newSourceTemplate);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingInbound.get(tenant).get(id).getSnoopedTemplates());
        } else {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingOutbound.get(tenant).get(id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
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

    public void setDebugMapping(String tenant, String id, Boolean debug) throws Exception {
        // step 1. update debug for mapping
        log.info("Tenant {} - Setting debug: {} got mapping: {}", tenant, id, debug);
        Mapping mapping = getMapping(tenant, id);
        mapping.setDebug(debug);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingInbound.get(tenant).get(id).getSnoopedTemplates());
        } else {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingOutbound.get(tenant).get(id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
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

    public void setSnoopStatusMapping(String tenant, String id, SnoopStatus snoop) throws Exception {
        // step 1. update debug for mapping
        log.info("Tenant {} - Setting snoop: {} got mapping: {}", tenant, id, snoop);
        Mapping mapping = getMapping(tenant, id);
        mapping.setSnoopStatus(snoop);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingInbound.get(tenant).get(id).getSnoopedTemplates());
        } else {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(cacheMappingOutbound.get(tenant).get(id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
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
        log.debug("Tenant {} - Testing for dirty maps", tenant);
        if (dirtyMappings.get(tenant) != null && dirtyMappings.get(tenant).size() > 0) {
            for (Mapping mapping : dirtyMappings.get(tenant)) {
                log.info("Tenant {} - Found mapping to be saved: {}, {}", tenant, mapping.id, mapping.snoopStatus);
                // no reload required
                updateMapping(tenant, mapping, true, false);
            }

            configurationRegistry.getC8yAgent().createEvent("Mappings updated in backend",
                    LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE,
                    DateTime.now(), configurationRegistry.getMappingServiceRepresentations().get(tenant), tenant,
                    null);
        }
        // reset dirtySet
        dirtyMappings.put(tenant, new HashSet<Mapping>());
    }

    private void removeDirtyMapping(String tenant, Mapping mapping) {
        if (dirtyMappings.get(tenant) == null)
            dirtyMappings.put(tenant, new HashSet<>());
        dirtyMappings.get(tenant).removeIf(m -> m.id.equals(mapping.id));
    }

    public void addDirtyMapping(String tenant, Mapping mapping) {
        if (dirtyMappings.get(tenant) == null)
            dirtyMappings.put(tenant, new HashSet<>());
        dirtyMappings.get(tenant).add(mapping);
    }

    public List<Mapping> resolveMappingInbound(String tenant, String topic) throws ResolveException {
        List<Mapping> resolvedMappings = getResolverMappingInbound().get(tenant).resolveMapping(topic);
        return resolvedMappings;
    }

    public void resetSnoop(String tenant, String id) throws Exception {
        // step 1. update debug for mapping
        log.info("Tenant {} - Reset snoop for mapping: {}", tenant, id);
        Mapping mapping = getMapping(tenant, id);

        // nothing to do for outbound mappings
        // if (Direction.INBOUND.equals(mapping.direction)) {
        // step 2. retrieve collected snoopedTemplates
        mapping.setSnoopedTemplates(new ArrayList<>());
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
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
        configurationRegistry.getC8yAgent().createEvent("Mappings updated in backend",
                LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(), configurationRegistry.getMappingServiceRepresentations().get(tenant), tenant,
                null);
        // }
    }

    public void updateDeploymentMapEntry(String tenant, String mappingIdent, @Valid List<String> deployment) {
        if (!tenantDeploymentMap.containsKey(tenant)) {
            tenantDeploymentMap.put(tenant, new HashMap<>());
        }
        Map<String, List<String>> map = tenantDeploymentMap.get(tenant);
        map.put(mappingIdent, deployment);
        saveDeploymentMap(tenant);
    }

    public List<String> getDeploymentMapEntry(String tenant, String mappingIdent) {
        Map<String, List<String>> map = getDeploymentMap(tenant);
        if (!map.containsKey(mappingIdent)) {
            map.put(mappingIdent, new ArrayList<>());
        }
        return map.get(mappingIdent);
    }

    public Map<String, List<String>> getDeploymentMap(String tenant) {
        if (!tenantDeploymentMap.containsKey(tenant)) {
            tenantDeploymentMap.put(tenant, new HashMap<>());
        }
        Map<String, List<String>> map = tenantDeploymentMap.get(tenant);
        return map;
    }

    public boolean removeConnectorFromDeploymentMap(String tenant, String connectorIdentifier) {
        MutableBoolean result = new MutableBoolean(false);
        if (!tenantDeploymentMap.containsKey(tenant)) {
            return result.booleanValue();
        }
        for (List<String> deploymentList : tenantDeploymentMap.get(tenant).values()) {
            result.setValue(deploymentList.remove(connectorIdentifier) || result.booleanValue());
        }
        if (result.getValue())
            saveDeploymentMap(tenant);
        return result.booleanValue();
    }

    public boolean removeMappingFromDeploymentMap(String tenant, String mappingIdent) {
        MutableBoolean result = new MutableBoolean(false);
        if (!tenantDeploymentMap.containsKey(tenant)) {
            saveDeploymentMap(tenant);
        }
        List<String> remove = tenantDeploymentMap.get(tenant).remove(mappingIdent);
        result.setValue(remove != null && remove.size() > 0);
        if (result.getValue())
            saveDeploymentMap(tenant);
        return result.booleanValue();
    }

    public void saveDeploymentMap(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                    .getMappingServiceRepresentations().get(tenant);
            // avoid sending empty monitoring events
            log.info("Tenant {} - Saving deploymentMap: number all deploments:{}", tenant,
                    tenantDeploymentMap.get(tenant).size());
            Map<String, Object> map = new HashMap<String, Object>();
            Map<String, List<String>> deploymentMapPerTenant = tenantDeploymentMap.get(tenant);
            map.put(C8YAgent.DEPLOYMENT_MAP_FRAGMENT, deploymentMapPerTenant);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
            updateMor.setAttrs(map);
            this.inventoryApi.update(updateMor);
        });
    }

}