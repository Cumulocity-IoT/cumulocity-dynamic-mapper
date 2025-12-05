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
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import com.hivemq.client.mqtt.mqtt3.message.unsubscribe.Mqtt3Unsubscribe;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder.Complete;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.Certificate;
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

import javax.net.ssl.TrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * MQTT 3.x Connector Client.
 * Simplified and refactored with better separation of concerns.
 */
@Slf4j
public class MQTT3Client extends AConnectorClient {

    // MQTT-specific fields
    private Mqtt3BlockingClient mqttClient;
    private MQTT3Callback mqttCallback;
    private MqttClientSslConfig sslConfig;
    protected Certificate cert;
    private Boolean cleanSession = true;

    // Synchronization objects
    private final Object connectionLock = new Object();
    private final Object disconnectionLock = new Object();
    private volatile boolean isConnecting = false;
    private volatile boolean isDisconnecting = false;
    private volatile boolean intentionalDisconnect = false;

    @Getter
    @Setter
    private List<Qos> supportedQOS = Arrays.asList(
            Qos.AT_MOST_ONCE,
            Qos.AT_LEAST_ONCE,
            Qos.EXACTLY_ONCE);

    /**
     * Default constructor - initializes connector specification
     */
    public MQTT3Client() {
        this.connectorType = ConnectorType.MQTT;
        this.connectorSpecification = createConnectorSpecification();
        this.singleton = false;
    }

