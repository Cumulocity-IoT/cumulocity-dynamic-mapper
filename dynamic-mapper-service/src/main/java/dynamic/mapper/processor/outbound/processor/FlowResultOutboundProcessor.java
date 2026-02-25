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
package dynamic.mapper.processor.outbound.processor;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractFlowResultProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalIdInfo;
import dynamic.mapper.processor.model.OutputCollector;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingState;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Slf4j
@Component
public class FlowResultOutboundProcessor extends AbstractFlowResultProcessor {

    protected static final String EXTERNAL_ID_TOKEN = "_externalId_";

    public FlowResultOutboundProcessor(
            MappingService mappingService,
            ObjectMapper objectMapper) {
        super(mappingService, objectMapper);
    }

    @Override
    protected void processMessage(
            Object message,
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context) throws ProcessingException {
        String tenant = routing.getTenant();
        Mapping mapping = context.getMapping();

        if (message instanceof DeviceMessage) {
            processDeviceMessage((DeviceMessage) message, routing, state, output, context, tenant, mapping);
        } else if (message instanceof CumulocityObject) {
            processCumulocityObject((CumulocityObject) message, routing, state, output, context, tenant, mapping);
        } else {
            log.debug("{} - Message is not a recognized type, skipping: {}", tenant,
                    message.getClass().getSimpleName());
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        int lineNumber = 0;
        if (e.getStackTrace().length > 0) {
            lineNumber = e.getStackTrace()[0].getLineNumber();
        }
        String errorMessage = String.format(
                "Tenant %s - Error in FlowResultOutboundProcessor: %s for mapping: %s, line %s",
                tenant, mapping.getName(), e.getMessage(), lineNumber);
        log.error(errorMessage, e);

        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    /**
     * NEW: Process DeviceMessage using focused contexts.
     */
    private void processDeviceMessage(
            DeviceMessage deviceMessage,
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context,
            String tenant,
            Mapping mapping) throws ProcessingException {

        try {

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(deviceMessage.getPayload());

            // Check if sourceId is explicitly set in DeviceMessage
            String resolvedExternalId;
            if (deviceMessage.getSourceId() != null && !deviceMessage.getSourceId().isEmpty()) {
                // Use explicitly provided sourceId
                resolvedExternalId = deviceMessage.getSourceId();
                context.setSourceId(resolvedExternalId);
                log.debug("{} - Using explicit sourceId from DeviceMessage: {}", tenant, resolvedExternalId);
            } else {
                // Resolve device ID using existing logic
                resolvedExternalId = context.getSourceId();  // defaults to sourceId in context
                log.debug("{} - Initial context.sourceId before resolution: {}", tenant, resolvedExternalId);

                try {
                    resolvedExternalId = resolveGlobalId2ExternalId(deviceMessage, context, tenant);
                    context.setSourceId(resolvedExternalId);
                    log.debug("{} - Resolved external ID: {}", tenant, resolvedExternalId);
                } catch (ProcessingException e) {
                    log.warn("{} - Could not resolve external ID for device message: {}", tenant, e.getMessage());
                    // Fall back to context sourceId if resolution failed
                    if (resolvedExternalId == null || resolvedExternalId.isEmpty()) {
                        resolvedExternalId = context.getSourceId();
                        log.warn("{} - Using context sourceId as fallback: {}", tenant, resolvedExternalId);
                    }
                }

                log.debug("{} - Final resolvedExternalId to be used: {}", tenant, resolvedExternalId);
            }

            // Set resolved publish topic (from substituteInTargetAndSend logic)
            setResolvedPublishTopic(context, payload);

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Create the request - pass action from DeviceMessage for WebHook internal mode
            DynamicMapperRequest request = ProcessingResultHelper.createAndAddDynamicMapperRequest(context,
                    payloadJson, deviceMessage.getAction(), mapping);
            // Add to thread-safe output collector
            output.addRequest(request);

            // Override resolvedPublishTopic if DeviceMessage provides a topic
            String publishTopic = deviceMessage.getTopic();

            if (publishTopic != null && !publishTopic.isEmpty()) {
                // Handle EXTERNAL_ID_TOKEN replacement in the topic
                if (publishTopic.contains(EXTERNAL_ID_TOKEN)) {
                    if (resolvedExternalId != null) {
                        publishTopic = publishTopic.replace(EXTERNAL_ID_TOKEN, resolvedExternalId);
                    } else {
                        log.warn("{} - Publish topic contains {} token but external ID could not be resolved",
                                tenant, EXTERNAL_ID_TOKEN);
                        // Optionally: skip processing or use a default value
                        // return; // Uncomment to skip processing if external ID is required
                    }
                }
                // Override the resolved publish topic with the one from DeviceMessage
                context.setResolvedPublishTopic(publishTopic);
            }
            // If publishTopic is null or empty, keep the resolved topic from mapping (set at line 121)

            // set key for Kafka messages
            if (deviceMessage.getTransportFields() != null) {
                context.setKey(deviceMessage.getTransportFields().get(Mapping.CONTEXT_DATA_KEY_NAME));
            }

            // Derive API: prioritize cumulocityType from DeviceMessage, fallback to deriving from topic
            API derivedAPI = null;
            if (deviceMessage.getCumulocityType() != null) {
                // Use explicitly specified cumulocityType from DeviceMessage
                derivedAPI = APITopicUtil.deriveAPIFromTopic(deviceMessage.getCumulocityType().toString());
                if (derivedAPI != null) {
                    request.setApi(derivedAPI);
                    log.debug("{} - Using API {} from DeviceMessage.cumulocityType for DeviceMessage",
                            tenant, derivedAPI.name);
                }
            } else if (context.getResolvedPublishTopic() != null && !context.getResolvedPublishTopic().isEmpty()) {
                // Fallback: derive API from resolved publishTopic
                derivedAPI = APITopicUtil.deriveAPIFromTopic(context.getResolvedPublishTopic());
                if (derivedAPI != null) {
                    request.setApi(derivedAPI);
                    log.debug("{} - Derived API {} from resolved topic '{}' for DeviceMessage",
                            tenant, derivedAPI.name, context.getResolvedPublishTopic());
                }
            }

            // Set publishTopic on request from resolved topic
            request.setPublishTopic(context.getResolvedPublishTopic());

            // Set sourceId on request BEFORE calling populateSourceIdentifier
            // (populateSourceIdentifier needs request.getSourceId() to be set)
            request.setSourceId(resolvedExternalId);

            // Populate Cumulocity-specific request with source identifier
            if (request.getApi() != null && resolvedExternalId != null && !resolvedExternalId.isEmpty()) {
                log.info("{} - Populating source identifier: resolvedExternalId={}, api.identifier={}",
                        tenant, resolvedExternalId, request.getApi().identifier);
                String cumulocityPayload = populateSourceIdentifier(payloadJson, request, tenant);
                request.setRequestCumulocity(cumulocityPayload);
                log.info("{} - Set requestCumulocity: {}", tenant, cumulocityPayload);
            } else {
                log.warn("{} - Skipping populateSourceIdentifier: api={}, resolvedExternalId={}",
                        tenant, request.getApi(), resolvedExternalId);
            }

            // For PUT/PATCH/DELETE methods, append ID to path and remove from body
            log.info("{} - Checking PUT/PATCH/DELETE adjustment: api={}, apiName={}, resolvedExternalId={}, method={}, action={}, publishTopic={}",
                    tenant, request.getApi(), request.getApi() != null ? request.getApi().name : "null",
                    resolvedExternalId, request.getMethod(), deviceMessage.getAction(), context.getResolvedPublishTopic());

            // Special case: Measurements don't support PUT/PATCH - they are immutable time-series data
            if (request.getApi() == API.MEASUREMENT &&
                (request.getMethod() == RequestMethod.PUT || request.getMethod() == RequestMethod.PATCH)) {
                log.warn("{} - Measurements are immutable and don't support PUT/PATCH. " +
                        "Converting to POST to create a new measurement instead. Use action='create' to avoid this warning.",
                        tenant);
                // Convert PUT/PATCH to POST (create new measurement)
                request.setMethod(RequestMethod.POST);
                // For POST, use the base API path without ID in pathCumulocity
                request.setPathCumulocity(request.getApi().path);
                log.info("{} - ✅ Converted measurement from {} to POST, using base path: {}",
                        tenant, request.getMethod(), request.getApi().path);
                // The ID will remain in the body which is correct for POST
            } else if (request.getApi() != null && resolvedExternalId != null &&
                (request.getMethod() == RequestMethod.PUT ||
                 request.getMethod() == RequestMethod.PATCH ||
                 request.getMethod() == RequestMethod.DELETE)) {

                String pathWithId = request.getApi().path + "/" + resolvedExternalId;
                request.setPathCumulocity(pathWithId);

                // Remove ID from the Cumulocity request body
                if (request.getRequestCumulocity() != null) {
                    String bodyWithoutId = removeIdentifierFromPayload(request.getRequestCumulocity(),
                                                                        request.getApi().identifier, tenant);
                    request.setRequestCumulocity(bodyWithoutId);
                }

                log.info("{} - ✅ Adjusted pathCumulocity for {} method: {} -> {}",
                        tenant, request.getMethod(), request.getApi().path, pathWithId);
            } else if (request.getMethod() == RequestMethod.POST) {
                // For POST requests, use the base API path without ID in pathCumulocity
                // This overrides any ID that JavaScript may have appended to the topic
                if (request.getApi() != null) {
                    request.setPathCumulocity(request.getApi().path);
                    log.info("{} - ✅ Using base API path for POST in pathCumulocity: {}", tenant, request.getApi().path);
                }
            }

            context.setRetain(deviceMessage.getRetain());

            log.debug("{} - Created outbound request: deviceId={}, topic={}",
                    tenant, resolvedExternalId != null ? resolvedExternalId : "unresolved",
                    context.getResolvedPublishTopic());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process DeviceMessage: " + e.getMessage(), e);
        }
    }

    /**
     * NEW: Process CumulocityObject using focused contexts.
     */
    private void processCumulocityObject(
            CumulocityObject cumulocityMessage,
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context,
            String tenant,
            Mapping mapping) throws ProcessingException {

        try {
            // Get the API from the cumulocityType using unified API derivation
            API targetAPI = APITopicUtil.deriveAPIFromTopic(cumulocityMessage.getCumulocityType().toString());

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // Check if sourceId is explicitly set in CumulocityObject
            String resolvedDeviceId;
            ExternalIdInfo externalIdInfo = ExternalIdInfo.from(cumulocityMessage.getExternalSource());

            if (externalIdInfo.isPresent()) {
                context.setExternalId(externalIdInfo.getExternalId());
            }

            if (cumulocityMessage.getSourceId() != null && !cumulocityMessage.getSourceId().isEmpty()) {
                // Use explicitly provided sourceId
                resolvedDeviceId = cumulocityMessage.getSourceId();
                context.setSourceId(resolvedDeviceId);
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                log.debug("{} - Using explicit sourceId from CumulocityObject: {}", tenant, resolvedDeviceId);
            } else if ((resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant)) != null) {
                // Use resolved device ID from externalSource
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                context.setSourceId(resolvedDeviceId);
            } else if (externalIdInfo.isPresent()) {
                // No device ID and not creating implicit devices - skip this message
                log.warn(
                        "{} - Cannot process message: no device ID resolved and createNonExistingDevice is false for mapping {}",
                        tenant, mapping.getIdentifier());
                return; // Don't create a request
            } else {
                log.warn("{} - Cannot process message: no external source provided for mapping {}",
                        tenant, mapping.getIdentifier());
                return; // Don't create a request
            }

            // Set resolved publish topic (from substituteInTargetAndSend logic)
            setResolvedPublishTopic(context, payload);

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            DynamicMapperRequest c8yRequest = ProcessingResultHelper.createAndAddDynamicMapperRequest(context,
                    payloadJson,
                    cumulocityMessage.getAction(), mapping);
            // Add to thread-safe output collector
            output.addRequest(c8yRequest);
            c8yRequest.setApi(targetAPI);
            c8yRequest.setSourceId(resolvedDeviceId);
            c8yRequest.setExternalIdType(externalIdInfo.getExternalType());
            c8yRequest.setExternalId(externalIdInfo.getExternalId());

            // Set the publishTopic on the request object
            if (context.getResolvedPublishTopic() != null) {
                c8yRequest.setPublishTopic(context.getResolvedPublishTopic());
            }

            // Set sourceId on request BEFORE calling populateSourceIdentifier
            // (populateSourceIdentifier needs request.getSourceId() to be set)
            c8yRequest.setSourceId(resolvedDeviceId);

            // Populate Cumulocity-specific request with source identifier
            if (c8yRequest.getApi() != null && resolvedDeviceId != null && !resolvedDeviceId.isEmpty()) {
                log.info("{} - Populating source identifier: resolvedDeviceId={}, api.identifier={}",
                        tenant, resolvedDeviceId, c8yRequest.getApi().identifier);
                String cumulocityPayload = populateSourceIdentifier(payloadJson, c8yRequest, tenant);
                c8yRequest.setRequestCumulocity(cumulocityPayload);
                log.info("{} - Set requestCumulocity: {}", tenant, cumulocityPayload);
            } else {
                log.warn("{} - Skipping populateSourceIdentifier: api={}, resolvedDeviceId={}",
                        tenant, c8yRequest.getApi(), resolvedDeviceId);
            }

            // For PUT/PATCH/DELETE methods, append ID to path and remove from body
            log.info("{} - Checking PUT/PATCH/DELETE adjustment: api={}, apiName={}, resolvedDeviceId={}, method={}, action={}, publishTopic={}",
                    tenant, c8yRequest.getApi(), c8yRequest.getApi() != null ? c8yRequest.getApi().name : "null",
                    resolvedDeviceId, c8yRequest.getMethod(), cumulocityMessage.getAction(),
                    context.getResolvedPublishTopic());

            // Special case: Measurements don't support PUT/PATCH - they are immutable time-series data
            if (c8yRequest.getApi() == API.MEASUREMENT &&
                (c8yRequest.getMethod() == RequestMethod.PUT || c8yRequest.getMethod() == RequestMethod.PATCH)) {
                log.warn("{} - Measurements are immutable and don't support PUT/PATCH. " +
                        "Converting to POST to create a new measurement instead. Use action='create' to avoid this warning.",
                        tenant);
                // Convert PUT/PATCH to POST (create new measurement)
                c8yRequest.setMethod(RequestMethod.POST);
                // For POST, use the base API path without ID in pathCumulocity
                c8yRequest.setPathCumulocity(c8yRequest.getApi().path);
                log.info("{} - ✅ Converted measurement from {} to POST, using base path: {}",
                        tenant, c8yRequest.getMethod(), c8yRequest.getApi().path);
                // The ID will remain in the body which is correct for POST
            } else if (c8yRequest.getApi() != null && resolvedDeviceId != null &&
                (c8yRequest.getMethod() == RequestMethod.PUT ||
                 c8yRequest.getMethod() == RequestMethod.PATCH ||
                 c8yRequest.getMethod() == RequestMethod.DELETE)) {

                String pathWithId = c8yRequest.getApi().path + "/" + resolvedDeviceId;
                c8yRequest.setPathCumulocity(pathWithId);

                // Remove ID from the Cumulocity request body
                if (c8yRequest.getRequestCumulocity() != null) {
                    String bodyWithoutId = removeIdentifierFromPayload(c8yRequest.getRequestCumulocity(),
                                                                        c8yRequest.getApi().identifier, tenant);
                    c8yRequest.setRequestCumulocity(bodyWithoutId);
                }

                log.info("{} - ✅ Adjusted pathCumulocity for {} method: {} -> {}",
                        tenant, c8yRequest.getMethod(), c8yRequest.getApi().path, pathWithId);
            } else if (c8yRequest.getMethod() == RequestMethod.POST) {
                // For POST requests, use the base API path without ID in pathCumulocity
                // This overrides any ID that JavaScript may have appended to the topic
                if (c8yRequest.getApi() != null) {
                    c8yRequest.setPathCumulocity(c8yRequest.getApi().path);
                    log.info("{} - ✅ Using base API path for POST in pathCumulocity: {}", tenant, c8yRequest.getApi().path);
                }
            }

            context.setRetain(c8yRequest.getRetain());

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}, topic={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId,
                    context.getResolvedPublishTopic());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityObject: " + e.getMessage(), e);
        }
    }

