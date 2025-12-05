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

package dynamic.mapper.connector.pulsar;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
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

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.PulsarClientException.UnsupportedAuthenticationException;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Cumulocity MQTT Service Pulsar Connector.
 * Connects to Cumulocity MQTT Service using Pulsar protocol with device
 * isolation.
 * Uses two topics: to-device (outbound) and from-device (inbound).
 */
@Slf4j
public class MQTTServicePulsarClient extends PulsarConnectorClient {

    // Pulsar message properties
    public static final String PULSAR_PROPERTY_TOPIC = "topic";
    public static final String PULSAR_PROPERTY_CHANNEL = "channel";
    public static final String PULSAR_PROPERTY_CLIENT_ID = "clientID";

    // Topic names
    public static final String PULSAR_TOWARDS_DEVICE_TOPIC = "to-device";
    public static final String PULSAR_TOWARDS_PLATFORM_TOPIC = "from-device";
    public static final String PULSAR_NAMESPACE = "mqtt";

    private static final int DEFAULT_CONNECTION_TIMEOUT = 30;
    private static final int DEFAULT_OPERATION_TIMEOUT = 30;
    private static final int DEFAULT_KEEP_ALIVE = 30;

    // Cumulocity-specific consumer and producer
    private Consumer<byte[]> platformConsumer;
    private Producer<byte[]> deviceProducer;

    private String towardsDeviceTopic;
    private String towardsPlatformTopic;

    private MQTTServicePulsarCallback mqttServiceCallback;

    @Getter
    protected List<Qos> supportedQOS;

