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
import dynamic.mapper.connector.core.*;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.Direction;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.connector.mqtt.SparkplugCertificateManager;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.PulsarClientException.UnsupportedAuthenticationException;
import org.apache.pulsar.client.impl.MultiplierRedeliveryBackoff;

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
    private static final long DEFAULT_NEGATIVE_ACK_DELAY = 60;

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
                Throwable current = e;
                while (current != null) {
                    if (current instanceof PulsarClientException.FeatureNotSupportedException &&
                            current.getMessage() != null &&
                            current.getMessage().contains("PIP-344")) {
                        log.error("{} - Broker doesn't support PIP-344. Please upgrade Pulsar broker to 2.11.0+", tenant);
                        return false;
                    }
                    current = current.getCause();
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


    @SuppressWarnings("BusyWait")
    private void connectWithRetry() {
        int maxAttempts = 10;
        int attempt = 0;
        int delay = 0;
        int delayStep = 10000;

        while (attempt < maxAttempts && !isConnected() && shouldConnect()) {
            // Do not have a delay on first start, then for each attempt +10s
            if (attempt > 0) {
                delay = delay + delayStep;
                log.info("{} - Pulsar Connection Attempt {} with delay {}", tenant, attempt, delay);
                try {
                    Thread.sleep(delay);  // Intentional sleep for retry backoff
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
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
                attempt++;
            } catch (Exception e) {
                if (serviceConfiguration.getLogConnectorErrorInBackend())
                    log.error("{} - Error connecting MQTT Service Pulsar connector: {}", tenant, e.getMessage(), e);
                else
                    log.error("{} - Error connecting MQTT Service Pulsar connector: {}", tenant, e.getMessage());
                connectionStateManager.updateStatusWithError(e);
                connectionStateManager.setConnected(false);
                attempt++;
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
        Integer negativeAckRedeliveryDelay = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("negativeAckRedeliveryDelay", DEFAULT_NEGATIVE_ACK_DELAY)*1000;

        // Try multiple subscription strategies

        // Strategy 1: Standard subscription
        try {
            platformConsumer = pulsarClient.newConsumer()
                    .topic(towardsPlatformTopic)
                    .subscriptionName(subscriptionName)
                    .autoUpdatePartitions(false)
                    // Prevents a default exclusive consumer blocking other instances during restart
                    .subscriptionType(SubscriptionType.Failover)
                    .negativeAckRedeliveryBackoff(MultiplierRedeliveryBackoff.builder()
                            .minDelayMs(negativeAckRedeliveryDelay)
                            .maxDelayMs(negativeAckRedeliveryDelay * 10L)
                            .multiplier(2)
                            .build())
                    .messageListener(mqttServiceCallback)
                    .subscribe();

            log.info("{} - Subscribed to platform topic: [{}], subscription: [{}]",
                    tenant, towardsPlatformTopic, subscriptionName);
            return;

        } catch (PulsarClientException e) {
            log.warn("{} - Standard subscription failed (PIP-344), trying async", tenant);
        }

        // Strategy 2: Async subscription
        try {
            platformConsumer = pulsarClient.newConsumer()
                    .topic(towardsPlatformTopic)
                    .subscriptionName(subscriptionName)
                    .autoUpdatePartitions(false)
                    .messageListener(mqttServiceCallback)
                    .subscriptionType(SubscriptionType.Failover)
                    .negativeAckRedeliveryBackoff(MultiplierRedeliveryBackoff.builder()
                            .minDelayMs(negativeAckRedeliveryDelay)
                            .maxDelayMs(negativeAckRedeliveryDelay * 10)
                            .multiplier(2)
                            .build())
                    .subscribeAsync()
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Subscribed to platform topic via async: [{}], subscription: [{}]",
                    tenant, towardsPlatformTopic, subscriptionName);
            return;

        } catch (Exception e) {
            log.warn("{} - Async subscription failed, trying basic", tenant);
        }

        // Strategy 3: Basic subscription
        try {
            platformConsumer = pulsarClient.newConsumer()
                    .topic(towardsPlatformTopic)
                    .subscriptionName(subscriptionName)
                    .messageListener(mqttServiceCallback)
                    .subscriptionType(SubscriptionType.Failover)
                    .negativeAckRedeliveryBackoff(MultiplierRedeliveryBackoff.builder()
                            .minDelayMs(negativeAckRedeliveryDelay)
                            .maxDelayMs(negativeAckRedeliveryDelay * 10L)
                            .multiplier(2)
                            .build())
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
     * @param clientBuilder the Pulsar client builder
     * @param authMethod the authentication method (token, oauth2, tls, basic, none)
     * @param authParams the authentication parameters
     * @throws UnsupportedAuthenticationException if authentication method is not supported
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

    /**
     * Delete resources permanently - called only when connector is being deleted,
     * not just disconnected
     * This removes the subscription from the broker by calling unsubscribe()
     * If the consumer is already closed, it will temporarily reconnect to perform
     * the unsubscribe
     */
    public void deleteResources() {
        if (towardsPlatformTopic == null) {
            log.debug("{} - No platform topic configured, skipping subscription deletion", tenant);
            return;
        }

        String subscriptionName = getSubscriptionName(connectorIdentifier, additionalSubscriptionIdTest);
        Consumer<byte[]> tempConsumer = null;
        boolean createdTempConsumer = false;

        try {
            // Check if we can use the existing consumer
            if (platformConsumer != null && platformConsumer.isConnected()) {
                tempConsumer = platformConsumer;
                log.debug("{} - Using existing connected consumer to unsubscribe", tenant);
            } else {
                // Consumer is closed, need to create a temporary one just for unsubscribing
                log.info("{} - Consumer not connected, creating temporary consumer to unsubscribe from [{}]",
                        tenant, subscriptionName);

                try {
                    // Check if we have a valid Pulsar client
                    if (pulsarClient == null || pulsarClient.isClosed()) {
                        log.warn("{} - Pulsar client not available, cannot unsubscribe from [{}]",
                                tenant, subscriptionName);
                        return;
                    }

                    // Create temporary consumer with the same subscription name
                    tempConsumer = pulsarClient.newConsumer()
                            .topic(towardsPlatformTopic)
                            .subscriptionName(subscriptionName)
                            .subscribe();
                    createdTempConsumer = true;
                    log.debug("{} - Created temporary consumer for unsubscribe", tenant);
                } catch (Exception e) {
                    log.warn("{} - Could not create temporary consumer to unsubscribe [{}]: {}",
                            tenant, subscriptionName, e.getMessage());
                    return; // Can't unsubscribe without a consumer
                }
            }

            // Now unsubscribe using the consumer (either existing or temporary)
            if (tempConsumer != null) {
                try {
                    tempConsumer.unsubscribe();
                    log.info("{} - Successfully unsubscribed from Pulsar subscription [{}] on topic [{}]",
                            tenant, subscriptionName, towardsPlatformTopic);
                } catch (PulsarClientException e) {
                    log.warn("{} - Could not unsubscribe from Pulsar subscription [{}]: {}",
                            tenant, subscriptionName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("{} - Exception deleting Pulsar subscription [{}]", tenant, subscriptionName, e);
        } finally {
            // Clean up temporary consumer if we created one
            if (createdTempConsumer && tempConsumer != null) {
                try {
                    tempConsumer.close();
                    log.debug("{} - Closed temporary consumer", tenant);
                } catch (Exception e) {
                    log.debug("{} - Error closing temporary consumer: {}", tenant, e.getMessage());
                }
            }
        }
    }

    @Override
    protected boolean isPhysicallyConnected() {
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

        if (request == null || (request.getRequest() == null && request.getBinaryPayload() == null)) {
            log.warn("{} - No payload to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }
        byte[] payloadBytes = request.getBinaryPayload() != null
                ? request.getBinaryPayload()
                : request.getRequest().getBytes(StandardCharsets.UTF_8);
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

            sendMessageToDevice(deviceProducer, payloadBytes, originalMqttTopic, qos, context);

        } catch (Exception e) {
            log.error("{} - Error publishing to MQTT Service: {}", tenant, e.getMessage(), e);
            context.addError(new dynamic.mapper.processor.ProcessingException(
                    "Failed to publish message", e));
        }
    }

    /**
     * Send message to device with topic as property
     */
    private void sendMessageToDevice(Producer<byte[]> producer, byte[] payloadBytes, String mqttTopic,
            Qos qos, ProcessingContext<?> context) throws PulsarClientException {

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

    /**
     * Generate subscription name
     */
    private static String getSubscriptionName(String identifier, String suffix) {
        return "CUMULOCITY_MQTT_SERVICE_PULSAR_" + identifier +
                (suffix != null ? suffix : "");
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        return true;
    }


    @Override
    protected SparkplugCertificateManager.SparkplugPublisher createSparkplugPublisher() {
        return new SparkplugCertificateManager.SparkplugPublisher() {
            @Override
            public void publishCertificate(String topic, byte[] payload) throws Exception {
                if (pulsarClient == null || pulsarClient.isClosed() || deviceProducer == null) {
                    throw new ConnectorException("Cannot publish Sparkplug certificate: Pulsar producer not available");
                }

                try {
                    // Convert MQTT topic to Pulsar topic property
                    deviceProducer.newMessage()
                            .value(payload)
                            .property(PULSAR_PROPERTY_TOPIC, topic)
                            .key(topic)
                            .send();

                    log.debug("{} - Published Sparkplug certificate to Pulsar for MQTT topic: [{}]", tenant, topic);
                } catch (Exception e) {
                    log.error("{} - Error publishing Sparkplug certificate for topic [{}]", tenant, topic, e);
                    throw new ConnectorException("Failed to publish Sparkplug certificate via Pulsar", e);
                }
            }

            @Override
            public void subscribeTopic(String topicPattern) {
                // Sparkplug topics are automatically subscribed via platform topic
                // Just log for tracking
                log.debug("{} - Sparkplug topic pattern [{}] will be received via platform topic", tenant, topicPattern);
            }
        };
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

        // Initialize Sparkplug support if enabled
        initializeSparkplugSupport();

        // Connect with retry logic for consumer and producer
        connectWithRetry();

         // Publish Birth Certificate if Sparkplug Host mode is enabled
         if (isSparkplugHost && sparkplugCertificateManager != null) {
             sparkplugCertificateManager.subscribeToSparkplugTopics();
             sparkplugCertificateManager.publishBirthCertificate();
         }

        // Schedule periodic Birth Certificate publishing if connected and Sparkplug Host enabled
        if (isConnected() && isSparkplugHost && sparkplugCertificateManager != null) {
            // Use a scheduled executor for periodic Birth certificates
            java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "sparkplug-periodic-birth-" + tenant);
                    t.setDaemon(true);
                    return t;
                });
            sparkplugCertificateManager.schedulePeriodicBirthCertificates(scheduler);
        }
    }

    @Override
    public void disconnect() {
        // Stop periodic Birth Certificate publishing before disconnect
        if (isSparkplugHost && sparkplugCertificateManager != null) {
            sparkplugCertificateManager.stopPeriodicPublishing();
        }
        // Call parent disconnect
        super.disconnect();
    }

    /**
     * Create MQTT Service Pulsar specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("Cumulocity MQTT-Service", ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR)
                .description("Connector for connecting to Cumulocity MQTT Service using Pulsar protocol. " +
                        "The QoS 'exactly once' is reduced to 'at least once'.")
                .singleton(true)
                .supportedDirections(supportedDirections())

                // Connection settings (pre-configured, read-only)
                .property("serviceUrl", ConnectorPropertyBuilder.requiredString()
                        .order(0)
                        .description("Pulsar service URL for Cumulocity MQTT Service")
                        .readonly(true)
                        .hidden(true)
                        .defaultValue("pulsar://cumulocity:6650"))

                // TLS configuration (read-only)
                .property("enableTls", ConnectorPropertyBuilder.optionalBoolean()
                        .order(1)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(false))

                .property("useSelfSignedCertificate", ConnectorPropertyBuilder.optionalBoolean()
                        .order(2)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(false)
                        .condition("enableTls", "true"))

                .property("fingerprintSelfSignedCertificate", ConnectorPropertyBuilder.optionalString()
                        .order(3)
                        .readonly(true)
                        .hidden(true))

                .property("nameCertificate", ConnectorPropertyBuilder.optionalString()
                        .order(4)
                        .readonly(true)
                        .hidden(true))

                // Authentication (pre-configured, read-only)
                .property("authenticationMethod", ConnectorPropertyBuilder.optionalOption()
                        .order(5)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue("basic")
                        .optionsWithLabels("none", "None", "token", "Token", "oauth2", "OAuth2",
                                "tls", "TLS", "basic", "Basic"))

                .property("authenticationParams", ConnectorPropertyBuilder.optionalSensitive()
                        .order(6)
                        .readonly(true)
                        .hidden(true)
                        .condition("authenticationMethod", "token", "oauth2", "tls", "basic"))

                // Timeouts (read-only)
                .property("connectionTimeoutSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .order(7)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(DEFAULT_CONNECTION_TIMEOUT))

                .property("operationTimeoutSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .order(8)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(DEFAULT_OPERATION_TIMEOUT))

                .property("keepAliveIntervalSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .order(9)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(DEFAULT_KEEP_ALIVE))

                // Subscription settings (read-only)
                .property("subscriptionType", ConnectorPropertyBuilder.optionalOption()
                        .order(10)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue("Shared")
                        .optionsWithLabels("Exclusive", "Exclusive", "Shared", "Shared",
                                "Failover", "Failover", "Key_Shared", "Key Shared"))

                .property("subscriptionName", ConnectorPropertyBuilder.optionalString()
                        .order(11)
                        .description("Pulsar subscription name")
                        .readonly(true)
                        .hidden(true))

                // Wildcard support (read-only)
                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(12)
                        .readonly(true)
                        .defaultValue(true))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(13)
                        .readonly(true)
                        .defaultValue(true))

                // Pulsar-specific settings (read-only, hidden)
                .property("pulsarTenant", ConnectorPropertyBuilder.optionalString()
                        .order(14)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue("public"))

                .property("pulsarNamespace", ConnectorPropertyBuilder.optionalString()
                        .order(15)
                        .readonly(true)
                        .hidden(true)
                        .defaultValue(PULSAR_NAMESPACE))

                .property("negativeAckRedeliveryDelay", ConnectorPropertyBuilder.requiredNumeric()
                        .order(16)
                        .description("Delay for redelivery of negatively acknowledged messages in s (Default: 60")
                        .defaultValue(60))

                // Sparkplug Host support
                .property("isSparkplugHost", ConnectorPropertyBuilder.optionalBoolean()
                        .order(17)
                        .defaultValue(false)
                        .description("Enable Sparkplug Host mode to publish Birth/Death certificates on connection/disconnection"))

                .property("sparkplugHostId", ConnectorPropertyBuilder.optionalString()
                        .order(18)
                        .description("Sparkplug Host ID (used for Birth/Death certificates)")
                        .defaultValue(tenant)
                        .condition("isSparkplugHost", "true"))


                .build();
    }
}



