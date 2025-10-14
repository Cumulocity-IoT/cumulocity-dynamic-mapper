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
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
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
        this.supportsMessageContext = true; // Supports context for HTTP methods
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
                log.warn("{} - No health endpoint configured for WebHook connector {}, skipping health check and assuming connection is valid", tenant, connectorName);
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

        log.info("{} - Checking health of webHook endpoint: {}", tenant, healthEndpoint);

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
    public boolean isConnected() {
        return connectionStateManager.isConnected();
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (webhookClient == null) {
            log.error("{} - WebClient is not initialized", tenant);
            return;
        }

        DynamicMapperRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        String contextPath = context.getResolvedPublishTopic();
        RequestMethod method = currentRequest.getMethod();

        // Build full path
        String fullPath = buildFullPath(contextPath);

        log.debug("{} - Publishing to path: {}, method: {}", tenant, fullPath, method);

        try {
            Mono<ResponseEntity<String>> responseEntity = executeHttpRequest(method, fullPath, payload);

            ResponseEntity<String> response = responseEntity.block();

            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                if (context.getMapping().getDebug() || serviceConfiguration.isLogPayload()) {
                    log.info("{} - Published message successfully: path: {}, method: {}, mapping: {}",
                            tenant, fullPath, method, context.getMapping().getName());
                }
            } else {
                String error = String.format("Failed to publish: status %s",
                        response != null ? response.getStatusCode() : "unknown");
                log.error("{} - {}", tenant, error);
                context.addError(new ProcessingException(error));
            }

        } catch (Exception e) {
            String error = String.format("Error publishing to %s: %s", fullPath, e.getMessage());
            log.error("{} - {}", tenant, error, e);
            context.addError(new ProcessingException(error, e));
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
    private Mono<ResponseEntity<String>> executeHttpRequest(RequestMethod method, String path, String payload) {
        switch (method) {
            case PUT:
                return executePut(path, payload);
            case DELETE:
                return executeDelete(path);
            case PATCH:
                return executePatch(path, payload);
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
    private Mono<ResponseEntity<String>> executePatch(String path, String payload) {
        Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("cumulocityInternal", false);

        if (cumulocityInternal) {
            // For Cumulocity internal, do GET + merge + PUT
            return patchObject(path, payload);
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
    public Mono<ResponseEntity<String>> patchObject(String path, String payload) {
        return webhookClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                .toEntity(String.class)
                .flatMap(existingResponse -> {
                    try {
                        String mergedPayload = mergeJsonObjects(existingResponse.getBody(), payload);

                        return webhookClient.put()
                                .uri(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(BodyInserters.fromValue(mergedPayload))
                                .retrieve()
                                .onStatus(HttpStatusCode::is4xxClientError, this::handleClientError)
                                .onStatus(HttpStatusCode::is5xxServerError, this::handleServerError)
                                .toEntity(String.class);
                    } catch (IOException e) {
                        return Mono.error(new ProcessingException("Error merging JSON: " + e.getMessage(), e));
                    }
                });
    }

    /**
     * Merge JSON objects for PATCH operation
     */
    public String mergeJsonObjects(String existingJson, String newJson) throws IOException {
        JsonNode existingNode = objectMapper.readTree(existingJson);
        JsonNode newNode = objectMapper.readTree(newJson);
        JsonNode mergedNode = mergeNodes(existingNode, newNode);
        return objectMapper.writeValueAsString(mergedNode);
    }

    /**
     * Merge JSON nodes (top-level merge)
     */
    public JsonNode mergeNodes(JsonNode existingNode, JsonNode updateNode) {
        ObjectNode result = objectMapper.createObjectNode();

        Iterator<String> fieldNames = updateNode.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode fieldValue = updateNode.get(fieldName);

            if (existingNode.has(fieldName)) {
                JsonNode existingValue = existingNode.get(fieldName);

                if (existingValue.isObject() && fieldValue.isObject()) {
                    result.set(fieldName, deepMergeObjects(existingValue, fieldValue));
                } else {
                    result.set(fieldName, fieldValue);
                }
            } else {
                result.set(fieldName, fieldValue);
            }
        }

        return result;
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
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        ConnectorPropertyCondition basicAuthCondition = new ConnectorPropertyCondition(
                "authentication", new String[] { "Basic" });
        ConnectorPropertyCondition bearerAuthCondition = new ConnectorPropertyCondition(
                "authentication", new String[] { "Bearer" });
        ConnectorPropertyCondition cumulocityInternalCondition = new ConnectorPropertyCondition(
                "cumulocityInternal", new String[] { "false" });

        configProps.put("baseUrl",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, cumulocityInternalCondition));

        configProps.put("authentication",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.OPTION_PROPERTY,
                        false, false, null,
                        Map.of("Basic", "Basic", "Bearer", "Bearer"),
                        cumulocityInternalCondition));

        configProps.put("user",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, basicAuthCondition));

        configProps.put("password",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                        false, false, null, null, basicAuthCondition));

        configProps.put("token",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, bearerAuthCondition));

        configProps.put("headerAccept",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, "application/json", null, cumulocityInternalCondition));

        configProps.put("baseUrlHealthEndpoint",
                new ConnectorProperty("health endpoint for GET request", false, 6,
                        ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        cumulocityInternalCondition));

        configProps.put("cumulocityInternal",
                new ConnectorProperty(
                        "When checked the webHook connector can automatically connect to the Cumulocity instance the mapper is deployed to.",
                        false, 7, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, false, null, null));

        configProps.put("headers",
                new ConnectorProperty("Define additional headers", false, 8,
                        ConnectorPropertyType.MAP_PROPERTY, false, false,
                        new HashMap<String, String>(), null, cumulocityInternalCondition));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, false, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, true, null, null));

        String name = "Webhook";
        String description = "Webhook to send outbound messages to the configured REST endpoint as POST in JSON format. "
                +
                "The publishTopic is appended to the REST endpoint. " +
                "In case the endpoint does not end with a trailing / and the publishTopic does not start with a / it is automatically added. "
                +
                "The health endpoint is tested with a GET request. " +
                "Supports POST, PUT, PATCH, and DELETE methods.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.WEB_HOOK,
                false,
                configProps,
                true, // supportsMessageContext
                supportedDirections());
    }

}