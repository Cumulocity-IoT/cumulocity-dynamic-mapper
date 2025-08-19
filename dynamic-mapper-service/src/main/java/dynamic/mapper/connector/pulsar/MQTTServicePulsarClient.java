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

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.core.ConnectorStatusEvent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.DispatcherInbound;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTTServicePulsarClient extends PulsarConnectorClient {
    public static final String PULSAR_PROPERTY_MQTT_TOPIC = "mqttTopic";
    /**
     * Handling Pulsar connections with QoS support
     * QoS 0 (AT_MOST_ONCE): Messages are acknowledged immediately, providing
     * fire-and-forget behavior
     * QoS 1 (AT_LEAST_ONCE): Uses Pulsar's default acknowledgment mechanism with
     * retry
     * QoS 2 (EXACTLY_ONCE): Uses exclusive subscription and enables deduplication
     * features
     * Flexible Configuration: Allows configuration override while providing
     * sensible QoS-based defaults
     * Performance Optimization: Different timeout and batching strategies based on
     * QoS requirements
     */

    private static final int DEFAULT_CONNECTION_TIMEOUT = 30;
    private static final int DEFAULT_OPERATION_TIMEOUT = 30;
    private static final int DEFAULT_KEEP_ALIVE = 30;

    public MQTTServicePulsarClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();

        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("enableTls",
                new String[] { "true" });
        ConnectorPropertyCondition authCondition = new ConnectorPropertyCondition("authenticationMethod",
                new String[] { "token", "oauth2", "tls" });

        configProps.put("serviceUrl",
                new ConnectorProperty(
                        "This can be in the format: pulsar://localhost:6650 for non-TLS or pulsar+ssl://localhost:6651 for TLS",
                        true, 0, ConnectorPropertyType.STRING_PROPERTY, true, true,
                        "pulsar://cumulocity:6650", null, null));
        configProps.put("enableTls",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY, false, true,
                        false, null, null));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY, false, true,
                        false, null, tlsCondition));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.STRING_PROPERTY, false, true,
                        null, null, null));
        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, true,
                        null, null, null));
        configProps.put("authenticationMethod",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.OPTION_PROPERTY, false, true,
                        "none",
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("none", "None"),
                                new AbstractMap.SimpleEntry<String, String>("token", "Token"),
                                new AbstractMap.SimpleEntry<String, String>("oauth2", "OAuth2"),
                                new AbstractMap.SimpleEntry<String, String>("tls", "TLS")),
                        null));
        configProps.put("authenticationParams",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, true,
                        null, null, authCondition));
        configProps.put("connectionTimeoutSeconds",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.NUMERIC_PROPERTY, false, true,
                        DEFAULT_CONNECTION_TIMEOUT, null, null));
        configProps.put("operationTimeoutSeconds",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.NUMERIC_PROPERTY, false, true,
                        DEFAULT_OPERATION_TIMEOUT, null, null));
        configProps.put("keepAliveIntervalSeconds",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.NUMERIC_PROPERTY, false, true,
                        DEFAULT_KEEP_ALIVE, null, null));
        configProps.put("subscriptionType",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.OPTION_PROPERTY, false, true,
                        "Shared",
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("Exclusive", "Exclusive"),
                                new AbstractMap.SimpleEntry<String, String>("Shared", "Shared"),
                                new AbstractMap.SimpleEntry<String, String>("Failover", "Failover"),
                                new AbstractMap.SimpleEntry<String, String>("Key_Shared", "Key Shared")),
                        null));
        configProps.put("subscriptionName",
                new ConnectorProperty(
                        "Controls how Pulsar subscription names are generated - 'default' creates connector-specific subscriptions, 'mapping' creates separate subscriptions per mapping, 'shared' uses one subscription for all mappings, 'custom' allows user-defined patterns.",
                        false, 11, ConnectorPropertyType.STRING_PROPERTY, false, true,
                        null, null, null));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false,
                        false, null, null));
        configProps.put("pulsarTenant",
                new ConnectorProperty(null, false, 13, ConnectorPropertyType.STRING_PROPERTY, false, true,
                        "public", null, null));
        configProps.put("pulsarNamespace",
                new ConnectorProperty(null, false, 14, ConnectorPropertyType.STRING_PROPERTY, false, true,
                        "default", null, null));

        String name = "Cumulocity MQTT Service - (device isolation)";
        String description = "Connector for connecting to Cumulocity MQTT Service. The MQTT Service does not support wildcards, i.e. '+', '#'. The QoS 'exactly once' is reduced to 'at least once'.";
        connectorType = ConnectorType.CUMULOCITY_MQTT_SERVICE_PULSAR;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, false,
                supportedDirections());
    }

    public MQTTServicePulsarClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            DispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.connectorConfiguration = connectorConfiguration;
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorId = new ConnectorId(connectorConfiguration.name, connectorConfiguration.identifier,
                connectorType);
        this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name,
                connectorConfiguration.identifier);
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        // Pulsar doesn't have MQTT QoS but supports reliable delivery through
        // acknowledgments
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE, Qos.AT_MOST_ONCE);
        this.towardsDeviceTopic = configurationRegistry.getTowardsDeviceTopic();
        this.towardsPlatformTopic = configurationRegistry.getTowardsPlatformTopic();
        getConnectorSpecification().getProperties().put("serviceUrl",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY, true, true,
                        configurationRegistry.getMqttServicePulsartUrl(), null, null));
    }

    protected AConnectorClient.Certificate cert;
    protected MQTTServicePulsarCallback pulsarCallback = null;
    protected PulsarClient pulsarClient;
    private Consumer<byte[]> consumer;
    private Producer<byte[]> producer;
    private String towardsDeviceTopic;
    private String towardsPlatformTopic;

    @Getter
    protected List<Qos> supportedQOS;

    public boolean initialize() {
        loadConfiguration();
        log.info("{} - Phase 0: {} initialized , connectorType: {}", tenant,
                getConnectorType(),
                getConnectorName());
        return true;
    }

    @Override
    public void connect() {
        log.info("{} - Phase I: {} connecting, isConnected: {}, shouldConnect: {}",
                tenant, getConnectorName(), isConnected(), shouldConnect());

        if (isConnected())
            disconnect();

        if (shouldConnect())
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());

        String serviceUrl = (String) connectorConfiguration.getProperties().get("serviceUrl");
        Boolean enableTls = (Boolean) connectorConfiguration.getProperties().getOrDefault("enableTls", false);
        String authenticationMethod = (String) connectorConfiguration.getProperties()
                .getOrDefault("authenticationMethod", "none");
        String authenticationParams = (String) connectorConfiguration.getProperties().get("authenticationParams");
        Integer connectionTimeout = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("connectionTimeoutSeconds", DEFAULT_CONNECTION_TIMEOUT);
        Integer operationTimeout = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("operationTimeoutSeconds", DEFAULT_OPERATION_TIMEOUT);
        Integer keepAlive = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("keepAliveIntervalSeconds", DEFAULT_KEEP_ALIVE);

        try {
            String finalServiceUrl = adjustServiceUrlForTls(serviceUrl, enableTls);

            ClientBuilder clientBuilder = PulsarClient.builder()
                    .serviceUrl(finalServiceUrl)
                    .connectionTimeout(connectionTimeout, TimeUnit.SECONDS)
                    .operationTimeout(operationTimeout, TimeUnit.SECONDS)
                    .keepAliveInterval(keepAlive, TimeUnit.SECONDS);

            configureAuthentication(clientBuilder, authenticationMethod, authenticationParams);
            pulsarClient = clientBuilder.build();

            pulsarCallback = new MQTTServicePulsarCallback(tenant, configurationRegistry, dispatcher,
                    getConnectorIdentifier(), getConnectorName(), false);

            boolean successful = false;
            while (!successful) {
                if (Thread.currentThread().isInterrupted())
                    return;
                loadConfiguration();
                var firstRun = true;
                var mappingOutboundCacheRebuild = false;

                while (!isConnected() && shouldConnect()) {
                    if (Thread.currentThread().isInterrupted())
                        return;
                    log.info("{} - Phase II: {} connecting, shouldConnect: {}, server: {}", tenant,
                            getConnectorName(), shouldConnect(), serviceUrl);
                    if (!firstRun) {
                        try {
                            Thread.sleep(WAIT_PERIOD_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    try {
                        connectionState.setTrue();
                        log.info("{} - Phase III: {} connected, server: {}", tenant, getConnectorName(), serviceUrl);
                        updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);

                        // TODO IMPLEMENTATION: Subscribe to towardsPlatformTopic on connect
                        subscribeToTowardsPlatformTopic();

                        // Create producer for towardsDeviceTopic
                        createTowardsDeviceProducer();

                        List<Mapping> updatedMappingsInbound = mappingService.rebuildMappingInboundCache(tenant,
                                connectorId);
                        initializeSubscriptionsInbound(updatedMappingsInbound, true, true);
                        List<Mapping> updatedMappingsOutbound = mappingService.rebuildMappingOutboundCache(tenant,
                                connectorId);
                        mappingOutboundCacheRebuild = true;
                        initializeSubscriptionsOutbound(updatedMappingsOutbound);

                    } catch (Exception e) {
                        if (e instanceof InterruptedException || e instanceof RuntimeException) {
                            log.error("{} - Phase III: {} interrupted while connecting to server: {}, {}, {}, {}",
                                    tenant, getConnectorName(), serviceUrl,
                                    e.getMessage(), connectionState.booleanValue(), isConnected(), e);
                            return;
                        }
                        log.error("{} - Phase III: {} failed to connect to server {}, {}, {}, {}", tenant,
                                serviceUrl, e.getMessage(), connectionState.booleanValue(),
                                isConnected(), e);
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
        } catch (Exception e) {
            log.error("{} - Error creating Pulsar client: ", tenant, e);
            updateConnectorStatusToFailed(e);
            sendConnectorLifecycle();
        }
    }

    /**
     * Subscribe to the towardsPlatformTopic to receive inbound messages from MQTT
     * Service
     */
    private void subscribeToTowardsPlatformTopic() throws PulsarClientException {
        if (consumer != null) {
            log.warn("{} - Consumer already exists for towardsPlatformTopic, closing existing", tenant);
            consumer.close();
        }

        String subscriptionName = generateSubscriptionName("platform");

        consumer = pulsarClient.newConsumer()
                .topic(towardsPlatformTopic)
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(pulsarCallback)
                .subscribe();

        log.info("{} - Subscribed to towardsPlatformTopic: [{}] with subscription: [{}]",
                tenant, towardsPlatformTopic, subscriptionName);
    }

    /**
     * Create producer for towardsDeviceTopic for outbound messages to MQTT Service
     */
    private void createTowardsDeviceProducer() throws PulsarClientException {
        if (producer != null) {
            log.warn("{} - Producer already exists for towardsDeviceTopic, closing existing", tenant);
            producer.close();
        }

        producer = pulsarClient.newProducer()
                .topic(towardsDeviceTopic)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create();

        log.info("{} - Created producer for towardsDeviceTopic: [{}]", tenant, towardsDeviceTopic);
    }

    /**
     * Generate consistent subscription name for Cumulocity MQTT Service
     */
    private String generateSubscriptionName(String suffix) {
        return String.format("cumulocity-mqtt-service-%s-%s-%s",
                tenant, connectorIdentifier, suffix);
    }

    @Override
    public boolean isConnected() {
        return connectionState.booleanValue() && pulsarClient != null && !pulsarClient.isClosed();
    }

    @Override
    public void disconnect() {
        if (isConnected()) {
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTING, true, true);
            log.info("{} - Disconnecting from Pulsar: {}", tenant,
                    (pulsarClient == null ? (String) connectorConfiguration.getProperties().get("serviceUrl")
                            : "Pulsar client"));

            // TODO IMPLEMENTATION: Close consumer for towardsPlatformTopic on disconnect
            if (consumer != null) {
                try {
                    consumer.close();
                    log.info("{} - Closed consumer for towardsPlatformTopic", tenant);
                } catch (PulsarClientException e) {
                    log.error("{} - Error closing consumer for towardsPlatformTopic:", tenant, e);
                }
                consumer = null;
            }

            // Close producer for towardsDeviceTopic
            if (producer != null) {
                try {
                    producer.close();
                    log.info("{} - Closed producer for towardsDeviceTopic", tenant);
                } catch (PulsarClientException e) {
                    log.error("{} - Error closing producer for towardsDeviceTopic:", tenant, e);
                }
                producer = null;
            }

            try {
                if (pulsarClient != null && !pulsarClient.isClosed()) {
                    pulsarClient.close();
                }
            } catch (Exception e) {
                log.error("{} - Error disconnecting from Pulsar:", tenant, e);
            }

            connectionState.setFalse();
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);

            List<Mapping> updatedMappingsInbound = mappingService.rebuildMappingInboundCache(tenant, connectorId);
            initializeSubscriptionsInbound(updatedMappingsInbound, true, true);
            List<Mapping> updatedMappingsOutbound = mappingService.rebuildMappingOutboundCache(tenant, connectorId);
            initializeSubscriptionsOutbound(updatedMappingsOutbound);

            log.info("{} - Disconnected from MQTTService Pulsar", tenant);
        }
    }

    /**
     * Override subscribe method - Cumulocity MQTT Service handles subscriptions
     * differently
     */
    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        if (isConnected()) {
            log.debug(
                    "{} - MQTT Service subscription request for topic: [{}] with QoS: {} - handled by platform topic consumer",
                    tenant, topic, qos);
            sendSubscriptionEvents(topic, "Subscribing");

            // In Cumulocity MQTT Service model, we don't create individual topic
            // subscriptions
            // All inbound messages come through towardsPlatformTopic and are filtered by
            // properties
            log.info(
                    "{} - Subscription registered for topic: [{}] - messages will be received via towardsPlatformTopic",
                    tenant, topic);
        }
    }

    /**
     * Override unsubscribe method - Cumulocity MQTT Service handles subscriptions
     * differently
     */
    @Override
    public void unsubscribe(String topic) throws Exception {
        log.debug("{} - MQTT Service unsubscription request for topic: [{}] - handled by platform topic consumer",
                tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");

        // In Cumulocity MQTT Service model, we don't manage individual subscriptions
        // The platform topic consumer remains active
        log.info("{} - Unsubscription registered for topic: [{}]", tenant, topic);
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        String topic = context.getResolvedPublishTopic();

        Qos qos = context.getQos(); // Get QoS from context

        try {
            // Check if client is still valid before creating producer
            if (pulsarClient == null || pulsarClient.isClosed()) {
                log.warn("{} - Pulsar client is closed, attempting to reconnect", tenant);
                reconnect();
                return; // Message will be retried
            }

            // Check if producer exists producer
            if (producer != null && !producer.isConnected()) {
                log.warn("{} - Producer is disconnected, recreating", tenant);
                try {
                    producer.close();
                } catch (PulsarClientException closeEx) {
                    log.debug("{} - Error closing disconnected producer: {}", tenant, closeEx.getMessage());
                }
                producer = createProducerWithQos(towardsDeviceTopic, qos);
            } else if (producer == null) {
                // Create new producer if it doesn't exist
                log.debug("{} - Creating new producer for topic: {} with QoS: {}", tenant, towardsDeviceTopic, qos);
                producer = createProducerWithQos(towardsDeviceTopic, qos);
            }

            if (producer != null) {
                sendMessageWithQos(producer, payload, qos, context);
            } else {
                log.error("{} - No producer available", tenant);
            }

        } catch (PulsarClientException e) {
            handlePublishError(e, topic, qos, context);
        } catch (Exception e) {
            log.error("{} - Unexpected error publishing to topic: {} with QoS: {}", tenant, topic, qos, e);
        }
    }

    /**
     * Sends message with QoS-specific logic and topic as Pulsar message property
     */
    private void sendMessageWithQos(Producer<byte[]> producer, String payload, Qos qos, ProcessingContext<?> context)
            throws PulsarClientException {

        // TODO IMPLEMENTATION: Add topic as property to Pulsar message
        // All messages go to towardsDeviceTopic, but original MQTT topic is stored as
        // property
        String originalMqttTopic = context.getResolvedPublishTopic();

        if (qos == Qos.AT_MOST_ONCE) {
            // Fire and forget with topic property
            producer.newMessage()
                    .value(payload.getBytes())
                    .property(PULSAR_PROPERTY_MQTT_TOPIC, originalMqttTopic) // Store original MQTT topic
                    .property("tenant", tenant) // Add tenant for routing
                    .property("connectorId", connectorIdentifier) // Add connector ID
                    .property("qos", qos.name()) // Add QoS level
                    .sendAsync()
                    .exceptionally(throwable -> {
                        log.debug("{} - Failed to send AT_MOST_ONCE message (expected): {}",
                                tenant, throwable.getMessage());
                        return null;
                    });
        } else {
            // Wait for acknowledgment with topic property
            producer.newMessage()
                    .value(payload.getBytes())
                    .property(PULSAR_PROPERTY_MQTT_TOPIC, originalMqttTopic) // Store original MQTT topic
                    .property("tenant", tenant) // Add tenant for routing
                    .property("connectorId", connectorIdentifier) // Add connector ID
                    .property("qos", qos.name()) // Add QoS level
                    .send();
        }

        // Enhanced logging to show the N-2 topic mapping
        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info(
                    "{} - Published to Cumulocity MQTT Service: QoS={}, originalTopic=[{}], pulsarTopic=[{}], mapping={}, connector={}",
                    tenant, qos, originalMqttTopic, towardsDeviceTopic, context.getMapping().name, connectorName);
        }
    }

}