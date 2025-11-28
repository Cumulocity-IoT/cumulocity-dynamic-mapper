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

package dynamic.mapper.processor.inbound.processor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

import com.cumulocity.model.ID;
import com.cumulocity.sdk.client.ProcessingMode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class SubstitutionInboundProcessor extends BaseProcessor {

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private MappingService mappingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.getTesting();

        try {
            validateProcessingCache(context);
            substituteInTargetAndCreateRequests(context, exchange);

            // Check inventory filter condition if specified
            // if (mapping.getFilterInventory() != null && !mapping.getCreateNonExistingDevice()) {
            if (mapping.getFilterInventory() != null) {
                boolean filterInventory = evaluateInventoryFilter(tenant, mapping.getFilterInventory(),
                        context.getSourceId(), context.getTesting());
                if (context.getSourceId() == null
                        || !filterInventory) {
                    if (mapping.getDebug()) {
                        log.info(
                                "{} - Inbound mapping {}/{} not processed, failing Filter inventory execution: filterResult {}",
                                tenant, mapping.getName(), mapping.getIdentifier(),
                                filterInventory);
                    }
                    context.setIgnoreFurtherProcessing(true);
                }
            }
        } catch (Exception e) {
            String errorMessage = String.format("Tenant %s - Error in substitution processor for mapping: %s",
                    tenant, mapping.getName());
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));

            if (!testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
        }

    }

    /**
     * Perform substitution and create C8Y requests
     */
    private void substituteInTargetAndCreateRequests(ProcessingContext<Object> context, Exchange exchange)
            throws Exception {
        Mapping mapping = context.getMapping();

        if (mapping.getTargetTemplate() == null || mapping.getTargetTemplate().trim().isEmpty()) {
            log.warn("No target template defined for mapping: {}", mapping.getName());
            return;
        }

        String targetTemplate = mapping.getTargetTemplate();
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

        if (processingCache == null || processingCache.isEmpty()) {
            log.debug("Processing cache is empty for mapping: {}", mapping.getName());
            // Create single request with original template
            ProcessingResultHelper.createAndAddDynamicMapperRequest(context, targetTemplate, null, mapping);
            return;
        }

        // Determine cardinality based on expandArray values
        List<SubstituteValue> deviceEntries = context.getDeviceEntries();
        int cardinality = deviceEntries.size();
        log.debug("Determined cardinality: {} for mapping: {}", cardinality, mapping.getName());

        for (int i = 0; i < cardinality; i++) {
            try {
                getBuildProcessingContext(context, deviceEntries.get(i),
                        i, deviceEntries.size());
                log.debug("Created request {} of {} for mapping: {}", i + 1, cardinality, mapping.getName());
            } catch (Exception e) {
                log.error("Failed to create request {} for mapping: {}", i, mapping.getName(), e);
                context.addError(new ProcessingException("Failed to create request " + i, e));

                if (!context.getNeedsRepair()) {
                    throw e;
                }
            }

            // Create requests based on cardinality
            // TODO: if (mapping.createNonExistingDevice) process sequentially
            // else clone context and add multiContext to exchange
            // then in pipeline split and process in parallel
            // Set processing mode flag based on createNonExistingDevice
            if (!mapping.getCreateNonExistingDevice()) {
                // Mark for parallel processing
                exchange.getIn().setHeader("parallelProcessing", true);
                log.debug("Marked requests for parallel processing for mapping: {}", mapping.getName());
            } else {
                // Mark for sequential processing
                exchange.getIn().setHeader("parallelProcessing", false);
                log.debug("Marked requests for sequential processing for mapping: {}", mapping.getName());
            }
        }
    }

    private void prepareAndSubstituteInPayload(ProcessingContext<Object> context, DocumentContext payloadTarget,
            String pathTarget, SubstituteValue substitute) {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        if ((Mapping.TOKEN_IDENTITY + ".externalId").equals(pathTarget)) {
            String externalId = substitute.getValue().toString();
            context.setExternalId(externalId);
            ID identity = new ID(mapping.getExternalIdType(), externalId);
            SubstituteValue sourceId = new SubstituteValue(substitute.getValue(),
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            if (!context.getApi().equals(API.INVENTORY)) {
                var resolvedSourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity, context.getTesting());
                if (resolvedSourceId == null) {
                    if (mapping.getCreateNonExistingDevice()) {
                        sourceId.setValue(ProcessingResultHelper.createImplicitDevice(identity, context, log, c8yAgent,
                                objectMapper));
                    }
                } else {
                    sourceId.setValue(resolvedSourceId.getManagedObject().getId().getValue());
                }
                SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                        mapping.transformGenericPath2C8YPath(pathTarget));
                context.setSourceId(sourceId.getValue().toString());
                // DO NOT REMOVE deviceToClient feature currently disabled
                // cache the mapping of device to client ID
                // if (context.getClientId() != null) {
                // configurationRegistry.addOrUpdateClientRelation(tenant,
                // context.getClientId(),
                // sourceId.getValue().toString());
                // }
                substitute.setRepairStrategy(RepairStrategy.CREATE_IF_MISSING);
            }
        } else if ((Mapping.TOKEN_IDENTITY + ".c8ySourceId").equals(pathTarget)) {
            SubstituteValue sourceId = new SubstituteValue(substitute.getValue(),
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            // in this case the device needs to exists beforehand
            SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                    mapping.transformGenericPath2C8YPath(pathTarget));
            context.setSourceId(sourceId.getValue().toString());
            // DO NOT REMOVE deviceToClient feature currently disabled
            // cache the mapping of device to client ID
            // if (context.getClientId() != null) {
            // configurationRegistry.addOrUpdateClientRelation(tenant,
            // context.getClientId(),
            // sourceId.getValue().toString());
            // }
            substitute.setRepairStrategy(RepairStrategy.CREATE_IF_MISSING);
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".api").equals(pathTarget)) {
            context.setApi(API.fromString((String) substitute.getValue()));
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Name").equals(pathTarget)) {
            context.getBinaryInfo().setName((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Type").equals(pathTarget)) {
            context.getBinaryInfo().setType((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Data").equals(pathTarget)) {
            context.getBinaryInfo().setData((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".processingMode").equals(pathTarget)) {
            context.setProcessingMode(ProcessingMode.parse((String) substitute.getValue()));
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".retain").equals(pathTarget)) {
            context.setRetain((boolean) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".deviceName").equals(pathTarget)) {
            context.setDeviceName((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".deviceType").equals(pathTarget)) {
            context.setDeviceType((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA).equals(pathTarget)) {
            // Handle the case where substitute.value is a Map containing context data keys
            if (substitute.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> contextDataMap = (Map<String, Object>) substitute.getValue();

                // Process each key in the map
                for (Map.Entry<String, Object> entry : contextDataMap.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();

                    switch (key) {
                        case "api":
                            if (value instanceof String) {
                                context.setApi(API.fromString((String) value));
                            }
                            break;
                        case "attachment_Name":
                            if (value instanceof String) {
                                context.getBinaryInfo().setName((String) value);
                            }
                            break;
                        case "attachment_Type":
                            if (value instanceof String) {
                                context.getBinaryInfo().setType((String) value);
                            }
                            break;
                        case "attachment_Data":
                            if (value instanceof String) {
                                context.getBinaryInfo().setData((String) value);
                            }
                            break;
                        case "processingMode":
                            if (value instanceof String) {
                                context.setProcessingMode(ProcessingMode.parse((String) substitute.getValue()));
                            }
                            break;
                        case "deviceName":
                            if (value instanceof String) {
                                context.setDeviceName((String) value);
                            }
                            break;
                        case "deviceType":
                            if (value instanceof String) {
                                context.setDeviceType((String) value);
                            }
                            break;
                        default:
                            // Handle unknown keys - you might want to log a warning or ignore
                            // Optional: log.warn("Unknown context data key: {}", key);
                            break;
                    }
                }
            }
        } else {
            SubstituteValue.substituteValueInPayload(substitute, payloadTarget, pathTarget);
        }
    }

    private ProcessingContext<Object> getBuildProcessingContext(ProcessingContext<Object> context,
            SubstituteValue device, int finalI,
            int size) {
        Set<String> pathTargets = context.getPathTargets();
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        DocumentContext payloadTarget = JsonPath.parse(mapping.getTargetTemplate());
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
                        "{} - Processing pathTarget: '{}', repairStrategy: '{}'.",
                        tenant,
                        pathTarget, substitute.repairStrategy);
            }

            prepareAndSubstituteInPayload(context, payloadTarget, pathTarget, substitute);
        }
        ProcessingResultHelper.createAndAddDynamicMapperRequest(context, payloadTarget.jsonString(), null, mapping);
        if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
            log.info("{} - Transformed message sent: API: {}, numberDevices: {}, message: {}", tenant,
                    context.getApi(),
                    payloadTarget.jsonString(),
                    size);
        }
        return context;
    }

}
