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
import dynamic.mapper.processor.flow.CumulocitySource;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;

@Component
public class ProcessingResultHelper {

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

    public static <T> ProcessingResultWrapper<T> successAsync(Future<List<ProcessingContext<T>>> future, Qos qos,
            int maxCPUTimeMS) {
        return ProcessingResultWrapper.<T>builder()
                .processingResult(future)
                .consolidatedQos(qos)
                .maxCPUTimeMS(maxCPUTimeMS)
                .build();
    }

    public static <T> ProcessingResultWrapper<T> failure(Exception error) {
        return ProcessingResultWrapper.<T>builder()
                .error(error)
                .maxCPUTimeMS(0)
                .build();
    }

    public static <T> ProcessingResultWrapper<T> failure(Exception error, int maxCPUTimeMS) {
        return ProcessingResultWrapper.<T>builder()
                .error(error)
                .maxCPUTimeMS(maxCPUTimeMS)
                .build();
    }

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
     * Create implicit device when needed - extracted from original
     * createImplicitDevice
     */
    public static String createImplicitDevice(ID identity, ProcessingContext<?> context, Logger log, C8YAgent c8yAgent,
            ObjectMapper objectMapper) {
        Map<String, Object> request = new HashMap<>();

        // Set device name
        if (context.getDeviceName() != null) {
            request.put("name", context.getDeviceName());
        } else {
            request.put("name", "device_" + identity.getType() + "_" + identity.getValue());
        }

        // Set device type
        if (context.getDeviceType() != null) {
            request.put("type", context.getDeviceType());
        } else {
            request.put("type", "c8y_GeneratedDeviceType");
        }

        // Set device properties
        request.put(MappingRepresentation.MAPPING_GENERATED_TEST_DEVICE, null);
        request.put("c8y_IsDevice", null);
        request.put("com_cumulocity_model_Agent", null);

        try {
            int predecessor = context.getRequests().size();
            String requestString = objectMapper.writeValueAsString(request);

            // Create C8Y request for device creation
            DynamicMapperRequest deviceRequest = DynamicMapperRequest.builder()
                    .predecessor(predecessor)
                    .method(context.getMapping().getUpdateExistingDevice() ? RequestMethod.POST : RequestMethod.PATCH)
                    .api(API.INVENTORY)
                    .externalIdType(context.getMapping().getExternalIdType())
                    .externalId(context.getExternalId())
                    .request(requestString)
                    .build();

            var index = context.addRequest(deviceRequest);

            // Create the device
            ManagedObjectRepresentation adHocDevice = c8yAgent.upsertDevice(context.getTenant(), identity, context,
                    index);

            // Update request with response
            String response = objectMapper.writeValueAsString(adHocDevice);
            context.getCurrentRequest().setResponse(response);
            context.getCurrentRequest().setSourceId(adHocDevice.getId().getValue());

            return adHocDevice.getId().getValue();

        } catch (Exception e) {
            context.getCurrentRequest().setError(e);
            log.error("Failed to create implicit device: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates a DynamicMapperRequest based on the reference implementation from
     * BaseProcessorOutbound
     * This follows the same pattern as substituteInTargetAndSend method
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
     * Sets a value hierarchically in a map using dot notation
     * E.g., "source.id" will create nested maps: {"source": {"id": value}}
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

    // Keep all existing conversion methods unchanged
    @SuppressWarnings("unchecked")
    public static List<ExternalSource> convertToExternalSourceList(Object obj) {
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

    @SuppressWarnings("unchecked")
    public static List<CumulocitySource> convertToInternalSourceList(Object obj) {
        List<CumulocitySource> result = new ArrayList<>();

        if (obj == null) {
            return result;
        }

        if (obj instanceof CumulocitySource) {
            result.add((CumulocitySource) obj);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof CumulocitySource) {
                    result.add((CumulocitySource) item);
                } else if (item instanceof Map) {
                    // Convert Map to CumulocitySource
                    CumulocitySource cumulocitySource = convertMapToCumulocitySource((Map<String, Object>) item);
                    if (cumulocitySource != null) {
                        result.add(cumulocitySource);
                    }
                }
            }
        } else if (obj instanceof Map) {
            CumulocitySource cumulocitySource = convertMapToCumulocitySource((Map<String, Object>) obj);
            if (cumulocitySource != null) {
                result.add(cumulocitySource);
            }
        }

        return result;
    }

    private static ExternalSource convertMapToExternalSource(Map<String, Object> map) {
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

    private static CumulocitySource convertMapToCumulocitySource(Map<String, Object> map) {
        if (map == null || !map.containsKey("internalId")) {
            return null;
        }

        return new CumulocitySource(String.valueOf(map.get("internalId")));
    }

    private static Qos getHigherQos(Qos q1, Qos q2) {
        // Assuming QoS priority: EXACTLY_ONCE > AT_LEAST_ONCE > AT_MOST_ONCE
        if (q1 == Qos.EXACTLY_ONCE || q2 == Qos.EXACTLY_ONCE) {
            return Qos.EXACTLY_ONCE;
        }
        if (q1 == Qos.AT_LEAST_ONCE || q2 == Qos.AT_LEAST_ONCE) {
            return Qos.AT_LEAST_ONCE;
        }
        return Qos.AT_MOST_ONCE;
    }

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
