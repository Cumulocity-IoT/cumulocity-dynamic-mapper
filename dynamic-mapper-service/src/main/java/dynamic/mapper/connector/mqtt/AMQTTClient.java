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

package dynamic.mapper.connector.mqtt;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
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
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Abstract base class for MQTT connector clients.
 * Extracts common functionality shared between MQTT 3.1.1 and MQTT 5.0 clients.
 */
@Slf4j
public abstract class AMQTTClient extends AConnectorClient {

    // MQTT-specific fields - note: parent class already has sslContext and sslConfig
    protected MqttClientSslConfig mqttSslConfig; // MQTT-specific SSL config
    protected Boolean cleanSession = true; // MQTT 3.x uses cleanSession, MQTT 5 uses cleanStart

    @Getter
    @Setter
    protected List<Qos> supportedQOS = Arrays.asList(
            Qos.AT_MOST_ONCE,
            Qos.AT_LEAST_ONCE,
            Qos.EXACTLY_ONCE);

    /**
     * Default constructor - initializes connector specification
     * Subclasses should call this and set their specific connectorSpecification
     */
    protected AMQTTClient() {
        this.connectorType = ConnectorType.MQTT;
        this.singleton = false;
    }

    /**
     * Full constructor with dependencies
     * Subclasses should call this from their constructor
     */
    protected AMQTTClient(ConfigurationRegistry configurationRegistry,
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
            Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                    .getOrDefault("useSelfSignedCertificate", false);

            if (useSelfSignedCertificate) {
                initializeMqttSslConfiguration();
            }

            log.info("{} - MQTT Connector {} initialized successfully", tenant, connectorName);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing MQTT connector: {}", tenant, connectorName, e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    /**
     * Initialize MQTT-specific SSL configuration
     * Creates MqttClientSslConfig for HiveMQ client
     */
    protected void initializeMqttSslConfiguration() throws Exception {
        try {
            // Load certificate using base class method
            cert = loadCertificateFromConfiguration();

            // Log certificate information
            logCertificateInfo(cert);

            // Get X509 certificates
            List<X509Certificate> customCertificates = cert.getX509Certificates();
            if (customCertificates.isEmpty()) {
                throw new ConnectorException("No valid X.509 certificates found in PEM");
            }

            log.info("{} - Successfully parsed {} X.509 certificate(s)", tenant, customCertificates.size());

            // Create truststore with custom certificates
            KeyStore trustStore = createTrustStore(true, customCertificates, cert);

            // Create TrustManagerFactory
            TrustManagerFactory tmf = createTrustManagerFactory(trustStore);

            // Build MQTT-specific SSL configuration
            mqttSslConfig = MqttClientSslConfig.builder()
                    .trustManagerFactory(tmf)
                    .protocols(DEFAULT_TLS_PROTOCOLS)
                    .handshakeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .hostnameVerifier(createHostnameVerifier())
                    .build();

            log.info("{} - MQTT SSL configuration initialized successfully", tenant);
            log.info("{}   Custom CAs: {}", tenant, customCertificates.size());
            log.info("{}   Protocols: {}", tenant, DEFAULT_TLS_PROTOCOLS);

            // Log chain structure
            logChainStructure(cert);

            // Optional: Print full summary at debug level
            if (log.isDebugEnabled()) {
                log.debug("{} - Full certificate chain summary:\n{}", tenant, cert.getSummary());
            }

        } catch (Exception e) {
            log.error("{} - Error creating MQTT SSL configuration", tenant, e);
            throw new ConnectorException("Failed to initialize SSL configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public void connect() {
        // Use base class synchronization helpers
        if (!beginConnection()) {
            return;
        }

        try {
            log.info("{} - Connecting MQTT client: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            // Build MQTT client (version-specific)
            buildMqttClient();

            // Create callback (version-specific)
            createMqttCallback();

            // Connect with retry logic
            connectMqttWithRetry();

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

        } catch (Exception e) {
            log.error("{} - Error connecting MQTT client: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
        } finally {
            endConnection();
        }
    }

    /**
     * Build MQTT client - version-specific implementation
     * Subclasses must implement this to create MQTT3 or MQTT5 client
     */
    protected abstract void buildMqttClient();

    /**
     * Create MQTT callback - version-specific implementation
     * Subclasses must implement this to create MQTT3 or MQTT5 callback
     */
    protected abstract void createMqttCallback();

    /**
     * Connect to MQTT broker with retry logic - version-specific
     * Subclasses must implement the actual connection call
     */
    protected abstract void connectMqttWithRetry();

    /**
     * Disconnect from MQTT broker - version-specific
     * Subclasses must implement the actual disconnection
     */
    protected abstract void disconnectMqttClient();

    /**
     * Check if MQTT client is physically connected - version-specific
     * Subclasses must implement the actual connectivity check
     */
    protected abstract boolean isMqttClientConnected();

    /**
     * Close MQTT client resources - version-specific
     * Subclasses must implement resource cleanup
     */
    protected abstract void closeMqttResources();

    @Override
    public void disconnect() {
        // Use base class synchronization helpers
        if (!beginDisconnection()) {
            return;
        }

        try {
            log.info("{} - Disconnecting MQTT client", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            // Unsubscribe from all topics (if client is connected)
            if (mappingSubscriptionManager != null && isMqttClientConnected()) {
                try {
                    mappingSubscriptionManager.getSubscriptionCountsView().keySet().forEach(topic -> {
                        try {
                            unsubscribeMqttTopic(topic);
                            log.debug("{} - Unsubscribed from topic: [{}]", tenant, topic);
                        } catch (Exception e) {
                            log.debug("{} - Error unsubscribing from topic: [{}] (may already be unsubscribed)",
                                    tenant, topic);
                        }
                    });
                } catch (Exception e) {
                    log.debug("{} - Error during unsubscribe phase: {}", tenant, e.getMessage());
                }
            }

            // Disconnect client (version-specific)
            disconnectMqttClient();

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            // Clear subscriptions
            if (mappingSubscriptionManager != null) {
                mappingSubscriptionManager.clear();
            }

            log.info("{} - MQTT client disconnect completed", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage());
            // Still mark as disconnected even if there was an error
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);
        } finally {
            endDisconnection();
        }
    }

    /**
     * Unsubscribe from MQTT topic - version-specific
     * Subclasses must implement the actual unsubscribe call
     */
    protected abstract void unsubscribeMqttTopic(String topic);

    @Override
    public void close() {
        log.info("{} - Closing MQTT client", tenant);
        disconnect();
        closeMqttResources();
        log.info("{} - MQTT client closed", tenant);
    }

    /**
     * Adjust QoS to supported level
     * Common logic for both MQTT 3.x and MQTT 5.0
     */
    protected Qos adjustQos(Qos requestedQos) {
        if (requestedQos == null) {
            return Qos.AT_MOST_ONCE;
        }

        if (!supportedQOS.contains(requestedQos)) {
            // Find maximum supported QoS that is less than requested
            Qos adjusted = Qos.AT_MOST_ONCE;
            for (Qos supported : supportedQOS) {
                if (supported.ordinal() < requestedQos.ordinal() &&
                        supported.ordinal() > adjusted.ordinal()) {
                    adjusted = supported;
                }
            }

            if (adjusted.ordinal() < requestedQos.ordinal()) {
                log.warn("{} - QoS {} not supported, using {} instead",
                        tenant, requestedQos, adjusted);
            }
            return adjusted;
        }

        return requestedQos;
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        // Use base class certificate validation
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (useSelfSignedCertificate) {
            if (!validateCertificateConfig(configuration)) {
                return false;
            }
        }

        // Check required properties
        for (Map.Entry<String, ConnectorProperty> entry : connectorSpecification.getProperties().entrySet()) {
            if (entry.getValue().getRequired() &&
                    configuration.getProperties().get(entry.getKey()) == null) {
                log.warn("{} - Missing required property: {}", tenant, entry.getKey());
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
                            .getOrDefault("supportsWildcardInTopicInbound", "true").toString());
        } else {
            return Boolean.parseBoolean(
                    connectorConfiguration.getProperties()
                            .getOrDefault("supportsWildcardInTopicOutbound", "false").toString());
        }
    }

    @Override
    public List<Direction> supportedDirections() {
        return Arrays.asList(Direction.INBOUND, Direction.OUTBOUND);
    }

    /**
     * Create common MQTT connector specification properties using builder pattern
     * Returns base properties that are common to MQTT 3.x and 5.0
     *
     * @param defaultVersion The default MQTT version (3.1.1 or 5.0)
     * @return Map of property names to ConnectorProperty instances
     */
    protected Map<String, ConnectorProperty> buildCommonMqttProperties(String defaultVersion) {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        // MQTT Version selection
        configProps.put("version", ConnectorPropertyBuilder.requiredOption()
                .order(0)
                .defaultValue(defaultVersion)
                .options(MQTT_VERSION_3_1_1, MQTT_VERSION_5_0)
                .build());

        // Protocol selection (mqtt://, mqtts://, ws://, wss://)
        configProps.put("protocol", ConnectorPropertyBuilder.requiredOption()
                .order(1)
                .defaultValue(MQTT_PROTOCOL_MQTT)
                .options(MQTT_PROTOCOL_MQTT, MQTT_PROTOCOL_MQTTS, MQTT_PROTOCOL_WS, MQTT_PROTOCOL_WSS)
                .build());

        // Basic connection properties
        configProps.put("mqttHost", ConnectorPropertyBuilder.requiredString()
                .order(2)
                .build());

        configProps.put("mqttPort", ConnectorPropertyBuilder.requiredNumeric()
                .order(3)
                .build());

        // Optional authentication
        configProps.put("user", ConnectorPropertyBuilder.optionalString()
                .order(4)
                .build());

        configProps.put("password", ConnectorPropertyBuilder.optionalSensitive()
                .order(5)
                .build());

        configProps.put("clientId", ConnectorPropertyBuilder.requiredString()
                .order(6)
                .build());

        // TLS/SSL Certificate configuration (only visible for secure protocols)
        configProps.put("useSelfSignedCertificate", ConnectorPropertyBuilder.optionalBoolean()
                .order(7)
                .defaultValue(false)
                .condition("protocol", MQTT_PROTOCOL_MQTTS, MQTT_PROTOCOL_WSS)
                .build());

        configProps.put("fingerprintSelfSignedCertificate", ConnectorPropertyBuilder.largeText()
                .order(8)
                .description("SHA 1 fingerprint of CA or Self Signed Certificate")
                .condition("useSelfSignedCertificate", "true")
                .build());

        configProps.put("nameCertificate", ConnectorPropertyBuilder.optionalString()
                .order(9)
                .condition("useSelfSignedCertificate", "true")
                .build());

        configProps.put("certificateChainInPemFormat", ConnectorPropertyBuilder.largeText()
                .order(10)
                .description("Either enter certificate in PEM format or identify certificate by name and fingerprint (must be uploaded as Trusted Certificate in Device Management)")
                .condition("useSelfSignedCertificate", "true")
                .build());

        configProps.put("disableHostnameValidation", ConnectorPropertyBuilder.optionalBoolean()
                .order(11)
                .defaultValue(false)
                .condition("useSelfSignedCertificate", "true")
                .build());

        // Wildcard support flags
        configProps.put("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                .order(12)
                .defaultValue(true)
                .build());

        configProps.put("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                .order(13)
                .defaultValue(false)
                .build());

        // WebSocket specific configuration (only visible for WS/WSS protocols)
        configProps.put("serverPath", ConnectorPropertyBuilder.optionalString()
                .order(14)
                .condition("protocol", MQTT_PROTOCOL_WS, MQTT_PROTOCOL_WSS)
                .build());

        // MQTT session behavior
        configProps.put("cleanSession", ConnectorPropertyBuilder.optionalBoolean()
                .order(15)
                .defaultValue(true)
                .build());

        return configProps;
    }

    /**
     * Create connector specification - subclasses should implement
     */
    protected abstract ConnectorSpecification createConnectorSpecification();
}
