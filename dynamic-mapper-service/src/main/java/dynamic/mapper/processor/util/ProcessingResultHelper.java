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
package dynamic.mapper.processor.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingRepresentation;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;

/**
 * Utility class for creating and managing processing results in the dynamic mapper system.
 *
 * <p>This helper provides factory methods to construct {@link ProcessingResultWrapper} instances
 * for various processing outcomes including success, failure, and asynchronous operations.
 * It also includes utilities for device management and data manipulation during message processing.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Creating standardized processing result wrappers with appropriate QoS and timing information</li>
 *   <li>Managing implicit device creation when devices don't exist in Cumulocity</li>
 *   <li>Building dynamic mapper requests for C8Y API operations</li>
 *   <li>Utility methods for hierarchical data structure manipulation</li>
 * </ul>
 *
 * @author Christof Strack, Stefan Witschel
 * @see ProcessingResultWrapper
 * @see ProcessingContext
 */
@Component
public class ProcessingResultHelper {

    /**
     * Creates a successful processing result wrapper from a list of completed processing contexts.
     *
     * <p>This method consolidates multiple processing contexts into a single result, determining
     * the highest QoS level across all contexts and calculating the total processing time.
     *
     * @param <T> the type of payload in the processing contexts
     * @param contexts the list of processing contexts that were successfully processed
     * @return a ProcessingResultWrapper containing the completed future, consolidated QoS, and processing time
     */
    public static <T> ProcessingResultWrapper<T> success(List<ProcessingContext<T>> contexts) {
        CompletableFuture<List<ProcessingContext<T>>> future = CompletableFuture.completedFuture(contexts);

        // Determine consolidated QoS from contexts
        Qos consolidatedQos = contexts.stream()
                .map(ProcessingContext::getQos)
                .filter(qos -> qos != null)
                .reduce((q1, q2) -> getHigherQos(q1, q2))
                .orElse(Qos.AT_LEAST_ONCE);

        // Calculate processing time based on contexts
        int processingTime = calculateProcessingTime(contexts);

        return ProcessingResultWrapper.<T>builder()
                .processingResult(future)
                .consolidatedQos(consolidatedQos)
                .maxCPUTimeMS(processingTime)
                .build();
    }

    /**
     * Creates a successful processing result wrapper for asynchronous operations.
     *
     * <p>Use this method when processing is performed asynchronously and you want to return
     * a result immediately while processing continues in the background.
     *
     * @param <T> the type of payload in the processing contexts
     * @param future a Future containing the list of processing contexts that will be completed asynchronously
     * @param qos the Quality of Service level for this processing operation
     * @param maxCPUTimeMS the maximum CPU time in milliseconds allocated for this operation
     * @return a ProcessingResultWrapper for the asynchronous operation
     */
    public static <T> ProcessingResultWrapper<T> successAsync(Future<List<ProcessingContext<T>>> future, Qos qos,
            int maxCPUTimeMS) {
        return ProcessingResultWrapper.<T>builder()
                .processingResult(future)
                .consolidatedQos(qos)
                .maxCPUTimeMS(maxCPUTimeMS)
                .build();
    }

    /**
     * Creates a failure processing result wrapper with zero processing time.
     *
     * @param <T> the type of payload that would have been in the processing contexts
     * @param error the exception that caused the processing to fail
     * @return a ProcessingResultWrapper indicating failure with the provided error
     */
    public static <T> ProcessingResultWrapper<T> failure(Exception error) {
        return ProcessingResultWrapper.<T>builder()
                .error(error)
                .maxCPUTimeMS(0)
                .build();
    }

    /**
     * Creates a failure processing result wrapper with specified processing time.
     *
     * <p>Use this method when processing failed but you want to record how much CPU time
     * was consumed before the failure occurred.
     *
     * @param <T> the type of payload that would have been in the processing contexts
     * @param error the exception that caused the processing to fail
     * @param maxCPUTimeMS the CPU time in milliseconds consumed before failure
     * @return a ProcessingResultWrapper indicating failure with the provided error and timing
     */
    public static <T> ProcessingResultWrapper<T> failure(Exception error, int maxCPUTimeMS) {
        return ProcessingResultWrapper.<T>builder()
                .error(error)
                .maxCPUTimeMS(maxCPUTimeMS)
                .build();
    }

