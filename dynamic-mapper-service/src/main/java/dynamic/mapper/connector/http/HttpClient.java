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

package dynamic.mapper.connector.http;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * HTTP Connector Client.
 * Receives messages via HTTP POST requests to a REST endpoint.
 * The URL path after the base endpoint is used as the mapping topic.
 * 
 * Example: POST to /service/dynamic-mapper-service/httpConnector/temp/berlin_01
 * will match mappings with topic "temp/berlin_01"
 */
@Slf4j
public class HttpClient extends AConnectorClient {

    // Constants
    public static final String HTTP_CONNECTOR_PATH = "httpConnector";
    public static final String HTTP_CONNECTOR_IDENTIFIER = "HTTP_CONNECTOR_IDENTIFIER";
    public static final String HTTP_CONNECTOR_ABSOLUTE_PATH = "/httpConnector";
    public static final String PROPERTY_CUTOFF_LEADING_SLASH = "cutOffLeadingSlash";

    @Getter
    protected List<Qos> supportedQOS;

    /**
     * Default constructor
     */
    public HttpClient() {
        this.connectorType = ConnectorType.HTTP;
        this.singleton = true; // HTTP endpoint is singleton
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE);
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public HttpClient(ConfigurationRegistry configurationRegistry,
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

        // Initialize managers
        initializeManagers();
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            String path = getHttpPath();
            log.info("{} - HTTP connector initialized, endpoint: {}", tenant, path);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;
        } catch (Exception e) {
            log.error("{} - Error initializing HTTP connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    @Override
    public void connect() {
        log.info("{} - Connecting HTTP connector: {}", tenant, connectorName);

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

            String path = getHttpPath();

            // HTTP connector is always "connected" - it's a passive receiver
            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize subscriptions
            List<Mapping> inboundMappings = mappingService.getMappings(tenant, Direction.INBOUND);
            initializeSubscriptionsInbound(inboundMappings, true, true);

            log.info("{} - HTTP connector connected, endpoint: {}", tenant, path);

        } catch (Exception e) {
            log.error("{} - Error connecting HTTP connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
        }
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        // HTTP is passive - subscriptions are just tracked for routing
        log.debug("{} - Subscribed to HTTP topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Subscribed");
    }

    @Override
    protected void unsubscribe(String topic) throws Exception {
        // HTTP is passive - just remove from tracking
        log.debug("{} - Unsubscribed from HTTP topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribed");
    }

    @Override
    public void disconnect() {
        if (!isConnected()) {
            log.debug("{} - Already disconnected", tenant);
            return;
        }

        log.info("{} - Disconnecting HTTP connector", tenant);
        connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        try {
            String path = getHttpPath();

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            // Rebuild caches
            List<Mapping> inboundMappings = mappingService.getMappings(tenant, Direction.INBOUND);
            initializeSubscriptionsInbound(inboundMappings, true, true);

            log.info("{} - HTTP connector disconnected, endpoint: {}", tenant, path);

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
        // HTTP connector is connected if state is true
        // It's a passive receiver, so there's no active connection to test
        return connectionStateManager.isConnected();
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        // HTTP connector is inbound only - no outbound publishing
        log.warn("{} - HTTP connector does not support outbound publishing", tenant);
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        // HTTP connector has minimal configuration requirements
        // All properties have defaults, so configuration is always valid
        return configuration != null;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        if (direction == Direction.INBOUND) {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicInbound", "true").toString());
        } else {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicOutbound", "false").toString());
        }
    }

    @Override
    public void monitorSubscriptions() {
        // HTTP is passive - no subscriptions to monitor
        // All subscriptions are always "active" as they just define routing rules
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // No specific housekeeping needed for HTTP connector
        // It's a passive receiver with no active connections
    }

    @Override
    public List<Direction> supportedDirections() {
        return Collections.singletonList(Direction.INBOUND);
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
     * Handle incoming HTTP message
     * Called by the HTTP endpoint controller
     * 
     * @param message The connector message containing the HTTP request data
     */
    public void onMessage(ConnectorMessage message) {
        if (dispatcher == null) {
            log.error("{} - Dispatcher is not initialized, cannot process message", tenant);
            return;
        }

        try {
            if (log.isDebugEnabled()) {
                log.debug("{} - Received HTTP message on topic: [{}]",
                        tenant, message.getTopic());
            }

            dispatcher.onMessage(message);

        } catch (Exception e) {
            log.error("{} - Error processing HTTP message on topic: [{}]",
                    tenant, message.getTopic(), e);
        }
    }

    /**
     * Get the HTTP endpoint path from configuration
     */
    private String getHttpPath() {
        Object pathValue = connectorConfiguration != null &&
                connectorConfiguration.getProperties() != null
                        ? connectorConfiguration.getProperties().get("path")
                        : null;

        if (pathValue != null) {
            return pathValue.toString();
        }

        // Fallback to default from specification
        return connectorSpecification.getProperties().get("path").getDefaultValue().toString();
    }

    /**
     * Check if leading slash should be cut off from topic path
     */
    public boolean shouldCutOffLeadingSlash() {
        Object cutOffValue = connectorConfiguration != null &&
                connectorConfiguration.getProperties() != null
                        ? connectorConfiguration.getProperties().get(PROPERTY_CUTOFF_LEADING_SLASH)
                        : null;

        if (cutOffValue != null) {
            return Boolean.parseBoolean(cutOffValue.toString());
        }

        // Default to true
        return true;
    }

    /**
     * Convert HTTP path to mapping topic
     * Handles the optional leading slash removal
     * 
     * @param path The HTTP request path after the base endpoint
     * @return The mapping topic to use for routing
     */
    public String pathToTopic(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        String topic = path;

        if (shouldCutOffLeadingSlash() && topic.startsWith("/")) {
            topic = topic.substring(1);
        }

        log.debug("{} - Converted HTTP path '{}' to topic '{}'", tenant, path, topic);

        return topic;
    }

    /**
     * Create HTTP connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        String httpPath = "/service/dynamic-mapper-service/" + HTTP_CONNECTOR_PATH;

        configProps.put("path",
                new ConnectorProperty(null, false, 0, ConnectorPropertyType.STRING_PROPERTY,
                        true, false, httpPath, null, null));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, true, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, false, null, null));

        configProps.put(PROPERTY_CUTOFF_LEADING_SLASH,
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        false, false, true, null, null));

        String name = "HTTP Endpoint";
        String description = "HTTP Endpoint to receive custom payload in the body.\n" +
                "The sub path following '.../dynamic-mapper-service/httpConnector/' is used as '<MAPPING_TOPIC>', " +
                "e.g. a json payload sent to 'https://<YOUR_CUMULOCITY_TENANT>/service/dynamic-mapper-service/httpConnector/temp/berlin_01' "
                +
                "will be resolved to a mapping with mapping topic: 'temp/berlin_01'.\n" +
                "The message must be sent in a POST request.\n" +
                "NOTE: The leading '/' is cut off from the sub path automatically. This can be configured with the '" +
                PROPERTY_CUTOFF_LEADING_SLASH + "' property.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.HTTP,
                true, // singleton
                configProps,
                false, // doesn't support message context
                supportedDirections());
    }
}