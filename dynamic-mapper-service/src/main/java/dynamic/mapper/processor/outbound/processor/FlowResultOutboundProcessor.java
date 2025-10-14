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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cumulocity.model.ID;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowResultOutboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            processFlowResults(context);
        } catch (Exception e) {
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
            return;
        }
    }

    private void processFlowResults(ProcessingContext<?> context) throws ProcessingException {
        Object flowResult = context.getFlowResult();
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        if (flowResult == null) {
            log.debug("{} - No flow result available, skipping flow result processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> messagesToProcess = new ArrayList<>();

        // Handle both single objects and lists
        if (flowResult instanceof List) {
            messagesToProcess.addAll((List<?>) flowResult);
        } else {
            messagesToProcess.add(flowResult);
        }

        if (messagesToProcess.isEmpty()) {
            log.info("{} - Flow result is empty, skipping processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        // Process each message
        for (Object message : messagesToProcess) {
            if (message instanceof DeviceMessage) {
                processDeviceMessage((DeviceMessage) message, context, tenant, mapping);
            } else {
                log.debug("{} - Message is not a CumulocityMessage, skipping: {}", tenant,
                        message.getClass().getSimpleName());
            }
        }

        if (context.getRequests().isEmpty()) {
            log.info("{} - No requests generated from flow result", tenant);
            context.setIgnoreFurtherProcessing(true);
        } else {
            log.info("{} - Generated {} requests from flow result", tenant, context.getRequests().size());
        }
    }

    private void processDeviceMessage(DeviceMessage deviceMessage, ProcessingContext<?> context,
            String tenant, Mapping mapping) throws ProcessingException {

        try {

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(deviceMessage.getPayload());

            // Resolve device ID and set it hierarchically in the payload
            String resolvedExternalId = resolveExternalIdentifier(deviceMessage, context, tenant);

            // Set resolved publish topic (from substituteInTargetAndSend logic)
            setResolvedPublishTopic(context, payload);

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Create the request using the corrected method
            DynamicMapperRequest request = createDynamicMapperRequest(context,
                    payloadJson, resolvedExternalId, mapping);

            // Set resolvedPublishTopic topic in context
            String publishTopic = deviceMessage.getTopic();

            if (publishTopic != null && !publishTopic.isEmpty() && publishTopic.contains(EXTERNAL_ID_TOKEN)) {
                publishTopic = publishTopic.replace(EXTERNAL_ID_TOKEN, resolvedExternalId);
            }

            // set key for Kafka messages
            if (mapping.getSupportsMessageContext() && deviceMessage.getTransportFields() != null) {
                context.setKey(deviceMessage.getTransportFields().get(Mapping.CONTEXT_DATA_KEY_NAME));

            }
            context.setResolvedPublishTopic(publishTopic);

            // Add the request to context
            context.addRequest(request);

            log.debug("{} - Created outbound request: deviceId={}, topic={}",
                    tenant, resolvedExternalId,
                    context.getResolvedPublishTopic());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityMessage: " + e.getMessage(), e);
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

            if (mapping.getDebug() || context.getServiceConfiguration().isLogPayload()) {
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
        if (mapping.getSupportsMessageContext()) {
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
    }

    private String resolveExternalIdentifier(DeviceMessage deviceMessage, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        // First try externalSource
        if (deviceMessage.getExternalSource() != null) {
            return resolveFromExternalSource(deviceMessage.getExternalSource(), context, tenant);
        }

        // Fallback to mapping's generic device identifier or context source ID
        if (context.getSourceId() != null) {
            return context.getSourceId();
        }

        return context.getMapping().getGenericDeviceIdentifier();
    }

    private String resolveFromExternalSource(Object externalSourceObj, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        List<ExternalSource> externalSources = convertToExternalSourceList(externalSourceObj);

        if (externalSources.isEmpty()) {
            throw new ProcessingException("External source is empty");
        }

        // Use the first external source for resolution
        ExternalSource externalSource = externalSources.get(0);

        try {
            // Use C8YAgent to resolve external ID to global ID
            var globalId = c8yAgent.resolveExternalId2GlobalId(tenant,
                    new ID(externalSource.getType(), externalSource.getExternalId()),
                    context);
            context.setExternalId(externalSource.getExternalId());

            if (globalId != null) {
                return globalId.getManagedObject().getId().getValue();
            } else {
                throw new ProcessingException("Could not resolve external ID: " + externalSource.getExternalId());
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> clonePayload(Object payload) throws ProcessingException {
        try {
            if (payload instanceof Map) {
                return new HashMap<>((Map<String, Object>) payload);
            } else {
                // Convert object to map using Jackson
                return objectMapper.convertValue(payload, Map.class);
            }
        } catch (Exception e) {
            throw new ProcessingException("Failed to clone payload: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a DynamicMapperRequest based on the reference implementation from
     * BaseProcessorOutbound
     * This follows the same pattern as substituteInTargetAndSend method
     */
    private DynamicMapperRequest createDynamicMapperRequest(ProcessingContext<?> context, String payloadJson,
            String sourceId, Mapping mapping) throws ProcessingException {
        try {
            // Determine the request method based on action (from substituteInTargetAndSend)
            RequestMethod method = RequestMethod.POST; // Default from reference

            // Use -1 as predecessor for flow-generated requests (no predecessor in flow
            // context)
            int predecessor = -1;

            // Create the request using the same pattern as BaseProcessorOutbound
            DynamicMapperRequest request = DynamicMapperRequest.builder()
                    .predecessor(predecessor)
                    .method(method)
                    .sourceId(sourceId) // Device/source identifier
                    .externalIdType(mapping.getExternalIdType()) // External ID type from mapping
                    .externalId(context.getExternalId())
                    .api(context.getApi())
                    .request(payloadJson) // JSON payload
                    .build();

            return request;

        } catch (Exception e) {
            throw new ProcessingException("Failed to create DynamicMapperRequest: " + e.getMessage(), e);
        }
    }

    // Keep all existing conversion methods unchanged
    @SuppressWarnings("unchecked")
    private List<ExternalSource> convertToExternalSourceList(Object obj) {
        // ... (keep existing implementation)
        List<ExternalSource> result = new ArrayList<>();

        if (obj == null) {
            return result;
        }

        if (obj instanceof ExternalSource) {
            result.add((ExternalSource) obj);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof ExternalSource) {
                    result.add((ExternalSource) item);
                } else if (item instanceof Map) {
                    ExternalSource externalSource = convertMapToExternalSource((Map<String, Object>) item);
                    if (externalSource != null) {
                        result.add(externalSource);
                    }
                }
            }
        } else if (obj instanceof Map) {
            ExternalSource externalSource = convertMapToExternalSource((Map<String, Object>) obj);
            if (externalSource != null) {
                result.add(externalSource);
            }
        }

        return result;
    }

    private ExternalSource convertMapToExternalSource(Map<String, Object> map) {
        // ... (keep existing implementation)
        if (map == null) {
            return null;
        }

        ExternalSource externalSource = new ExternalSource();

        if (map.containsKey("externalId")) {
            externalSource.setExternalId(String.valueOf(map.get("externalId")));
        }
        if (map.containsKey("type")) {
            externalSource.setType(String.valueOf(map.get("type")));
        }
        if (map.containsKey("autoCreateDeviceMO")) {
            externalSource.setAutoCreateDeviceMO((Boolean) map.get("autoCreateDeviceMO"));
        }
        if (map.containsKey("parentId")) {
            externalSource.setParentId(String.valueOf(map.get("parentId")));
        }
        if (map.containsKey("childReference")) {
            externalSource.setChildReference(String.valueOf(map.get("childReference")));
        }
        if (map.containsKey("clientId")) {
            externalSource.setClientId(String.valueOf(map.get("clientId")));
        }

        // Only return if we have the required fields
        if (externalSource.getExternalId() != null && externalSource.getType() != null) {
            return externalSource;
        }

        return null;
    }

}