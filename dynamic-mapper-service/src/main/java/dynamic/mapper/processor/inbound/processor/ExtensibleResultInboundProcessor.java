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

import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.model.ID;
import com.cumulocity.sdk.client.ProcessingMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractExtensibleResultProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.ExternalIdInfo;
import dynamic.mapper.processor.model.OutputCollector;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingState;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes results from inbound Java Extensions (ProcessorExtensionInbound).
 *
 * <p>This processor converts CumulocityObject[] returned by extensions into
 * DynamicMapperRequest[] for execution by SendInboundProcessor. It handles:</p>
 * <ul>
 *   <li>Device identification and creation (implicit device creation)</li>
 *   <li>External ID resolution</li>
 *   <li>Context data propagation (deviceName, deviceType, processingMode)</li>
 *   <li>Payload serialization and source field injection</li>
 * </ul>
 *
 * <p>Follows the same pattern as FlowResultInboundProcessor for consistency.</p>
 *
 * @see CumulocityObject
 * @see DynamicMapperRequest
 * @see dynamic.mapper.processor.extension.ProcessorExtensionInbound
 */
@Slf4j
@Component
public class ExtensibleResultInboundProcessor extends AbstractExtensibleResultProcessor {

    public ExtensibleResultInboundProcessor(
            MappingService mappingService,
            C8YAgent c8yAgent,
            ObjectMapper objectMapper) {
        super(mappingService, objectMapper, c8yAgent);
    }

    @Override
    protected void processExtensionResults(
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context) throws ProcessingException {
        // Extension results are stored in context by ExtensibleInboundProcessor
        Object extensionResult = context.getExtensionResult();
        String tenant = routing.getTenant();

        if (extensionResult == null) {
            log.debug("{} - No extension result available, skipping extension result processing", tenant);
            state.setIgnoreFurtherProcessing(true);
            return;
        }

        if (!(extensionResult instanceof CumulocityObject[])) {
            log.warn("{} - Extension result is not CumulocityObject[], skipping", tenant);
            state.setIgnoreFurtherProcessing(true);
            return;
        }

        CumulocityObject[] results = (CumulocityObject[]) extensionResult;

        if (results.length == 0) {
            log.info("{} - Extension result is empty, skipping processing", tenant);
            state.setIgnoreFurtherProcessing(true);
            return;
        }

        // Process each CumulocityObject using thread-safe output collector
        for (CumulocityObject c8yObj : results) {
            processCumulocityObject(c8yObj, output, context);
        }

        if (output.getRequests().isEmpty()) {
            log.info("{} - No requests generated from extension result", tenant);
            state.setIgnoreFurtherProcessing(true);
        } else {
            log.info("{} - Generated {} requests from extension result", tenant, output.getRequests().size());
        }
    }

    @Override
    protected void postProcessExtensionResults(ProcessingState state, OutputCollector output,
                                              ProcessingContext<?> context) throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        // Check inventory filter condition if specified
        if (mapping.getFilterInventory() != null) {
            boolean filterInventory = evaluateInventoryFilter(tenant, mapping.getFilterInventory(),
                    context.getSourceId(), context.getTesting());
            if (context.getSourceId() == null || !filterInventory) {
                if (mapping.getDebug()) {
                    log.info(
                            "{} - Inbound mapping {}/{} not processed, failing Filter inventory execution: filterResult {}",
                            tenant, mapping.getName(), mapping.getIdentifier(),
                            filterInventory);
                }
                state.setIgnoreFurtherProcessing(true);
            }
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        int lineNumber = 0;
        if (e.getStackTrace().length > 0) {
            lineNumber = e.getStackTrace()[0].getLineNumber();
        }
        String errorMessage = String.format(
                "%s - Error in ExtensibleResultInboundProcessor: %s for mapping: %s, line %s",
                tenant, mapping.getName(), e.getMessage(), lineNumber);
        log.error(errorMessage, e);

        if (e instanceof ProcessingException) {
            context.addError((ProcessingException) e);
        } else {
            context.addError(new ProcessingException(errorMessage, e));
        }

        if (!context.getTesting()) {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }
    }

