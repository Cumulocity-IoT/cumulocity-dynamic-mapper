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

package dynamic.mapper.connector.test;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorPropertyBuilder;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.ConnectorSpecificationBuilder;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test Connector Client for simulating connector behavior without external
 * connections.
 * Used primarily for testing mappings in isolation.
 */
@Slf4j
public class TestClient extends AConnectorClient {

    public static final String TEST_CONNECTOR_IDENTIFIER = "TESTCONNECTOR";
    public static final String TEST_CONNECTOR_NAME = "Test Connector";

    // supportedQos is inherited from AConnectorClient (all three levels), so the test
    // connector never clamps and mappings can be exercised at their configured QoS.

    // Track subscriptions for testing
    private final Map<String, Qos> testSubscriptions = new ConcurrentHashMap<>();

    // Simulated connection state
    private volatile boolean simulatedConnected = false;

    /**
     * Default constructor - initializes connector specification
     */
    public TestClient() {
        this.connectorType = ConnectorType.TEST; // Use MQTT type for compatibility
        this.connectorSpecification = createConnectorSpecification();
        this.singleton = true; // Test connector is singleton
    }

    /**
     * Full constructor with dependencies
     */
    public TestClient(ConfigurationRegistry configurationRegistry,
            ConnectorRegistry connectorRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        this();
        wireFromRegistry(configurationRegistry, connectorRegistry, connectorConfiguration,
                dispatcher, additionalSubscriptionIdTest, tenant);

        // Test connector uses fixed identity fields rather than the connector configuration's
        // own name/identifier — override what wireFromRegistry derived from it.
        this.connectorName = TEST_CONNECTOR_NAME;
        this.connectorIdentifier = TEST_CONNECTOR_IDENTIFIER;
        this.connectorId = new ConnectorId(
                TEST_CONNECTOR_NAME,
                TEST_CONNECTOR_IDENTIFIER,
                connectorType);

        // Initialize managers
        initializeManagers();
    }

    @Override
    public boolean initialize() {
        try {
            log.info("{} - Initializing Test Connector", tenant);

            // Create minimal configuration if not exists
            if (connectorConfiguration == null) {
                connectorConfiguration = createDefaultConfiguration();
            }

            connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            log.info("{} - Test Connector initialized successfully", tenant);
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing Test Connector: {}", tenant, connectorName, e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    @Override
    public void connect() {
        try {
            log.info("{} - Connecting Test Connector", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            // Simulate connection
            simulatedConnected = true;
            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            log.info("{} - Test Connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting Test Connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
        }
    }

    @Override
    public void disconnect() {
        try {
            log.info("{} - Disconnecting Test Connector", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            // Clear test subscriptions
            testSubscriptions.clear();

            // Simulate disconnection
            simulatedConnected = false;
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            log.info("{} - Test Connector disconnected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error disconnecting Test Connector: {}", tenant, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        log.info("{} - Closing Test Connector", tenant);
        disconnect();
        testSubscriptions.clear();
        log.info("{} - Test Connector closed", tenant);
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Cannot subscribe: Test Connector not connected");
        }

        log.debug("{} - Test Connector subscribing to topic: [{}], QoS: {}", tenant, topic, qos);
        testSubscriptions.put(topic, qos);
        log.info("{} - Test Connector subscribed to topic: [{}]", tenant, topic);
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        if (!isConnected()) {
            log.warn("{} - Cannot unsubscribe: Test Connector not connected", tenant);
            return;
        }

        log.debug("{} - Test Connector unsubscribing from topic: [{}]", tenant, topic);
        testSubscriptions.remove(topic);
        log.info("{} - Test Connector unsubscribed from topic: [{}]", tenant, topic);
    }

    @Override
    public void monitorSubscriptions() {
        // No monitoring needed for test connector
        log.trace("{} - Test Connector subscription monitoring (no-op)", tenant);
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        // Simulate publishing - just log the message
        var requests = context.getRequests();
        if (requests == null || requests.isEmpty()) {
            log.warn("{} - No requests to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }

        Qos qos = effectivePublishQos(context);

        // Process each request
        for (int i = 0; i < requests.size(); i++) {
            DynamicMapperRequest request = requests.get(i);

            if (request == null || request.getRequest() == null) {
                log.warn("{} - Skipping null request or payload ({}/{})", tenant, i + 1, requests.size());
                continue;
            }

            String payload = request.getRequest();
            // Use the publishTopic from the request, fallback to context if not set
            String topic = request.getPublishTopic() != null ? request.getPublishTopic() : context.getResolvedPublishTopic();

            log.info("{} - Test Connector simulating publish ({}/{}): topic=[{}], QoS: {}, payload: {}",
                    tenant, i + 1, requests.size(), topic, qos, payload);
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        // Test connector always has valid configuration
        return true;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        // Test connector supports wildcards in both directions
        return true;
    }

    @Override
    public List<Direction> supportedDirections() {
        return Arrays.asList(Direction.INBOUND, Direction.OUTBOUND);
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // No specific housekeeping needed for test connector
        log.trace("{} - Test Connector housekeeping (no-op)", tenant);
    }

    @Override
    public String getConnectorIdentifier() {
        return TEST_CONNECTOR_IDENTIFIER;
    }

    @Override
    public String getConnectorName() {
        return TEST_CONNECTOR_NAME;
    }

    @Override
    protected boolean isPhysicallyConnected() {
        return simulatedConnected;
    }

    /**
     * Get current test subscriptions (for testing/verification)
     */
    public Map<String, Qos> getTestSubscriptions() {
        return Collections.unmodifiableMap(testSubscriptions);
    }

    /**
     * Check if subscribed to a specific topic (for testing)
     */
    public boolean isSubscribedTo(String topic) {
        return testSubscriptions.containsKey(topic);
    }

    /**
     * Get QoS for a subscribed topic (for testing)
     */
    public Qos getSubscriptionQos(String topic) {
        return testSubscriptions.get(topic);
    }

    /**
     * Create connector specification for test connector
     */
    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("Test Connector", ConnectorType.TEST)
                .description("Internal connector for testing mappings without external connections.")
                .singleton(true)
                .supportedDirections(supportedDirections())

                // Minimal configuration - test connector doesn't need real connection properties
                .property("enabled", ConnectorPropertyBuilder.optionalBoolean()
                        .order(0)
                        .defaultValue(true))

                .property("description", ConnectorPropertyBuilder.optionalString()
                        .order(1)
                        .defaultValue("Test Connector for mapping validation"))

                .build();
    }

    /**
     * Create default configuration for test connector
     */
    private ConnectorConfiguration createDefaultConfiguration() {
        ConnectorConfiguration config = new ConnectorConfiguration();
        config.setIdentifier(TEST_CONNECTOR_IDENTIFIER);
        config.setName(TEST_CONNECTOR_NAME);
        config.setEnabled(true);
        config.setProperties(new HashMap<>());
        config.getProperties().put("enabled", true);
        config.getProperties().put("description", "Test Connector for mapping validation");
        return config;
    }
}