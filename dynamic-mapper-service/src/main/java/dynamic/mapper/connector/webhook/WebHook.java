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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import jakarta.ws.rs.NotSupportedException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.core.ConnectorStatusEvent;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
public class WebHook extends AConnectorClient {
    public WebHook() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        ConnectorPropertyCondition basicAuthenticationCondition = new ConnectorPropertyCondition("authentication",
                new String[] { "Basic" });
        ConnectorPropertyCondition bearerAuthenticationCondition = new ConnectorPropertyCondition("authentication",
                new String[] { "Bearer" });
        ConnectorPropertyCondition cumulocityInternal = new ConnectorPropertyCondition("cumulocityInternal",
                new String[] { "false" });

        configProps.put("baseUrl",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        cumulocityInternal));
        configProps.put("authentication",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.OPTION_PROPERTY, false, false, null,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("Basic", "Basic"),
                                new AbstractMap.SimpleEntry<String, String>("Bearer", "Bearer")),
                        cumulocityInternal));
        configProps.put("user",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        basicAuthenticationCondition));
        configProps.put("password",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null,
                        null, basicAuthenticationCondition));
        configProps.put("token",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        bearerAuthenticationCondition));
        configProps.put("headerAccept",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "application/json", null,
                        cumulocityInternal));
        configProps.put("baseUrlHealthEndpoint",
                new ConnectorProperty("health endpoint for GET request", false, 6,
                        ConnectorPropertyType.STRING_PROPERTY, false, false, null, null, cumulocityInternal));
        configProps.put("cumulocityInternal",
                new ConnectorProperty(
                        "When checked the webHook connector can automatically connect to the Cumulocity instance the mapper is deployed to.",
                        false, 7, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false, false, null,
                        null));
        configProps.put("headers",
                new ConnectorProperty("Define additional headers", false, 7, ConnectorPropertyType.MAP_PROPERTY, false,
                        false,
                        new HashMap<String, String>(),
                        null, cumulocityInternal));
        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false, true, null,
                        null));
        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false, true, null,
                        null));
        String name = "Webhook";
        String description = "Webhook to send outbound messages to the configured REST endpoint as POST in JSON format. The publishTopic is appended to the Rest endpoint. In case the endpoint does not end with a trailing / and the publishTopic is not start with a / it is automatically added. The health endpoint is tested with a GET request.";
        connectorType = ConnectorType.WEB_HOOK;
        supportsMessageContext = true;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, singleton, configProps,
                supportsMessageContext,
                supportedDirections());
    }

    public WebHook(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorId = new ConnectorId(connectorConfiguration.name, connectorConfiguration.identifier,
                connectorType);
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        // this.connectorType = connectorConfiguration.connectorType;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;

        Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties().getOrDefault("cumulocityInternal",
                false);
        log.info("{} - Connector {} - Cumulocity internal: {}", tenant, this.connectorName, cumulocityInternal);
        if (cumulocityInternal) {
            MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
            String user = String.format("%s/%s", tenant, msc.getUsername());
            getConnectorSpecification().getProperties().put("user",
                    new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, true, true, user, null,
                            null));
            getConnectorSpecification().getProperties().put("password",
                    new ConnectorProperty(null, true, 3, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, true,
                            msc.getPassword(), null, null));
            getConnectorSpecification().getProperties().put("authentication",
                    new ConnectorProperty(null, false, 1, ConnectorPropertyType.OPTION_PROPERTY, true, true, "Basic",
                            null, null));
            getConnectorSpecification().getProperties().put("baseUrl",
                    new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY, true, true,
                            "http://cumulocity:8111", null,
                            null));
            getConnectorSpecification().getProperties().put("headerAccept",
                    new ConnectorProperty(null, false, 5, ConnectorPropertyType.STRING_PROPERTY, true, true,
                            "application/json", null,
                            null));
            // getConnectorSpecification().getProperties().put("baseUrlHealthEndpoint",
            // new ConnectorProperty("health endpoint for GET request", false, 6,
            // ConnectorPropertyType.STRING_PROPERTY, true, true,
            // "http://cumulocity:8111/application/currentApplication", null, null));

            getConnectorSpecification().getProperties().put("baseUrlHealthEndpoint",
                    new ConnectorProperty("health endpoint for GET request", false, 6,
                            ConnectorPropertyType.STRING_PROPERTY, true, true,
                            "http://cumulocity:8111/notification2", null, null));

        }
    }

    protected WebClient webhookClient;

    protected String baseUrl;
    protected Boolean baseUrlEndsWithSlash;

    public boolean initialize() {
        loadConfiguration();
        log.info("{} - Phase 0: {} initialized, connectorType: {}", tenant,
                getConnectorType(),
                getConnectorName());
        return true;
    }

    @Override
    public void connect() {
        log.info("{} - Phase I: {} connecting, isConnected: {}, shouldConnect: {}",
                tenant, getConnectorName(), isConnected(),
                shouldConnect());
        if (isConnected())
            disconnect();

        if (shouldConnect())
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());
        baseUrl = (String) connectorConfiguration.getProperties().getOrDefault("baseUrl", null);
        baseUrlEndsWithSlash = baseUrl.endsWith("/");
        // if no baseUrlHealthEndpoint is defined use the baseUrl
        String baseUrlHealthEndpoint = (String) connectorConfiguration.getProperties()
                .getOrDefault("baseUrlHealthEndpoint", null);
        String authentication = (String) connectorConfiguration.getProperties().get("authentication");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String token = (String) connectorConfiguration.getProperties().get("token");
        String headerAccept = (String) connectorConfiguration.getProperties().getOrDefault("headerAccept",
                "application/json");
        Map headers = (Map) connectorConfiguration.getProperties().get("headers");

        // Create RestClient builder
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", headerAccept);

        // Add authentication if specified
        if ("Basic".equalsIgnoreCase(authentication) && !StringUtils.isEmpty(user) && !StringUtils.isEmpty(password)) {
            String credentials = Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader("Authorization", "Basic " + credentials);
        } else if ("Bearer".equalsIgnoreCase(authentication) && password != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }

        // Add additional headers if specified
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((key, value) -> {
                if (value != null) {
                    builder.defaultHeader(key.toString(), value.toString());
                }
            });
        }

        // Build the client
        webhookClient = builder.build();

        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            var firstRun = true;
            var mappingOutboundCacheRebuild = false;
            while (!isConnected() && shouldConnect()) {

                log.info("{} - Phase II: {} connecting, shouldConnect: {}, server: {}", tenant,
                        getConnectorName(),
                        shouldConnect(), baseUrl);
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        // ignore errorMessage
                        // log.error("{} - Error on reconnect: {}", tenant, e.getMessage());
                    }
                }
                try {
                    if (!StringUtils.isEmpty(baseUrlHealthEndpoint)) {
                        checkHealth().block();
                    }

                    connectionState.setTrue();
                    log.info("{} - Phase III: {} connected", tenant, getConnectorName(),
                            baseUrl);
                    updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                    List<Mapping> updatedMappingsOutbound = mappingService.rebuildMappingOutboundCache(tenant,
                            connectorId);
                    mappingOutboundCacheRebuild = true;
                    initializeSubscriptionsOutbound(updatedMappingsOutbound);

                } catch (Exception e) {
                    log.error("{} - Phase III: {} failed to connect to webHook: {}, {}, {}", tenant, getConnectorName(),
                            baseUrl, e.getMessage(), connectionState.booleanValue(), e);
                    updateConnectorStatusToFailed(e);
                    sendConnectorLifecycle();
                }
                firstRun = false;
            }

            if (!mappingOutboundCacheRebuild) {
                mappingService.rebuildMappingOutboundCache(tenant, connectorId);
            }
            successful = true;
        }
    }

    public Mono<ResponseEntity<String>> checkHealth() {
        try {
            String baseUrlHealthEndpoint = (String) connectorConfiguration.getProperties()
                    .getOrDefault("baseUrlHealthEndpoint", null);
            log.info("{} - Checking health of webHook endpoint {}", tenant, baseUrlHealthEndpoint);
            return webhookClient.get()
                    .uri(baseUrlHealthEndpoint) // Use the full health endpoint URL
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                        return Mono.error(new RuntimeException(
                                "Health check failed with client error: " + response.statusCode()));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                        return Mono.error(new RuntimeException(
                                "Health check failed with client error: " + response.statusCode()));

                    })
                    .toEntity(String.class);
        } catch (Exception e) {
            log.error("{} - Health check failed: ", tenant, e);
            throw e;
        }
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties().getOrDefault("cumulocityInternal",
                false);
        if (cumulocityInternal) {
            MicroserviceCredentials msc = configurationRegistry.getMicroserviceCredential(tenant);
            if (msc == null || StringUtils.isEmpty(msc.getUsername()) || StringUtils.isEmpty(msc.getPassword())) {
                return false;
            }
        }
        // if using authentication additional properties have to be set
        String authentication = (String) connectorConfiguration.getProperties().getOrDefault("authentication", null);
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String token = (String) connectorConfiguration.getProperties().get("token");
        if ("Basic".equalsIgnoreCase(authentication)
                && (StringUtils.isEmpty(user) || StringUtils.isEmpty(password))) {
            return false;
        } else if (("Bearer".equalsIgnoreCase(authentication) && (!StringUtils.isEmpty(token)))) {
            return false;
        }
        // check if all required properties are set
        for (String property : getConnectorSpecification().getProperties().keySet()) {
            if (getConnectorSpecification().getProperties().get(property).required
                    && configuration.getProperties().get(property) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        return connectionState.booleanValue();
    }

    @Override
    public void disconnect() {
        if (isConnected()) {
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTING, true, true);
            log.info("{} - Disconnecting from webHook endpoint {}", tenant, baseUrl);

            connectionState.setFalse();
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            List<Mapping> updatedMappingsInbound = mappingService.rebuildMappingInboundCache(tenant, connectorId);
            initializeSubscriptionsInbound(updatedMappingsInbound, true, true);
            List<Mapping> updatedMappingsOutbound = mappingService.rebuildMappingOutboundCache(tenant, connectorId);
            initializeSubscriptionsOutbound(updatedMappingsOutbound);
            log.info("{} - Disconnected from webHook endpoint II: {}", tenant,
                    baseUrl);
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    public void unsubscribe(String topic) throws Exception {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    public void publishMEAO(ProcessingContext<?> context) {
        DynamicMapperRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        String contextPath = context.getResolvedPublishTopic();

        // The publishTopic is appended to the Rest endpoint. In case the endpoint does
        // not end with a trailing / and the publishTopic is not start with a / it is
        // automatically added.
        if (!baseUrlEndsWithSlash && !contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        String path = (new StringBuffer(baseUrl)).append(contextPath).toString();
        log.info("{} - Published path: {}",
                tenant, path);

        try {
            RequestMethod method = currentRequest.getMethod();
            Mono<ResponseEntity<String>> responseEntity;
            if (RequestMethod.PUT.equals(method)) {
                responseEntity = webhookClient.put()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Mono.just(payload), String.class)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                            String errorMessage = "Client error when publishing MEAO: " + response.statusCode();
                            log.error("{} - {} {}", tenant, errorMessage, path);
                            return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                        })
                        .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                            String errorMessage = "Server error when publishing MEAO: " + response.statusCode();
                            log.error("{} - {} {}", tenant, errorMessage, path);
                            return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                        })
                        .toEntity(String.class);
            } else if (RequestMethod.DELETE.equals(method)) {
                responseEntity = webhookClient.delete()
                        .uri(path)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                            String errorMessage = "Client error when publishing MEAO: " + response.statusCode();
                            log.error("{} - {} {}", tenant, errorMessage, path);
                            return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                        })
                        .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                            String errorMessage = "Server error when publishing MEAO: " + response.statusCode();
                            log.error("{} - {} {}", tenant, errorMessage, path);
                            return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                        })
                        .toEntity(String.class);
            } else if (RequestMethod.PATCH.equals(method)) {
                Boolean cumulocityInternal = (Boolean) connectorConfiguration.getProperties().getOrDefault(
                        "cumulocityInternal",
                        false);
                if (cumulocityInternal) {
                    responseEntity = patchObject(tenant, path, payload);
                } else {
                    responseEntity = webhookClient.patch()
                            .uri(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Mono.just(payload), String.class)
                            .retrieve()
                            .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                                String errorMessage = "Client error when publishing MEAO: " + response.statusCode();
                                log.error("{} - {} {}", tenant, errorMessage, path);
                                return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                            })
                            .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                                String errorMessage = "Server error when publishing MEAO: " + response.statusCode();
                                log.error("{} - {} {}", tenant, errorMessage, path);
                                return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                            })
                            .toEntity(String.class);
                }
            } else {
                responseEntity = webhookClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Mono.just(payload), String.class)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                            String errorMessage = "Client error when publishing MEAO: " + response.statusCode();
                            log.error("{} - {} {}", tenant, errorMessage, path);
                            return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                        })
                        .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                            String errorMessage = "Server error when publishing MEAO: " + response.statusCode();
                            log.error("{} - {} {}", tenant, errorMessage, path);
                            return Mono.error(new ProcessingException(errorMessage, response.statusCode().value()));
                        })
                        .toEntity(String.class);
            }
            responseEntity.doOnSuccess(response -> {
                log.info("{} - Published outbound message: {} for mapping: {} on topic: [{}], {}, {}, {}",
                        tenant, payload, context.getMapping().name, context.getResolvedPublishTopic(), path,
                        connectorName, method);
            });
            responseEntity.doOnError(e -> {
                String errorMessage = "Failed to publish MEAO message";
                log.error("{} - {} - Error: {}", tenant, errorMessage, e.getMessage());
                throw new RuntimeException(errorMessage, e);
            });
            if (responseEntity.block().getStatusCode().is2xxSuccessful()) {
                log.info("{} - Published outbound message: {} for mapping: {} on topic: [{}], {}, {}, {}",
                        tenant, payload, context.getMapping().name, context.getResolvedPublishTopic(), path,
                        connectorName, method);
            }

        } catch (Exception e) {
            String errorMessage = "Failed to publish MEAO message";
            log.error("{} - {} - Error: {}", tenant, errorMessage, e.getMessage());
            throw new RuntimeException(errorMessage, e);
        }
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        if (direction == Direction.INBOUND) {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties().get("supportsWildcardInTopicInbound").toString());
        } else {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties().get("supportsWildcardInTopicOutbound").toString());
        }
    }

    @Override
    public void monitorSubscriptions() {
        // nothing to do
    }

    @Override
    public List<Direction> supportedDirections() {
        return new ArrayList<>(Arrays.asList(Direction.OUTBOUND));
    }

    public Mono<ResponseEntity<String>> patchObject(String tenant, String path, String payload) {
        // Step 1: Retrieve the existing object
        return webhookClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                    String errorMessage = "Client error when retrieving existing object: " + response.statusCode();
                    log.error("{} - {} {}", tenant, errorMessage, path);
                    return Mono.error(new RuntimeException(errorMessage));
                })
                .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                    String errorMessage = "Server error when retrieving existing object: " + response.statusCode();
                    log.error("{} - {} {}", tenant, errorMessage, path);
                    return Mono.error(new RuntimeException(errorMessage));
                })
                .toEntity(String.class)
                .flatMap(existingObjectResponse -> {
                    try {
                        // Step 2: Merge the existing payload with the new payload
                        String mergedPayload = mergeJsonObjects(existingObjectResponse.getBody(), payload);

                        // Step 3: Send the merged payload as a PUT operation
                        return webhookClient.put()
                                .uri(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(BodyInserters.fromValue(mergedPayload))
                                .retrieve()
                                .onStatus(HttpStatusCode::is4xxClientError, (response) -> {
                                    String errorMessage = "Client error when publishing merged object: "
                                            + response.statusCode();
                                    log.error("{} - {} {}", tenant, errorMessage, path);
                                    return Mono.error(new RuntimeException(errorMessage));
                                })
                                .onStatus(HttpStatusCode::is5xxServerError, (response) -> {
                                    String errorMessage = "Server error when publishing merged object: "
                                            + response.statusCode();
                                    log.error("{} - {} {}", tenant, errorMessage, path);
                                    return Mono.error(new RuntimeException(errorMessage));
                                })
                                .toEntity(String.class);
                    } catch (Exception e) {
                        log.error("{} - Error during PATCH operation for path {}: {}", tenant, path,
                                e.getMessage());
                        return Mono.error(new RuntimeException("Error during PATCH operation: " + e.getMessage(), e));
                    }
                });
    }

    public String mergeJsonObjects(String existingJson, String newJson) throws IOException {
        // Convert JSON strings to JsonNode objects
        JsonNode existingNode = objectMapper.readTree(existingJson);
        JsonNode newNode = objectMapper.readTree(newJson);

        // Perform deep merge of the two objects
        JsonNode mergedNode = mergeNodes(existingNode, newNode);

        // Convert merged node back to JSON string
        return objectMapper.writeValueAsString(mergedNode);
    }

    public JsonNode mergeNodes(JsonNode existingNode, JsonNode updateNode) {
        ObjectNode result = objectMapper.createObjectNode();

        // Only process top-level fields from update node
        updateNode.fields().forEachRemaining(field -> {
            String fieldName = field.getKey();
            JsonNode fieldValue = field.getValue();

            // Check if it exists in the existing node
            if (existingNode.has(fieldName)) {
                JsonNode existingValue = existingNode.get(fieldName);

                // If both are objects, do a deep merge
                if (existingValue.isObject() && fieldValue.isObject()) {
                    // Recursive deep merge of objects
                    result.set(fieldName, deepMergeObjects(existingValue, fieldValue));
                } else {
                    // Not both objects, use the update value
                    result.set(fieldName, fieldValue);
                }
            } else {
                // Field doesn't exist in existing node, use the update value directly
                result.set(fieldName, fieldValue);
            }
        });

        return result;
    }

    @Override
    public void connectorSpecificHousekeeping(String tenant) {
    }

    // Helper method for deep merging of objects - unlike mergeNodes, this preserves
    // all fields
    private JsonNode deepMergeObjects(JsonNode existingObj, JsonNode updateObj) {
        ObjectNode result = objectMapper.createObjectNode();

        // First, copy all fields from the existing object
        existingObj.fields().forEachRemaining(field -> result.set(field.getKey(), field.getValue()));

        // Then update with fields from the update object
        updateObj.fields().forEachRemaining(field -> {
            String fieldName = field.getKey();
            JsonNode fieldValue = field.getValue();

            // If both values are objects, recursively merge them
            if (result.has(fieldName) && result.get(fieldName).isObject() && fieldValue.isObject()) {
                result.set(fieldName, deepMergeObjects(result.get(fieldName), fieldValue));
            } else {
                // Otherwise, take the update value
                result.set(fieldName, fieldValue);
            }
        });

        return result;
    }

}