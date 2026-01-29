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

package dynamic.mapper.processor.extension;

import com.cumulocity.sdk.client.ProcessingMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.model.API;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.Map;

/**
 * Utility processor for converting extension results to DynamicMapperRequest objects.
 *
 * <p>This component bridges the new return-value based extension pattern with the existing
 * request-based processing pipeline. It converts:</p>
 * <ul>
 *   <li>CumulocityObject arrays (from inbound extensions) → DynamicMapperRequest for C8Y API</li>
 *   <li>DeviceMessage arrays (from outbound extensions) → DynamicMapperRequest for broker publishing</li>
 * </ul>
 *
 * <p>The conversion handles:</p>
 * <ul>
 *   <li>Mapping domain object fields to request fields</li>
 *   <li>JSON serialization of payloads</li>
 *   <li>Action to HTTP method mapping</li>
 *   <li>External ID handling</li>
 *   <li>Context data propagation</li>
 * </ul>
 *
 * @see CumulocityObject
 * @see DeviceMessage
 * @see DynamicMapperRequest
 */
@Slf4j
@Component
public class ExtensionResultProcessor {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Process results from an inbound extension and add requests to the context.
     *
     * <p>Converts each CumulocityObject to a DynamicMapperRequest and adds it to the
     * context's request list. The requests will later be executed by SendInboundProcessor.</p>
     *
     * @param results Array of CumulocityObject instances returned by the extension
     * @param context Processing context to add requests to
     * @throws ProcessingException if conversion fails
     */
    public void processInboundResults(CumulocityObject[] results, ProcessingContext<?> context)
            throws ProcessingException {
        if (results == null || results.length == 0) {
            return;
        }

        String tenant = context.getTenant();
        log.debug("{} - Processing {} inbound result(s) from extension", tenant, results.length);

        for (int i = 0; i < results.length; i++) {
            CumulocityObject c8yObj = results[i];
            try {
                DynamicMapperRequest request = convertCumulocityObjectToRequest(c8yObj, context);
                context.addRequest(request);
                log.debug("{} - Added inbound request {}/{}: api={}, action={}",
                        tenant, i + 1, results.length, c8yObj.getCumulocityType(), c8yObj.getAction());
            } catch (Exception e) {
                String errorMsg = String.format("Failed to convert CumulocityObject #%d to request: %s",
                        i, e.getMessage());
                log.error("{} - {}", tenant, errorMsg, e);
                throw new ProcessingException(errorMsg, e);
            }
        }
    }

    /**
     * Process results from an outbound extension and add requests to the context.
     *
     * <p>Converts each DeviceMessage to a DynamicMapperRequest for broker publishing
     * and adds it to the context's request list. The requests will later be executed
     * by SendOutboundProcessor.</p>
     *
     * @param results Array of DeviceMessage instances returned by the extension
     * @param context Processing context to add requests to
     * @throws ProcessingException if conversion fails
     */
    public void processOutboundResults(DeviceMessage[] results, ProcessingContext<?> context)
            throws ProcessingException {
        if (results == null || results.length == 0) {
            return;
        }

        String tenant = context.getTenant();
        log.debug("{} - Processing {} outbound result(s) from extension", tenant, results.length);

        for (int i = 0; i < results.length; i++) {
            DeviceMessage deviceMsg = results[i];
            try {
                DynamicMapperRequest request = convertDeviceMessageToRequest(deviceMsg, context);
                context.addRequest(request);
                log.debug("{} - Added outbound request {}/{}: topic={}",
                        tenant, i + 1, results.length, deviceMsg.getTopic());
            } catch (Exception e) {
                String errorMsg = String.format("Failed to convert DeviceMessage #%d to request: %s",
                        i, e.getMessage());
                log.error("{} - {}", tenant, errorMsg, e);
                throw new ProcessingException(errorMsg, e);
            }
        }
    }

