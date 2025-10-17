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
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Apache Pulsar Connector Client.
 * Supports pub-sub messaging with QoS levels, wildcards, and TLS.
 * Maps MQTT-style topics to Pulsar topic format.
 * 
 * QoS Mapping:
 * - AT_MOST_ONCE: Immediate ack, fire-and-forget
 * - AT_LEAST_ONCE: Ack after processing, Shared subscription
 * - EXACTLY_ONCE: Ack after processing, Exclusive subscription
 */
@Slf4j
public class PulsarConnectorClient extends AConnectorClient {

    private static final int DEFAULT_CONNECTION_TIMEOUT = 30;
    private static final int DEFAULT_OPERATION_TIMEOUT = 30;
    private static final int DEFAULT_KEEP_ALIVE = 30;
    private static final int MAX_PRODUCER_CREATE_RETRIES = 3;
    private static final int PRODUCER_CREATE_RETRY_DELAY_MS = 1000;

    protected PulsarClient pulsarClient;
    protected PulsarCallback pulsarCallback;
    protected SSLContext sslContext;
    protected Certificate cert;

    // Consumer and producer management
    protected final Map<String, Consumer<byte[]>> consumers = new ConcurrentHashMap<>();
    protected final Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();

    @Getter
    protected List<Qos> supportedQOS;

    /**
     * Default constructor
     */
    public PulsarConnectorClient() {
        this.connectorType = ConnectorType.PULSAR;
        this.singleton = false;
        this.supportedQOS = Arrays.asList(Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE, Qos.EXACTLY_ONCE);
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public PulsarConnectorClient(ConfigurationRegistry configurationRegistry,
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
                initializeSslConfiguration();
            }

            log.info("{} - Pulsar connector initialized successfully", tenant);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing Pulsar connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    /**
     * Initialize SSL context for self-signed certificates
     */
    private void initializeSslConfiguration() throws Exception {
        try {
            // Load certificate using common method
            cert = loadCertificateFromConfiguration();

            // Log basic info
            log.info("{} - Loaded {} certificate(s)", tenant, cert.getCertificateCount());

            // Get X509 certificates
            List<X509Certificate> customCertificates = cert.getX509Certificates();
            if (customCertificates.isEmpty()) {
                throw new ConnectorException("No valid X.509 certificates found in PEM");
            }

            // Create truststore (can choose whether to include system CAs) - PASS cert
            KeyStore trustStore = createTrustStore(false, customCertificates, cert);

            // Create TrustManagerFactory
            TrustManagerFactory tmf = createTrustManagerFactory(trustStore);

            // Create SSLContext for Pulsar
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, tmf.getTrustManagers(), null);

            log.info("{} - SSL context initialized for Pulsar", tenant);

        } catch (Exception e) {
            log.error("{} - Error creating SSL context for Pulsar", tenant, e);
            throw new ConnectorException("Failed to initialize SSL context: " + e.getMessage(), e);
        }
    }

    @Override
    public void connect() {
        log.info("{} - Connecting Pulsar connector: {}", tenant, connectorName);

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

            // Build Pulsar client
            pulsarClient = buildPulsarClient();

            // Create callback
            pulsarCallback = new PulsarCallback(
                    tenant,
                    configurationRegistry,
                    dispatcher,
                    connectorIdentifier,
                    connectorName,
                    false);

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

            log.info("{} - Pulsar connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting Pulsar connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            connectionStateManager.setConnected(false);
        }
    }