    /**
     * Creates an empty processing result wrapper indicating no processing was performed.
     *
     * <p>This is typically used when there are no messages to process or when processing
     * is skipped due to conditions not being met. The result has AT_MOST_ONCE QoS and
     * zero processing time.
     *
     * @param <T> the type of payload that would have been in the processing contexts
     * @return a ProcessingResultWrapper with an empty context list and minimal resource usage
     */
    public static <T> ProcessingResultWrapper<T> empty() {
        CompletableFuture<List<ProcessingContext<T>>> emptyFuture = CompletableFuture
                .completedFuture(new ArrayList<>());

        return ProcessingResultWrapper.<T>builder()
                .processingResult(emptyFuture)
                .consolidatedQos(Qos.AT_MOST_ONCE)
                .maxCPUTimeMS(0)
                .build();
    }

    /**
     * Creates an implicit device in Cumulocity when a device identity is encountered but doesn't exist yet.
     *
     * <p>This method automatically provisions a new device in Cumulocity with appropriate properties
     * when an external device sends data but hasn't been explicitly registered. The device is created
     * with properties from the processing context (if provided) or with generated defaults.
     *
     * <p>Device properties set:
     * <ul>
     *   <li>name: from context.getDeviceName() or generated as "device_{type}_{value}"</li>
     *   <li>type: from context.getDeviceType() or defaults to "c8y_GeneratedDeviceType"</li>
     *   <li>Special markers: MAPPING_GENERATED_DEVICE, c8y_IsDevice, com_cumulocity_model_Agent</li>
     * </ul>
     *
     * <p>The method creates a {@link DynamicMapperRequest} and adds it to the processing context,
     * then executes the device creation via the C8Y agent. The response is stored back in the context.
     *
     * @param identity the external identity for the device (type and value)
     * @param context the current processing context containing mapping and device information
     * @param log the logger for recording operations and errors
     * @param c8yAgent the Cumulocity agent for executing the device creation
     * @param objectMapper the Jackson ObjectMapper for JSON serialization/deserialization
     * @return the Cumulocity internal device ID (managed object ID) if successful, null if creation failed
     */
    public static String createImplicitDevice(ID identity, ProcessingContext<?> context, Logger log, C8YAgent c8yAgent,
            ObjectMapper objectMapper) {
        Map<String, Object> request = new HashMap<>();

        // Set device name to either from context (provided as part of the mapping) or
        // based on identity
        if (context.getDeviceName() != null) {
            request.put("name", context.getDeviceName());
        } else {
            request.put("name", "device_" + identity.getType() + "_" + identity.getValue());
        }

        // Set device type to either from context (provided as part of the mapping) or
        // default
        if (context.getDeviceType() != null) {
            request.put("type", context.getDeviceType());
        } else {
            request.put("type", "c8y_GeneratedDeviceType");
        }

        // update context with values from identity
        context.setExternalId(identity.getValue());
        String externalIdType = identity.getType() != null ? identity.getType()
                : context.getMapping().getExternalIdType();

        // Set device properties
        // request.put(MappingRepresentation.MAPPING_GENERATED_DEVICE, new HashMap<>());
        request.put("c8y_IsDevice",  new HashMap<>());
        request.put("com_cumulocity_model_Agent",  new HashMap<>());

        try {
            int predecessor = context.getRequests().size();
            String requestString = objectMapper.writeValueAsString(request);

            // Create C8Y request for device creation
            DynamicMapperRequest deviceRequest = DynamicMapperRequest.builder()
                    .predecessor(predecessor)
                    .method(context.getMapping().getUpdateExistingDevice() ? RequestMethod.POST : RequestMethod.PATCH)
                    .api(API.INVENTORY)
                    .externalIdType(externalIdType)
                    .externalId(context.getExternalId())
                    .request(requestString)
                    .build();

            var index = context.addRequest(deviceRequest);

            // Create the device
            ManagedObjectRepresentation implicitDevice = c8yAgent.upsertDevice(context.getTenant(), identity, context,
                    index);

            // Update request with response
            String response = objectMapper.writeValueAsString(implicitDevice);
            context.getCurrentRequest().setResponse(response);
            context.getCurrentRequest().setSourceId(implicitDevice.getId().getValue());

            return implicitDevice.getId().getValue();

        } catch (Exception e) {
            context.getCurrentRequest().setError(e);
            log.error("Failed to create implicit device: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates a DynamicMapperRequest and adds it to the processing context.
     *
     * <p>This method constructs a request object that represents a Cumulocity API operation
     * based on the processing context, mapping configuration, and action. The request is
     * automatically added to the context's request list for tracking and execution.
     *
     * <p>This follows the same pattern as the substituteInTargetAndSend method from
     * BaseProcessorOutbound, ensuring consistency in request creation across the system.
     *
     * @param context the processing context containing current state and configuration
     * @param payloadJson the JSON payload to be sent in the request body
     * @param action the action type ("update" uses PUT, all others use POST)
     * @param mapping the mapping configuration containing target API and external ID information
     * @return the created DynamicMapperRequest that was added to the context
     */
    public static DynamicMapperRequest createAndAddDynamicMapperRequest(ProcessingContext<?> context,
            String payloadJson,
            String action, Mapping mapping) {

        // Determine the request method based on action (from substituteInTargetAndSend)
        RequestMethod method = "update".equals(action) ? RequestMethod.PUT : RequestMethod.POST; // Default from //
                                                                                                 // reference
        API api = context.getApi() != null ? context.getApi() : mapping.getTargetAPI();

        // Use -1 as predecessor for flow-generated requests (no predecessor in flow
        // context)
        int predecessor = context.getCurrentRequest() != null
                ? context.getCurrentRequest().getPredecessor()
                : -1;

        // Create the request using the same pattern as BaseProcessorOutbound
        DynamicMapperRequest request = DynamicMapperRequest.builder()
                .predecessor(predecessor)
                .method(method)
                .api(api)
                .externalIdType(mapping.getExternalIdType()) // External ID type from mapping
                .externalId(context.getExternalId())
                .request(payloadJson) // JSON payload
                .build();

        context.addRequest(request);
        return request;

    }

    /**
     * Sets a value hierarchically in a map using dot notation path.
     *
     * <p>This utility method creates nested map structures as needed to accommodate
     * a dot-separated path. If intermediate maps don't exist, they are created automatically.
     * If a non-map value exists at an intermediate key, it is replaced with a map.
     *
     * <p>Examples:
     * <pre>
     * Map&lt;String, Object&gt; map = new HashMap&lt;&gt;();
     * setHierarchicalValue(map, "source.id", "12345");
     * // Result: {"source": {"id": "12345"}}
     *
     * setHierarchicalValue(map, "data.temperature.value", 23.5);
     * // Result: {"data": {"temperature": {"value": 23.5}}}
     * </pre>
     *
     * @param map the root map to set the value in
     * @param path the dot-separated path (e.g., "source.id" or "data.temperature.value")
     * @param value the value to set at the specified path
     */
    public static void setHierarchicalValue(Map<String, Object> map, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = map;

        // Navigate/create the hierarchy up to the last key
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            if (!current.containsKey(key) || !(current.get(key) instanceof Map)) {
                current.put(key, new HashMap<String, Object>());
            }
            current = (Map<String, Object>) current.get(key);
        }

        // Set the value at the final key
        current.put(keys[keys.length - 1], value);
    }

    /**
     * Determines the higher Quality of Service level between two QoS values.
     *
     * <p>QoS priority order (highest to lowest):
     * <ol>
     *   <li>EXACTLY_ONCE - highest reliability</li>
     *   <li>AT_LEAST_ONCE - medium reliability</li>
     *   <li>AT_MOST_ONCE - lowest reliability</li>
     * </ol>
     *
     * @param q1 first QoS value
     * @param q2 second QoS value
     * @return the higher QoS level between the two inputs
     */
    private static Qos getHigherQos(Qos q1, Qos q2) {
        if (q1 == Qos.EXACTLY_ONCE || q2 == Qos.EXACTLY_ONCE) {
            return Qos.EXACTLY_ONCE;
        }
        if (q1 == Qos.AT_LEAST_ONCE || q2 == Qos.AT_LEAST_ONCE) {
            return Qos.AT_LEAST_ONCE;
        }
        return Qos.AT_MOST_ONCE;
    }

    /**
     * Calculates the estimated processing time based on processing contexts.
     *
     * <p>The calculation takes into account:
     * <ul>
     *   <li>Base time of 200ms per context</li>
     *   <li>50ms per request in the context</li>
     *   <li>Additional 100ms penalty for contexts with errors</li>
     * </ul>
     *
     * <p>The minimum returned value is 100ms even for empty context lists.
     *
     * @param <T> the type of payload in the processing contexts
     * @param contexts the list of processing contexts to calculate time for
     * @return the estimated processing time in milliseconds, minimum 100ms
     */
    private static <T> int calculateProcessingTime(List<ProcessingContext<T>> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 100;
        }

        // Calculate based on complexity - number of requests, errors, etc.
        int totalTime = contexts.stream()
                .mapToInt(ctx -> {
                    int contextTime = 200; // Base time per context
                    contextTime += ctx.getRequests().size() * 50; // Time per request
                    if (ctx.hasError()) {
                        contextTime += 100; // Additional time for error handling
                    }
                    return contextTime;
                })
                .sum();

        return Math.max(100, totalTime); // Minimum 100ms
    }
}