    /**
     * Default constructor
     */
    public MQTTServicePulsarClient() {
        super();
        this.connectorType = ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR;
        this.singleton = true;
        this.supportedQOS = Arrays.asList(Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE);
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public MQTTServicePulsarClient(ConfigurationRegistry configurationRegistry,
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

        // Configure for Cumulocity internal
        configureCumulocityMqttService();

        // Initialize managers
        initializeManagers();
    }

    /**
     * Configure for Cumulocity internal MQTT Service
     */
    private void configureCumulocityMqttService() {
        Map<String, ConnectorProperty> props = connectorSpecification.getProperties();

        // Set service URL
        String serviceUrl = configurationRegistry.getMqttServicePulsarUrl();
        props.put("serviceUrl",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY,
                        true, true, serviceUrl, null, null));

        // Set authentication
        props.put("authenticationMethod",
                new ConnectorProperty(null, true, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                        true, true, "basic", null, null));

        MicroserviceCredentials credentials = configurationRegistry.getMicroserviceCredential(tenant);
        String authParams = MessageFormat.format(
                "'{'\"userId\":\"{0}/{1}\",\"password\":\"{2}\"'}'",
                tenant, credentials.getUsername(), credentials.getPassword());

        props.put("authenticationParams",
                new ConnectorProperty(null, true, 6, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                        true, true, authParams, null, null));

        // Set tenant and namespace
        props.put("pulsarTenant",
                new ConnectorProperty(null, true, 13, ConnectorPropertyType.STRING_PROPERTY,
                        true, true, tenant, null, null));

        props.put("pulsarNamespace",
                new ConnectorProperty(null, true, 14, ConnectorPropertyType.STRING_PROPERTY,
                        true, true, PULSAR_NAMESPACE, null, null));

        log.info("{} - Configured MQTT Service Pulsar connector for Cumulocity internal use", tenant);
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            // Build Pulsar client
            String serviceUrl = (String) connectorConfiguration.getProperties().get("serviceUrl");
            Boolean enableTls = (Boolean) connectorConfiguration.getProperties().getOrDefault("enableTls", false);
            String authMethod = (String) connectorConfiguration.getProperties()
                    .getOrDefault("authenticationMethod", "basic");
            String authParams = (String) connectorConfiguration.getProperties().get("authenticationParams");
            Integer connectionTimeout = (Integer) connectorConfiguration.getProperties()
                    .getOrDefault("connectionTimeoutSeconds", DEFAULT_CONNECTION_TIMEOUT);
            Integer operationTimeout = (Integer) connectorConfiguration.getProperties()
                    .getOrDefault("operationTimeoutSeconds", DEFAULT_OPERATION_TIMEOUT);
            Integer keepAlive = (Integer) connectorConfiguration.getProperties()
                    .getOrDefault("keepAliveIntervalSeconds", DEFAULT_KEEP_ALIVE);

            String finalServiceUrl = adjustServiceUrlForTls(serviceUrl, enableTls);

            ClientBuilder clientBuilder = PulsarClient.builder()
                    .serviceUrl(finalServiceUrl)
                    .connectionTimeout(connectionTimeout, TimeUnit.SECONDS)
                    .operationTimeout(operationTimeout, TimeUnit.SECONDS)
                    .keepAliveInterval(keepAlive, TimeUnit.SECONDS)
                    .enableBusyWait(false)
                    .maxNumberOfRejectedRequestPerConnection(0);

            configureAuthentication(clientBuilder, authMethod, authParams);

            try {
                pulsarClient = clientBuilder.build();
                log.info("{} - MQTT Service Pulsar client created successfully", tenant);
                if (isConfigValid(connectorConfiguration)) {
                    connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
                }
            } catch (Exception e) {
                if (containsPip344Error(e)) {
                    log.error("{} - Broker doesn't support PIP-344. Please upgrade Pulsar broker to 2.11.0+", tenant);
                    return false;
                }
                throw e;
            }

            // Create callback
            mqttServiceCallback = new MQTTServicePulsarCallback(
                    tenant,
                    configurationRegistry,
                    dispatcher,
                    connectorIdentifier,
                    connectorName);

            // Build topic names
            String namespace = (String) connectorConfiguration.getProperties()
                    .getOrDefault("pulsarNamespace", PULSAR_NAMESPACE);
            towardsPlatformTopic = String.format("persistent://%s/%s/%s",
                    tenant, namespace, PULSAR_TOWARDS_PLATFORM_TOPIC);
            towardsDeviceTopic = String.format("persistent://%s/%s/%s",
                    tenant, namespace, PULSAR_TOWARDS_DEVICE_TOPIC);

            log.info("{} - MQTT Service Pulsar connector initialized", tenant);
            log.info("{} - Platform topic: {}", tenant, towardsPlatformTopic);
            log.info("{} - Device topic: {}", tenant, towardsDeviceTopic);

            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing MQTT Service Pulsar connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    @Override
    public void connect() {
        log.info("{} - Connecting MQTT Service Pulsar connector: {}", tenant, connectorName);

        if (isConnected()) {
            log.debug("{} - Already connected", tenant);
            return;
        }

        if (!shouldConnect()) {
            log.info("{} - Connector disabled or invalid configuration", tenant);
            return;
        }

        if (pulsarClient == null || pulsarClient.isClosed()) {
            log.error("{} - Pulsar client not available - initialization may have failed", tenant);
            connectionStateManager.updateStatusWithError(new Exception("Pulsar client not initialized"));
            return;
        }

        connectWithRetry();
    }

    private void connectWithRetry() {
        int maxAttempts = 3;
        int attempt = 0;

        while (attempt < maxAttempts && !isConnected() && shouldConnect()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            try {
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

                // Subscribe to platform topic (inbound)
                subscribeToTowardsPlatformTopic();

                // Create producer for device topic (outbound)
                createTowardsDeviceProducer();

                // Verify connection
                boolean consumerConnected = platformConsumer != null && platformConsumer.isConnected();
                boolean producerConnected = deviceProducer != null && deviceProducer.isConnected();

                if (!consumerConnected || !producerConnected) {
                    throw new ConnectorException(
                            String.format("Connection incomplete - consumer: %s, producer: %s",
                                    consumerConnected, producerConnected));
                }

                // Now set connected state
                connectionStateManager.setConnected(true);
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

                log.info("{} - MQTT Service Pulsar connector connected successfully (consumer: {}, producer: {})",
                        tenant, consumerConnected, producerConnected);

                // Initialize subscriptions after successful connection
                if (isConnected()) {
                    initializeSubscriptionsAfterConnect();
                }

            } catch (Exception e) {
                log.error("{} - Error connecting MQTT Service Pulsar connector: {}", tenant, e.getMessage(), e);
                connectionStateManager.updateStatusWithError(e);
                connectionStateManager.setConnected(false);

                // Cleanup on failure
                cleanupOnConnectionFailure();
            }
        }
    }

    private void cleanupOnConnectionFailure() {
        if (platformConsumer != null) {
            try {
                platformConsumer.close();
            } catch (Exception e) {
                log.debug("{} - Error closing consumer during cleanup", tenant);
            }
            platformConsumer = null;
        }

        if (deviceProducer != null) {
            try {
                deviceProducer.close();
            } catch (Exception e) {
                log.debug("{} - Error closing producer during cleanup", tenant);
            }
            deviceProducer = null;
        }
    }

    /**
     * Subscribe to platform topic for inbound messages
     */
    private void subscribeToTowardsPlatformTopic() throws PulsarClientException {
        if (platformConsumer != null) {
            log.warn("{} - Platform consumer already exists, closing existing", tenant);
            platformConsumer.close();
        }

        String subscriptionName = getSubscriptionName(connectorIdentifier, additionalSubscriptionIdTest);

        Exception lastException = null;

        // Try multiple subscription strategies

        // Strategy 1: Standard subscription
        try {
            platformConsumer = pulsarClient.newConsumer()
                    .topic(towardsPlatformTopic)
                    .subscriptionName(subscriptionName)
                    .autoUpdatePartitions(false)
                    .messageListener(mqttServiceCallback)
                    .subscribe();

            log.info("{} - Subscribed to platform topic: [{}], subscription: [{}]",
                    tenant, towardsPlatformTopic, subscriptionName);
            return;

        } catch (PulsarClientException e) {
            lastException = e;
            log.warn("{} - Standard subscription failed (PIP-344), trying async", tenant);
        }

        // Strategy 2: Async subscription
        try {
            platformConsumer = pulsarClient.newConsumer()
                    .topic(towardsPlatformTopic)
                    .subscriptionName(subscriptionName)
                    .autoUpdatePartitions(false)
                    .messageListener(mqttServiceCallback)
                    .subscribeAsync()
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Subscribed to platform topic via async: [{}], subscription: [{}]",
                    tenant, towardsPlatformTopic, subscriptionName);
            return;

        } catch (Exception e) {
            lastException = e;
            log.warn("{} - Async subscription failed, trying basic", tenant);
        }

        // Strategy 3: Basic subscription
        try {
            platformConsumer = pulsarClient.newConsumer()
                    .topic(towardsPlatformTopic)
                    .subscriptionName(subscriptionName)
                    .messageListener(mqttServiceCallback)
                    .subscribeAsync()
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Subscribed to platform topic via basic async: [{}], subscription: [{}]",
                    tenant, towardsPlatformTopic, subscriptionName);

        } catch (Exception e) {
            log.error("{} - All subscription strategies failed for platform topic", tenant);
            throw new PulsarClientException(
                    "Failed to subscribe after trying multiple strategies. Last error: " + e.getMessage(), e);
        }
    }

    /**
     * Create producer for device topic
     */
    private void createTowardsDeviceProducer() throws PulsarClientException {
        if (deviceProducer != null) {
            log.warn("{} - Device producer already exists, closing existing", tenant);
            deviceProducer.close();
        }

        deviceProducer = pulsarClient.newProducer()
                .topic(towardsDeviceTopic)
                .create();

        log.info("{} - Created producer for device topic: [{}]", tenant, towardsDeviceTopic);
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        // MQTT Service handles subscriptions via platform topic
        log.debug("{} - MQTT Service subscription for topic: [{}], QoS: {} - handled by platform topic",
                tenant, topic, qos);
        sendSubscriptionEvents(topic, "Subscribed");

        log.info("{} - Subscription registered for topic: [{}] - messages via platform topic",
                tenant, topic);
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        // MQTT Service handles unsubscriptions via platform topic
        log.debug("{} - MQTT Service unsubscription for topic: [{}] - handled by platform topic", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribed");

        log.info("{} - Unsubscription registered for topic: [{}]", tenant, topic);
    }

    @Override
    public void disconnect() {
        if (!isConnected()) {
            log.debug("{} - Already disconnected", tenant);
            return;
        }

        log.info("{} - Disconnecting MQTT Service Pulsar connector", tenant);
        connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        try {
            // Close platform consumer
            if (platformConsumer != null) {
                try {
                    platformConsumer.close();
                    log.info("{} - Closed platform consumer", tenant);
                } catch (PulsarClientException e) {
                    log.error("{} - Error closing platform consumer: {}", tenant, e.getMessage());
                }
                platformConsumer = null;
            }

            // Close device producer
            if (deviceProducer != null) {
                try {
                    deviceProducer.close();
                    log.info("{} - Closed device producer", tenant);
                } catch (PulsarClientException e) {
                    log.error("{} - Error closing device producer: {}", tenant, e.getMessage());
                }
                deviceProducer = null;
            }

            // Close client
            if (pulsarClient != null && !pulsarClient.isClosed()) {
                pulsarClient.close();
            }

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            log.info("{} - MQTT Service Pulsar connector disconnected", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage(), e);
        }
    }

    /**
     * Adjust service URL for TLS
     */
    private String adjustServiceUrlForTls(String originalUrl, Boolean enableTls) {
        if (enableTls != null && enableTls) {
            if (originalUrl.startsWith("pulsar://")) {
                return originalUrl.replace("pulsar://", "pulsar+ssl://");
            }
        } else {
            if (originalUrl.startsWith("pulsar+ssl://")) {
                return originalUrl.replace("pulsar+ssl://", "pulsar://");
            }
        }
        return originalUrl;
    }

    /**
     * Configures authentication based on method
     * 
     * @throws UnsupportedAuthenticationException
     */
    protected void configureAuthentication(ClientBuilder clientBuilder, String authMethod, String authParams)
            throws UnsupportedAuthenticationException {
        if (!"none".equals(authMethod) && !StringUtils.isEmpty(authParams)) {
            switch (authMethod) {
                case "token":
                    clientBuilder.authentication(AuthenticationFactory.token(authParams));
                    log.debug("{} - Using token authentication", tenant);
                    break;
                case "oauth2":
                    clientBuilder.authentication(
                            AuthenticationFactory.create(
                                    "org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2",
                                    authParams));
                    log.debug("{} - Using OAuth2 authentication", tenant);
                    break;
                case "tls":
                    clientBuilder.authentication(
                            AuthenticationFactory.create(
                                    "org.apache.pulsar.client.impl.auth.AuthenticationTls",
                                    authParams));
                    log.debug("{} - Using TLS authentication", tenant);
                    break;
                case "basic":
                    clientBuilder.authentication(
                            AuthenticationFactory.create(
                                    "org.apache.pulsar.client.impl.auth.AuthenticationBasic",
                                    authParams));
                    log.debug("{} - Using basic authentication", tenant);
                    break;
                default:
                    log.warn("{} - Unknown authentication method: {}", tenant, authMethod);
                    break;
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public boolean isConnected() {
        boolean clientConnected = pulsarClient != null && !pulsarClient.isClosed();
        boolean consumerConnected = platformConsumer != null && platformConsumer.isConnected();
        boolean producerConnected = deviceProducer != null && deviceProducer.isConnected();

        boolean fullyConnected = clientConnected && consumerConnected && producerConnected;

        // Sync connection state with manager
        if (fullyConnected != connectionStateManager.isConnected()) {
            connectionStateManager.setConnected(fullyConnected);
        }

        return fullyConnected;
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (pulsarClient == null || pulsarClient.isClosed()) {
            log.warn("{} - Pulsar client is closed, attempting reconnect", tenant);
            reconnect();
            return;
        }

        DynamicMapperRequest request = context.getCurrentRequest();

        if (context.getCurrentRequest() == null ||
                context.getCurrentRequest().getRequest() == null) {
            log.warn("{} - No payload to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }
        String payload = request.getRequest();
        String originalMqttTopic = context.getResolvedPublishTopic();
        Qos qos = Qos.AT_LEAST_ONCE; // MQTT Service uses AT_LEAST_ONCE

        try {
            // Check/recreate producer if needed
            if (deviceProducer == null || !deviceProducer.isConnected()) {
                log.warn("{} - Device producer disconnected, recreating", tenant);
                if (deviceProducer != null) {
                    try {
                        deviceProducer.close();
                    } catch (PulsarClientException e) {
                        log.debug("{} - Error closing disconnected producer: {}", tenant, e.getMessage());
                    }
                }
                createTowardsDeviceProducer();
            }

            sendMessageToDevice(deviceProducer, payload, originalMqttTopic, qos, context);

        } catch (Exception e) {
            log.error("{} - Error publishing to MQTT Service: {}", tenant, e.getMessage(), e);
            context.addError(new dynamic.mapper.processor.ProcessingException(
                    "Failed to publish message", e));
        }
    }

    /**
     * Send message to device with topic as property
     */
    private void sendMessageToDevice(Producer<byte[]> producer, String payload, String mqttTopic,
            Qos qos, ProcessingContext<?> context) throws PulsarClientException {

        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        if (qos == Qos.AT_MOST_ONCE) {
            producer.newMessage()
                    .value(payloadBytes)
                    .property(PULSAR_PROPERTY_TOPIC, mqttTopic)
                    .key(mqttTopic)
                    .sendAsync()
                    .exceptionally(throwable -> {
                        log.debug("{} - AT_MOST_ONCE send failed (expected): {}",
                                tenant, throwable.getMessage());
                        return null;
                    });
        } else {
            producer.newMessage()
                    .value(payloadBytes)
                    .property(PULSAR_PROPERTY_TOPIC, mqttTopic)
                    .key(mqttTopic)
                    .send();
        }

        if (context.getMapping().getDebug() || serviceConfiguration.getLogPayload()) {
            log.info("{} - Published to MQTT Service: QoS={}, topic=[{}], pulsarTopic=[{}], mapping={}",
                    tenant, qos, mqttTopic, towardsDeviceTopic, context.getMapping().getName());
        }
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Check consumer and producer health
        if (platformConsumer != null && !platformConsumer.isConnected()) {
            log.warn("{} - Platform consumer disconnected, will reconnect on next cycle", tenant);
        }

        if (deviceProducer != null && !deviceProducer.isConnected()) {
            log.warn("{} - Device producer disconnected, will reconnect on next publish", tenant);
        }
    }

    @Override
    public List<Direction> supportedDirections() {
        return Arrays.asList(Direction.INBOUND, Direction.OUTBOUND);
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
     * Generate subscription name
     */
    private static String getSubscriptionName(String identifier, String suffix) {
        return "CUMULOCITY_MQTT_SERVICE_PULSAR_" + identifier +
                (suffix != null ? suffix : "");
    }

    /**
     * Check if exception contains PIP-344 error
     */
    private Boolean containsPip344Error(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PulsarClientException.FeatureNotSupportedException &&
                    current.getMessage() != null &&
                    current.getMessage().contains("PIP-344")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Create MQTT Service Pulsar specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("enableTls", new String[] { "true" });
        ConnectorPropertyCondition authCondition = new ConnectorPropertyCondition(
                "authenticationMethod", new String[] { "token", "oauth2", "tls", "basic" });

        configProps.put("serviceUrl",
                new ConnectorProperty(
                        "Pulsar service URL for Cumulocity MQTT Service",
                        true, 0, ConnectorPropertyType.STRING_PROPERTY, true, true,
                        "pulsar://cumulocity:6650", null, null));

        configProps.put("enableTls",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        false, true, false, null, null));

        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        false, true, false, null, tlsCondition));

        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.STRING_PROPERTY,
                        false, true, null, null, null));

        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY,
                        false, true, null, null, null));

        configProps.put("authenticationMethod",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.OPTION_PROPERTY,
                        false, true, "basic",
                        Map.of("none", "None", "token", "Token", "oauth2", "OAuth2",
                                "tls", "TLS", "basic", "Basic"),
                        null));

        configProps.put("authenticationParams",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                        false, true, null, null, authCondition));

        configProps.put("connectionTimeoutSeconds",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.NUMERIC_PROPERTY,
                        false, true, DEFAULT_CONNECTION_TIMEOUT, null, null));

        configProps.put("operationTimeoutSeconds",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.NUMERIC_PROPERTY,
                        false, true, DEFAULT_OPERATION_TIMEOUT, null, null));

        configProps.put("keepAliveIntervalSeconds",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.NUMERIC_PROPERTY,
                        false, true, DEFAULT_KEEP_ALIVE, null, null));

        configProps.put("subscriptionType",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.OPTION_PROPERTY,
                        false, true, "Shared",
                        Map.of("Exclusive", "Exclusive", "Shared", "Shared",
                                "Failover", "Failover", "Key_Shared", "Key Shared"),
                        null));

        configProps.put("subscriptionName",
                new ConnectorProperty("Pulsar subscription name", false, 11,
                        ConnectorPropertyType.STRING_PROPERTY, false, true, null, null, null));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, true, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 13, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, false, null, null));

        configProps.put("pulsarTenant",
                new ConnectorProperty(null, false, 14, ConnectorPropertyType.STRING_PROPERTY,
                        false, true, "public", null, null));

        configProps.put("pulsarNamespace",
                new ConnectorProperty(null, false, 15, ConnectorPropertyType.STRING_PROPERTY,
                        false, true, PULSAR_NAMESPACE, null, null));

        String name = "Cumulocity MQTT-Service";
        String description = "Connector for connecting to Cumulocity MQTT Service using Pulsar protocol. " +
                "The MQTT Service does not support wildcards. " +
                "The QoS 'exactly once' is reduced to 'at least once'.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR,
                true, // singleton
                configProps,
                false,
                supportedDirections());
    }
}