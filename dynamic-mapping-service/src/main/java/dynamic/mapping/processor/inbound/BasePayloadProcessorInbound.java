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

package dynamic.mapping.processor.inbound;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.API;
import dynamic.mapping.model.MappingRepresentation;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import org.json.JSONException;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class BasePayloadProcessorInbound<T> {

    public BasePayloadProcessorInbound(ConfigurationRegistry configurationRegistry) {
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.processingCachePool = configurationRegistry.getProcessingCachePool();
    }

    protected C8YAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected ExecutorService processingCachePool;

    public abstract ProcessingContext<T> deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException;

    public abstract void extractFromSource(ProcessingContext<T> context) throws ProcessingException;

    public abstract void applyFilter(ProcessingContext<T> context);

    public void substituteInTargetAndSend(ProcessingContext<T> context) {
        /*
         * step 3 replace target with extract content from inbound payload
         */
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        // if there are too few devices identified, then we replicate the first device
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        String maxEntry = postProcessingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();

        // the following stmt does not work for mapping_type protobuf
        // String deviceIdentifierMapped2PathTarget =
        // MappingRepresentation.findDeviceIdentifier(mapping).pathTarget;
        // using alternative method
        // String deviceIdentifierMapped2PathTarget = mapping.targetAPI.identifier;
        String deviceIdentifierMapped2PathTarget = mapping.getGenericDeviceIdentifier();
        List<MappingSubstitution.SubstituteValue> deviceEntries = postProcessingCache
                .get(deviceIdentifierMapped2PathTarget);
        int countMaxlistEntries = postProcessingCache.get(maxEntry).size();
        MappingSubstitution.SubstituteValue toDuplicate = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxlistEntries) {
            deviceEntries.add(toDuplicate);
        }

        // if devices have to be created implicitly, then request have to b process in
        // sequence, other multiple threads will try to create a device with the same
        // externalId
        if (mapping.createNonExistingDevice) {
            for (int i = 0; i < deviceEntries.size(); i++) {
                // for (MappingSubstitution.SubstituteValue device : deviceEntries) {
                getBuildProcessingContext(context, i, postProcessingCache);
            }
            log.info("Tenant {} - Context is completed, sequentially processed, createNonExistingDevice: {} !", tenant,
                    mapping.createNonExistingDevice);

        } else {
            List<Future<ProcessingContext<T>>> contextFutureList = new ArrayList<>();
            for (int i = 0; i < deviceEntries.size(); i++) {
                // for (MappingSubstitution.SubstituteValue device : deviceEntries) {
                int finalI = i;
                contextFutureList.add(processingCachePool.submit(() -> {
                    return getBuildProcessingContext(context, finalI, postProcessingCache);
                }));
            }
            int j = 0;
            for (Future<ProcessingContext<T>> currentContext : contextFutureList) {
                try {
                    log.debug("Tenant {} - Waiting context is completed {}...", tenant, j);
                    currentContext.get(60, TimeUnit.SECONDS);
                    j++;
                } catch (Exception e) {
                    log.error("Tenant {} - Error waiting for result of Processing context", tenant, e);
                }
            }
            log.info("Tenant {} - Context is completed, {} parallel requests processed!", tenant, j);
        }
    }

    private ProcessingContext<T> getBuildProcessingContext(ProcessingContext<T> context, int finalI,
            Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache) {
        Set<String> pathTargets = postProcessingCache.keySet();
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        // String deviceIdentifierMapped2PathTarget = mapping.targetAPI.identifier;
                // String deviceIdentifierMapped2PathTarget = mapping.targetAPI.identifier;
                String deviceIdentifierMapped2PathTarget = mapping.getGenericDeviceIdentifier();
        List<MappingSubstitution.SubstituteValue> deviceEntries = postProcessingCache
                .get(deviceIdentifierMapped2PathTarget);
        MappingSubstitution.SubstituteValue device = deviceEntries.get(finalI);
        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.targetTemplate);
        // patch target template with IDENTITY attribute
        // if (mapping.externalIdType != null && !("").equals(mapping.externalIdType)) {
        //     payloadTarget.put("$", "_IDENTITY_",
        //             Collections.singletonMap("_IDENTITY_",
        //                     Map.of("externalIdType", "someType",
        //                             "externalId", "someId")));
        // } else {
        //     payloadTarget.put("$", "_IDENTITY_",
        //             Collections.singletonMap("_IDENTITY_",
        //                     Map.of(
        //                             "c8ySourceId", "someId")));
        // }
        for (String pathTarget : pathTargets) {
            MappingSubstitution.SubstituteValue substituteValue = new MappingSubstitution.SubstituteValue(
                    "NOT_DEFINED", MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                    RepairStrategy.DEFAULT);
            if (finalI < postProcessingCache.get(pathTarget).size()) {
                substituteValue = postProcessingCache.get(pathTarget).get(finalI).clone();
            } else if (postProcessingCache.get(pathTarget).size() == 1) {
                // this is an indication that the substitution is the same for all
                // events/alarms/measurements/inventory
                if (substituteValue.repairStrategy.equals(RepairStrategy.USE_FIRST_VALUE_OF_ARRAY) ||
                        substituteValue.repairStrategy.equals(RepairStrategy.DEFAULT)) {
                    substituteValue = postProcessingCache.get(pathTarget).get(0).clone();
                } else if (substituteValue.repairStrategy.equals(RepairStrategy.USE_LAST_VALUE_OF_ARRAY)) {
                    int last = postProcessingCache.get(pathTarget).size() - 1;
                    substituteValue = postProcessingCache.get(pathTarget).get(last).clone();
                }
                log.warn(
                        "Tenant {} - During the processing of this pathTarget: '{}' a repair strategy: '{}' was used.",
                        tenant,
                        pathTarget, substituteValue.repairStrategy);
            }

            if (!mapping.targetAPI.equals(API.INVENTORY)) {
                if (pathTarget.equals(deviceIdentifierMapped2PathTarget) && mapping.useExternalId) {

                    ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant,
                            new ID(mapping.externalIdType, substituteValue.toString()), context);
                    if (sourceId == null && mapping.createNonExistingDevice) {
                        ManagedObjectRepresentation attocDevice = null;
                        Map<String, Object> request = new HashMap<String, Object>();
                        request.put("name",
                                "device_" + mapping.externalIdType + "_" + substituteValue.value.toString());
                        request.put(MappingRepresentation.MAPPING_GENERATED_TEST_DEVICE, null);
                        request.put("c8y_IsDevice", null);
                        request.put("com_cumulocity_model_Agent", null);
                        try {
                            var requestString = objectMapper.writeValueAsString(request);
                            var newPredecessor = context.addRequest(
                                    new C8YRequest(predecessor, RequestMethod.PATCH, device.value.toString(),
                                            mapping.externalIdType, requestString, null, API.INVENTORY, null));
                            attocDevice = c8yAgent.upsertDevice(tenant,
                                    new ID(mapping.externalIdType, substituteValue.value.toString()), context,
                                    null);
                            var response = objectMapper.writeValueAsString(attocDevice);
                            context.getCurrentRequest().setResponse(response);
                            substituteValue.value = attocDevice.getId().getValue();
                            predecessor = newPredecessor;
                        } catch (ProcessingException | JsonProcessingException e) {
                            context.getCurrentRequest().setError(e);
                        }
                    } else if (sourceId == null && context.isSendPayload()) {
                        throw new RuntimeException(String.format(
                                "External id %s for type %s not found!",
                                substituteValue.toString(),
                                mapping.externalIdType));
                    } else if (sourceId == null) {
                        substituteValue.value = null;
                    } else {
                        substituteValue.value = sourceId.getManagedObject().getId().getValue();
                    }

                }
                // since the attributes identifying the MEA and Invnetory requests are removed during the design time, htey have to be added before sending
                if (mapping.getGenericDeviceIdentifier().equals(pathTarget)) {
                    substituteValue.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
                }
                substituteValueInPayload(mapping.mappingType, substituteValue, payloadTarget, mapping.transformGenericPath2C8YPath(pathTarget));
            } else if (!pathTarget.equals(deviceIdentifierMapped2PathTarget)) {
                // since the attributes identifying the MEA and Invnetory requests are removed during the design time, htey have to be added before sending
                if (mapping.getGenericDeviceIdentifier().equals(pathTarget)) {
                    substituteValue.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
                }
                substituteValueInPayload(mapping.mappingType, substituteValue, payloadTarget, mapping.transformGenericPath2C8YPath(pathTarget));
            }
        }
        /*
         * step 4 prepare target payload for sending to c8y
         */
        if (mapping.targetAPI.equals(API.INVENTORY)) {
            ManagedObjectRepresentation attocDevice = null;
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor, RequestMethod.PATCH, device.value.toString(),
                            mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, API.INVENTORY, null));
            try {
                ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant,
                        new ID(mapping.externalIdType, device.value.toString()), context);
                attocDevice = c8yAgent.upsertDevice(tenant,
                        new ID(mapping.externalIdType, device.value.toString()), context, sourceId);
                var response = objectMapper.writeValueAsString(attocDevice);
                context.getCurrentRequest().setResponse(response);
            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
            }
            predecessor = newPredecessor;
        } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
            AbstractExtensibleRepresentation attocRequest = null;
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor, RequestMethod.POST, device.value.toString(),
                            mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, mapping.targetAPI, null));
            try {
                if (context.isSendPayload()) {
                    c8yAgent.createMEAO(context);
                    String response = objectMapper.writeValueAsString(attocRequest);
                    context.getCurrentRequest().setResponse(response);
                    /*
                     * c8yAgent.createMEAOAsync(context).thenApply(resp -> {
                     * String response = null;
                     * try {
                     * response = objectMapper.writeValueAsString(attocRequest);
                     * } catch (JsonProcessingException e) {
                     * context.getCurrentRequest().setError(e);
                     * }
                     * context.getCurrentRequest().setResponse(response);
                     * return null;
                     * });
                     */
                }

            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
            }
            predecessor = newPredecessor;
        } else {
            log.warn("Tenant {} - Ignoring payload: {}, {}, {}", tenant, payloadTarget, mapping.targetAPI,
                    postProcessingCache.size());
        }
        log.debug("Tenant {} - Added payload for sending: {}, {}, numberDevices: {}", tenant, payloadTarget,
                mapping.targetAPI,
                deviceEntries.size());
        return context;
    }

    public static void substituteValueInPayload(MappingType type, MappingSubstitution.SubstituteValue sub,
            DocumentContext jsonObject, String keys)
            throws JSONException {
        boolean subValueMissing = sub.value == null;
        boolean subValueNull = (sub.value == null) || (sub.value != null);
        try {
            if ("$".equals(keys)) {
                Object replacement = sub;
                if (replacement instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rm = (Map<String, Object>) map;
                    for (Map.Entry<String, Object> entry : rm.entrySet()) {
                        jsonObject.put("$", entry.getKey(), entry.getValue());
                    }
                }
            } else {
                if ((sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING) && subValueMissing) ||
                        (sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_NULL) && subValueNull)) {
                    jsonObject.delete(keys);
                } else if (sub.repairStrategy.equals(RepairStrategy.CREATE_IF_MISSING)) {
                    // boolean pathIsNested = keys.contains(".") || keys.contains("[");
                    // if (pathIsNested) {
                    //     throw new JSONException("Can only create new nodes on the root level!");
                    // }
                    //jsonObject.put("$", keys, sub.typedValue());
                    jsonObject.set("$."+ keys, sub);

                } else {
                    jsonObject.set(keys, sub);
                }
            }
        } catch (PathNotFoundException e) {
            throw new PathNotFoundException(String.format("Path: %s not found!", keys));
        }
    }

}