    /**
     * Set resolved publish topic based on topic levels in payload
     * Logic taken from BaseProcessorOutbound.substituteInTargetAndSend
     */
    private void setResolvedPublishTopic(ProcessingContext<?> context, Map<String, Object> payload) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        // Check if payload contains topic levels (similar to substituteInTargetAndSend)
        @SuppressWarnings("unchecked")
        List<String> topicLevels = (List<String>) payload.get(Mapping.TOKEN_TOPIC_LEVEL);

        if (topicLevels != null && topicLevels.size() > 0) {
            // Merge the replaced topic levels (logic from substituteInTargetAndSend)
            MutableInt c = new MutableInt(0);
            String[] splitTopicInAsList = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());
            String[] splitTopicInAsListOriginal = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());

            topicLevels.forEach(tl -> {
                while (c.intValue() < splitTopicInAsList.length
                        && ("/".equals(splitTopicInAsList[c.intValue()]) && c.intValue() > 0)) {
                    c.increment();
                }
                splitTopicInAsList[c.intValue()] = tl;
                c.increment();
            });

            if (mapping.getDebug() || context.getServiceConfiguration().getLogPayload()) {
                log.info("{} - Resolved topic from {} to {}",
                        tenant, splitTopicInAsListOriginal, splitTopicInAsList);
            }

            StringBuilder resolvedPublishTopic = new StringBuilder();
            for (String topicPart : splitTopicInAsList) {
                resolvedPublishTopic.append(topicPart);
            }
            context.setResolvedPublishTopic(resolvedPublishTopic.toString());

            // Remove TOPIC_LEVEL from payload (as done in substituteInTargetAndSend)
            payload.remove(Mapping.TOKEN_TOPIC_LEVEL);
        } else {
            // Use the mapping's publish topic as fallback
            context.setResolvedPublishTopic(mapping.getPublishTopic());
        }

        // Handle context data for message context support
        @SuppressWarnings("unchecked")
        Map<String, String> contextData = (Map<String, String>) payload.get(Mapping.TOKEN_CONTEXT_DATA);

        if (contextData != null) {
            // Extract key for message context
            String key = contextData.get(Mapping.CONTEXT_DATA_KEY_NAME);
            if (key != null && !key.equals("dummy")) {
                context.setKey(key);
            }

            // Extract publish topic override
            String publishTopic = contextData.get("publishTopic");
            if (publishTopic != null && !publishTopic.equals("")) {
                context.setTopic(publishTopic);
                context.setResolvedPublishTopic(publishTopic);
            }

            // Remove TOKEN_CONTEXT_DATA from payload
            payload.remove(Mapping.TOKEN_CONTEXT_DATA);
        }
    }

    /**
     * Populate source identifier in the payload for Cumulocity API requests.
     * Uses API.identifier field to determine which field name to populate (e.g., "source.id", "id", "deviceId")
     * and sets it to the value from request.getSourceId().
     *
     * @param payload The JSON payload as a string
     * @param request The DynamicMapperRequest containing API and sourceId information
     * @param tenant The tenant identifier for logging
     * @return The modified payload with source identifier populated, or original payload on error
     */
    private String populateSourceIdentifier(String payload, DynamicMapperRequest request, String tenant) {
        // Only populate if we have both API info and sourceId
        if (request.getApi() == null || request.getSourceId() == null || request.getSourceId().isEmpty()) {
            return payload;
        }

        String identifier = request.getApi().identifier;
        String sourceId = request.getSourceId();

        try {
            // Parse the JSON payload
            JsonNode jsonNode = objectMapper.readTree(payload);

            if (!(jsonNode instanceof ObjectNode)) {
                log.warn("{} - Payload is not a JSON object, cannot populate source identifier",
                        tenant);
                return payload;
            }

            ObjectNode objectNode = (ObjectNode) jsonNode;

            // Handle hierarchical identifiers like "source.id"
            if (identifier.contains(".")) {
                String[] parts = identifier.split("\\.");
                ObjectNode currentNode = objectNode;

                // Navigate/create nested structure
                for (int i = 0; i < parts.length - 1; i++) {
                    String part = parts[i];
                    if (!currentNode.has(part) || !currentNode.get(part).isObject()) {
                        currentNode.set(part, objectMapper.createObjectNode());
                    }
                    currentNode = (ObjectNode) currentNode.get(part);
                }

                // Set the final value
                String lastPart = parts[parts.length - 1];
                currentNode.put(lastPart, sourceId);
            } else {
                // Simple identifier - set directly
                objectNode.put(identifier, sourceId);
            }

            String modifiedPayload = objectMapper.writeValueAsString(objectNode);

            log.debug("{} - Populated {} with value {}",
                    tenant, identifier, sourceId);

            return modifiedPayload;

        } catch (Exception e) {
            log.warn("{} - Failed to populate source identifier in payload: {}",
                    tenant, e.getMessage());
            return payload; // Return original payload on error
        }
    }

    /**
     * Remove identifier field from the payload for PUT/PATCH/DELETE operations.
     * For these methods, the ID is in the URL path and should not be in the body.
     *
     * @param payload The JSON payload as a string
     * @param identifier The identifier field to remove (e.g., "id", "source.id")
     * @param tenant The tenant identifier for logging
     * @return The modified payload with identifier removed, or original payload on error
     */
    private String removeIdentifierFromPayload(String payload, String identifier, String tenant) {
        try {
            // Parse the JSON payload
            JsonNode jsonNode = objectMapper.readTree(payload);

            if (!(jsonNode instanceof ObjectNode)) {
                log.warn("{} - Payload is not a JSON object, cannot remove identifier",
                        tenant);
                return payload;
            }

            ObjectNode objectNode = (ObjectNode) jsonNode;

            // Handle hierarchical identifiers like "source.id"
            if (identifier.contains(".")) {
                String[] parts = identifier.split("\\.");
                ObjectNode currentNode = objectNode;

                // Navigate to the parent node
                for (int i = 0; i < parts.length - 1; i++) {
                    String part = parts[i];
                    if (!currentNode.has(part) || !currentNode.get(part).isObject()) {
                        // Path doesn't exist, nothing to remove
                        return payload;
                    }
                    currentNode = (ObjectNode) currentNode.get(part);
                }

                // Remove the final field
                String lastPart = parts[parts.length - 1];
                currentNode.remove(lastPart);
            } else {
                // Simple identifier - remove directly
                objectNode.remove(identifier);
            }

            String modifiedPayload = objectMapper.writeValueAsString(objectNode);

            log.debug("{} - Removed {} from payload for PUT/PATCH/DELETE request",
                    tenant, identifier);

            return modifiedPayload;

        } catch (Exception e) {
            log.warn("{} - Failed to remove identifier from payload: {}",
                    tenant, e.getMessage());
            return payload; // Return original payload on error
        }
    }

}