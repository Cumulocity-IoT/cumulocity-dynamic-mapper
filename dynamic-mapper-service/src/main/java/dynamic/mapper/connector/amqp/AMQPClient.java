/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.connector.amqp;

import com.rabbitmq.client.*;
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
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * AMQP 0-9-1 Connector Client using RabbitMQ client library.
 * Supports both inbound (consumer) and outbound (producer) operations.
 */
@Slf4j
public class AMQPClient extends AConnectorClient {

    // AMQP-specific fields
    private Connection connection;
    private Channel channel;
    private final Map<String, String> consumerTags = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private List<Qos> supportedQOS = Arrays.asList(
            Qos.AT_MOST_ONCE,
            Qos.AT_LEAST_ONCE);

    /**
     * Default constructor - initializes connector specification
     */
    public AMQPClient() {
        this.connectorType = ConnectorType.AMQP;
        this.connectorSpecification = createConnectorSpecification();
        this.singleton = false;
    }

    /**
     * Full constructor with dependencies
     */
    public AMQPClient(ConfigurationRegistry configurationRegistry,
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
            // Initialize SSL if needed (checks protocol and certificate configuration)
            initializeSslIfNeeded();

            log.info("{} - Connector {} initialized successfully", tenant, connectorName);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing connector: {}", tenant, connectorName, e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    @Override
    public void connect() {
        if (!beginConnection()) {
            return;
        }

        try {
            log.info("{} - Connecting AMQP client: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            // Build connection
            connection = buildConnection();

            // Create channel
            channel = connection.createChannel();

            // Set up recovery listener
            ((Recoverable) connection).addRecoveryListener(new RecoveryListener() {
                @Override
                public void handleRecovery(Recoverable recoverable) {
                    log.info("{} - AMQP connection recovered", tenant);
                    connectionStateManager.setConnected(true);
                    connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);
                }

                @Override
                public void handleRecoveryStarted(Recoverable recoverable) {
                    log.warn("{} - AMQP connection recovery started", tenant);
                    connectionStateManager.setConnected(false);
                }
            });

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            log.info("{} - AMQP client connected successfully to {}:{}",
                    tenant, connectorConfiguration.getProperties().get("host"),
                    connectorConfiguration.getProperties().get("port"));

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

        } catch (Exception e) {
            log.error("{} - Error connecting AMQP client: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
        } finally {
            endConnection();
        }
    }

    private Connection buildConnection() throws IOException, TimeoutException, java.security.NoSuchAlgorithmException, java.security.KeyManagementException {
        String host = (String) connectorConfiguration.getProperties().get("host");
        Integer port = (Integer) connectorConfiguration.getProperties().get("port");
        String virtualHost = (String) connectorConfiguration.getProperties()
                .getOrDefault("virtualHost", "/");
        String username = (String) connectorConfiguration.getProperties().get("username");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String protocol = (String) connectorConfiguration.getProperties()
                .getOrDefault("protocol", "amqp://");
        Boolean automaticRecovery = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("automaticRecovery", true);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setVirtualHost(virtualHost);

        if (!StringUtils.isEmpty(username)) {
            factory.setUsername(username);
        }
        if (!StringUtils.isEmpty(password)) {
            factory.setPassword(password);
        }

        // Configure SSL if needed
        if ("amqps://".equals(protocol)) {
            if (sslContext != null) {
                factory.useSslProtocol(sslContext);
            } else {
                factory.useSslProtocol();
            }
        }

        // Configure automatic recovery
        factory.setAutomaticRecoveryEnabled(automaticRecovery);
        if (automaticRecovery) {
            factory.setNetworkRecoveryInterval(5000);
        }

        // Connection timeout
        factory.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS * 1000);

        return factory.newConnection();
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Cannot subscribe: not connected");
        }

        try {
            String queueName = (String) connectorConfiguration.getProperties()
                    .getOrDefault("queuePrefix", "");
            if (!queueName.isEmpty()) {
                queueName += ".";
            }
            queueName += topic.replace("/", ".");

            String exchangeName = (String) connectorConfiguration.getProperties()
                    .getOrDefault("exchange", "");
            String exchangeType = (String) connectorConfiguration.getProperties()
                    .getOrDefault("exchangeType", "topic");

            // Declare exchange if specified
            if (!StringUtils.isEmpty(exchangeName)) {
                channel.exchangeDeclare(exchangeName, exchangeType, true);
            }

            // Declare queue
            Boolean autoDeleteQueue = (Boolean) connectorConfiguration.getProperties()
                    .getOrDefault("autoDeleteQueue", false);
            channel.queueDeclare(queueName, true, false, autoDeleteQueue, null);

            // Bind queue to exchange if exchange is specified
            if (!StringUtils.isEmpty(exchangeName)) {
                String routingKey = topic.replace("/", ".");
                channel.queueBind(queueName, exchangeName, routingKey);
                log.debug("{} - Bound queue {} to exchange {} with routing key: {}",
                        tenant, queueName, exchangeName, routingKey);
            }

            // Create callback
            AMQPCallback callback = new AMQPCallback(
                    tenant,
                    configurationRegistry,
                    dispatcher,
                    connectorIdentifier,
                    connectorName);

            // Start consuming
            boolean autoAck = qos == Qos.AT_MOST_ONCE;
            String consumerTag = channel.basicConsume(queueName, autoAck, callback);
            consumerTags.put(topic, consumerTag);

            log.info("{} - Successfully subscribed to queue: [{}], QoS: {}",
                    tenant, queueName, qos);
            sendSubscriptionEvents(topic, "Subscribed");

        } catch (Exception e) {
            throw new ConnectorException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        if (!isConnected()) {
            log.warn("{} - Cannot unsubscribe: not connected", tenant);
            return;
        }

        try {
            String consumerTag = consumerTags.remove(topic);
            if (consumerTag != null) {
                channel.basicCancel(consumerTag);
                log.info("{} - Successfully unsubscribed from topic: [{}]", tenant, topic);
                sendSubscriptionEvents(topic, "Unsubscribed");
            }
        } catch (Exception e) {
            log.error("{} - Failed to unsubscribe from topic: [{}]", tenant, topic, e);
        }
    }

    @Override
    public void disconnect() {
        if (!beginDisconnection()) {
            return;
        }

        try {
            log.info("{} - Disconnecting AMQP client", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            // Cancel all consumers
            if (channel != null && channel.isOpen()) {
                for (String consumerTag : consumerTags.values()) {
                    try {
                        channel.basicCancel(consumerTag);
                    } catch (Exception e) {
                        log.debug("{} - Error cancelling consumer: {}", tenant, e.getMessage());
                    }
                }
                consumerTags.clear();
            }

            // Close channel
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                } catch (Exception e) {
                    log.debug("{} - Error closing channel: {}", tenant, e.getMessage());
                }
            }

            // Close connection
            if (connection != null && connection.isOpen()) {
                try {
                    connection.close();
                    log.info("{} - AMQP connection closed successfully", tenant);
                } catch (Exception e) {
                    log.debug("{} - Error closing connection: {}", tenant, e.getMessage());
                }
            }

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            // Clear subscriptions
            if (mappingSubscriptionManager != null) {
                mappingSubscriptionManager.clear();
            }

            log.info("{} - AMQP client disconnect completed", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage());
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);
        } finally {
            endDisconnection();
        }
    }

    @Override
    protected boolean isPhysicallyConnected() {
        return connection != null && connection.isOpen() &&
               channel != null && channel.isOpen();
    }

    @Override
    public void close() {
        log.info("{} - Closing AMQP client", tenant);
        disconnect();

        synchronized (disconnectionLock) {
            channel = null;
            connection = null;
        }

        log.info("{} - AMQP client closed", tenant);
    }

    @Override
    public void monitorSubscriptions() {
        // Check connection health
        if (connection != null && !connection.isOpen() && shouldConnect()) {
            log.warn("{} - AMQP connection is closed, attempting reconnection", tenant);
            virtualThreadPool.submit(() -> {
                try {
                    Thread.sleep(5000);
                    connect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("{} - Error during reconnection", tenant, e);
                }
            });
        }
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (!isConnected()) {
            log.warn("{} - Cannot publish: not connected", tenant);
            return;
        }

        var requests = context.getRequests();
        if (requests == null || requests.isEmpty()) {
            log.warn("{} - No requests to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }

        String exchangeName = (String) connectorConfiguration.getProperties()
                .getOrDefault("exchange", "");

        // Process each request
        for (int i = 0; i < requests.size(); i++) {
            DynamicMapperRequest request = requests.get(i);

            if (request == null || request.getRequest() == null) {
                log.warn("{} - Skipping null request or payload ({}/{})", tenant, i + 1, requests.size());
                continue;
            }

            String payload = request.getRequest();
            String topic = request.getPublishTopic() != null ? request.getPublishTopic() : context.getResolvedPublishTopic();

            if (topic == null || topic.isEmpty()) {
                log.warn("{} - No topic specified for request ({}/{}), skipping", tenant, i + 1, requests.size());
                request.setError(new Exception("No publish topic specified"));
                continue;
            }

            try {
                // Convert topic to routing key (replace / with .)
                String routingKey = topic.replace("/", ".");

                // Build message properties
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .deliveryMode(context.getQos() == Qos.AT_LEAST_ONCE ? 2 : 1)
                        .contentType("application/json")
                        .build();

                // Publish message
                channel.basicPublish(exchangeName, routingKey, props, payload.getBytes(StandardCharsets.UTF_8));

                if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
                    log.info("{} - Published message ({}/{}): exchange=[{}], routingKey=[{}], QoS: {}, payload: {}",
                            tenant, i + 1, requests.size(), exchangeName, routingKey, context.getQos(), payload);
                } else {
                    log.debug("{} - Published message ({}/{}): exchange=[{}], routingKey=[{}], QoS: {}",
                            tenant, i + 1, requests.size(), exchangeName, routingKey, context.getQos());
                }

            } catch (Exception e) {
                log.error("{} - Error publishing to topic: {} ({}/{})", tenant, topic, i + 1, requests.size(), e);
                request.setError(e);
                context.addError(new dynamic.mapper.processor.ProcessingException(
                        "Failed to publish message " + (i + 1) + "/" + requests.size(), e));
            }
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        // Check SSL certificate requirements
        String protocol = (String) configuration.getProperties()
                .getOrDefault("protocol", "amqp://");
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if ("amqps://".equals(protocol) && useSelfSignedCertificate) {
            if (configuration.getProperties().get("fingerprintSelfSignedCertificate") == null ||
                    configuration.getProperties().get("nameCertificate") == null) {
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
    protected void connectorSpecificHousekeeping(String tenant) {
        // AMQP-specific housekeeping tasks
        if (connection != null && !connection.isOpen() && shouldConnect()) {
            log.warn("{} - AMQP connection is closed (state will be handled by connection manager)", tenant);
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
     * Create connector specification with all properties
     */
    private ConnectorSpecification createConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("protocol",
                new String[] { "amqps://" });
        ConnectorPropertyCondition certCondition = new ConnectorPropertyCondition(
                "useSelfSignedCertificate", new String[] { "true" });

        configProps.put("protocol",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        "amqp://",
                        Map.of("amqp://", "amqp://", "amqps://", "amqps://"),
                        null));

        configProps.put("host",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "localhost", null, null));

        configProps.put("port",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.NUMERIC_PROPERTY, false, false,
                        5672, null, null));

        configProps.put("virtualHost",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "/", null, null));

        configProps.put("username",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "guest", null, null));

        configProps.put("password",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("exchange",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "", null, null));

        configProps.put("exchangeType",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        "topic",
                        Map.of("topic", "Topic", "direct", "Direct", "fanout", "Fanout", "headers", "Headers"),
                        null));

        configProps.put("queuePrefix",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "", null, null));

        configProps.put("autoDeleteQueue",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, null));

        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, tlsCondition));

        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty("SHA 1 fingerprint of CA or Self Signed Certificate", false, 11,
                        ConnectorPropertyType.STRING_LARGE_PROPERTY, false, false,
                        null, null, certCondition));

        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, certCondition));

        configProps.put("certificateChainInPemFormat",
                new ConnectorProperty(
                        "Either enter certificate in PEM format or identify certificate by name and fingerprint (must be uploaded as Trusted Certificate in Device Management)",
                        false, 13, ConnectorPropertyType.STRING_LARGE_PROPERTY, false, false,
                        null, null, certCondition));

        configProps.put("automaticRecovery",
                new ConnectorProperty(null, false, 14, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        true, null, null));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 15, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        true, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 16, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, null));

        String name = "AMQP Connector";
        String description = "Connector for connecting to AMQP 0-9-1 brokers (RabbitMQ, etc.). " +
                "Supports publishing and consuming messages via queues and exchanges.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.AMQP,
                false,
                configProps,
                false,
                supportedDirections());
    }
}
