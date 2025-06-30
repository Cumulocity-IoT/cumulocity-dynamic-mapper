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

package dynamic.mapper.core;

import static java.util.Map.entry;
import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.validation.Valid;

import org.apache.commons.lang3.mutable.MutableObject;
import org.graalvm.polyglot.Context;
import org.joda.time.DateTime;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.MappingTreeNode;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingRepresentation;
import dynamic.mapper.model.MappingServiceRepresentation;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.ResolveException;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.model.ValidationError;
import dynamic.mapper.processor.C8YMessage;

@Slf4j
@Component
public class MappingComponent {

    private static final Handler GRAALJS_LOG_HANDLER = new SLF4JBridgeHandler();

    @Autowired
    ConfigurationRegistry configurationRegistry;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    // Structure: < Tenant, < MappingIdentifier , MappingStatus > >
    private Map<String, Map<String, MappingStatus>> mappingStatusS = new ConcurrentHashMap<>();

    // a mapping is added to the deploymentMap with its specified connectors that
    // are defined in the second step of the stepper
    // the deploymentMap contains active & inactive mappings. This distinction is
    // handled in every connector in mappingsDeployedInbound
    // Structure: < Tenant, < MappingIdentifier, List of ConnectorIdentifier > >
    private Map<String, Map<String, List<String>>> deploymentMaps = new ConcurrentHashMap<>();

    private Map<String, Set<Mapping>> dirtyMappings = new ConcurrentHashMap<>();

    // Structure: <Tenant, Initialized >
    private Map<String, Boolean> initializedMappingStatus = new ConcurrentHashMap<>();

    // cache of inbound mappings stored by mapping.id
    private Map<String, Map<String, Mapping>> cacheMappingInbound = new ConcurrentHashMap<>();

    // cache of outbound mappings stored by mapping.id
    private Map<String, Map<String, Mapping>> cacheMappingOutbound = new ConcurrentHashMap<>();

    // cache of outbound mappings stored by mapping.filterOutbound used for
    // resolving
    private Map<String, Map<String, List<Mapping>>> resolverMappingOutbound = new ConcurrentHashMap<>();

    // cache of inbound mappings stored in a tree used for resolving
    private Map<String, MappingTreeNode> resolverMappingInbound = new ConcurrentHashMap<>();

    public void createResources(String tenant) {
        cacheMappingInbound.put(tenant, new ConcurrentHashMap<>());
        cacheMappingOutbound.put(tenant, new ConcurrentHashMap<>());
        resolverMappingOutbound.put(tenant, new ConcurrentHashMap<>());
        resolverMappingInbound.put(tenant, MappingTreeNode.createRootNode(tenant));
        deploymentMaps.put(tenant, new ConcurrentHashMap<>());
        initializedMappingStatus.put(tenant, true);
        mappingStatusS.put(tenant, new ConcurrentHashMap<String, MappingStatus>());
        initializeDeploymentMap(tenant, false);
    }

    public void initializeResources(String tenant) {
        initializeMappingStatus(tenant, false);
        rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        rebuildMappingInboundCache(tenant, ConnectorId.INTERNAL);
    }

    public void removeResources(String tenant) {
        cacheMappingInbound.remove(tenant);
        cacheMappingOutbound.remove(tenant);
        resolverMappingInbound.remove(tenant);
        resolverMappingOutbound.remove(tenant);
        mappingStatusS.remove(tenant);
        deploymentMaps.remove(tenant);
        initializedMappingStatus.remove(tenant);
    }

    public void initializeMappingStatus(String tenant, boolean reset) {
        MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                .getMappingServiceRepresentation(tenant);

        if (mappingServiceRepresentation.getMappingStatus() != null && !reset) {
            log.debug("{} - Initializing status: {}, {} ", tenant,
                    mappingServiceRepresentation.getMappingStatus(),
                    (mappingServiceRepresentation.getMappingStatus() == null
                            || mappingServiceRepresentation.getMappingStatus().size() == 0 ? 0
                                    : mappingServiceRepresentation.getMappingStatus().size()));
            Map<String, MappingStatus> mappingStatus = new ConcurrentHashMap<>();
            mappingServiceRepresentation.getMappingStatus().forEach(ms -> {
                mappingStatus.put(ms.identifier, ms);
            });
            mappingStatusS.put(tenant, mappingStatus);
            resolverMappingInbound.put(tenant, MappingTreeNode.createRootNode(tenant));
        } else {
            Map<String, MappingStatus> map = mappingStatusS.get(tenant);
            if (map != null) {
                map.clear();
            }
        }
        if (!mappingStatusS.get(tenant).containsKey(MappingStatus.IDENT_UNSPECIFIED_MAPPING)) {
            mappingStatusS.get(tenant).put(MappingStatus.UNSPECIFIED_MAPPING_STATUS.identifier,
                    MappingStatus.UNSPECIFIED_MAPPING_STATUS);
        }

    }

