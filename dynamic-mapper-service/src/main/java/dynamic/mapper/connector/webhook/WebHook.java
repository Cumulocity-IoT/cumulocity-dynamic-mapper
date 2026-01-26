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

package dynamic.mapper.connector.webhook;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyBuilder;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.ConnectorSpecificationBuilder;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.APITopicUtil;
import jakarta.ws.rs.NotSupportedException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * WebHook Connector Client.
 * Sends outbound messages to a configured REST endpoint via HTTP
 * POST/PUT/PATCH/DELETE.
 * Supports Basic and Bearer authentication.
 * Can be configured for internal Cumulocity communication or external
 * endpoints.
 */
@Slf4j
public class WebHook extends AConnectorClient {

    protected WebClient webhookClient;
    protected String baseUrl;
    protected Boolean baseUrlEndsWithSlash;

    @Getter
    protected List<Qos> supportedQOS;

    /**
     * Default constructor
     */
    public WebHook() {
        this.connectorType = ConnectorType.WEB_HOOK;
        this.singleton = false;
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE);
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public WebHook(ConfigurationRegistry configurationRegistry,
            ConnectorRegistry connectorRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        this();

        this.configurationRegistry = configurationRegistry;
        this.connectorRegistry = connectorRegistry;
        this.connectorConfiguration = connectorConfiguration;
        this.connectorName = connectorConfiguration.getName();
        this.connectorIdentifier = connectorConfiguration.getIdentifier();
        this.connectorId = new ConnectorId(
                connectorConfiguration.getName(),
                connectorConfiguration.getIdentifier(),
                connectorType);
        this.tenant = tenant;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;

        // Initialize dependencies from registry
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;

        // Configure for Cumulocity internal if needed
        configureCumulocityInternal();

        // Initialize managers
        initializeManagers();
    }

    /**
     * Configure for internal Cumulocity communication
     */
    private void configureCumulocityInternal() {
        Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("cumulocityInternal", false);

        log.info("{} - Connector {} - Cumulocity internal: {}", tenant, connectorName, cumulocityInternal);

        if (cumulocityInternal) {
            MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
            String user = String.format("%s/%s", tenant, msc.getUsername());

            Map<String, ConnectorProperty> props = connectorSpecification.getProperties();

            props.put("user",
                    new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY,
                            true, true, user, null, null));

            props.put("password",
                    new ConnectorProperty(null, true, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                            true, true, msc.getPassword(), null, null));

            props.put("authentication",
                    new ConnectorProperty(null, false, 1, ConnectorPropertyType.OPTION_PROPERTY,
                            true, true, "Basic", null, null));

            props.put("baseUrl",
                    new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY,
                            true, true, "http://cumulocity:8111", null, null));

            props.put("headerAccept",
                    new ConnectorProperty(null, false, 5, ConnectorPropertyType.STRING_PROPERTY,
                            true, true, "application/json", null, null));

