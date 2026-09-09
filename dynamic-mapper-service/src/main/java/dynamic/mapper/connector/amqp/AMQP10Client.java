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
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.qpid.jms.JmsConnectionFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AMQP 1.0 Connector Client using Apache Qpid JMS.
 *
 * <p>Connects to any AMQP 1.0-compliant broker (Azure Service Bus, ActiveMQ Artemis,
 * Solace, etc.) using the JMS 2.0 API provided by the Qpid JMS client library.</p>
 *
 * <p>Key differences from the AMQP 0-9-1 connector ({@link AMQPClient}):</p>
 * <ul>
 *   <li>No exchange / routing-key concept — destinations are plain node addresses.</li>
 *   <li>Destination type is configurable: {@code queue} (default) or {@code topic}.</li>
 *   <li>Automatic reconnection is provided by the Qpid JMS failover transport.</li>
 *   <li>SSL is configured via URI transport parameters rather than a RabbitMQ-specific API.</li>
 * </ul>
 */
@Slf4j
public class AMQP10Client extends AConnectorClient {

    // JMS resources
    private volatile Connection connection;
    private volatile Session session;
    private volatile boolean physicallyConnected = false;

    private final Map<String, MessageConsumer> consumers = new ConcurrentHashMap<>();
    private final Map<String, MessageProducer> producers = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private List<Qos> supportedQOS = Arrays.asList(
            Qos.AT_MOST_ONCE,
            Qos.AT_LEAST_ONCE);

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Default constructor — initialises the connector specification only.
     * Used by the registry to expose the specification without a live connection.
     */
    public AMQP10Client() {
        this.connectorType = ConnectorType.AMQP_10;
        this.connectorSpecification = createConnectorSpecification();
        this.singleton = false;
    }

    /**
     * Full constructor with all runtime dependencies.
     */
    public AMQP10Client(ConfigurationRegistry configurationRegistry,
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

        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.dispatcher = dispatcher;

        initializeManagers();
    }

    // -------------------------------------------------------------------------
    // SSL helpers
    // -------------------------------------------------------------------------

    @Override
    protected boolean isSslRequired() {
        Object protocol = connectorConfiguration.getProperties().get("protocol");
        return "amqps://".equals(protocol);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            initializeSslIfNeeded();

            log.info("{} - AMQP 1.0 connector {} initialized successfully", tenant, connectorName);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing AMQP 1.0 connector: {}", tenant, connectorName, e);
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
            log.info("{} - Connecting AMQP 1.0 client: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            // Close any connection/session left over from a previous connect() attempt (e.g. one
            // that got partway through before failing) before building new ones — otherwise that
            // old connection's socket, and Qpid's failover reconnect loop if automaticRecovery is
            // enabled, would leak forever once these fields are overwritten below.
            closeExistingConnectionQuietly();

            String remoteUri = buildConnectionUri();
            log.debug("{} - Connecting to AMQP 1.0 URI: {}", tenant, sanitizeUri(remoteUri));

            JmsConnectionFactory factory = new JmsConnectionFactory(remoteUri);

            String username = (String) connectorConfiguration.getProperties().get("username");
            String password = (String) connectorConfiguration.getProperties().get("password");
            if (StringUtils.isNotEmpty(username)) {
                factory.setUsername(username);
            }
            if (StringUtils.isNotEmpty(password)) {
                factory.setPassword(password);
            }
            String clientId = (String) connectorConfiguration.getProperties().get("clientId");
            if (StringUtils.isNotEmpty(clientId)) {
                factory.setClientID(clientId);
            }

            connection = factory.createConnection();
            connection.setExceptionListener(ex -> {
                boolean intentional;
                synchronized (disconnectionLock) {
                    intentional = intentionalDisconnect || isDisconnecting;
                }
                if (intentional) {
                    // disconnect() already reports DISCONNECTED itself. Guards against the same
                    // double-report race fixed for MQTT (see MQTT3Client/MQTT5Client's
                    // addDisconnectedListener) in case the JMS provider ever invokes this listener
                    // as a side effect of our own graceful, client-initiated close.
                    log.debug("{} - AMQP 1.0 JMS exception listener fired during our own disconnect, skipping duplicate report: {}",
                            tenant, ex.getMessage());
                    return;
                }
                log.error("{} - AMQP 1.0 JMS exception: {}", tenant, ex.getMessage());
                physicallyConnected = false;
                connectionStateManager.setConnected(false, ex);
            });
            connection.start();

            // Single CLIENT_ACKNOWLEDGE session — gives manual ack control for AT_LEAST_ONCE
            session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

            physicallyConnected = true;
            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            log.info("{} - AMQP 1.0 client connected successfully to {}:{}",
                    tenant,
                    connectorConfiguration.getProperties().get("host"),
                    connectorConfiguration.getProperties().get("port"));

            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

        } catch (Exception e) {
            log.error("{} - Error connecting AMQP 1.0 client: {}", tenant, e.getMessage(), e);
            physicallyConnected = false;
            connectionStateManager.updateStatusWithError(e);
        } finally {
            endConnection();
        }
    }