    public void sendMappingStatus(String tenant) {
        boolean initialized = this.initializedMappingStatus != null
                ? this.initializedMappingStatus.getOrDefault(tenant, false)
                : false;
        if (configurationRegistry.getServiceConfiguration(tenant).sendMappingStatus & initialized) {
            subscriptionsService.runForTenant(tenant, () -> {
                Map<String, MappingStatus> statusMapping = mappingStatusS.get(tenant);
                MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                        .getMappingServiceRepresentation(tenant);
                // avoid sending empty monitoring events
                if (statusMapping.values().size() > 0 && mappingServiceRepresentation != null) {
                    log.debug("{} - Sending monitoring: {}", tenant, statusMapping.values().size());
                    Map<String, Object> service = new ConcurrentHashMap<String, Object>();
                    // Convert statusMapping values to a list for filtering
                    List<MappingStatus> msList = new ArrayList<>(statusMapping.values());

                    // Filter the list to keep only desired items
                    msList.removeIf(status -> !"UNSPECIFIED".equals(status.id) &&
                            !containsMappingInboundInCache(tenant, status.id) &&
                            !containsMappingOutboundInCache(tenant, status.id));

                    // Convert filtered list to array
                    MappingStatus[] ms = msList.toArray(new MappingStatus[0]);

                    // Set names according to the rules
                    for (int index = 0; index < ms.length; index++) {
                        if ("UNSPECIFIED".equals(ms[index].id)) {
                            ms[index].name = "Unspecified";
                        } else if (containsMappingInboundInCache(tenant, ms[index].id)) {
                            ms[index].name = getMappingInboundFromCache(tenant, ms[index].id).name;
                        } else if (containsMappingOutboundInCache(tenant, ms[index].id)) {
                            ms[index].name = getMappingOutboundFromCache(tenant, ms[index].id).name;
                        }
                    }
                    service.put(C8YAgent.MAPPING_FRAGMENT, ms);
                    ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
                    updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
                    updateMor.setAttrs(service);
                    this.inventoryApi.update(updateMor);
                } else {
                    log.debug("{} - Ignoring mapping monitoring: {}, initialized: {}", tenant,
                            statusMapping.values().size(),
                            initialized);
                }
            });
        }
    }

    public MappingStatus getMappingStatus(String tenant, Mapping m) {
        // log.info("{} - get MappingStatus: {}", tenant, m.identifier);
        Map<String, MappingStatus> mappingStatus = mappingStatusS.get(tenant);
        MappingStatus ms = mappingStatus.get(m.identifier);
        if (ms == null) {
            log.info("{} - Adding: {}", tenant, m.identifier);
            ms = new MappingStatus(m.id, m.name, m.identifier, m.direction, m.mappingTopic, m.publishTopic, 0, 0,
                    0, 0, 0, null);
            mappingStatus.put(m.identifier, ms);
        }
        return ms;
    }

    public List<MappingStatus> getMappingStatus(String tenant) {
        Map<String, MappingStatus> statusMapping = mappingStatusS.get(tenant);
        return new ArrayList<MappingStatus>(statusMapping.values());
    }

    private void removeMappingStatus(String tenant, String id) {
        mappingStatusS.get(tenant).remove(id);
    }

    public void removeMappingInboundFromResolver(String tenant, Mapping mapping) {
        try {
            resolverMappingInbound.get(tenant).deleteMapping(mapping);
        } catch (ResolveException e) {
            log.error("{} - Could not delete mapping {}, ignoring mapping", tenant, mapping, e);
        }
    }