    /**
     * Convert a CumulocityObject to a DynamicMapperRequest for Cumulocity API.
     *
     * <p>Maps the domain object fields to request fields:</p>
     * <ul>
     *   <li>cumulocityType → api field (MEASUREMENT, EVENT, ALARM, etc.)</li>
     *   <li>action → HTTP method (create→POST, update→PUT, etc.)</li>
     *   <li>payload → request JSON</li>
     *   <li>externalSource → externalId, externalIdType</li>
     *   <li>contextData → processing context fields (deviceName, deviceType, processingMode)</li>
     * </ul>
     *
     * @param c8yObj The CumulocityObject to convert
     * @param context Processing context for additional information
     * @return DynamicMapperRequest ready to be executed
     * @throws JsonProcessingException if payload serialization fails
     */
    private DynamicMapperRequest convertCumulocityObjectToRequest(
            CumulocityObject c8yObj, ProcessingContext<?> context) throws JsonProcessingException {

        // Determine API from cumulocityType
        API api = mapCumulocityTypeToAPI(c8yObj.getCumulocityType());

        // Determine HTTP method from action
        RequestMethod method = ProcessingResultHelper.mapActionToRequestMethod(c8yObj.getAction());

        // Serialize payload to JSON
        String payloadJson = serializePayload(c8yObj.getPayload());

        // Extract external ID information from the first externalSource entry
        String externalId = null;
        String externalIdType = null;
        if (c8yObj.getExternalSource() != null && !c8yObj.getExternalSource().isEmpty()) {
            ExternalId firstExternal = c8yObj.getExternalSource().get(0);
            externalId = firstExternal.getExternalId();
            externalIdType = firstExternal.getType();
        }

        // Use external ID type from mapping if not provided
        if (externalIdType == null) {
            externalIdType = context.getMapping().getExternalIdType();
        }

        // Apply context data to processing context
        if (c8yObj.getContextData() != null && !c8yObj.getContextData().isEmpty()) {
            applyContextData(c8yObj.getContextData(), context);
        }

        // Use predecessor from context if available
        int predecessor = context.getCurrentRequest() != null
                ? context.getCurrentRequest().getPredecessor()
                : -1;

        // Build the request
        return DynamicMapperRequest.builder()
                .predecessor(predecessor)
                .method(method)
                .api(api)
                .externalId(externalId)
                .externalIdType(externalIdType)
                .request(payloadJson)
                .build();
    }

    /**
     * Convert a DeviceMessage to a DynamicMapperRequest for broker publishing.
     *
     * <p>Maps the domain object fields to request fields:</p>
     * <ul>
     *   <li>topic → publishTopic</li>
     *   <li>payload → request (serialized if needed)</li>
     *   <li>retain → retain flag</li>
     *   <li>transportFields → stored in context for later use</li>
     * </ul>
     *
     * @param deviceMsg The DeviceMessage to convert
     * @param context Processing context for additional information
     * @return DynamicMapperRequest ready to be published
     * @throws JsonProcessingException if payload serialization fails
     */
    private DynamicMapperRequest convertDeviceMessageToRequest(
            DeviceMessage deviceMsg, ProcessingContext<?> context) throws JsonProcessingException {

        // Determine the publish topic (from message or context)
        String publishTopic = deviceMsg.getTopic();
        if (publishTopic == null) {
            publishTopic = context.getResolvedPublishTopic();
        }

        // Serialize payload if needed
        String payloadString = serializePayload(deviceMsg.getPayload());

        // Apply transport fields to context if present
        if (deviceMsg.getTransportFields() != null && !deviceMsg.getTransportFields().isEmpty()) {
            // Transport fields can be accessed later by the connector
            log.debug("{} - DeviceMessage has {} transport field(s)",
                    context.getTenant(), deviceMsg.getTransportFields().size());
            // Note: Transport fields handling depends on connector implementation
            // For now, they're available via deviceMsg if needed by SendOutboundProcessor
        }

        // Use predecessor from context if available
        int predecessor = context.getCurrentRequest() != null
                ? context.getCurrentRequest().getPredecessor()
                : -1;

        // Build the request for outbound publishing
        return DynamicMapperRequest.builder()
                .predecessor(predecessor)
                .publishTopic(publishTopic)
                .retain(deviceMsg.getRetain())
                .request(payloadString)
                .build();
    }

    /**
     * Map CumulocityType enum to API enum.
     *
     * @param cumulocityType The CumulocityType to map
     * @return The corresponding API
     * @throws IllegalArgumentException if cumulocityType is null or unknown
     */
    private API mapCumulocityTypeToAPI(dynamic.mapper.processor.model.CumulocityType cumulocityType) {
        if (cumulocityType == null) {
            throw new IllegalArgumentException("cumulocityType must not be null");
        }

        switch (cumulocityType) {
            case MEASUREMENT:
                return API.MEASUREMENT;
            case EVENT:
                return API.EVENT;
            case ALARM:
                return API.ALARM;
            case OPERATION:
                return API.OPERATION;
            case MANAGED_OBJECT:
                return API.INVENTORY;
            default:
                throw new IllegalArgumentException("Unknown CumulocityType: " + cumulocityType);
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
        if (contextData.containsKey("deviceName")) {
            context.setDeviceName(contextData.get("deviceName"));
        }

        if (contextData.containsKey("deviceType")) {
            context.setDeviceType(contextData.get("deviceType"));
        }

        if (contextData.containsKey("processingMode")) {
            String mode = contextData.get("processingMode");
            context.setProcessingMode(ProcessingMode.valueOf(mode));
        }

        // Note: Attachment handling for events would go here
        // For now, attachment data is in contextData but actual file upload
        // would need to be implemented separately by SendInboundProcessor
    }
}
