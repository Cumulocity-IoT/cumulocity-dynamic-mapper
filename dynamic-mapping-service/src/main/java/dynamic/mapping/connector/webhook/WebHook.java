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

package dynamic.mapping.connector.webhook;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import jakarta.ws.rs.NotSupportedException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorPropertyCondition;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.core.ConnectorStatusEvent;

@Slf4j
public class WebHook extends AConnectorClient {
    public WebHook() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        ConnectorPropertyCondition basicAuthenticationCondition = new ConnectorPropertyCondition("authentication",
                new String[] { "Basic" });
        ConnectorPropertyCondition bearerAuthenticationCondition = new ConnectorPropertyCondition("authentication",
                new String[] { "Bearer" });
        configProps.put("baseUrl",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null,
                        null));
        configProps.put("authentication",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.OPTION_PROPERTY, false, false, null,
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("Basic", "Basic"),
                                new AbstractMap.SimpleEntry<String, String>("Bearer", "Bearer")),
                        null));
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
                        null));
        configProps.put("baseUrlHealthEndpoint",
                new ConnectorProperty("health endpoint for GET request", false, 6,
                        ConnectorPropertyType.STRING_PROPERTY, false, false, null, null, null));
        String name = "Webhook";
        String description = "Webhook to send outbound messages to the configured REST endpoint as POST in JSON format. The publishTopic is appended to the Rest endpoint. In case the endpoint does not end with a trailing / and the publishTopic is not start with a / it is automatically added. The health endpoint is tested with a GET request.";
        connectorType = ConnectorType.WEB_HOOK;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false,
                supportedDirections());
    }

    public WebHook(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            DispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        // this.connectorType = connectorConfiguration.connectorType;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtThreadPool = configurationRegistry.getVirtThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
    }

    protected RestClient webhookClient;

    protected String baseUrl;
    protected Boolean baseUrlEndsWithSlash;

    public boolean initialize() {
        loadConfiguration();
        log.info("Tenant {} - Connector {} - Initialization of connector {} was successful!", tenant,
                getConnectorType(),
                getConnectorName());
        return true;
    }

    @Override
    public void connect() {
        log.info("Tenant {} - Trying to connect to {} - phase I: (isConnected:shouldConnect) ({}:{})",
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

        // Create RestClient builder
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory())
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

        // Build the client
        webhookClient = builder.build();

        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Tenant {} - Trying to connect {} - phase II: (shouldConnect):{} {}", tenant,
                        getConnectorName(),
                        shouldConnect(), baseUrl);
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        // ignore errorMessage
                        // log.error("Tenant {} - Error on reconnect: {}", tenant, e.getMessage());
                    }
                }
                try {
                    if (!StringUtils.isEmpty(baseUrlHealthEndpoint)) {
                        checkHealth(baseUrlHealthEndpoint);
                    }

                    connectionState.setTrue();
                    log.info("Tenant {} - Connected to webHook endpoint {}", tenant,
                            baseUrl);
                    updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                    List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant);
                    updateActiveSubscriptionsOutbound(updatedMappingsOutbound);

                } catch (Exception e) {
                    log.error("Tenant {} - Error connecting to webHook: {}, {}, {}", tenant,
                            baseUrl, e.getMessage(), connectionState.booleanValue());
                    updateConnectorStatusToFailed(e);
                    sendConnectorLifecycle();
                }
                firstRun = false;
            }

            try {
                // test if the mqtt connection is configured and enabled
                if (shouldConnect()) {
                    mappingComponent.rebuildMappingOutboundCache(tenant);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // previously used updatedMappings
                }
                successful = true;
            } catch (Exception e) {
                log.error("Tenant {} - Error on reconnect, retrying ... {}: ", tenant, e.getMessage(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                if (serviceConfiguration.logConnectorErrorInBackend) {
                    log.error("Tenant {} - Stacktrace: ", tenant, e);
                }
                successful = false;
            }
        }
    }

    public ResponseEntity<String> checkHealth(String baseUrlHealthEndpoint) {
        try {
            return webhookClient.get()
                    .uri(baseUrlHealthEndpoint) // Use the full health endpoint URL
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw new RuntimeException(
                                "Health check failed with client error: " + response.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new RuntimeException(
                                "Health check failed with server error: " + response.getStatusCode());
                    })
                    .toEntity(String.class);
        } catch (Exception e) {
            log.error("Health check failed: ", e);
            throw e;
        }
    }

    @Override
    public void close() {
    }

    @Override
    public boolean // The code appears to be a method or function named "isConfigValid" in Java. It
                   // is
            // likely used to check the validity of a configuration or settings. However,
            // without the actual implementation of the method, it is not possible to
            // determine
            // the specific logic or criteria used to validate the configuration.
            isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
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
            log.info("Tenant {} - Disconnecting from webHook endpoint {}", tenant, baseUrl);

            connectionState.setFalse();
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            List<Mapping> updatedMappingsInbound = mappingComponent.rebuildMappingInboundCache(tenant);
            updateActiveSubscriptionsInbound(updatedMappingsInbound, true);
            List<Mapping> updatedMappingsOutbound = mappingComponent.rebuildMappingOutboundCache(tenant);
            updateActiveSubscriptionsOutbound(updatedMappingsOutbound);
            log.info("Tenant {} - Disconnected from webHook endpoint II: {}", tenant,
                    baseUrl);
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, QOS qos) throws ConnectorException {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    public void unsubscribe(String topic) throws Exception {
        throw new NotSupportedException("WebHook does not support inbound mappings");
    }

    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        String contextPath = context.getResolvedPublishTopic();

        // The publishTopic is appended to the Rest endpoint. In case the endpoint does
        // not end with a trailing / and the publishTopic is not start with a / it is
        // automatically added.
        if (!baseUrlEndsWithSlash && !contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        String path = (new StringBuffer(baseUrl)).append(contextPath).toString();
        log.info("Tenant {} - Published path: {}",
                tenant, path);

        try {
            ResponseEntity<String> responseEntity = webhookClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        String errorMessage = "Client error when publishing MEAO: " + response.getStatusCode();
                        log.error("Tenant {} - {} {}", tenant, errorMessage, path);
                        throw new RuntimeException(errorMessage);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        String errorMessage = "Server error when publishing MEAO: " + response.getStatusCode();
                        log.error("Tenant {} - {} {}", tenant, errorMessage, path);
                        throw new RuntimeException(errorMessage);
                    })
                    .toEntity(String.class);

            if (responseEntity.getStatusCode().is2xxSuccessful()) {
                log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}, {}, {}",
                        tenant, payload, context.getMapping().name, context.getResolvedPublishTopic(), path,
                        connectorName);
            }

        } catch (Exception e) {
            String errorMessage = "Failed to publish MEAO message";
            log.error("Tenant {} - {} - Error: {}", tenant, errorMessage, e.getMessage());
            throw new RuntimeException(errorMessage, e);
        }
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return true;
    }

    @Override
    public void monitorSubscriptions() {
        // nothing to do
    }

    @Override
    public List<Direction> supportedDirections() {
        return new ArrayList<>(Arrays.asList(Direction.OUTBOUND));
    }

}