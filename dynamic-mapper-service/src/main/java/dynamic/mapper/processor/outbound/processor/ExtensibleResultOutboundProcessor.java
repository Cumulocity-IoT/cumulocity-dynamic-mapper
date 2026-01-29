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

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractExtensibleResultProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes results from outbound Java Extensions (ProcessorExtensionOutbound).
 *
 * <p>This processor converts DeviceMessage[] returned by extensions into
 * DynamicMapperRequest[] for execution by SendOutboundProcessor. It handles:</p>
 * <ul>
 *   <li>Topic resolution</li>
 *   <li>Payload serialization</li>
 *   <li>Transport fields (e.g., Kafka keys)</li>
 *   <li>Retain flags</li>
 * </ul>
 *
 * <p>Follows the same pattern as FlowResultOutboundProcessor for consistency.</p>
 *
 * @see DeviceMessage
 * @see DynamicMapperRequest
 * @see dynamic.mapper.processor.extension.ProcessorExtensionOutbound
 */
@Slf4j
@Component
public class ExtensibleResultOutboundProcessor extends AbstractExtensibleResultProcessor {

    public ExtensibleResultOutboundProcessor(
            MappingService mappingService,
            C8YAgent c8yAgent,
            ObjectMapper objectMapper) {
        super(mappingService, objectMapper, c8yAgent);
    }

    @Override
    protected void processExtensionResults(ProcessingContext<?> context) throws ProcessingException {
        // Extension results are stored in context by ExtensibleOutboundProcessor
        Object extensionResult = context.getExtensionResult();
        String tenant = context.getTenant();

        if (extensionResult == null) {
            log.debug("{} - No extension result available, skipping extension result processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        if (!(extensionResult instanceof DeviceMessage[])) {
            log.warn("{} - Extension result is not DeviceMessage[], skipping", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        DeviceMessage[] results = (DeviceMessage[]) extensionResult;

        if (results.length == 0) {
            log.info("{} - Extension result is empty, skipping processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        // Set resolved publish topic from mapping as fallback
        // Extensions can override this by setting topic in DeviceMessage
        Mapping mapping = context.getMapping();
        if (mapping.getPublishTopic() != null && !mapping.getPublishTopic().isEmpty()) {
            context.setResolvedPublishTopic(mapping.getPublishTopic());
            log.debug("{} - Set resolved publish topic from mapping: {}", tenant, mapping.getPublishTopic());
        }

        // Process each DeviceMessage
        for (DeviceMessage deviceMsg : results) {
            processDeviceMessage(deviceMsg, context);
        }

        if (context.getRequests().isEmpty()) {
            log.info("{} - No requests generated from extension result", tenant);
            context.setIgnoreFurtherProcessing(true);
        } else {
            log.info("{} - Generated {} requests from extension result", tenant, context.getRequests().size());
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        int lineNumber = 0;
        if (e.getStackTrace().length > 0) {
            lineNumber = e.getStackTrace()[0].getLineNumber();
        }
        String errorMessage = String.format(
                "Tenant %s - Error in ExtensibleResultOutboundProcessor: %s for mapping: %s, line %s",
                tenant, mapping.getName(), e.getMessage(), lineNumber);
        log.error(errorMessage, e);

        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    /**
     * Process a single DeviceMessage and convert it to a DynamicMapperRequest.
     *
     * <p>This method handles:</p>
     * <ul>
     *   <li>Topic determination (from message or context)</li>
     *   <li>Payload serialization</li>
     *   <li>Transport fields extraction</li>
     *   <li>Retain flag handling</li>
     * </ul>
     *
     * @param deviceMsg The DeviceMessage to process
     * @param context The processing context
     * @throws ProcessingException if processing fails
     */
    private void processDeviceMessage(DeviceMessage deviceMsg, ProcessingContext<?> context)
            throws ProcessingException {

        String tenant = context.getTenant();

        try {
            // Determine the publish topic (from message or context)
            String publishTopic = deviceMsg.getTopic();
            if (publishTopic == null || publishTopic.isEmpty()) {
                publishTopic = context.getResolvedPublishTopic();
            }

            // Serialize payload if needed
            String payloadString = serializePayload(deviceMsg.getPayload());

            // Apply transport fields to context if present
            if (deviceMsg.getTransportFields() != null && !deviceMsg.getTransportFields().isEmpty()) {
                // Extract key for Kafka messages
                String key = deviceMsg.getTransportFields().get(Mapping.CONTEXT_DATA_KEY_NAME);
                if (key != null) {
                    context.setKey(key);
                }
                log.debug("{} - DeviceMessage has {} transport field(s)",
                        tenant, deviceMsg.getTransportFields().size());
            }

            // Build the request for outbound publishing
            DynamicMapperRequest request = DynamicMapperRequest.builder()
                    .predecessor(context.getCurrentRequest() != null
                            ? context.getCurrentRequest().getPredecessor()
                            : -1)
                    .publishTopic(publishTopic)
                    .retain(deviceMsg.getRetain())
                    .request(payloadString)
                    .build();

            // Add request to context
            context.addRequest(request);

            // Set retain flag on context
            context.setRetain(deviceMsg.getRetain());

            log.debug("{} - Created outbound request: topic={}, retain={}",
                    tenant, publishTopic, deviceMsg.getRetain());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process DeviceMessage: " + e.getMessage(), e);
        }
    }

    /**
     * Serialize payload object to JSON string.
     *
     * <p>Handles different payload types:</p>
     * <ul>
     *   <li>String → returned as-is (assumed to be JSON)</li>
     *   <li>byte[] → converted to String (UTF-8)</li>
     *   <li>Map/Object → serialized to JSON using ObjectMapper</li>
     * </ul>
     *
     * @param payload The payload to serialize
     * @return JSON string representation
     * @throws JsonProcessingException if serialization fails
     */
    private String serializePayload(Object payload) throws JsonProcessingException {
        if (payload == null) {
            return "{}";
        }

        if (payload instanceof String) {
            return (String) payload;
        }

        if (payload instanceof byte[]) {
            return new String((byte[]) payload);
        }

        // For Map, POJO, or any other object, serialize to JSON
        return objectMapper.writeValueAsString(payload);
    }
}