    /**
     * Build Pulsar client with configuration
     */
    private PulsarClient buildPulsarClient() throws PulsarClientException {
        String serviceUrl = (String) connectorConfiguration.getProperties().get("serviceUrl");
        Boolean enableTls = (Boolean) connectorConfiguration.getProperties().getOrDefault("enableTls", false);
        String authMethod = (String) connectorConfiguration.getProperties().getOrDefault("authenticationMethod",
                "none");
        String authParams = (String) connectorConfiguration.getProperties().get("authenticationParams");
        Integer connectionTimeout = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("connectionTimeoutSeconds", DEFAULT_CONNECTION_TIMEOUT);
        Integer operationTimeout = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("operationTimeoutSeconds", DEFAULT_OPERATION_TIMEOUT);
        Integer keepAlive = (Integer) connectorConfiguration.getProperties()
                .getOrDefault("keepAliveIntervalSeconds", DEFAULT_KEEP_ALIVE);

        // Adjust service URL for TLS
        String finalServiceUrl = adjustServiceUrlForTls(serviceUrl, enableTls);

        ClientBuilder clientBuilder = PulsarClient.builder()
                .serviceUrl(finalServiceUrl)
                .connectionTimeout(connectionTimeout, TimeUnit.SECONDS)
                .operationTimeout(operationTimeout, TimeUnit.SECONDS)
                .keepAliveInterval(keepAlive, TimeUnit.SECONDS);

        // Configure TLS
        if (isUsingTls(finalServiceUrl)) {
            configureTls(clientBuilder);
        }

        // Configure authentication
        configureAuthentication(clientBuilder, authMethod, authParams);

        return clientBuilder.build();
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
     * Check if URL uses TLS
     */
    private boolean isUsingTls(String serviceUrl) {
        return serviceUrl.startsWith("pulsar+ssl://") || serviceUrl.startsWith("https://");
    }

    /**
     * Configure TLS settings
     */
    private void configureTls(ClientBuilder clientBuilder) throws PulsarClientException {
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);

        if (useSelfSignedCertificate && cert != null) {
            try {
                Path certFile = writeCertificateToTempFile(cert.getCertInPemFormat());
                clientBuilder.tlsTrustCertsFilePath(certFile.toString());
                log.debug("{} - Using self-signed certificate", tenant);
            } catch (IOException e) {
                throw new PulsarClientException(e);
            }
        }
    }

    /**
     * Write certificate to temporary file
     */
    private Path writeCertificateToTempFile(String certContent) throws IOException {
        Path tempFile = Files.createTempFile("pulsar-cert", ".pem");
        Files.write(tempFile, certContent.getBytes(StandardCharsets.UTF_8));
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    /**
     * Configure authentication
     */
    private void configureAuthentication(ClientBuilder clientBuilder, String authMethod, String authParams)
            throws PulsarClientException {
        if (!"none".equals(authMethod) && !StringUtils.isEmpty(authParams)) {
            try {
                switch (authMethod) {
                    case "token":
                        clientBuilder.authentication(AuthenticationFactory.token(authParams));
                        log.debug("{} - Using token authentication", tenant);
                        break;
                    case "oauth2":
                        clientBuilder.authentication(AuthenticationFactory.create(
                                "org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2", authParams));
                        log.debug("{} - Using OAuth2 authentication", tenant);
                        break;
                    case "tls":
                        clientBuilder.authentication(AuthenticationFactory.create(
                                "org.apache.pulsar.client.impl.auth.AuthenticationTls", authParams));
                        log.debug("{} - Using TLS authentication", tenant);
                        break;
                    case "basic":
                        clientBuilder.authentication(AuthenticationFactory.create(
                                "org.apache.pulsar.client.impl.auth.AuthenticationBasic", authParams));
                        log.debug("{} - Using basic authentication", tenant);
                        break;
                    default:
                        log.warn("{} - Unknown authentication method: {}", tenant, authMethod);
                        break;
                }
            } catch (PulsarClientException.UnsupportedAuthenticationException e) {
                throw new PulsarClientException(e);
            }
        }
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected() || consumers.containsKey(topic)) {
            return;
        }

        log.debug("{} - Subscribing to topic: [{}], QoS: {}", tenant, topic, qos);

        try {
            String subscriptionName = buildSubscriptionName(topic);
            SubscriptionType subscriptionType = determineSubscriptionType(qos);

            Consumer<byte[]> consumer;

            if (containsMqttWildcards(topic)) {
                // Handle wildcard subscription
                String pulsarPattern = translateMqttTopicToPulsarRegex(topic);
                consumer = pulsarClient.newConsumer()
                        .topicsPattern(Pattern.compile(pulsarPattern))
                        .subscriptionName(subscriptionName)
                        .subscriptionType(subscriptionType)
                        .messageListener(new QoSAwarePulsarCallback(pulsarCallback, qos))
                        .acknowledgmentGroupTime(requiresAcknowledgment(qos) ? 100 : 0, TimeUnit.MILLISECONDS)
                        .subscribe();
                log.info("{} - Subscribed to pattern: [{}] (MQTT: [{}])", tenant, pulsarPattern, topic);
            } else {
                // Handle regular subscription
                String pulsarTopic = ensurePulsarTopicFormat(topic);
                consumer = pulsarClient.newConsumer()
                        .topic(pulsarTopic)
                        .subscriptionName(subscriptionName)
                        .subscriptionType(subscriptionType)
                        .messageListener(new QoSAwarePulsarCallback(pulsarCallback, qos))
                        .acknowledgmentGroupTime(requiresAcknowledgment(qos) ? 100 : 0, TimeUnit.MILLISECONDS)
                        .subscribe();
                log.info("{} - Subscribed to topic: [{}] (MQTT: [{}])", tenant, pulsarTopic, topic);
            }

            consumers.put(topic, consumer);
            sendSubscriptionEvents(topic, "Subscribed");

        } catch (PulsarClientException e) {
            throw new ConnectorException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);

        Consumer<byte[]> consumer = consumers.remove(topic);
        if (consumer != null) {
            try {
                consumer.close();
            } catch (PulsarClientException e) {
                throw new ConnectorException(
                        String.format("%s - Failed to unsubscribe from topic: %s", tenant, topic), e);
            }
            log.info("{} - Unsubscribed from topic: [{}]", tenant, topic);
            sendSubscriptionEvents(topic, "Unsubscribed");
        }
    }

