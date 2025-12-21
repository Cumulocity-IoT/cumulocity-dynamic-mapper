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
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.ExternalId;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.notification.websocket.Notification;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowResultOutboundProcessor extends BaseProcessor {

    private final MappingService mappingService;
    private final ObjectMapper objectMapper;

    public FlowResultOutboundProcessor(
            MappingService mappingService,
            ObjectMapper objectMapper) {
        this.mappingService = mappingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

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
            } else if (message instanceof CumulocityObject) {
                processCumulocityObject((CumulocityObject) message, context, tenant, mapping);
            } else {
                log.debug("{} - Message is not a CumulocityObject, skipping: {}", tenant,
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
            String resolvedExternalId = null;
            try {
                resolvedExternalId = resolveGlobalId2ExternalId(deviceMessage, context, tenant);
                context.setSourceId(resolvedExternalId);
            } catch (ProcessingException e) {
                log.warn("{} - Could not resolve external ID for device message: {}", tenant, e.getMessage());
                // Continue processing without resolved external ID
            }

            // Set resolved publish topic (from substituteInTargetAndSend logic)
            setResolvedPublishTopic(context, payload);

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Create the request using the corrected method
            ProcessingResultHelper.createAndAddDynamicMapperRequest(context,
                    payloadJson, null, mapping);

            // Set resolvedPublishTopic topic in context
            String publishTopic = deviceMessage.getTopic();

            if (publishTopic != null && !publishTopic.isEmpty() && publishTopic.contains(EXTERNAL_ID_TOKEN)) {
                if (resolvedExternalId != null) {
                    publishTopic = publishTopic.replace(EXTERNAL_ID_TOKEN, resolvedExternalId);
                } else {
                    log.warn("{} - Publish topic contains {} token but external ID could not be resolved",
                            tenant, EXTERNAL_ID_TOKEN);
                    // Optionally: skip processing or use a default value
                    // return; // Uncomment to skip processing if external ID is required
                }
            }

            // set key for Kafka messages
            if (deviceMessage.getTransportFields() != null) {
                context.setKey(deviceMessage.getTransportFields().get(Mapping.CONTEXT_DATA_KEY_NAME));
            }
            context.setResolvedPublishTopic(publishTopic);

            context.setRetain(deviceMessage.getRetain());

            log.debug("{} - Created outbound request: deviceId={}, topic={}",
                    tenant, resolvedExternalId != null ? resolvedExternalId : "unresolved",
                    context.getResolvedPublishTopic());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process DeviceMessage: " + e.getMessage(), e);
        }
    }

    private void processCumulocityObject(CumulocityObject cumulocityMessage, ProcessingContext<?> context,
            String tenant, Mapping mapping) throws ProcessingException {

        try {
            // Get the API from the cumulocityType
            API targetAPI = Notification.convertResourceToAPI(cumulocityMessage.getCumulocityType().name());

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // Resolve device ID and set it hierarchically in the payload
            String resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant);
            List<ExternalId> externalSources = cumulocityMessage.getExternalSource();
            String externalId = null;
            String externalType = null;

            if (externalSources != null && !externalSources.isEmpty()) {
                ExternalId externalSource = externalSources.get(0);
                externalId = externalSource.getExternalId();
                externalType = externalSource.getType();
                context.setExternalId(externalId);
            }

            if (resolvedDeviceId != null) {
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                context.setSourceId(resolvedDeviceId);
            } else if (externalSources != null && !externalSources.isEmpty()) {
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

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            DynamicMapperRequest c8yRequest = ProcessingResultHelper.createAndAddDynamicMapperRequest(context,
                    payloadJson,
                    cumulocityMessage.getAction(), mapping);
            c8yRequest.setApi(targetAPI);
            c8yRequest.setSourceId(resolvedDeviceId);
            c8yRequest.setExternalIdType(externalType);
            c8yRequest.setExternalId(externalId);

            context.setRetain(c8yRequest.getRetain());

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId);

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

}