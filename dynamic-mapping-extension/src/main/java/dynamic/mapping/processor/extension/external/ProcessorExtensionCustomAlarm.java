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

package dynamic.mapping.processor.extension.external;

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
import dynamic.mapping.processor.model.SubstituteValue.TYPE;
import dynamic.mapping.processor.model.SubstituteValue;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.extension.ProcessorExtensionTarget;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import dynamic.mapping.processor.ProcessingException;

import dynamic.mapping.core.C8YAgent;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.web.bind.annotation.RequestMethod;

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

            context.addSubstitution("time", new DateTime(
                    jsonObject.get("time"))
                    .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("type",
                    jsonObject.get("type")
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            Object se = jsonObject.get("alarmType");
            context.addSubstitution("severity", se.toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            Object message = jsonObject.get("message");
            context.addSubstitution("text", message.toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addSubstitution(context.getMapping().getGenericDeviceIdentifier(),
                    jsonObject.get("externalId")
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

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
        List<SubstituteValue> deviceEntries = context.getDeviceEntries();

        for (int i = 0; i < deviceEntries.size(); i++) {
            // for (SubstituteValue device : deviceEntries) {
            getBuildProcessingContext(context, deviceEntries.get(i),
                    i, deviceEntries.size(), c8yAgent);
        }
        log.info("Tenant {} - Context is completed, sequentially processed, createNonExistingDevice: {} !", tenant,
                mapping.createNonExistingDevice);

    }

    private ProcessingContext<byte[]> getBuildProcessingContext(ProcessingContext<byte[]> context,
            SubstituteValue device, int finalI,
            int size, C8YAgent c8yAgent) {
        Set<String> pathTargets = context.getPathTargets();
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.targetTemplate);
        for (String pathTarget : pathTargets) {
            SubstituteValue substitute = new SubstituteValue(
                    "NOT_DEFINED", TYPE.TEXTUAL,
                    RepairStrategy.DEFAULT, false);
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

            /*
             * step 4 resolve externalIds to c8ySourceIds and create implicit devices
             */
            // check if the targetPath == externalId and we need to resolve an external id
            prepareAndSubstituteInPayload(context, payloadTarget, pathTarget, substitute, c8yAgent);
        }
        /*
         * step 5 prepare target payload for sending to c8y
         */
        if (mapping.targetAPI.equals(API.INVENTORY)) {
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor,
                            context.getMapping().updateExistingDevice ? RequestMethod.POST : RequestMethod.PATCH,
                            device.value.toString(),
                            mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, API.INVENTORY, null));
            try {
                ID identity = new ID(mapping.externalIdType, device.value.toString());
                ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant,
                        identity, context);
                context.setSourceId(sourceId.getManagedObject().getId().getValue());
                ManagedObjectRepresentation implicitDevice = c8yAgent.upsertDevice(tenant,
                        identity, context);
                var response = objectMapper.writeValueAsString(implicitDevice);
                context.getCurrentRequest().setResponse(response);
                context.getCurrentRequest().setSourceId(implicitDevice.getId().getValue());
            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
            }
            predecessor = newPredecessor;
        } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
            AbstractExtensibleRepresentation implicitRequest = null;
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor, RequestMethod.POST, device.value.toString(),
                            mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, mapping.targetAPI, null));
            try {
                if (context.isSendPayload()) {
                    c8yAgent.createMEAO(context);
                    String response = objectMapper.writeValueAsString(implicitRequest);
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
        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("Tenant {} - Added payload for sending: {}, {}, numberDevices: {}", tenant,
                    payloadTarget.jsonString(),
                    mapping.targetAPI,
                    size);
        }

        return context;
    }

    private void prepareAndSubstituteInPayload(ProcessingContext<byte[]> context, DocumentContext payloadTarget,
            String pathTarget, SubstituteValue substitute, C8YAgent c8yAgent) {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        if ((Mapping.IDENTITY + ".externalId").equals(pathTarget)) {
            ID identity = new ID(mapping.externalIdType, substitute.value.toString());
            SubstituteValue sourceId = new SubstituteValue(substitute.value,
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            if (!mapping.targetAPI.equals(API.INVENTORY)) {
                var resolvedSourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity, context);
                if (resolvedSourceId == null) {
                    if (mapping.createNonExistingDevice) {
                        sourceId.value = createImplicitDevice(identity, context, c8yAgent);
                    }
                } else {
                    sourceId.value = resolvedSourceId.getManagedObject().getId().getValue();
                }
                SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                        mapping.transformGenericPath2C8YPath(pathTarget));
                context.setSourceId(sourceId.value.toString());
                substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
            }
        } else if ((Mapping.IDENTITY + ".c8ySourceId").equals(pathTarget)) {
            SubstituteValue sourceId = new SubstituteValue(substitute.value,
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            // in this case the device needs to exists beforehand
            SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                    mapping.transformGenericPath2C8YPath(pathTarget));
            context.setSourceId(sourceId.value.toString());
            substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
        } else {
            SubstituteValue.substituteValueInPayload(substitute, payloadTarget, pathTarget);
        }
    }

    private String createImplicitDevice(ID identity, ProcessingContext<byte[]> context, C8YAgent c8yAgent) {
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("name",
                "device_" + identity.getType() + "_" + identity.getValue());
        request.put(MappingRepresentation.MAPPING_GENERATED_TEST_DEVICE, null);
        request.put("c8y_IsDevice", null);
        request.put("com_cumulocity_model_Agent", null);
        try {
            var predecessor = context.getRequests().size();
            var requestString = objectMapper.writeValueAsString(request);
            context.addRequest(
                    new C8YRequest(predecessor,
                            context.getMapping().updateExistingDevice ? RequestMethod.POST : RequestMethod.PATCH, null,
                            context.getMapping().externalIdType, requestString, null, API.INVENTORY, null));
            ManagedObjectRepresentation implicitDevice = c8yAgent.upsertDevice(context.getTenant(),
                    identity, context);
            var response = objectMapper.writeValueAsString(implicitDevice);
            context.getCurrentRequest().setResponse(response);
            context.getCurrentRequest().setSourceId(implicitDevice.getId().getValue());
            return implicitDevice.getId().getValue();
        } catch (ProcessingException | JsonProcessingException e) {
            context.getCurrentRequest().setError(e);
        }
        return null;
    }

}