    /**
     * Full constructor with dependencies
     */
    public MQTT3Client(ConfigurationRegistry configurationRegistry,
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

            // log.info("{} - Debugging server certificate before SSL initialization",
            // tenant);
            // String mqttHost = (String)
            // connectorConfiguration.getProperties().get("mqttHost");
            // Integer mqttPort = (Integer)
            // connectorConfiguration.getProperties().get("mqttPort");
            // debugServerCertificate(mqttHost, mqttPort);

            if (useSelfSignedCertificate) {
                initializeSslConfiguration();
            }

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

    private void initializeSslConfiguration() throws Exception {
        try {
            // Load certificate using common method
            cert = loadCertificateFromConfiguration();

            // Log certificate information
            logCertificateInfo(cert);

            // Get X509 certificates
            List<X509Certificate> customCertificates = cert.getX509Certificates();
            if (customCertificates.isEmpty()) {
                throw new ConnectorException("No valid X.509 certificates found in PEM");
            }

            log.info("{} - Successfully parsed {} X.509 certificate(s)", tenant, customCertificates.size());

            // Create truststore (include system CAs) - PASS cert as parameter
            KeyStore trustStore = createTrustStore(true, customCertificates, cert);

            // Create TrustManagerFactory
            TrustManagerFactory tmf = createTrustManagerFactory(trustStore);

            // Build SSL configuration
            sslConfig = MqttClientSslConfig.builder()
                    .trustManagerFactory(tmf)
                    .protocols(DEFAULT_TLS_PROTOCOLS)
                    .handshakeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .hostnameVerifier(createHostnameVerifier())
                    .build();

            log.info("{} - SSL configuration initialized successfully", tenant);
            log.info("{}   Custom CAs: {}", tenant, customCertificates.size());
            log.info("{}   Protocols: {}", tenant, DEFAULT_TLS_PROTOCOLS);

            // Log chain structure
            logChainStructure(cert);

            // Optional: Print full summary at debug level
            if (log.isDebugEnabled()) {
                log.debug("{} - Full certificate chain summary:\n{}", tenant, cert.getSummary());
            }

        } catch (Exception e) {
            log.error("{} - Error creating SSL configuration", tenant, e);
            throw new ConnectorException("Failed to initialize SSL configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public void connect() {
        synchronized (connectionLock) {
            if (isConnecting) {
                log.debug("{} - Connection attempt already in progress", tenant);
                return;
            }

            if (isDisconnecting) {
                log.debug("{} - Disconnect in progress, cannot connect", tenant);
                return;
            }

            if (isConnected()) {
                log.debug("{} - Already connected", tenant);
                return;
            }

            isConnecting = true;
        }

        try {
            log.info("{} - Connecting MQTT client: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            // Build MQTT client
            mqttClient = buildMqttClient();

            // Create callback
            mqttCallback = new MQTT3Callback(
                    tenant,
                    configurationRegistry,
                    dispatcher,
                    connectorIdentifier,
                    connectorName);

            // Connect with retry logic
            connectWithRetry();

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

        } catch (Exception e) {
            log.error("{} - Error connecting MQTT client: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
        } finally {
            synchronized (connectionLock) {
                isConnecting = false;
            }
        }
    }

    private Mqtt3BlockingClient buildMqttClient() {
        String protocol = (String) connectorConfiguration.getProperties()
                .getOrDefault("protocol", MQTT_PROTOCOL_MQTT);
        String mqttHost = (String) connectorConfiguration.getProperties().get("mqttHost");
        Integer mqttPort = (Integer) connectorConfiguration.getProperties().get("mqttPort");
        String clientId = (String) connectorConfiguration.getProperties().get("clientId");
        String user = (String) connectorConfiguration.getProperties().get("user");
        String password = (String) connectorConfiguration.getProperties().get("password");
        cleanSession = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("cleanSession", true);

        // Build client
        Mqtt3ClientBuilder builder = Mqtt3Client.builder()
                .serverHost(mqttHost)
                .serverPort(mqttPort)
                .identifier(clientId + (additionalSubscriptionIdTest != null ? additionalSubscriptionIdTest : ""));

        // Add authentication if provided
        if (!StringUtils.isEmpty(user)) {
            Complete authBuilder = Mqtt3SimpleAuth.builder().username(user);
            if (!StringUtils.isEmpty(password)) {
                authBuilder.password(password.getBytes(StandardCharsets.UTF_8));
            }
            builder.simpleAuth(authBuilder.build());
        }

        // Add SSL if needed
        boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (useSelfSignedCertificate && sslConfig != null) {
            builder.sslConfig(sslConfig);
            log.debug("{} - Using self-signed certificate", tenant);
        } else if (MQTT_PROTOCOL_MQTTS.equals(protocol) || MQTT_PROTOCOL_WSS.equals(protocol)) {
            builder.sslWithDefaultConfig();
        }

        // Add WebSocket if needed
        if (MQTT_PROTOCOL_WS.equals(protocol) || MQTT_PROTOCOL_WSS.equals(protocol)) {
            builder.webSocketWithDefaultConfig();
            String serverPath = (String) connectorConfiguration.getProperties().get("serverPath");
            if (serverPath != null) {
                builder.webSocketConfig()
                        .serverPath(serverPath)
                        .applyWebSocketConfig();
            }
            log.debug("{} - Using WebSocket with path: {}", tenant, serverPath);
        }

        // Add listeners
        return builder
                .addDisconnectedListener(context -> {
                    boolean wasConnected = connectionStateManager.isConnected();
                    connectionStateManager.setConnected(false);

                    // Check flags with proper synchronization
                    boolean shouldReconnect;
                    synchronized (disconnectionLock) {
                        shouldReconnect = !intentionalDisconnect &&
                                !isDisconnecting &&
                                connectorConfiguration.getEnabled() &&
                                wasConnected;
                        // Mark as connecting if we're going to reconnect
                        if (shouldReconnect) {
                            isConnecting = true; // ADD THIS
                        }
                    }

                    if (shouldReconnect) {
                        log.warn("{} - Unexpected disconnection, attempting to reconnect", tenant);
                        // Schedule reconnection in a separate thread to avoid blocking
                        virtualThreadPool.submit(() -> {
                            try {
                                Thread.sleep(5000); // Wait before reconnecting
                                connect();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.debug("{} - Reconnection interrupted", tenant);
                            } catch (Exception e) {
                                log.error("{} - Error during reconnection", tenant, e);
                            }
                        });
                    } else {
                        log.debug(
                                "{} - Intentional disconnect or not reconnecting (intentional={}, disconnecting={}, enabled={}, wasConnected={})",
                                tenant, intentionalDisconnect, isDisconnecting,
                                connectorConfiguration.getEnabled(), wasConnected);
                    }
                })
                .addConnectedListener(context -> {
                    connectionStateManager.setConnected(true);
                    log.info("{} - MQTT3 client connected", tenant);
                })
                .buildBlocking();
    }

    private void connectWithRetry() {
        int maxAttempts = 3;
        int attempt = 0;

        while (attempt < maxAttempts && !isConnected() && shouldConnect()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            try {
                if (attempt > 0) {
                    log.info("{} - Connection attempt {} of {}", tenant, attempt + 1, maxAttempts);
                    Thread.sleep(WAIT_PERIOD_MS);
                }

                Mqtt3ConnAck ack = mqttClient.connectWith()
                        .cleanSession(cleanSession)
                        .keepAlive(60)
                        .send();

                if (!ack.getReturnCode().equals(Mqtt3ConnAckReturnCode.SUCCESS)) {
                    throw new ConnectorException(
                            String.format("Connection failed with code: %s", ack.getReturnCode().name()));
                }

                connectionStateManager.setConnected(true);
                connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

                log.info("{} - MQTT client connected successfully to {}:{}",
                        tenant, mqttClient.getConfig().getServerHost(), mqttClient.getConfig().getServerPort());

            } catch (Exception e) {
                attempt++;
                log.error("{} - Connection attempt {} failed: {}", tenant, attempt, e);

                if (attempt >= maxAttempts) {
                    connectionStateManager.updateStatusWithError(e);
                }
            }
        }
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Cannot subscribe: not connected");
        }

        try {
            // Validate and adjust QoS
            Qos adjustedQos = adjustQos(qos);

            log.debug("{} - Subscribing to topic: [{}], QoS: {}", tenant, topic, adjustedQos);

            Mqtt3AsyncClient asyncClient = mqttClient.toAsync();
            asyncClient.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.fromCode(adjustedQos.ordinal()))
                    .callback(mqttCallback)
                    .manualAcknowledgement(true)
                    .send()
                    .whenComplete((subAck, throwable) -> {
                        if (throwable != null) {
                            log.error("{} - Failed to subscribe to topic: [{}]", tenant, topic, throwable);
                        } else {
                            log.info("{} - Successfully subscribed to topic: [{}], QoS: {}",
                                    tenant, topic, adjustedQos);
                            sendSubscriptionEvents(topic, "Subscribed");
                        }
                    });

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

        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);

        Mqtt3AsyncClient asyncClient = mqttClient.toAsync();
        asyncClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(topic).build())
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        log.error("{} - Failed to unsubscribe from topic: [{}]", tenant, topic, throwable);
                    } else {
                        log.info("{} - Successfully unsubscribed from topic: [{}]", tenant, topic);
                        sendSubscriptionEvents(topic, "Unsubscribed");
                    }
                });
    }

    private Qos adjustQos(Qos requestedQos) {
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
    public void disconnect() {
        synchronized (disconnectionLock) {
            if (isDisconnecting) {
                log.debug("{} - Disconnect already in progress", tenant);
                return;
            }

            if (!isConnected() && mqttClient != null && !mqttClient.getState().isConnected()) {
                log.debug("{} - Already disconnected", tenant);
                return;
            }

            isDisconnecting = true;
            intentionalDisconnect = true;
        }

        try {
            log.info("{} - Disconnecting MQTT3 client", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            // Unsubscribe from all topics
            if (mappingSubscriptionManager != null && mqttClient != null) {
                try {
                    if (mqttClient.getState().isConnected()) {
                        mappingSubscriptionManager.getSubscriptionCountsView().keySet().forEach(topic -> {
                            try {
                                mqttClient.unsubscribe(Mqtt3Unsubscribe.builder().topicFilter(topic).build());
                                log.debug("{} - Unsubscribed from topic: [{}]", tenant, topic);
                            } catch (Exception e) {
                                log.debug("{} - Error unsubscribing from topic: [{}] (may already be unsubscribed)",
                                        tenant, topic);
                            }
                        });
                    }
                } catch (Exception e) {
                    log.debug("{} - Error during unsubscribe phase: {}", tenant, e.getMessage());
                }
            }

            // Disconnect client
            if (mqttClient != null) {
                try {
                    if (mqttClient.getState().isConnected()) {
                        mqttClient.disconnect();
                        log.info("{} - MQTT client disconnected successfully", tenant);
                    } else {
                        log.debug("{} - MQTT client was not connected, skipping disconnect", tenant);
                    }
                } catch (Exception e) {
                    log.debug("{} - Error disconnecting MQTT client (may already be disconnected): {}",
                            tenant, e.getMessage());
                }
            }

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            // Clear subscriptions
            if (mappingSubscriptionManager != null) {
                mappingSubscriptionManager.clear();
            }

            log.info("{} - MQTT3 client disconnect completed", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage());
            // Still mark as disconnected even if there was an error
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);
        } finally {
            synchronized (disconnectionLock) {
                isDisconnecting = false;
                intentionalDisconnect = false;
            }
        }
    }

    @Override
    public void close() {
        log.info("{} - Closing MQTT3 client", tenant);
        disconnect();

        synchronized (disconnectionLock) {
            if (mqttClient != null) {
                mqttClient = null;
            }
            if (mqttCallback != null) {
                mqttCallback = null;
            }
        }

        log.info("{} - MQTT3 client closed", tenant);
    }

    @Override
    public void monitorSubscriptions() {
        // nothing to do
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (!isConnected()) {
            log.warn("{} - Cannot publish: not connected", tenant);
            return;
        }

        try {
            DynamicMapperRequest request = context.getCurrentRequest();

            if (context.getCurrentRequest() == null ||
                    context.getCurrentRequest().getRequest() == null) {
                log.warn("{} - No payload to publish for mapping: {}", tenant, context.getMapping().getName());
                return;
            }

            String payload = request.getRequest();
            String topic = context.getResolvedPublishTopic();
            MqttQos mqttQos = MqttQos.fromCode(context.getQos().ordinal());

            Mqtt3Publish message = Mqtt3Publish.builder()
                    .topic(topic)
                    .retain(context.getRetain() == null ? false : context.getRetain())
                    .qos(mqttQos)
                    .payload(payload.getBytes(StandardCharsets.UTF_8))
                    .build();

            mqttClient.publish(message);

            if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
                log.info("{} - Published message on topic: [{}], QoS: {}, payload: {}",
                        tenant, topic, mqttQos, payload);
            } else {
                log.debug("{} - Published message on topic: [{}], QoS: {}", tenant, topic, mqttQos);
            }

        } catch (Exception e) {
            log.error("{} - Error publishing message: {}", tenant, e.getMessage(), e);
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        // Check self-signed certificate requirements
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (useSelfSignedCertificate) {
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
        // MQTT-specific housekeeping tasks
        if (mqttClient != null && !mqttClient.getState().isConnected() && shouldConnect()) {
            log.warn("{} - MQTT3 client is disconnected (state will be handled by connection manager)", tenant);
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
                new String[] { MQTT_PROTOCOL_MQTTS, MQTT_PROTOCOL_WSS });
        ConnectorPropertyCondition certCondition = new ConnectorPropertyCondition(
                "useSelfSignedCertificate", new String[] { "true" });
        ConnectorPropertyCondition wsCondition = new ConnectorPropertyCondition("protocol",
                new String[] { MQTT_PROTOCOL_WS, MQTT_PROTOCOL_WSS });

        configProps.put("version",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        MQTT_VERSION_3_1_1,
                        Map.of(MQTT_VERSION_3_1_1, MQTT_VERSION_3_1_1, MQTT_VERSION_5_0, MQTT_VERSION_5_0),
                        null));

        configProps.put("protocol",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        MQTT_PROTOCOL_MQTT,
                        Map.of(
                                MQTT_PROTOCOL_MQTT, MQTT_PROTOCOL_MQTT,
                                MQTT_PROTOCOL_MQTTS, MQTT_PROTOCOL_MQTTS,
                                MQTT_PROTOCOL_WS, MQTT_PROTOCOL_WS,
                                MQTT_PROTOCOL_WSS, MQTT_PROTOCOL_WSS),
                        null));

        configProps.put("mqttHost",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("mqttPort",
                new ConnectorProperty(null, true, 3, ConnectorPropertyType.NUMERIC_PROPERTY, false, false,
                        null, null, null));

        configProps.put("user",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("password",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("clientId",
                new ConnectorProperty(null, true, 6, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, tlsCondition));

        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty("SHA 1 fingerprint of CA or Self Signed Certificate", false, 8,
                        ConnectorPropertyType.STRING_LARGE_PROPERTY, false, false,
                        null, null, certCondition));

        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, certCondition));

        configProps.put("certificateChainInPemFormat",
                new ConnectorProperty(
                        "Either enter certificate in PEM format or identify certificate by name and fingerprint (must be uploaded as Trusted Certificate in Device Management)",
                        false, 10, ConnectorPropertyType.STRING_LARGE_PROPERTY, false, false,
                        null, null, certCondition));

        configProps.put("disableHostnameValidation",
                new ConnectorProperty(null, false, 11, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, certCondition));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        true, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 13, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, null));

        configProps.put("serverPath",
                new ConnectorProperty(null, false, 14, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, wsCondition));

        configProps.put("cleanSession",
                new ConnectorProperty(null, false, 15, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        true, null, null));

        String name = "Generic MQTT";
        String description = "Connector for connecting to external MQTT broker over tcp or websocket.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.MQTT,
                false,
                configProps,
                false,
                supportedDirections());
    }

}