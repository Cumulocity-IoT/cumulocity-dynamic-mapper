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

package dynamic.mapping.processor.inbound;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapping.model.Substitution.toPrettyJsonString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.API;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingRepresentation;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import dynamic.mapping.processor.model.SubstituteValue;
import dynamic.mapping.processor.model.SubstituteValue.TYPE;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseProcessorInbound<T> {

    public BaseProcessorInbound(ConfigurationRegistry configurationRegistry) {
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    }

    protected C8YAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected ExecutorService virtualThreadPool;

    public abstract T deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException;

    public abstract void extractFromSource(ProcessingContext<T> context) throws ProcessingException;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void enrichPayload(ProcessingContext<T> context) {
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();

        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
        if (payloadObject instanceof Map) {
            ((Map) payloadObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);
            if (context.isSupportsMessageContext() && context.getKey() != null) {
                String keyString = new String(context.getKey(), StandardCharsets.UTF_8);
                Map contextData = new HashMap<String, String>() {
                    {
                        put(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
                        put("api",
                                context.getMapping().getTargetAPI().toString());
                    }
                };
                ((Map) payloadObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
            }
            // Handle attachment properties independently
            if (context.getMapping().eventWithAttachment) {
                // Get or create the context data map
                Map<String, String> contextData;
                if (((Map) payloadObject).containsKey(Mapping.TOKEN_CONTEXT_DATA)) {
                    contextData = (Map<String, String>) ((Map) payloadObject).get(Mapping.TOKEN_CONTEXT_DATA);
                } else {
                    contextData = new HashMap<>();
                    ((Map) payloadObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
                }

                // Add attachment properties
                contextData.put("attachment_Name", "");
                contextData.put("attachment_Type", "");
                contextData.put("attachment_Data", "");
            }
        } else {
            log.info(
                    "Tenant {} - This message is not parsed by Base Inbound Processor, will be potentially parsed by extension due to custom format.",
                    tenant);
        }
    }

    public void validateProcessingCache(ProcessingContext<T> context) {
        // if there are too few devices identified, then we replicate the first device
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        String entryWithMaxSubstitutes = processingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();
        int countMaxEntries = processingCache.get(entryWithMaxSubstitutes).size();

        List<String> pathsTargetForDeviceIdentifiers = context.getPathsTargetForDeviceIdentifiers();
        String firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.size() > 0
                ? pathsTargetForDeviceIdentifiers.get(0)
                : null;

        List<SubstituteValue> deviceEntries = processingCache
                .get(firstPathTargetForDeviceIdentifiers);
        SubstituteValue toDuplicate = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxEntries) {
            deviceEntries.add(toDuplicate);
        }
    }

    public void substituteInTargetAndSend(ProcessingContext<T> context) {
        /*
         * step 3 replace target with extract content from inbound payload
         */
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        List<SubstituteValue> deviceEntries = context.getDeviceEntries();
        // if devices have to be created implicitly, then request have to b process in
        // sequence, other multiple threads will try to create a device with the same
        // externalId
        if (mapping.createNonExistingDevice) {
            for (int i = 0; i < deviceEntries.size(); i++) {
                // for (MappingSubstituteValue device : deviceEntries) {
                getBuildProcessingContext(context, deviceEntries.get(i),
                        i, deviceEntries.size());
            }
            log.info("Tenant {} - Completed context, processing sequentially, createNonExistingDevice: {}", tenant,
                    mapping.createNonExistingDevice);
        } else {
            List<Future<ProcessingContext<T>>> contextFutureList = new ArrayList<>();
            for (int i = 0; i < deviceEntries.size(); i++) {
                // for (MappingSubstituteValue device : deviceEntries) {
                int finalI = i;
                contextFutureList.add(virtualThreadPool.submit(() -> {
                    return getBuildProcessingContext(context, deviceEntries.get(finalI),
                            finalI, deviceEntries.size());
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
            log.info("Tenant {} - Completed context, processing in parallel {} requests", tenant, j);
        }
    }

    private ProcessingContext<T> getBuildProcessingContext(ProcessingContext<T> context,
            SubstituteValue device, int finalI,
            int size) {
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
                        "Tenant {} - Processing pathTarget: '{}', repairStrategy: '{}'.",
                        tenant,
                        pathTarget, substitute.repairStrategy);
            }

            /*
             * step 4 resolve externalIds to c8ySourceIds and create attroc devices
             */
            // check if the targetPath == externalId and we need to resolve an external id
            prepareAndSubstituteInPayload(context, payloadTarget, pathTarget, substitute);
        }
        /*
         * step 5 prepare target payload for sending to c8y
         */
        if (context.getApi().equals(API.INVENTORY)) {
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
                ManagedObjectRepresentation attocDevice = c8yAgent.upsertDevice(tenant,
                        identity, context);
                var response = objectMapper.writeValueAsString(attocDevice);
                context.getCurrentRequest().setResponse(response);
                context.getCurrentRequest().setSourceId(attocDevice.getId().getValue());
            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
            }
            predecessor = newPredecessor;
        } else if (!context.getApi().equals(API.INVENTORY)) {
            AbstractExtensibleRepresentation attocRequest = null;
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor, RequestMethod.POST, device.value.toString(),
                            mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, context.getApi(), null));
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
            log.warn("Tenant {} - Ignoring payload: {}, {}, {}", tenant, payloadTarget, context.getApi(),
                    context.getProcessingCacheSize());
        }
        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("Tenant {} - Payload was sent: {}, API: {}, numberDevices: {}", tenant,
                    payloadTarget.jsonString(),
                    context.getApi(),
                    size);
        }
        return context;
    }

    private void prepareAndSubstituteInPayload(ProcessingContext<T> context, DocumentContext payloadTarget,
            String pathTarget, SubstituteValue substitute) {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        if ((Mapping.TOKEN_IDENTITY + ".externalId").equals(pathTarget)) {
            ID identity = new ID(mapping.externalIdType, substitute.value.toString());
            SubstituteValue sourceId = new SubstituteValue(substitute.value,
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            if (!context.getApi().equals(API.INVENTORY)) {
                var resolvedSourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity, context);
                if (resolvedSourceId == null) {
                    if (mapping.createNonExistingDevice) {
                        sourceId.value = createAttocDevice(identity, context);
                    }
                } else {
                    sourceId.value = resolvedSourceId.getManagedObject().getId().getValue();
                }
                SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                        mapping.transformGenericPath2C8YPath(pathTarget));
                context.setSourceId(sourceId.value.toString());
                substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
            }
        } else if ((Mapping.TOKEN_IDENTITY + ".c8ySourceId").equals(pathTarget)) {
            SubstituteValue sourceId = new SubstituteValue(substitute.value,
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            // in this case the device needs to exists beforehand
            SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                    mapping.transformGenericPath2C8YPath(pathTarget));
            context.setSourceId(sourceId.value.toString());
            substitute.repairStrategy = RepairStrategy.CREATE_IF_MISSING;
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".api").equals(pathTarget)) {
            context.setApi(API.fromString((String) substitute.value));
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Name").equals(pathTarget)) {
            context.getBinaryInfo().setName((String) substitute.value);
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Type").equals(pathTarget)) {
            context.getBinaryInfo().setType((String) substitute.value);
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Data").equals(pathTarget)) {
            context.getBinaryInfo().setData((String) substitute.value);
        } else {
            SubstituteValue.substituteValueInPayload(substitute, payloadTarget, pathTarget);
        }
    }

    protected String createAttocDevice(ID identity, ProcessingContext<T> context) {
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
            ManagedObjectRepresentation attocDevice = c8yAgent.upsertDevice(context.getTenant(),
                    identity, context);
            var response = objectMapper.writeValueAsString(attocDevice);
            context.getCurrentRequest().setResponse(response);
            context.getCurrentRequest().setSourceId(attocDevice.getId().getValue());
            return attocDevice.getId().getValue();
        } catch (ProcessingException | JsonProcessingException e) {
            context.getCurrentRequest().setError(e);
        }
        return null;
    }

    public void applyFilter(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        String mappingFilter = context.getMapping().getFilterMapping();
        if (mappingFilter != null && !("").equals(mappingFilter)) {
            Object payloadObjectNode = context.getPayload();
            String payload = toPrettyJsonString(payloadObjectNode);
            try {
                var expr = jsonata(mappingFilter);
                Object extractedSourceContent = expr.evaluate(payloadObjectNode);
                if (!isNodeTrue(extractedSourceContent)) {
                    log.info("Tenant {} - Payload will be ignored due to filter: {}, {}", tenant, mappingFilter,
                            payload);
                    context.setIgnoreFurtherProcessing(true);
                }
            } catch (Exception e) {
                log.error("Tenant {} - Exception for: {}, {}: ", tenant, mappingFilter,
                        payload, e);
            }
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
}