    /**
     * NEW: Process a single CumulocityObject using OutputCollector.
     *
     * <p>This method handles:</p>
     * <ul>
     *   <li>Context data extraction (deviceName, deviceType, processingMode)</li>
     *   <li>External ID resolution and device creation</li>
     *   <li>Payload serialization</li>
     *   <li>Request creation</li>
     * </ul>
     *
     * @param c8yObj The CumulocityObject to process
     * @param output Thread-safe output collector
     * @param context The processing context
     * @throws ProcessingException if processing fails
     */
    private void processCumulocityObject(CumulocityObject c8yObj, OutputCollector output,
                                        ProcessingContext<?> context) throws ProcessingException {

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            // Get the API from the cumulocityType
            API targetAPI = APITopicUtil.deriveAPIFromTopic(c8yObj.getCumulocityType().toString());

            // Set API on context for consistency
            context.setApi(targetAPI);

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(c8yObj.getPayload());

            // Apply context data to processing context
            applyContextData(c8yObj.getContextData(), context);

            // Check if sourceId is explicitly set in CumulocityObject
            String resolvedDeviceId;
            List<ExternalId> externalSources = c8yObj.getExternalSource();
            ExternalIdInfo externalIdInfo = ExternalIdInfo.from(externalSources);

            if (externalIdInfo.isPresent()) {
                context.setExternalId(externalIdInfo.getExternalId());
            }

            if (c8yObj.getSourceId() != null && !c8yObj.getSourceId().isEmpty()) {
                // Use explicitly provided sourceId
                resolvedDeviceId = c8yObj.getSourceId();
                context.setSourceId(resolvedDeviceId);
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                log.debug("{} - Using explicit sourceId from CumulocityObject: {}", tenant, resolvedDeviceId);
            } else if ((resolvedDeviceId = resolveDeviceIdentifier(c8yObj, context, tenant)) != null) {
                // Use resolved device ID from externalSource
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                context.setSourceId(resolvedDeviceId);
            } else if (externalSources != null && !externalSources.isEmpty()) {
                // Create implicit device if enabled
                if (mapping.getCreateNonExistingDevice()) {
                    ExternalId externalSource = externalSources.get(0);
                    if (externalSource != null && externalSource.getType() != null
                            && externalSource.getExternalId() != null) {
                        ID identity = new ID(externalSource.getType(), externalSource.getExternalId());
                        String sourceId = ProcessingResultHelper.createImplicitDevice(identity, context, log,
                                c8yAgent, objectMapper);
                        context.setSourceId(sourceId);
                        resolvedDeviceId = sourceId;
                        // Update externalIdInfo with created device info
                        externalIdInfo = ExternalIdInfo.builder()
                                .externalType(externalSource.getType())
                                .externalId(externalSource.getExternalId())
                                .build();
                        context.setExternalId(externalSource.getExternalId());
                        ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, sourceId);
                    }
                } else {
                    // No device ID and not creating implicit devices - skip this message
                    log.warn(
                            "{} - Cannot process message: no device ID resolved and createNonExistingDevice is false for mapping {}",
                            tenant, mapping.getIdentifier());
                    return; // Don't create a request
                }
            } else {
                log.warn("{} - Cannot process message: no external source provided for mapping {}",
                        tenant, mapping.getIdentifier());
                return; // Don't create a request
            }

            // Only create request if we have a resolved device ID
            if (resolvedDeviceId == null) {
                log.warn("{} - Skipping request creation: no device ID available for API {} in mapping {}",
                        tenant, targetAPI.name, mapping.getIdentifier());
                return;
            }

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Determine HTTP method from action
            RequestMethod method = ProcessingResultHelper.mapActionToRequestMethod(c8yObj.getAction());

            // Build the request
            DynamicMapperRequest request = DynamicMapperRequest.builder()
                    .predecessor(context.getCurrentRequest() != null
                            ? context.getCurrentRequest().getPredecessor()
                            : -1)
                    .method(method)
                    .api(targetAPI)
                    .externalId(externalIdInfo.getExternalId())
                    .externalIdType(externalIdInfo.getExternalType())
                    .sourceId(resolvedDeviceId)
                    .request(payloadJson)
                    .build();

            // Add request to thread-safe output collector
            output.addRequest(request);

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}",
                    tenant, targetAPI.name, c8yObj.getAction(), resolvedDeviceId);

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityObject: " + e.getMessage(), e);
        }
    }

    /**
     * Clone a payload object to a Map for modification.
     *
     * @param payload The payload to clone
     * @return A mutable Map representation of the payload
     * @throws ProcessingException if cloning fails
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> clonePayload(Object payload) throws ProcessingException {
        try {
            if (payload instanceof Map) {
                return new java.util.HashMap<>((Map<String, Object>) payload);
            } else {
                // Convert object to map using Jackson
                return objectMapper.convertValue(payload, Map.class);
            }
        } catch (Exception e) {
            throw new ProcessingException("Failed to clone payload: " + e.getMessage(), e);
        }
    }

    /**
     * Apply context data from CumulocityObject to ProcessingContext.
     *
     * <p>Handles special context fields:</p>
     * <ul>
     *   <li>deviceName → context.setDeviceName()</li>
     *   <li>deviceType → context.setDeviceType()</li>
     *   <li>processingMode → context.setProcessingMode()</li>
     *   <li>attachmentName, attachmentType, attachmentData → event attachment handling</li>
     * </ul>
     *
     * @param contextData Map of context data from CumulocityObject
     * @param context Processing context to update
     */
    private void applyContextData(Map<String, String> contextData, ProcessingContext<?> context) {
        if (contextData == null) {
            return;
        }

        if (contextData.containsKey("deviceName")) {
            context.setDeviceName(contextData.get("deviceName"));
        }

        if (contextData.containsKey("deviceType")) {
            context.setDeviceType(contextData.get("deviceType"));
        }

        if (contextData.containsKey("processingMode")) {
            String mode = contextData.get("processingMode");
            context.setProcessingMode(ProcessingMode.parse(mode));
        }

        // Attachment handling for events
        if (contextData.containsKey("attachmentName")) {
            context.getBinaryInfo().setName(contextData.get("attachmentName"));
        }
        if (contextData.containsKey("attachmentType")) {
            context.getBinaryInfo().setType(contextData.get("attachmentType"));
        }
        if (contextData.containsKey("attachmentData")) {
            context.getBinaryInfo().setData(contextData.get("attachmentData"));
        }
    }
}