    @Override
    public void disconnect() {
        if (!isConnected()) {
            log.debug("{} - Already disconnected", tenant);
            return;
        }

        log.info("{} - Disconnecting Pulsar connector", tenant);
        connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        try {
            // Close consumers
            consumers.values().forEach(consumer -> {
                try {
                    consumer.close();
                } catch (PulsarClientException e) {
                    log.warn("{} - Error closing consumer: {}", tenant, e.getMessage());
                }
            });
            consumers.clear();

            // Close producers
            producers.values().forEach(producer -> {
                try {
                    producer.close();
                } catch (PulsarClientException e) {
                    log.warn("{} - Error closing producer: {}", tenant, e.getMessage());
                }
            });
            producers.clear();

            // Close client
            if (pulsarClient != null && !pulsarClient.isClosed()) {
                pulsarClient.close();
            }

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            log.info("{} - Pulsar connector disconnected", tenant);

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
        return connectionStateManager.isConnected() &&
                pulsarClient != null &&
                !pulsarClient.isClosed();
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (pulsarClient == null || pulsarClient.isClosed()) {
            log.warn("{} - Pulsar client is closed, attempting reconnect", tenant);
            reconnect();
            return;
        }

        DynamicMapperRequest request = context.getCurrentRequest();
        String payload = request.getRequest();
        String topic = context.getResolvedPublishTopic();
        Qos qos = context.getQos();

        try {
            Producer<byte[]> producer = getOrCreateProducer(topic, qos);

            if (producer != null && producer.isConnected()) {
                sendMessageWithQos(producer, payload, qos, context);
            } else {
                log.error("{} - No connected producer for topic: {}", tenant, topic);
            }

        } catch (Exception e) {
            log.error("{} - Error publishing to topic: {}", tenant, topic, e);
            context.addError(new dynamic.mapper.processor.ProcessingException(
                    "Failed to publish message", e));
        }
    }

    /**
     * Get or create producer with retry logic
     */
    private Producer<byte[]> getOrCreateProducer(String topic, Qos qos) throws PulsarClientException {
        Producer<byte[]> producer = producers.get(topic);

        if (producer == null || !producer.isConnected()) {
            if (producer != null) {
                producers.remove(topic);
                try {
                    producer.close();
                } catch (PulsarClientException e) {
                    log.debug("{} - Error closing disconnected producer: {}", tenant, e.getMessage());
                }
            }

            producer = createProducerWithRetry(topic, qos);
            if (producer != null) {
                producers.put(topic, producer);
            }
        }

        return producer;
    }

    /**
     * Create producer with retry logic
     */
    private Producer<byte[]> createProducerWithRetry(String topic, Qos qos) throws PulsarClientException {
        for (int attempt = 1; attempt <= MAX_PRODUCER_CREATE_RETRIES; attempt++) {
            try {
                String pulsarTopic = ensurePulsarTopicFormat(topic);

                ProducerBuilder<byte[]> builder = pulsarClient.newProducer()
                        .topic(pulsarTopic);

                // Configure based on QoS
                switch (qos) {
                    case AT_MOST_ONCE:
                        builder.sendTimeout(0, TimeUnit.SECONDS);
                        break;
                    case AT_LEAST_ONCE:
                    case EXACTLY_ONCE:
                        builder.sendTimeout(30, TimeUnit.SECONDS);
                        break;
                }

                try {
                    return builder.create();
                } catch (PulsarClientException.FeatureNotSupportedException e) {
                    if (e.getMessage().contains("PIP-344")) {
                        log.warn("{} - Broker doesn't support PIP-344, using async fallback", tenant);
                        return builder.createAsync().get(30, TimeUnit.SECONDS);
                    }
                    throw e;
                }

            } catch (Exception e) {
                if (attempt == MAX_PRODUCER_CREATE_RETRIES) {
                    if (e instanceof PulsarClientException) {
                        throw (PulsarClientException) e;
                    }
                    throw new PulsarClientException(e);
                }

                try {
                    Thread.sleep(PRODUCER_CREATE_RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PulsarClientException(ie);
                }
            }
        }

        throw new PulsarClientException("Failed to create producer after " + MAX_PRODUCER_CREATE_RETRIES + " attempts");
    }

    /**
     * Send message with QoS handling
     */
    private void sendMessageWithQos(Producer<byte[]> producer, String payload, Qos qos, ProcessingContext<?> context)
            throws PulsarClientException {

        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        if (qos == Qos.AT_MOST_ONCE) {
            // Fire and forget
            producer.sendAsync(payloadBytes).exceptionally(throwable -> {
                log.debug("{} - AT_MOST_ONCE send failed (expected): {}",
                        tenant, throwable.getMessage());
                return null;
            });
        } else {
            // Wait for acknowledgment
            producer.send(payloadBytes);
        }

        if (context.getMapping().getDebug() || serviceConfiguration.isLogPayload()) {
            log.info("{} - Published message with QoS {}: topic: [{}], mapping: {}",
                    tenant, qos, context.getResolvedPublishTopic(), context.getMapping().getName());
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

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
            if (entry.getValue().getRequired() && configuration.getProperties().get(entry.getKey()) == null) {
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
    public void monitorSubscriptions() {
        // Monitor consumers and remove disconnected ones
        consumers.entrySet().removeIf(entry -> {
            if (!entry.getValue().isConnected()) {
                log.warn("{} - Consumer for topic {} disconnected, removing", tenant, entry.getKey());
                try {
                    entry.getValue().close();
                } catch (PulsarClientException e) {
                    log.error("{} - Error closing disconnected consumer: {}", tenant, e.getMessage());
                }
                return true;
            }
            return false;
        });
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Monitor producers and remove disconnected ones
        producers.entrySet().removeIf(entry -> {
            if (!entry.getValue().isConnected()) {
                log.warn("{} - Producer for topic {} disconnected, will recreate on next publish",
                        tenant, entry.getKey());
                try {
                    entry.getValue().close();
                } catch (PulsarClientException e) {
                    log.debug("{} - Error closing disconnected producer: {}", tenant, e.getMessage());
                }
                return true;
            }
            return false;
        });
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

    // ===== Helper Methods =====

    /**
     * Build subscription name
     */
    private String buildSubscriptionName(String topic) {
        String baseName = (String) connectorConfiguration.getProperties()
                .getOrDefault("subscriptionName", "dynamic-mapper-subscription");
        String sanitizedTopic = sanitizeTopicForSubscriptionName(topic);
        String suffix = additionalSubscriptionIdTest != null ? additionalSubscriptionIdTest : "";
        return baseName + "-" + sanitizedTopic + suffix;
    }

    /**
     * Sanitize topic for subscription name
     */
    private String sanitizeTopicForSubscriptionName(String topic) {
        return topic.replace("/", "-")
                .replace("+", "wildcard")
                .replace("#", "multilevel");
    }

    /**
     * Determine subscription type from QoS
     */
    private SubscriptionType determineSubscriptionType(Qos qos) {
        String configured = (String) connectorConfiguration.getProperties().get("subscriptionType");
        if (configured != null && !configured.isEmpty()) {
            return SubscriptionType.valueOf(configured);
        }

        switch (qos) {
            case EXACTLY_ONCE:
                return SubscriptionType.Exclusive;
            case AT_LEAST_ONCE:
            case AT_MOST_ONCE:
            default:
                return SubscriptionType.Shared;
        }
    }

    /**
     * Check if acknowledgment is required
     */
    private boolean requiresAcknowledgment(Qos qos) {
        return qos != Qos.AT_MOST_ONCE;
    }

    /**
     * Check if topic contains MQTT wildcards
     */
    private boolean containsMqttWildcards(String topic) {
        return topic != null && (topic.contains("+") || topic.contains("#"));
    }

    /**
     * Translate MQTT wildcard topic to Pulsar regex
     */
    private String translateMqttTopicToPulsarRegex(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.isEmpty()) {
            return mqttTopic;
        }

        validateMqttWildcards(mqttTopic);
        String escaped = escapeRegexCharacters(mqttTopic);
        String pulsarRegex = escaped
                .replace("+", "[^/]+")
                .replace("#", ".*");

        return ensurePulsarTopicFormat(pulsarRegex);
    }

    /**
     * Validate MQTT wildcards
     */
    private void validateMqttWildcards(String mqttTopic) {
        int hashIndex = mqttTopic.indexOf('#');
        if (hashIndex != -1) {
            if (hashIndex != mqttTopic.length() - 1) {
                throw new IllegalArgumentException("MQTT wildcard '#' must be last character");
            }
            if (hashIndex > 0 && mqttTopic.charAt(hashIndex - 1) != '/') {
                throw new IllegalArgumentException("MQTT wildcard '#' must be preceded by '/'");
            }
        }

        for (int i = 0; i < mqttTopic.length(); i++) {
            if (mqttTopic.charAt(i) == '+') {
                boolean validPosition = true;
                if (i > 0 && mqttTopic.charAt(i - 1) != '/') {
                    validPosition = false;
                }
                if (i < mqttTopic.length() - 1 && mqttTopic.charAt(i + 1) != '/') {
                    validPosition = false;
                }
                if (!validPosition) {
                    throw new IllegalArgumentException("MQTT wildcard '+' must be between '/' separators");
                }
            }
        }
    }

    /**
     * Escape regex characters
     */
    private String escapeRegexCharacters(String input) {
        String[] regexSpecialChars = { "\\", "^", "$", ".", "|", "?", "*", "(", ")", "[", "]", "{" };
        String result = input;
        for (String specialChar : regexSpecialChars) {
            result = result.replace(specialChar, "\\" + specialChar);
        }
        return result;
    }

    /**
     * Ensure Pulsar topic format
     */
    private String ensurePulsarTopicFormat(String topicPattern) {
        if (topicPattern.startsWith("persistent://") || topicPattern.startsWith("non-persistent://")) {
            return topicPattern;
        }

        String pulsarTopicName = convertMqttTopicToPulsarTopicName(topicPattern);
        String pulsarTenant = (String) connectorConfiguration.getProperties().getOrDefault("pulsarTenant", "public");
        String namespace = (String) connectorConfiguration.getProperties().getOrDefault("pulsarNamespace", "default");

        return String.format("persistent://%s/%s/%s", pulsarTenant, namespace, pulsarTopicName);
    }

    /**
     * Convert MQTT topic to Pulsar topic name
     */
    private String convertMqttTopicToPulsarTopicName(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.isEmpty()) {
            return "default-topic";
        }

        String pulsarTopic = mqttTopic
                .replace("/", "-")
                .replace(" ", "_")
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("^[._-]+|[._-]+$", "");

        if (pulsarTopic.isEmpty()) {
            pulsarTopic = "default-topic";
        }

        log.debug("{} - Converted MQTT topic '{}' to Pulsar topic '{}'", tenant, mqttTopic, pulsarTopic);

        return pulsarTopic;
    }

    /**
     * Create Pulsar connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("enableTls", new String[] { "true" });
        ConnectorPropertyCondition certCondition = new ConnectorPropertyCondition(
                "useSelfSignedCertificate", new String[] { "true" });
        ConnectorPropertyCondition authCondition = new ConnectorPropertyCondition(
                "authenticationMethod", new String[] { "token", "oauth2", "tls", "basic" });

        configProps.put("serviceUrl",
                new ConnectorProperty(
                        "This can be in the format: pulsar://localhost:6650 for non-TLS or pulsar+ssl://localhost:6651 for TLS",
                        true, 0, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "pulsar://localhost:6650", null, null));

        configProps.put("enableTls",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        false, false, false, null, null));

        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        false, false, false, null, tlsCondition));

        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, certCondition));

        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, certCondition));

        configProps.put("authenticationMethod",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.OPTION_PROPERTY,
                        false, false, "none",
                        Map.of("none", "None", "token", "Token", "oauth2", "OAuth2",
                                "tls", "TLS", "basic", "Basic"),
                        null));

        configProps.put("authenticationParams",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                        false, false, null, null, authCondition));

        configProps.put("connectionTimeoutSeconds",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.NUMERIC_PROPERTY,
                        false, false, DEFAULT_CONNECTION_TIMEOUT, null, null));

        configProps.put("operationTimeoutSeconds",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.NUMERIC_PROPERTY,
                        false, false, DEFAULT_OPERATION_TIMEOUT, null, null));

        configProps.put("keepAliveIntervalSeconds",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.NUMERIC_PROPERTY,
                        false, false, DEFAULT_KEEP_ALIVE, null, null));

        configProps.put("subscriptionType",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.OPTION_PROPERTY,
                        false, false, "Shared",
                        Map.of("Exclusive", "Exclusive", "Shared", "Shared",
                                "Failover", "Failover", "Key_Shared", "Key Shared"),
                        null));

        configProps.put("subscriptionName",
                new ConnectorProperty(
                        "Controls how Pulsar subscription names are generated",
                        false, 11, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("supportsWildcardInTopicInbound",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, true, null, null));

        configProps.put("supportsWildcardInTopicOutbound",
                new ConnectorProperty(null, false, 13, ConnectorPropertyType.BOOLEAN_PROPERTY,
                        true, false, false, null, null));

        configProps.put("pulsarTenant",
                new ConnectorProperty(null, false, 14, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, "public", null, null));

        configProps.put("pulsarNamespace",
                new ConnectorProperty(null, false, 15, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, "default", null, null));

        String name = "Apache Pulsar";
        String description = "Connector for connecting to Apache Pulsar message broker. " +
                "Supports QoS levels, wildcards, and MQTT-style topic format conversion.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.PULSAR,
                false,
                configProps,
                false,
                supportedDirections());
    }

    /**
     * QoS-aware message listener wrapper
     */
    protected static class QoSAwarePulsarCallback implements MessageListener<byte[]> {
        private final PulsarCallback delegate;
        private final Qos qos;

        public QoSAwarePulsarCallback(PulsarCallback delegate, Qos qos) {
            this.delegate = delegate;
            this.qos = qos;
        }

        @Override
        public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
            if (qos == Qos.AT_MOST_ONCE) {
                try {
                    consumer.acknowledge(message);
                } catch (PulsarClientException e) {
                    log.warn("Failed to acknowledge AT_MOST_ONCE message: {}", e.getMessage());
                }
            }

            delegate.received(consumer, message);
        }
    }
}