            props.put("baseUrlHealthEndpoint",
                    new ConnectorProperty("health endpoint for GET request", false, 6,
                            ConnectorPropertyType.STRING_PROPERTY, true, true,
                            "http://cumulocity:8111/notification2", null, null));
        }
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            baseUrl = (String) connectorConfiguration.getProperties().getOrDefault("baseUrl", null);
            if (baseUrl == null) {
                throw new ConnectorException("baseUrl is required but not configured");
            }

            baseUrlEndsWithSlash = baseUrl.endsWith("/");

            log.info("{} - WebHook connector initialized, baseUrl: {}", tenant, baseUrl);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing WebHook connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    @Override
    public void connect() {
        log.info("{} - Connecting WebHook connector: {}", tenant, connectorName);

        if (isConnected()) {
            log.debug("{} - Already connected, disconnecting first", tenant);
            disconnect();
        }

        if (!shouldConnect()) {
            log.info("{} - Connector disabled or invalid configuration", tenant);
            return;
        }

        try {
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            // Build WebClient
            webhookClient = buildWebClient();

            // Test health endpoint if configured
            String healthEndpoint = (String) connectorConfiguration.getProperties()
                    .get("baseUrlHealthEndpoint");

            if (!StringUtils.isEmpty(healthEndpoint)) {
                log.info("{} - Testing health endpoint: {}", tenant, healthEndpoint);
                checkHealth().block();
                log.info("{} - Health check passed", tenant);
            } else {
                log.warn(
                        "{} - No health endpoint configured for WebHook connector {}, skipping health check and assuming connection is valid",
                        tenant, connectorName);
            }

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize outbound subscriptions
            mappingService.rebuildMappingCaches(tenant, connectorId);
            List<Mapping> outboundMappings = new ArrayList<>(
                    mappingService.getCacheOutboundMappings(tenant).values());
            initializeSubscriptionsOutbound(outboundMappings);

            log.info("{} - WebHook connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting WebHook connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            connectionStateManager.setConnected(false);
        }
    }

    /**
     * Build WebClient with authentication and headers
     */
    private WebClient buildWebClient() {
        String authentication = (String) connectorConfiguration.getProperties().get("authentication");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String token = (String) connectorConfiguration.getProperties().get("token");
        String headerAccept = (String) connectorConfiguration.getProperties()
                .getOrDefault("headerAccept", "application/json");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) connectorConfiguration.getProperties().get("headers");

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", headerAccept);

        // Add authentication
        if ("Basic".equalsIgnoreCase(authentication) && !StringUtils.isEmpty(user) && !StringUtils.isEmpty(password)) {
            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader("Authorization", "Basic " + credentials);
            log.debug("{} - Using Basic authentication", tenant);
        } else if ("Bearer".equalsIgnoreCase(authentication) && !StringUtils.isEmpty(token)) {
            builder.defaultHeader("Authorization", "Bearer " + token);
            log.debug("{} - Using Bearer authentication", tenant);
        }

        // Add custom headers
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((key, value) -> {
                if (value != null) {
                    builder.defaultHeader(key, value);
                }
            });
            log.debug("{} - Added {} custom headers", tenant, headers.size());
        }

        return builder.build();
    }

    /**
     * Check health of the webhook endpoint
     */
    public Mono<ResponseEntity<String>> checkHealth() {
        String healthEndpoint = (String) connectorConfiguration.getProperties()
                .getOrDefault("baseUrlHealthEndpoint", null);

        log.debug("{} - Checking health of webHook endpoint: {}", tenant, healthEndpoint);

        return webhookClient.get()
                .uri(healthEndpoint)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> {
                    String error = "Health check failed with client error: " + response.statusCode();
                    return Mono.error(new ConnectorException(error));
                })
                .onStatus(HttpStatusCode::is5xxServerError, response -> {
                    String error = "Health check failed with server error: " + response.statusCode();
                    return Mono.error(new ConnectorException(error));
                })
                .toEntity(String.class);
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    @Override
    public void disconnect() {
        if (!isConnected()) {
            log.debug("{} - Already disconnected", tenant);
            return;
        }

        log.info("{} - Disconnecting WebHook connector", tenant);
        connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        try {
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            // Rebuild caches
            mappingService.rebuildMappingCaches(tenant, connectorId);
            List<Mapping> outboundMappings = new ArrayList<>(
                    mappingService.getCacheOutboundMappings(tenant).values());

            initializeSubscriptionsOutbound(outboundMappings);

            log.info("{} - WebHook connector disconnected", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (webhookClient == null) {
            log.error("{} - WebClient is not initialized", tenant);
            return;
        }

        var requests = context.getRequests();
        if (requests == null || requests.isEmpty()) {
            log.warn("{} - No requests to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }

        Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("cumulocityInternal", false);

        // Process each request
        for (int i = 0; i < requests.size(); i++) {
            DynamicMapperRequest request = requests.get(i);

            log.info("{} - 🔍 Processing request ({}/{}): method={}, api={}, hasRequest={}, hasRequestCumulocity={}",
                    tenant, i + 1, requests.size(),
                    request != null ? request.getMethod() : "null",
                    request != null && request.getApi() != null ? request.getApi().name : "null",
                    request != null && request.getRequest() != null,
                    request != null && request.getRequestCumulocity() != null);

            if (request == null || request.getRequest() == null) {
                log.warn("{} - Skipping null request or payload ({}/{})", tenant, i + 1, requests.size());
                continue;
            }

            String payload = request.getRequest();
            String contextPath;
            RequestMethod method = request.getMethod();
            API derivedAPI = request.getApi();

            // For cumulocityInternal, we need to determine the API endpoint
            if (cumulocityInternal) {
                // Check if API is already set (from FlowResultOutboundProcessor)
                if (derivedAPI == null) {
                    // Fallback: Try to derive API from publishTopic if not already set
                    String publishTopic = request.getPublishTopic() != null ? request.getPublishTopic() : context.getResolvedPublishTopic();
                    if (publishTopic != null && !publishTopic.isEmpty()) {
                        derivedAPI = APITopicUtil.deriveAPIFromTopic(publishTopic);
                        if (derivedAPI != null) {
                            request.setApi(derivedAPI);
                            log.info("{} - Topic '{}' -> API {} ({}/{})",
                                    tenant, publishTopic, derivedAPI.name, i + 1, requests.size());
                        } else {
                            log.warn("{} - Cannot derive API type from topic '{}' and no API set for cumulocityInternal connector ({}/{}), skipping",
                                    tenant, publishTopic, i + 1, requests.size());
                            request.setError(new Exception("Cannot derive API type from topic: " + publishTopic));
                            continue;
                        }
                    } else {
                        log.warn("{} - No publishTopic available and no API set for cumulocityInternal connector ({}/{}), skipping",
                                tenant, i + 1, requests.size());
                        request.setError(new Exception("No publishTopic available and no API set for cumulocityInternal connector"));
                        continue;
                    }
                } else {
                    log.debug("{} - Using pre-set API {} for cumulocityInternal connector ({}/{})",
                            tenant, derivedAPI.name, i + 1, requests.size());
                }

                // For Cumulocity internal, use the pre-populated requestCumulocity if available
                if (request.getRequestCumulocity() != null && !request.getRequestCumulocity().isEmpty()) {
                    payload = request.getRequestCumulocity();
                    log.debug("{} - Using requestCumulocity for internal API call ({}/{})",
                            tenant, i + 1, requests.size());
                }

                // Use the pathCumulocity field for Cumulocity REST endpoint
                // This field is set by FlowResultOutboundProcessor with proper ID handling
                if (request.getPathCumulocity() != null && !request.getPathCumulocity().isEmpty()) {
                    contextPath = request.getPathCumulocity();
                    log.debug("{} - Using pathCumulocity: {} ({}/{})",
                            tenant, contextPath, i + 1, requests.size());
                } else {
                    // Fallback to API base path if pathCumulocity is not set
                    contextPath = derivedAPI.path;
                    log.debug("{} - Using API base path as fallback: {} ({}/{})",
                            tenant, contextPath, i + 1, requests.size());
                }

                // Default method to POST if not set
                if (method == null) {
                    method = RequestMethod.POST;
                }
            } else {
                // For external webhooks, use publishTopic as the path
                contextPath = request.getPublishTopic() != null ? request.getPublishTopic() : context.getResolvedPublishTopic();
            }

            if (contextPath == null || contextPath.isEmpty()) {
                log.warn("{} - No path specified for request ({}/{}), skipping", tenant, i + 1, requests.size());
                request.setError(new Exception("No publish path specified"));
                continue;
            }

            // Build full path
            String fullPath = buildFullPath(contextPath);

            log.debug("{} - Publishing ({}/{}): path={}, method={}, API={}",
                    tenant, i + 1, requests.size(), fullPath, method, request.getApi());

            try {
                Mono<ResponseEntity<String>> responseEntity = executeHttpRequest(method, fullPath, payload, context);

                ResponseEntity<String> response = responseEntity.block();

                if (response != null && response.getStatusCode().is2xxSuccessful()) {
                    // Always log success with method info to verify correct build is deployed
                    log.info("{} - ✅ Published successfully ({}/{}): method={}, status={}, API={}, path={}",
                            tenant, i + 1, requests.size(), method, response.getStatusCode(),
                            request.getApi() != null ? request.getApi().name : "EXTERNAL", fullPath);

                    if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
                        log.info("{} - Request details ({}/{}): mapping={}, payload={}",
                                tenant, i + 1, requests.size(), context.getMapping().getName(), payload);
                    }
                    log.debug("{} - Response body ({}/{}): {}", tenant, i + 1, requests.size(),
                            response.getBody() != null ? response.getBody() : "<empty>");
                } else {
                    String error = String.format("Failed to publish (%d/%d): status %s",
                            i + 1, requests.size(), response != null ? response.getStatusCode() : "unknown");
                    log.error("{} - {} - Payload: {}", tenant, error, payload);
                    request.setError(new Exception(error));
                    context.addError(new ProcessingException(error));
                }

            } catch (Exception e) {
                String error = String.format("Error publishing to %s (%d/%d): %s", fullPath, i + 1, requests.size(), e.getMessage());
                log.error("{} - {} - Payload: {}", tenant, error, payload, e);
                request.setError(e);
                context.addError(new ProcessingException(error, e));
            }
        }
    }

    /**
     * Build full path from context path
     */
    private String buildFullPath(String contextPath) {
        if (!baseUrlEndsWithSlash && !contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        return baseUrl + contextPath;
    }

    /**
     * Execute HTTP request based on method
     */
    private Mono<ResponseEntity<String>> executeHttpRequest(RequestMethod method, String path, String payload, ProcessingContext<?> context) {
        switch (method) {
            case PUT:
                return executePut(path, payload);
            case DELETE:
                return executeDelete(path);
            case PATCH:
                return executePatch(path, payload, context);
            default:
                return executePost(path, payload);
        }
    }

    /**
     * Execute POST request
     */
    private Mono<ResponseEntity<String>> executePost(String path, String payload) {
        return webhookClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(payload), String.class)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                .toEntity(String.class);
    }

    /**
     * Execute PUT request
     */
    private Mono<ResponseEntity<String>> executePut(String path, String payload) {
        return webhookClient.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(payload), String.class)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                .toEntity(String.class);
    }

    /**
     * Execute DELETE request
     */
    private Mono<ResponseEntity<String>> executeDelete(String path) {
        return webhookClient.delete()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                .toEntity(String.class);
    }

    /**
     * Execute PATCH request
     */
    private Mono<ResponseEntity<String>> executePatch(String path, String payload, ProcessingContext<?> context) {
        Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("cumulocityInternal", false);

        if (cumulocityInternal) {
            // For Cumulocity internal, do GET + merge + PUT
            return patchObject(path, payload, context);
        } else {
            // For external, do direct PATCH
            return webhookClient.patch()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Mono.just(payload), String.class)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                    .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                    .toEntity(String.class);
        }
    }

    /**
     * Handle PATCH for Cumulocity internal (GET + merge + PUT)
     */
    public Mono<ResponseEntity<String>> patchObject(String path, String payload, ProcessingContext<?> context) {
        // Get logging flags from context
        final boolean enableDetailedLogging = context != null &&
                (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload());

        log.info("{} - PATCH: Starting GET+merge+PUT for path: {}", tenant, path);

        if (enableDetailedLogging) {
            log.info("{} - PATCH: Update payload: {}", tenant, payload);
        }

        return webhookClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                .toEntity(String.class)
                .flatMap(existingResponse -> {
                    try {
                        if (enableDetailedLogging) {
                            log.info("{} - PATCH: Existing object retrieved: {}", tenant, existingResponse.getBody());
                        }

                        String mergedPayload = mergeJsonObjects(existingResponse.getBody(), payload, context);

                        if (enableDetailedLogging) {
                            log.info("{} - PATCH: Merged payload for PUT: {}", tenant, mergedPayload);
                        }

                        return webhookClient.put()
                                .uri(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(BodyInserters.fromValue(mergedPayload))
                                .retrieve()
                                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                                .toEntity(String.class);
                    } catch (IOException e) {
                        log.error("{} - PATCH: Error merging JSON: {}", tenant, e.getMessage(), e);
                        return Mono.error(new ProcessingException("Error merging JSON: " + e.getMessage(), e));
                    }
                });
    }

    /**
     * Merge JSON objects for PATCH operation
     */
    public String mergeJsonObjects(String existingJson, String newJson, ProcessingContext<?> context) throws IOException {
        JsonNode existingNode = objectMapper.readTree(existingJson);
        JsonNode newNode = objectMapper.readTree(newJson);
        JsonNode mergedNode = mergeNodes(existingNode, newNode, context);
        return objectMapper.writeValueAsString(mergedNode);
    }

    /**
     * Merge JSON nodes (top-level merge)
     */
    public JsonNode mergeNodes(JsonNode existingNode, JsonNode updateNode, ProcessingContext<?> context) {
        ObjectNode result = objectMapper.createObjectNode();

        // First, copy all existing fields
        Iterator<String> existingFieldNames = existingNode.fieldNames();
        while (existingFieldNames.hasNext()) {
            String fieldName = existingFieldNames.next();
            result.set(fieldName, existingNode.get(fieldName));
        }

        // Then, update/add fields from updateNode
        Iterator<String> updateFieldNames = updateNode.fieldNames();
        while (updateFieldNames.hasNext()) {
            String fieldName = updateFieldNames.next();
            JsonNode fieldValue = updateNode.get(fieldName);

            if (result.has(fieldName)) {
                JsonNode existingValue = result.get(fieldName);

                if (existingValue.isObject() && fieldValue.isObject()) {
                    result.set(fieldName, deepMergeObjects(existingValue, fieldValue));
                } else {
                    result.set(fieldName, fieldValue);
                }
            } else {
                result.set(fieldName, fieldValue);
            }
        }

        // Remove read-only fields that Cumulocity rejects in PUT requests
        removeReadOnlyFields(result, context);

        return result;
    }

    /**
     * Remove read-only system fields from a JSON node before sending to Cumulocity.
     * These fields are returned in GET responses but cannot be updated via PUT.
     */
    private void removeReadOnlyFields(ObjectNode node, ProcessingContext<?> context) {
        // List of read-only fields that Cumulocity rejects in PUT requests
        String[] readOnlyFields = {
            "self",
            "id",
            "lastUpdated",
            "creationTime",
            "owner",
            "childDevices",
            "childAssets",
            "childAdditions",
            "deviceParents",
            "assetParents",
            "additionParents"
        };

        List<String> removedFields = new ArrayList<>();
        for (String field : readOnlyFields) {
            if (node.has(field)) {
                node.remove(field);
                removedFields.add(field);
            }
        }

        if (!removedFields.isEmpty()) {
            // Get logging flags from context
            boolean enableDetailedLogging = context != null &&
                    (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload());

            if (enableDetailedLogging) {
                log.info("{} - PATCH: Removed read-only fields from merged object: {}", tenant, removedFields);
            } else {
                log.debug("{} - PATCH: Removed {} read-only fields from merged object", tenant, removedFields.size());
            }
        }
    }

    /**
     * Deep merge JSON objects (preserves all fields)
     */
    private JsonNode deepMergeObjects(JsonNode existingObj, JsonNode updateObj) {
        ObjectNode result = objectMapper.createObjectNode();

        // Copy all existing fields
        Iterator<String> existingFieldNames = existingObj.fieldNames();
        while (existingFieldNames.hasNext()) {
            String fieldName = existingFieldNames.next();
            result.set(fieldName, existingObj.get(fieldName));
        }

        // Update with new fields
        Iterator<String> updateFieldNames = updateObj.fieldNames();
        while (updateFieldNames.hasNext()) {
            String fieldName = updateFieldNames.next();
            JsonNode fieldValue = updateObj.get(fieldName);

            if (result.has(fieldName) && result.get(fieldName).isObject() && fieldValue.isObject()) {
                result.set(fieldName, deepMergeObjects(result.get(fieldName), fieldValue));
            } else {
                result.set(fieldName, fieldValue);
            }
        }

        return result;
    }

    /**
     * Handle client errors (4xx)
     */
    private Mono<Throwable> handleClientError(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        String error = "Client error: " + response.statusCode();
        log.error("{} - {}", tenant, error);
        return Mono.error(new ProcessingException(error, response.statusCode().value()));
    }

    /**
     * Handle server errors (5xx)
     */
    private Mono<Throwable> handleServerError(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        String error = "Server error: " + response.statusCode();
        log.error("{} - {}", tenant, error);
        return Mono.error(new ProcessingException(error, response.statusCode().value()));
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        Boolean cumulocityInternal = (Boolean) configuration.getProperties()
                .getOrDefault("cumulocityInternal", false);

        if (cumulocityInternal) {
            MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
            if (msc == null || StringUtils.isEmpty(msc.getUsername()) || StringUtils.isEmpty(msc.getPassword())) {
                return false;
            }
        }

        // Validate authentication
        String authentication = (String) configuration.getProperties().get("authentication");
        String user = (String) configuration.getProperties().get("user");
        String password = (String) configuration.getProperties().get("password");
        String token = (String) configuration.getProperties().get("token");

        if ("Basic".equalsIgnoreCase(authentication)) {
            if (StringUtils.isEmpty(user) || StringUtils.isEmpty(password)) {
                return false;
            }
        } else if ("Bearer".equalsIgnoreCase(authentication)) {
            if (StringUtils.isEmpty(token)) {
                return false;
            }
        }

        // Check required properties
        for (Map.Entry<String, ConnectorProperty> entry : connectorSpecification.getProperties().entrySet()) {
            if (entry.getValue().getRequired() && configuration.getProperties().get(entry.getKey()) == null) {
                return false;
            }
        }

        return true;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        if (direction == Direction.INBOUND) {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicInbound", "false").toString());
        } else {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicOutbound", "true").toString());
        }
    }

    @Override
    public void monitorSubscriptions() {
        // WebHook is outbound only - no subscriptions to monitor
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Optional: Periodic health check
        String healthEndpoint = (String) connectorConfiguration.getProperties().get("baseUrlHealthEndpoint");
        if (!StringUtils.isEmpty(healthEndpoint) && isConnected()) {
            try {
                checkHealth().subscribe(
                        response -> log.debug("{} - Health check passed", tenant),
                        error -> log.warn("{} - Health check failed: {}", tenant, error.getMessage()));
            } catch (Exception e) {
                log.warn("{} - Error during health check: {}", tenant, e.getMessage());
            }
        }
    }

    @Override
    public List<Direction> supportedDirections() {
        return Collections.singletonList(Direction.OUTBOUND);
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    /**
     * Create WebHook connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("Webhook", ConnectorType.WEB_HOOK)
                .description("Webhook to send outbound messages to the configured REST endpoint as POST in JSON format. " +
                        "The publishTopic is appended to the REST endpoint. " +
                        "In case the endpoint does not end with a trailing / and the publishTopic does not start with a / it is automatically added. " +
                        "The health endpoint is tested with a GET request. " +
                        "Supports POST, PUT, PATCH, and DELETE methods.")
                .supportsMessageContext(true)
                .supportedDirections(supportedDirections())

                // Cumulocity internal mode toggle
                .property("cumulocityInternal", ConnectorPropertyBuilder.optionalBoolean()
                        .order(7)
                        .description("When checked the webHook connector can automatically connect to the Cumulocity instance the mapper is deployed to.")
                        .defaultValue(false))

                // External URL configuration (only when not using internal mode)
                .property("baseUrl", ConnectorPropertyBuilder.requiredString()
                        .order(0)
                        .condition("cumulocityInternal", "false"))

                .property("authentication", ConnectorPropertyBuilder.optionalOption()
                        .order(1)
                        .options("Basic", "Bearer")
                        .condition("cumulocityInternal", "false"))

                .property("user", ConnectorPropertyBuilder.optionalString()
                        .order(2)
                        .condition("authentication", "Basic"))

                .property("password", ConnectorPropertyBuilder.optionalSensitive()
                        .order(3)
                        .condition("authentication", "Basic"))

                .property("token", ConnectorPropertyBuilder.optionalString()
                        .order(4)
                        .condition("authentication", "Bearer"))

                .property("headerAccept", ConnectorPropertyBuilder.optionalString()
                        .order(5)
                        .defaultValue("application/json")
                        .condition("cumulocityInternal", "false"))

                .property("baseUrlHealthEndpoint", ConnectorPropertyBuilder.optionalString()
                        .order(6)
                        .description("health endpoint for GET request")
                        .condition("cumulocityInternal", "false"))

                .property("headers", ConnectorPropertyBuilder.create(ConnectorPropertyType.MAP_PROPERTY)
                        .order(8)
                        .description("Define additional headers")
                        .required(false)
                        .defaultValue(new HashMap<String, String>())
                        .condition("cumulocityInternal", "false"))

                // Wildcard support (read-only)
                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(9)
                        .readonly(true)
                        .defaultValue(false))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(10)
                        .readonly(true)
                        .defaultValue(true))

                .build();
    }

}