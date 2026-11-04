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

import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dynamic.mapper.model.API;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.runtime.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Completes an outbound {@link DynamicMapperRequest} for Cumulocity REST targets (internal webhook):
 * source identifier in the body and, for PUT/PATCH/DELETE, the device ID in the path. Shared by the
 * Smart Function, Java extension and JSONata result processors so they cannot drift apart.
 */
@Slf4j
public final class OutboundRequestFinalizer {

    // Only parses and writes plain JSON, so a shared default instance is enough for callers without one.
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private OutboundRequestFinalizer() {
    }

    /**
     * The Cumulocity API a device message targets: its {@code cumulocityType} first, otherwise the
     * topic. {@code null} when neither names an API, so callers keep what the request already has.
     */
    public static API deriveApi(CumulocityType cumulocityType, String topic) {
        if (cumulocityType != null) {
            return APITopicUtil.deriveAPIFromTopic(cumulocityType.toString());
        }
        if (topic != null && !topic.isEmpty()) {
            return APITopicUtil.deriveAPIFromTopic(topic);
        }
        return null;
    }

    /**
     * Applies the context-level transport settings of a device message: the broker message key (only
     * when present, so other transport fields cannot overwrite an already-resolved key with null)
     * and the retain flag.
     */
    public static void applyTransportFields(ProcessingContext<?> context, DeviceMessage message) {
        if (message.getTransportFields() != null) {
            String key = message.getTransportFields().get(Mapping.CONTEXT_DATA_KEY_NAME);
            if (key != null && !Mapping.CONTEXT_DATA_KEY_PLACEHOLDER.equals(key)) {
                context.setKey(key);
            }
        }
        context.setRetain(Boolean.TRUE.equals(message.getRetain()));
    }

    public static void finalizeRequest(DynamicMapperRequest request, String internalId, String payloadJson,
            String tenant) {
        finalizeRequest(request, internalId, payloadJson, tenant, DEFAULT_MAPPER);
    }

    /**
     * Sets source.id in the Cumulocity payload and adjusts the REST path for
     * PUT/PATCH/DELETE operations. Called by both processDeviceMessage and
     * processCumulocityObject so the logic lives in one place.
     *
     * @param internalId  internal C8Y managed object ID of the source device — what Cumulocity REST API expects
     *                    in source.id / id (never an external EUI or serial number); for
     *                    non-inventory PUT/PATCH/DELETE the resource's own id is read from the payload
     * @param payloadJson raw JSON string before source-identifier injection
     */
    public static void finalizeRequest(DynamicMapperRequest request, String internalId,
            String payloadJson, String tenant, ObjectMapper objectMapper) {
        request.setSourceId(internalId);

        if (request.getApi() != null && internalId != null && !internalId.isEmpty()) {
            log.debug("{} - Populating source identifier: id={}, api.identifier={}",
                    tenant, internalId, request.getApi().identifier);
            request.setRequestCumulocity(populateSourceIdentifier(payloadJson, request, tenant, objectMapper));
        } else {
            log.warn("{} - Skipping populateSourceIdentifier: api={}, id={}",
                    tenant, request.getApi(), internalId);
        }

        // Measurements are immutable time-series — PUT/PATCH must become POST
        if (request.getApi() == API.MEASUREMENT &&
                (request.getMethod() == RequestMethod.PUT || request.getMethod() == RequestMethod.PATCH)) {
            log.warn("{} - Measurements don't support PUT/PATCH, converting to POST. Use action='create' to avoid this warning.", tenant);
            request.setMethod(RequestMethod.POST);
            request.setPathCumulocity(request.getApi().path);
        } else if (request.getApi() != null && internalId != null &&
                (request.getMethod() == RequestMethod.PUT ||
                 request.getMethod() == RequestMethod.PATCH ||
                 request.getMethod() == RequestMethod.DELETE)) {
            // The URL addresses the resource itself: the managed object for INVENTORY, otherwise the
            // event/alarm/operation carried in the payload's own "id". The device ID only identifies
            // the source and must never stand in for it.
            String resourceId = request.getApi() == API.INVENTORY ? internalId
                    : readResourceId(payloadJson, objectMapper);
            if (resourceId == null) {
                String message = String.format("%s requires the id of the %s to address in the payload",
                        request.getMethod(), request.getApi().name);
                log.warn("{} - {}", tenant, message);
                request.setError(new IllegalArgumentException(message));
                return;
            }
            String pathWithId = request.getApi().path + "/" + resourceId;
            request.setPathCumulocity(pathWithId);
            // ID goes in the URL path; remove it from the body
            if (request.getRequestCumulocity() != null) {
                String body = removeIdentifierFromPayload(request.getRequestCumulocity(), request.getApi().identifier,
                        tenant, objectMapper);
                if (request.getApi() != API.INVENTORY) {
                    body = removeIdentifierFromPayload(body, "id", tenant, objectMapper);
                }
                request.setRequestCumulocity(body);
            }
            log.debug("{} - Set pathCumulocity for {}: {}", tenant, request.getMethod(), pathWithId);
        } else if (request.getMethod() == RequestMethod.POST && request.getApi() != null) {
            // POST uses the base API path — never append an ID
            request.setPathCumulocity(request.getApi().path);
            log.debug("{} - Using base API path for POST: {}", tenant, request.getApi().path);
        }
    }

    /** The top-level "id" of the resource in the payload, or null when absent or not a JSON object. */
    private static String readResourceId(String payload, ObjectMapper objectMapper) {
        try {
            JsonNode id = objectMapper.readTree(payload).get("id");
            return id == null || id.isNull() || id.asText().isEmpty() ? null : id.asText();
        } catch (Exception e) {
            return null;
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
    private static String populateSourceIdentifier(String payload, DynamicMapperRequest request, String tenant,
            ObjectMapper objectMapper) {
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
    private static String removeIdentifierFromPayload(String payload, String identifier, String tenant,
            ObjectMapper objectMapper) {
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