    public MappingTreeNode getResolverMappingInbound(String tenant) {
        return resolverMappingInbound.get(tenant);
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
                DateTime.now(), configurationRegistry.getMappingServiceRepresentation(tenant), tenant, stMap);
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
            log.debug("{} - Saved mappings!", tenant);
        });
    }

    public Mapping getMapping(String tenant, String id) {
        Mapping result = subscriptionsService.callForTenant(tenant, () -> {
            ManagedObjectRepresentation mo = null;
            try {
                mo = inventoryApi.get(GId.asGId(id));
                if (mo != null) {
                    MappingRepresentation mappingMO = toMappingObject(mo);
                    Mapping mapping = mappingMO.getC8yMQTTMapping();
                    mapping.setId(mappingMO.getId());
                    log.debug("{} - Found Mapping: {}", tenant, mapping.id);
                    return mapping;
                }
            } catch (SDKException e) {
                log.warn("Failed to find managed object to mapping: {}", id, e);
                return null;
            } catch (IllegalArgumentException e) {
                log.warn("Failed to convert managed object to mapping: {}", id, e);
                return null;
            }
            return null;
        });
        return result;
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
                            if (Direction.INBOUND.equals(mapping.getDirection()) && mapping.getMappingTopic() == null) {
                                log.warn("{} - Mapping {} has no mappingTopic, ignoring mapping", tenant,
                                        mapping);
                                return Optional.<Mapping>empty();
                            }
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
            log.debug("{} - Loaded mappings (inbound & outbound): {}", tenant, res.size());
            return res;
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
            removeMappingStatus(tenant, id);
            return m.getC8yMQTTMapping();
        });
        if (result != null) {
            removeMappingFromDeploymentMap(tenant, result.identifier);
            removeCodeFromEngine(result, tenant);
        }
        // log.info("{} - Deleted Mapping: {}", tenant, id);

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
            removeCodeFromEngine(mapping, tenant);

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
            log.info("{} - Mapping created: {}", tenant, mor.getName());
            log.debug("{} - Mapping created: {}", tenant, mor);
            return mapping;
        });
        return result;
    }

    public void setDebugMapping(String tenant, String id, Boolean debug) throws Exception {
        // step 1. update debug for mapping
        log.info("{} - Setting debug: {} got mapping: {}", tenant, id, debug);
        Mapping mapping = getMapping(tenant, id);
        mapping.setDebug(debug);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates from inbound cache
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, id).getSnoopedTemplates());
        } else {
            // step 2. retrieve collected snoopedTemplates from outbound cache
            mapping.setSnoopedTemplates(getMappingOutboundFromCache(tenant, id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
        // step 4. delete mapping from update cache
        removeDirtyMapping(tenant, mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        } else {
            removeMappingInboundFromResolver(tenant, mapping);
            addMappingInboundToResolver(tenant, mapping);
            addMappingInboundToCache(tenant, mapping.id, mapping);
        }
    }

    public Mapping setActivationMapping(String tenant, String mappingId, Boolean active) throws Exception {
        // step 1. update activation for mapping
        log.debug("{} - Setting active: {} got mapping: {}", tenant, active, mappingId);
        Mapping mapping = getMapping(tenant, mappingId);
        mapping.setActive(active);
        if (Direction.INBOUND.equals(mapping.direction)) {
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, mappingId).getSnoopedTemplates());
        } else {
            mapping.setSnoopedTemplates(getMappingOutboundFromCache(tenant, mappingId).getSnoopedTemplates());
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
            rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        } else {
            removeMappingInboundFromResolver(tenant, mapping);
            addMappingInboundToResolver(tenant, mapping);
            addMappingInboundToCache(tenant, mapping.id, mapping);
        }

        // if mapping is activated reset currentFailureCount of mapping
        if (active) {
            MappingStatus mappingStatus = getMappingStatus(tenant, mapping);
            mappingStatus.currentFailureCount = 0;
            // TODO GRAAL_PERFORMANCE add code source to graalCode cache
//            if(mapping.code != null)
//                configurationRegistry.updateGraalsSourceMapping(tenant, mappingId, mapping.code);
        } else {
            // TODO GRAAL_PERFORMANCE remove code source from graalCode cache
//            if(mapping.code != null)
//                configurationRegistry.removeGraalsSourceMapping(tenant, mappingId);

        }
        return mapping;
    }

    public Mapping setFilterMapping(String tenant, String mappingId, String filterMapping) throws Exception {
        // step 1. update activation for mapping
        log.debug("{} - Setting filterMapping: {} got mapping: {}", tenant, filterMapping, mappingId);
        Mapping mapping = getMapping(tenant, mappingId);
        mapping.setFilterMapping(filterMapping);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, mappingId).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, false);
        // step 4. delete mapping from update cache
        removeDirtyMapping(tenant, mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        } else {
            removeMappingInboundFromResolver(tenant, mapping);
            addMappingInboundToResolver(tenant, mapping);
            addMappingInboundToCache(tenant, mapping.id, mapping);
        }
        return mapping;
    }

    public void setSnoopStatusMapping(String tenant, String id, SnoopStatus snoop) throws Exception {
        // step 1. update debug for mapping
        log.info("{} - Setting snoop: {} got mapping: {}", tenant, id, snoop);
        Mapping mapping = getMapping(tenant, id);
        mapping.setSnoopStatus(snoop);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, id).getSnoopedTemplates());
        } else {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
        // step 4. delete mapping from update cache
        removeDirtyMapping(tenant, mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        } else {
            removeMappingInboundFromResolver(tenant, mapping);
            addMappingInboundToResolver(tenant, mapping);
            addMappingInboundToCache(tenant, mapping.id, mapping);
        }
    }

    public List<Mapping> resolveMappingInbound(String tenant, String topic) throws ResolveException {
        List<Mapping> resolvedMappings = resolverMappingInbound.get(tenant).resolveMapping(topic);
        return resolvedMappings;
    }

    private ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return configurationRegistry.getObjectMapper().convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return configurationRegistry.getObjectMapper().convertValue(mor, MappingRepresentation.class);
    }

    public void addMappingInboundToResolver(String tenant, Mapping mapping) {
        try {
            resolverMappingInbound.get(tenant).addMapping(mapping);
        } catch (ResolveException e) {
            log.error("{} - Could not add mapping {}, ignoring mapping", tenant, mapping, e);
        }
    }

    public List<Mapping> rebuildMappingOutboundCache(String tenant, ConnectorId connectorId) {
        // only add outbound mappings to the cache
        List<Mapping> updatedMappings = getMappings(tenant, Direction.OUTBOUND).stream()
                .filter(m -> Direction.OUTBOUND.equals(m.direction))
                .collect(Collectors.toList());
        log.info("{} - Loaded mappings outbound: {}, triggered by connector: {}", tenant, updatedMappings.size(),
                connectorId.getName());

        cacheMappingOutbound.replace(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));

        resolverMappingOutbound.replace(tenant, updatedMappings.stream()
                .filter(m -> {
                    if (m.getFilterMapping() == null) {
                        log.warn("{} - Mapping with ID {} has null filterMapping, ignoring for resolver",
                                tenant, m.getId());
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.groupingBy(Mapping::getFilterMapping)));

        return updatedMappings;
    }

    public List<Mapping> resolveMappingOutbound(String tenant, C8YMessage c8yMessage) throws ResolveException {
        List<Mapping> result = new ArrayList<>();
        API api = c8yMessage.getApi();

        try {

            for (Mapping mapping : cacheMappingOutbound.get(tenant).values()) {
                if (!mapping.active || !mapping.targetAPI.equals(api)) {
                    // if (!mapping.active) {
                    continue;
                }

                // Check message filter condition
                if (!evaluateFilter(tenant, mapping.getFilterMapping(), c8yMessage)) {
                    continue;
                }

                // Check inventory filter condition if specified
                if (mapping.getFilterInventory() != null) {
                    if (c8yMessage.getSourceId() == null
                            || !evaluateInventoryFilter(tenant, mapping.getFilterInventory(), c8yMessage)) {
                        continue;
                    }
                }

                // All conditions passed, add mapping to result
                result.add(mapping);
            }
        } catch (IllegalArgumentException e) {
            throw new ResolveException(e.getMessage());
        }

        return result;
    }

    /**
     * Evaluates a filter expression against the given data
     */
    private boolean evaluateFilter(String tenant, String filterExpression, C8YMessage message) {
        try {
            var expression = jsonata(filterExpression);
            Object result = expression.evaluate(message.getParsedPayload());

            if (result != null && isNodeTrue(result)) {
                log.info("{} - Found valid mapping for filter {} in C8Y message {}",
                        tenant, filterExpression, message.getMessageId());
                return true;
            } else {
                log.debug("{} - Not matching mapping key fragment {} in C8Y message {}, {}",
                        tenant, filterExpression, message.getMessageId(), toPrettyJsonString(message.getPayload()));
                return false;
            }
        } catch (Exception e) {
            log.debug("Filter evaluation error for {}: {}", filterExpression, e.getMessage());
            return false;
        }
    }

    /**
     * Evaluates an inventory filter against cached inventory data
     */
    private boolean evaluateInventoryFilter(String tenant, String filterExpression, C8YMessage message) {
        try {
            Map<String, Object> cachedInventoryContent = configurationRegistry.getC8yAgent()
                    .getMOFromInventoryCache(tenant, message.getSourceId());
            List<String> keyList = new ArrayList<>(cachedInventoryContent.keySet());
            log.info("{} - For object {} found following fragments in inventory cache {}",
                    tenant, message.getSourceId(), keyList);
            var expression = jsonata(filterExpression);
            Object result = expression.evaluate(cachedInventoryContent);

            if (result != null && isNodeTrue(result)) {
                log.info("{} - Found valid inventory for filter {} in C8Y message {}",
                        tenant, filterExpression, message.getMessageId());
                return true;
            } else {
                log.debug("{} - Not matching inventory filter {} for source {} in message {}",
                        tenant, filterExpression, message.getSourceId(), message.getMessageId());
                return false;
            }
        } catch (Exception e) {
            log.debug("Inventory filter evaluation error for {}: {}", filterExpression, e.getMessage());
            return false;
        }
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

    public Mapping removeFromMappingFromCaches(String tenant, Mapping mapping) {
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            Mapping deletedMapping = removeMappingOutboundFromCache(tenant, mapping.id);
            log.info("{} - Preparing to delete {} {}", tenant, resolverMappingOutbound.get(tenant),
                    mapping.filterMapping);

            List<Mapping> cmo = resolverMappingOutbound.get(tenant).get(mapping.filterMapping);
            cmo.removeIf(m -> mapping.id.equals(m.id));
            return deletedMapping;
        } else {
            Mapping deletedMapping = removeMappingInboundFromCache(tenant, mapping.id);
            removeMappingInboundFromResolver(tenant, deletedMapping);
            return deletedMapping;
        }
    }

    public MappingTreeNode rebuildMappingTree(List<Mapping> mappings, String tenant) {
        MappingTreeNode in = MappingTreeNode.createRootNode(tenant);
        mappings.forEach(m -> {
            try {
                in.addMapping(m);
            } catch (ResolveException e) {
                log.error("{} - Could not add mapping {}, ignoring mapping", tenant, m, e);
            }
        });
        return in;
    }

    private List<Mapping> rebuildMappingInboundCache(String tenant, List<Mapping> updatedMappings,
            ConnectorId connectorId) {
        log.info("{} - Loaded mappings inbound: {}, triggered by connector: {}", tenant, updatedMappings.size(),
                connectorId.getName());
        cacheMappingInbound.replace(tenant, updatedMappings.stream()
                .collect(Collectors.toMap(Mapping::getId, Function.identity())));
        // update mappings tree
        resolverMappingInbound.replace(tenant, rebuildMappingTree(updatedMappings, tenant));
        return updatedMappings;
    }

    public List<Mapping> rebuildMappingInboundCache(String tenant, ConnectorId connectorId) {
        List<Mapping> updatedMappings = getMappings(tenant, Direction.INBOUND).stream()
                .filter(m -> !Direction.OUTBOUND.equals(m.direction) || m.mappingTopic == null)
                .collect(Collectors.toList());
        return rebuildMappingInboundCache(tenant, updatedMappings, connectorId);
    }

    public void updateSourceTemplate(String tenant, String id, Integer index) throws Exception {
        // step 1. update debug for mapping
        Mapping mapping = getMapping(tenant, id);
        String newSourceTemplate = mapping.snoopedTemplates.get(index);
        log.info("{} - Setting sourceTemplate for mapping: {} to: {}", tenant, id, newSourceTemplate);
        mapping.setSourceTemplate(newSourceTemplate);
        if (Direction.INBOUND.equals(mapping.direction)) {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, id).getSnoopedTemplates());
        } else {
            // step 2. retrieve collected snoopedTemplates
            mapping.setSnoopedTemplates(getMappingInboundFromCache(tenant, id).getSnoopedTemplates());
        }
        // step 3. update mapping in inventory
        // don't validate mapping when setting active = false, this allows to remove
        // mappings that are not working
        updateMapping(tenant, mapping, true, true);
        // step 4. delete mapping from update cache
        removeDirtyMapping(tenant, mapping);
        // step 5. update caches
        if (Direction.OUTBOUND.equals(mapping.direction)) {
            rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        } else {
            removeMappingInboundFromResolver(tenant, mapping);
            addMappingInboundToResolver(tenant, mapping);
            addMappingInboundToCache(tenant, mapping.id, mapping);
        }
    }

    private void removeCodeFromEngine(Mapping mapping, String tenant) {

        if (mapping.code != null) {
            String globalIdentifier = "delete globalThis" + Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
            try (Context context = Context.newBuilder("js")
                    .engine(configurationRegistry.getGraalEngine(tenant))
                    .logHandler(GRAALJS_LOG_HANDLER)
                    .allowHostAccess(configurationRegistry.getHostAccess())
                    .allowAllAccess(true)
                    .build()) {

                // Before closing the context, clean up the members
                context.getBindings("js").removeMember(globalIdentifier);
            }
        }
    }

    public void cleanDirtyMappings(String tenant) throws Exception {
        // test if for this tenant dirty mappings exist
        log.debug("{} - Testing for dirty maps", tenant);
        if (dirtyMappings.get(tenant) != null && dirtyMappings.get(tenant).size() > 0) {
            for (Mapping mapping : dirtyMappings.get(tenant)) {
                log.info("{} - Found mapping to be saved: {}, {}", tenant, mapping.id, mapping.snoopStatus);
                // no reload required
                updateMapping(tenant, mapping, true, false);
            }

            configurationRegistry.getC8yAgent().createEvent("Mappings updated in backend",
                    LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE,
                    DateTime.now(), configurationRegistry.getMappingServiceRepresentation(tenant), tenant,
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

    public void resetSnoop(String tenant, String id) throws Exception {
        // step 1. update debug for mapping
        log.info("{} - Reset snoop for mapping: {}", tenant, id);
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
            rebuildMappingOutboundCache(tenant, ConnectorId.INTERNAL);
        } else {
            removeMappingInboundFromResolver(tenant, mapping);
            addMappingInboundToResolver(tenant, mapping);
            addMappingInboundToCache(tenant, mapping.id, mapping);
        }
        configurationRegistry.getC8yAgent().createEvent("Mappings updated in backend",
                LoggingEventType.STATUS_MAPPING_CHANGED_EVENT_TYPE,
                DateTime.now(), configurationRegistry.getMappingServiceRepresentation(tenant), tenant,
                null);
        // }
    }

    public void initializeDeploymentMap(String tenant, boolean reset) {
        MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                .getMappingServiceRepresentation(tenant);
        if (mappingServiceRepresentation.getDeploymentMap() != null && !reset) {
            log.debug("{} - Initializing deploymentMap: {}, {} ", tenant,
                    mappingServiceRepresentation.getDeploymentMap(),
                    (mappingServiceRepresentation.getDeploymentMap() == null
                            || mappingServiceRepresentation.getDeploymentMap().size() == 0 ? 0
                                    : mappingServiceRepresentation.getDeploymentMap().size()));
            deploymentMaps.put(tenant, mappingServiceRepresentation.getDeploymentMap());
        } else {
            deploymentMaps.put(tenant, new ConcurrentHashMap<>());
        }
    }

    public void updateDeploymentMapEntry(String tenant, String mappingIdentifier, @Valid List<String> deployment) {
        Map<String, List<String>> map = deploymentMaps.get(tenant);
        map.put(mappingIdentifier, deployment);
        saveDeploymentMap(tenant);
    }

    public List<String> getDeploymentMapEntry(String tenant, String mappingIdentifier) {
        Map<String, List<String>> map = getDeploymentMap(tenant);
        return map.computeIfAbsent(mappingIdentifier, k -> new ArrayList<>());
    }

    public Map<String, List<String>> getDeploymentMap(String tenant) {
        return deploymentMaps.get(tenant);
    }

    public boolean removeConnectorFromDeploymentMap(String tenant, String connectorIdentifier) {
        Map<String, List<String>> tenantMap = deploymentMaps.get(tenant);

        boolean modified = false;
        // Using a snapshot of the entries to avoid ConcurrentModificationException
        for (Map.Entry<String, List<String>> entry : new ArrayList<>(tenantMap.entrySet())) {
            List<String> list = entry.getValue();
            synchronized (list) { // Synchronize on the list if it's not thread-safe
                if (list.remove(connectorIdentifier)) {
                    modified = true;
                }
            }
        }

        if (modified) {
            saveDeploymentMap(tenant);
        }
        return modified;
    }

    public boolean removeMappingFromDeploymentMap(String tenant, String mappingIdentifier) {
        Map<String, List<String>> tenantMap = deploymentMaps.get(tenant);

        List<String> removed = tenantMap.remove(mappingIdentifier);
        boolean result = (removed != null && !removed.isEmpty());

        if (result) {
            saveDeploymentMap(tenant);
        }
        return result;
    }

    public void saveDeploymentMap(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            MappingServiceRepresentation mappingServiceRepresentation = configurationRegistry
                    .getMappingServiceRepresentation(tenant);
            // avoid sending empty monitoring events
            log.info("{} - Saving deploymentMap, number deployments: {}", tenant,
                    deploymentMaps.get(tenant).size());
            Map<String, Object> map = new ConcurrentHashMap<String, Object>();
            Map<String, List<String>> deploymentMapPerTenant = deploymentMaps.get(tenant);
            map.put(C8YAgent.DEPLOYMENT_MAP_FRAGMENT, deploymentMapPerTenant);
            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(mappingServiceRepresentation.getId()));
            updateMor.setAttrs(map);
            this.inventoryApi.update(updateMor);
        });
    }

    public void increaseAndHandleFailureCount(String tenant, Mapping mapping, MappingStatus mappingStatus) {
        mappingStatus.currentFailureCount++;
        // check if failure count is exceeded, a value of 0 means no limit
        if (mappingStatus.currentFailureCount >= mapping.getMaxFailureCount() && mapping.getMaxFailureCount() > 0) {
            // deactivate mapping
            try {
                setActivationMapping(tenant, mapping.id, false);
                String message = String.format(
                        "Tenant %s - Mapping %s deactivated mapping due to exceeded failure count: %d",
                        tenant, mapping.id, mappingStatus.getCurrentFailureCount());
                log.warn(message);
                configurationRegistry.getC8yAgent().createEvent(message,
                        LoggingEventType.STATUS_MAPPING_FAILURE_EVENT_TYPE,
                        DateTime.now(), configurationRegistry.getMappingServiceRepresentation(tenant), tenant,
                        null);
            } catch (Exception e) {
                log.error("{} - Mapping {} failed to deactivate mapping, due to exceeded failure count: {}",
                        tenant, mapping.id,
                        mappingStatus.getCurrentFailureCount());
            }
        }
    }

    public Map<String, Mapping> getCacheMappingInbound(String tenant) {
        return cacheMappingInbound.get(tenant);
    }

    public void addMappingInboundToCache(String tenant, String id, Mapping mapping) {
        cacheMappingInbound.get(tenant).put(id, mapping);
    }

    private Mapping getMappingInboundFromCache(String tenant, String mappingId) {
        return cacheMappingInbound.get(tenant).get(mappingId);
    }

    private Mapping getMappingOutboundFromCache(String tenant, String mappingId) {
        return cacheMappingOutbound.get(tenant).get(mappingId);
    }

    private Mapping removeMappingInboundFromCache(String tenant, String mappingId) {
        return cacheMappingInbound.get(tenant).remove(mappingId);
    }

    private Mapping removeMappingOutboundFromCache(String tenant, String mappingId) {
        return cacheMappingOutbound.get(tenant).remove(mappingId);
    }

    private boolean containsMappingInboundInCache(String tenant, String mappingId) {
        return cacheMappingInbound.get(tenant).containsKey(mappingId);
    }

    private boolean containsMappingOutboundInCache(String tenant, String mappingId) {
        return cacheMappingOutbound.get(tenant).containsKey(mappingId);
    }

}