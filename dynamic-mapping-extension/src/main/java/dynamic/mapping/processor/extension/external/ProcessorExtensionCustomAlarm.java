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

package dynamic.mapping.processor.extension.external;

import static dynamic.mapping.model.MappingSubstitution.substituteValueInPayload;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import dynamic.mapping.model.API;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingRepresentation;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.extension.ProcessorExtensionTarget;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import dynamic.mapping.core.C8YAgent;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.ws.rs.ProcessingException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

@Slf4j
public class ProcessorExtensionCustomAlarm
        implements ProcessorExtensionSource<byte[]>, ProcessorExtensionTarget<byte[]> {

    private ObjectMapper objectMapper;

    public ProcessorExtensionCustomAlarm() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        Map jsonObject;
        try {
            jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context
                .getPostProcessingCache();

        postProcessingCache.put("time",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(new MappingSubstitution.SubstituteValue(
                                new DateTime(
                                        jsonObject.get("time"))
                                        .toString(),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        postProcessingCache.put("type",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        jsonObject.get("alarmType"),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));

        postProcessingCache.put("severity",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        jsonObject.get("criticality"),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));

        postProcessingCache.put("text",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        jsonObject.get("message"),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));
                                        
        // as the mappping uses useExternalId we have to map the id to _IDENTITY_.externalId
        postProcessingCache.put(context.getMapping().getGenericDeviceIdentifier(),
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(
                                jsonObject.get("externalId"),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        log.info("Tenant {} - New alarm over json processor: {}, {}", context.getTenant(),
        jsonObject.get("time"), jsonObject.get("message"));
    }

    @Override
    public void substituteInTargetAndSend(ProcessingContext<byte[]> context, C8YAgent c8yAgent) {
        /*
         * step 3 replace target with extract content from inbound payload
         */
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        // if there are too few devices identified then we replicate the first device
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        String maxEntry = postProcessingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();

        // the following stmt does not work for mapping_type protobuf
        // String deviceIdentifierMapped2PathTarget2 =
        // MappingRepresentation.findDeviceIdentifier(mapping).pathTarget;
        // using alternative method
        String deviceIdentifierMapped2PathTarget2 = mapping.targetAPI.identifier;
        List<MappingSubstitution.SubstituteValue> deviceEntries = postProcessingCache
                .get(deviceIdentifierMapped2PathTarget2);
        int countMaxlistEntries = postProcessingCache.get(maxEntry).size();
        MappingSubstitution.SubstituteValue toDuplicate = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxlistEntries) {
            deviceEntries.add(toDuplicate);
        }

        for (int i = 0; i < deviceEntries.size(); i++) {
            // for (MappingSubstitution.SubstituteValue device : deviceEntries) {
            getBuildProcessingContext(context, i, postProcessingCache, c8yAgent);
        }
        log.info("Tenant {} - Context is completed, sequentially processed, createNonExistingDevice: {} !", tenant,
                mapping.createNonExistingDevice);

    }

    private ProcessingContext<byte[]> getBuildProcessingContext(ProcessingContext<byte[]> context, int finalI,
            Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache, C8YAgent c8yAgent) {
        Set<String> pathTargets = postProcessingCache.keySet();
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        String deviceIdentifierMapped2PathTarget2 = mapping.targetAPI.identifier;
        List<MappingSubstitution.SubstituteValue> deviceEntries = postProcessingCache
                .get(deviceIdentifierMapped2PathTarget2);
        MappingSubstitution.SubstituteValue device = deviceEntries.get(finalI);
        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.targetTemplate);
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
                if (pathTarget.equals(deviceIdentifierMapped2PathTarget2) && mapping.useExternalId) {

                    ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant,
                            new ID(mapping.externalIdType, substituteValue.toString()), context);
                    if (sourceId == null && mapping.createNonExistingDevice) {
                        ManagedObjectRepresentation attocDevice = null;
                        Map<String, Object> request = new HashMap<String, Object>();
                        request.put("name",
                                "device_" + mapping.externalIdType + "_" + substituteValue.value);
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
                        } catch (dynamic.mapping.processor.ProcessingException e) {
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
                substituteValueInPayload(mapping.mappingType, substituteValue,
                        payloadTarget, pathTarget);
            } else if (!pathTarget.equals(deviceIdentifierMapped2PathTarget2)) {
                substituteValueInPayload(mapping.mappingType, substituteValue,
                        payloadTarget, pathTarget);
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

}