    /** Close and null out any previous JMS session/connection, swallowing errors (best-effort). */
    private void closeExistingConnectionQuietly() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("{} - Error closing previous JMS session before reconnect: {}", tenant, e.getMessage());
            }
            session = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.debug("{} - Error closing previous JMS connection before reconnect: {}", tenant, e.getMessage());
            }
            connection = null;
        }
    }

    @Override
    public void disconnect() {
        if (!beginDisconnection()) {
            return;
        }

        try {
            log.info("{} - Disconnecting AMQP 1.0 client", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            physicallyConnected = false;

            // Guarded by the same lock publishMEAO() uses around its producer-get/create-and-send
            // sequence: without it, a publish in progress could have its producer/session closed
            // out from under it mid-send, and two threads could race on the producers map.
            synchronized (this) {
                // Close all consumers
                consumers.forEach((topic, consumer) -> {
                    try {
                        consumer.close();
                    } catch (JMSException e) {
                        log.debug("{} - Error closing consumer for {}: {}", tenant, topic, e.getMessage());
                    }
                });
                consumers.clear();

                // Close all cached producers
                producers.forEach((address, producer) -> {
                    try {
                        producer.close();
                    } catch (JMSException e) {
                        log.debug("{} - Error closing producer for {}: {}", tenant, address, e.getMessage());
                    }
                });
                producers.clear();

                // Close session
                if (session != null) {
                    try {
                        session.close();
                    } catch (JMSException e) {
                        log.debug("{} - Error closing JMS session: {}", tenant, e.getMessage());
                    }
                }

                // Close connection
                if (connection != null) {
                    try {
                        connection.close();
                        log.info("{} - AMQP 1.0 connection closed successfully", tenant);
                    } catch (JMSException e) {
                        log.debug("{} - Error closing JMS connection: {}", tenant, e.getMessage());
                    }
                }
            }

            // setConnected(false) already transitions to DISCONNECTED internally (see
            // ConnectionStateManager.setConnected) — no need to also call updateStatus explicitly.
            connectionStateManager.setConnected(false);

            if (mappingSubscriptionManager != null) {
                mappingSubscriptionManager.clear();
            }

            log.info("{} - AMQP 1.0 client disconnect completed", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage());
            connectionStateManager.setConnected(false);
        } finally {
            endDisconnection();
        }
    }

    @Override
    public void close() {
        log.info("{} - Closing AMQP 1.0 client", tenant);
        disconnect();

        synchronized (disconnectionLock) {
            session = null;
            connection = null;
        }

        log.info("{} - AMQP 1.0 client closed", tenant);
    }

    // -------------------------------------------------------------------------
    // Subscriptions
    // -------------------------------------------------------------------------

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Cannot subscribe: not connected");
        }

        try {
            Destination destination = resolveDestination(topic);

            AMQP10Callback callback = new AMQP10Callback(
                    tenant,
                    configurationRegistry,
                    dispatcher,
                    connectorIdentifier,
                    connectorName,
                    topic);

            MessageConsumer consumer = session.createConsumer(destination);
            consumer.setMessageListener(callback);
            consumers.put(topic, consumer);

            log.info("{} - AMQP 1.0: subscribed to address [{}], QoS: {}", tenant, topic, qos);
            sendSubscriptionEvents(topic, "Subscribed");

        } catch (JMSException e) {
            throw new ConnectorException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        if (!isConnected()) {
            log.warn("{} - Cannot unsubscribe: not connected", tenant);
            return;
        }

        MessageConsumer consumer = consumers.remove(topic);
        if (consumer != null) {
            try {
                consumer.close();
                log.info("{} - AMQP 1.0: unsubscribed from address [{}]", tenant, topic);
                sendSubscriptionEvents(topic, "Unsubscribed");
            } catch (JMSException e) {
                log.error("{} - Failed to close consumer for topic [{}]: {}", tenant, topic, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------

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

        for (int i = 0; i < requests.size(); i++) {
            DynamicMapperRequest request = requests.get(i);

            if (request == null || request.getRequest() == null) {
                log.warn("{} - Skipping null request ({}/{})", tenant, i + 1, requests.size());
                continue;
            }

            String payload = request.getRequest();
            String address = request.getPublishTopic() != null
                    ? request.getPublishTopic()
                    : context.getResolvedPublishTopic();

            if (address == null || address.isEmpty()) {
                log.warn("{} - No address specified for request ({}/{}), skipping", tenant, i + 1, requests.size());
                request.setError(new Exception("No publish address specified"));
                continue;
            }

            try {
                synchronized (this) {
                    Destination destination = resolveDestination(address);
                    MessageProducer producer = getOrCreateProducer(address, destination);

                    TextMessage message = session.createTextMessage(payload);
                    String contentType = (String) connectorConfiguration.getProperties().get("contentType");
                    if (StringUtils.isNotEmpty(contentType)) {
                        message.setStringProperty("JMS_AMQP_ContentType", contentType);
                    }
                    int deliveryMode = (context.getQos() == Qos.AT_LEAST_ONCE)
                            ? DeliveryMode.PERSISTENT
                            : DeliveryMode.NON_PERSISTENT;
                    producer.setDeliveryMode(deliveryMode);
                    producer.send(message);
                }

                if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
                    log.info("{} - OUTBOUND SEND: connector={}, topic={}, qos={}, payload={}",
                            tenant, getConnectorName(), address, context.getQos(), payload);
                } else {
                    log.debug("{} - AMQP 1.0 published ({}/{}): address=[{}], QoS: {}",
                            tenant, i + 1, requests.size(), address, context.getQos());
                }

            } catch (Exception e) {
                log.error("{} - Error publishing to address: {} ({}/{})", tenant, address, i + 1, requests.size(), e);
                request.setError(e);
                context.addError(new dynamic.mapper.processor.ProcessingException(
                        "Failed to publish message " + (i + 1) + "/" + requests.size(), e));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Status / monitoring
    // -------------------------------------------------------------------------

    @Override
    protected boolean isPhysicallyConnected() {
        return physicallyConnected && connection != null && session != null;
    }

    @Override
    public void monitorSubscriptions() {
        if (!isPhysicallyConnected() && shouldConnect()) {
            log.warn("{} - AMQP 1.0 connection lost, scheduling reconnect", tenant);
            virtualThreadPool.submit(() -> {
                try {
                    Thread.sleep(5000);
                    // submitConnect() (not connect() directly): goes through AConnectorClient's
                    // lifecycleLock/connectDisconnectExecutionLock, so this background reconnect
                    // can't race a concurrent submitConnect()/submitDisconnect() triggered by a
                    // user operation or another health check.
                    submitConnect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("{} - Error during AMQP 1.0 reconnection", tenant, e);
                }
            });
        }
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // No additional housekeeping beyond monitorSubscriptions()
    }

    // -------------------------------------------------------------------------
    // Configuration / metadata
    // -------------------------------------------------------------------------

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

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
                            .getOrDefault("supportsWildcardInTopicInbound", "false").toString());
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

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Build the Qpid JMS connection URI, optionally wrapping in a failover transport
     * and adding SSL transport options for amqps:// connections.
     */
    private String buildConnectionUri() {
        String protocol = (String) connectorConfiguration.getProperties()
                .getOrDefault("protocol", "amqp://");
        String host = (String) connectorConfiguration.getProperties().get("host");
        Integer port = (Integer) connectorConfiguration.getProperties().get("port");
        Boolean automaticRecovery = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("automaticRecovery", true);
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        StringBuilder baseUri = new StringBuilder();
        baseUri.append(protocol).append(host).append(":").append(port);

        // Append SSL transport options for amqps://
        if ("amqps://".equals(protocol)) {
            if (useSelfSignedCertificate) {
                baseUri.append("?transport.trustAll=true&transport.verifyHost=false");
            }
            // For standard amqps:// the JVM default truststore is used automatically
        }

        if (automaticRecovery) {
            // Wrap in failover transport for automatic reconnection
            return "failover:(" + baseUri + ")"
                    + "?failover.maxReconnectAttempts=-1"
                    + "&failover.initialReconnectDelay=1000"
                    + "&failover.reconnectDelay=5000"
                    + "&failover.useReconnectBackOff=true"
                    + "&failover.maxReconnectDelay=30000";
        }

        return baseUri.toString();
    }

    /**
     * Resolve a topic/address string to the correct JMS {@link Destination}
     * based on the configured {@code destinationType} property.
     */
    private Destination resolveDestination(String address) throws JMSException {
        String destinationType = (String) connectorConfiguration.getProperties()
                .getOrDefault("destinationType", "queue");
        if ("topic".equalsIgnoreCase(destinationType)) {
            return session.createTopic(address);
        }
        return session.createQueue(address);
    }

    /**
     * Return a cached {@link MessageProducer} for the given address, creating one if absent.
     * Must be called inside a {@code synchronized(this)} block.
     */
    private MessageProducer getOrCreateProducer(String address, Destination destination) throws JMSException {
        MessageProducer producer = producers.get(address);
        if (producer == null) {
            producer = session.createProducer(destination);
            producers.put(address, producer);
        }
        return producer;
    }

    /**
     * Remove the password from a connection URI before logging.
     */
    private String sanitizeUri(String uri) {
        // Passwords appear only in setPassword(), not in the URI for Qpid JMS
        return uri;
    }

    // -------------------------------------------------------------------------
    // Connector specification
    // -------------------------------------------------------------------------

    /**
     * Create the connector specification describing all configurable properties.
     */
    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("AMQP 1.0 Connector", ConnectorType.AMQP_10)
                .description("Connector for AMQP 1.0 brokers (Azure Service Bus, ActiveMQ Artemis, " +
                        "Solace, etc.) using the Apache Qpid JMS client.")
                .supportedDirections(supportedDirections())

                // Protocol selection
                .property("protocol", ConnectorPropertyBuilder.requiredOption()
                        .order(0)
                        .defaultValue("amqp://")
                        .options("amqp://", "amqps://"))

                // Connection
                .property("host", ConnectorPropertyBuilder.requiredString()
                        .order(1)
                        .defaultValue("localhost"))

                .property("port", ConnectorPropertyBuilder.requiredNumeric()
                        .order(2)
                        .defaultValue(5672))

                // Authentication
                .property("username", ConnectorPropertyBuilder.optionalString()
                        .order(3))

                .property("password", ConnectorPropertyBuilder.optionalSensitive()
                        .order(4))

                // AMQP container ID / JMS client ID (e.g. device ID for Azure IoT Hub)
                .property("clientId", ConnectorPropertyBuilder.optionalString()
                        .order(5))

                // AMQP 1.0 destination type — no exchange/routing-key concept
                .property("destinationType", ConnectorPropertyBuilder.requiredOption()
                        .order(6)
                        .defaultValue("queue")
                        .optionsWithLabels("queue", "Queue", "topic", "Topic"))

                // Content type set on outbound AMQP messages (e.g. application/json;charset=utf-8)
                .property("contentType", ConnectorPropertyBuilder.optionalString()
                        .order(7))

                // TLS/SSL certificate configuration (only for amqps://)
                .property("useSelfSignedCertificate", ConnectorPropertyBuilder.optionalBoolean()
                        .order(8)
                        .defaultValue(false)
                        .condition("protocol", "amqps://"))

                .property("fingerprintSelfSignedCertificate", ConnectorPropertyBuilder.largeText()
                        .order(9)
                        .description("SHA-1 fingerprint of CA or Self-Signed Certificate")
                        .condition("useSelfSignedCertificate", "true"))

                .property("nameCertificate", ConnectorPropertyBuilder.optionalString()
                        .order(10)
                        .condition("useSelfSignedCertificate", "true"))

                .property("certificateChainInPemFormat", ConnectorPropertyBuilder.largeText()
                        .order(11)
                        .description("Certificate in PEM format, or identify by name/fingerprint " +
                                "(must be uploaded as Trusted Certificate in Device Management)")
                        .condition("useSelfSignedCertificate", "true"))

                // Connection behaviour
                .property("automaticRecovery", ConnectorPropertyBuilder.optionalBoolean()
                        .order(12)
                        .defaultValue(true))

                // Wildcard support (broker-dependent for AMQP 1.0)
                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(13)
                        .defaultValue(false))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .order(14)
                        .defaultValue(false))

                .build();
    }
}
