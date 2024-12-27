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
import dynamic.mapping.model.MappingSubstitution.SubstituteValue;
import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        try {
            Map<?, ?> jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));

            context.addToProcessingCache("time", new DateTime(
                    jsonObject.get("time"))
                    .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT);
            context.addToProcessingCache("type",
                    jsonObject.get("type")
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT);
            Object se = jsonObject.get("alarmType");
            context.addToProcessingCache("severity", se.toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT);
            Object message = jsonObject.get("message");
            context.addToProcessingCache("text", message.toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT);

            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addToProcessingCache(context.getMapping().getGenericDeviceIdentifier(),
                    jsonObject.get("externalId")
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT);

            log.info("Tenant {} - New alarm over json processor: {}, {}", context.getTenant(),
                    jsonObject.get("time"), jsonObject.get("message"));
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }

    @Override
    public void substituteInTargetAndSend(ProcessingContext<byte[]> context, C8YAgent c8yAgent) {
        /*
         * step 3 replace target with extract content from inbound payload
         */
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        List<MappingSubstitution.SubstituteValue> deviceEntries = context.getDeviceEntries();

        for (int i = 0; i < deviceEntries.size(); i++) {
            // for (MappingSubstitution.SubstituteValue device : deviceEntries) {
            getBuildProcessingContext(context, deviceEntries.get(i),
             i, deviceEntries.size(), c8yAgent);
        }
        log.info("Tenant {} - Context is completed, sequentially processed, createNonExistingDevice: {} !", tenant,
                mapping.createNonExistingDevice);

    }

    private ProcessingContext<byte[]> getBuildProcessingContext(ProcessingContext<byte[]> context,
            MappingSubstitution.SubstituteValue device, int finalI,
            int size, C8YAgent c8yAgent) {
        Set<String> pathTargets = context.getPathTargets();
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        List<String> pathsTargetForDeviceIdentifiers = context.getPathsTargetForDeviceIdentifiers();
        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.targetTemplate);
        for (String pathTarget : pathTargets) {
            MappingSubstitution.SubstituteValue substitute = new MappingSubstitution.SubstituteValue(
                    "NOT_DEFINED", TYPE.TEXTUAL,
                    RepairStrategy.DEFAULT);
            List<SubstituteValue> pathTargetSubstitute = context.getFromProcessingCache(pathTarget);
            if (finalI < pathTargetSubstitute.size()) {
                substitute = pathTargetSubstitute.get(finalI).clone();
            } else if (pathTargetSubstitute.size() == 1) {
                // this is an indication that the substitution is the same for all
                // events/alarms/measurements/inventory
                if (substitute.repairStrategy.equals(RepairStrategy.USE_FIRST_VALUE_OF_ARRAY) ||
                        substitute.repairStrategy.equals(RepairStrategy.DEFAULT)) {
                    substitute = pathTargetSubstitute.get(0).clone();
                } else if (substitute.repairStrategy.equals(RepairStrategy.USE_LAST_VALUE_OF_ARRAY)) {
                    int last = pathTargetSubstitute.size() - 1;
                    substitute = pathTargetSubstitute.get(last).clone();
                }
                log.warn(
                        "Tenant {} - During the processing of this pathTarget: '{}' a repair strategy: '{}' was used.",
                        tenant,
                        pathTarget, substitute.repairStrategy);
            }

            if (!mapping.targetAPI.equals(API.INVENTORY)) {
                if (pathsTargetForDeviceIdentifiers.contains(pathTarget) && mapping.useExternalId) {

                    ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant,
                            new ID(mapping.externalIdType, substitute.value.toString()), context);
                    // since the attributes identifying the MEA and Inventory requests are removed
                    // during the design time, they have to be added before sending
                    substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
                    if (sourceId == null && mapping.createNonExistingDevice) {
                        ManagedObjectRepresentation attocDevice = null;
                        Map<String, Object> request = new HashMap<String, Object>();
                        request.put("name",
                                "device_" + mapping.externalIdType + "_" + substitute.value);
                        request.put(MappingRepresentation.MAPPING_GENERATED_TEST_DEVICE, null);
                        request.put("c8y_IsDevice", null);
                        request.put("com_cumulocity_model_Agent", null);
                        try {
                            var requestString = objectMapper.writeValueAsString(request);
                            var newPredecessor = context.addRequest(
                                    new C8YRequest(predecessor, RequestMethod.PATCH, device.value.toString(),
                                            mapping.externalIdType, requestString, null, API.INVENTORY, null));
                            attocDevice = c8yAgent.upsertDevice(tenant,
                                    new ID(mapping.externalIdType, substitute.value.toString()), context,
                                    null);
                            var response = objectMapper.writeValueAsString(attocDevice);
                            context.getCurrentRequest().setResponse(response);
                            substitute.value = attocDevice.getId().getValue();
                            predecessor = newPredecessor;
                        } catch (ProcessingException | JsonProcessingException e) {
                            context.getCurrentRequest().setError(e);
                        } catch (dynamic.mapping.processor.ProcessingException e) {
                            context.getCurrentRequest().setError(e);
                        }
                    } else if (sourceId == null && context.isSendPayload()) {
                        throw new RuntimeException(String.format(
                                "External id %s for type %s not found!",
                                substitute.toString(),
                                mapping.externalIdType));
                    } else if (sourceId == null) {
                        substitute.value = null;
                    } else {
                        substitute.value = sourceId.getManagedObject().getId().getValue();
                    }

                }
                substituteValueInPayload(mapping.mappingType, substitute, payloadTarget,
                        mapping.transformGenericPath2C8YPath(pathTarget));
            } else if (!pathsTargetForDeviceIdentifiers.contains(pathTarget)) {
                substituteValueInPayload(mapping.mappingType, substitute, payloadTarget,
                        mapping.transformGenericPath2C8YPath(pathTarget));
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
                }

            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
            }
            predecessor = newPredecessor;
        } else {
            log.warn("Tenant {} - Ignoring payload: {}, {}, {}", tenant, payloadTarget, mapping.targetAPI,
                    context.getProcessingCacheSize());
        }
        log.debug("Tenant {} - Added payload for sending: {}, {}, numberDevices: {}", tenant, payloadTarget,
                mapping.targetAPI,
                size);
        return context;
    }

}