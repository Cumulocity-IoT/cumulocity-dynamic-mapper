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

import dynamic.mapper.connector.core.client.ConnectionTestResult;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyBuilder;
import dynamic.mapper.connector.core.ConnectorPropertyGroup;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.ConnectorSpecificationBuilder;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.InboundMessageDispatcher;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.connector.mqtt.SparkplugCertificateManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    // Consumer and producer management
    protected final Map<String, Consumer<byte[]>> consumers = new ConcurrentHashMap<>();
    protected final Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();

    // Sparkplug Host support
    protected SparkplugCertificateManager sparkplugCertificateManager;
    protected boolean isSparkplugHost = false;
    protected String sparkplugHostId;

    /**
     * Default constructor
     */
    public PulsarConnectorClient() {
        this.connectorType = ConnectorType.PULSAR;
        this.singleton = false;
        this.supportedQos = Arrays.asList(Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE, Qos.EXACTLY_ONCE);
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public PulsarConnectorClient(ServiceRegistry serviceRegistry,
            ConnectorRegistry connectorRegistry,
            ConnectorConfiguration connectorConfiguration,
            InboundMessageDispatcher dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        this();
        wireFromRegistry(serviceRegistry, connectorRegistry, connectorConfiguration,
                dispatcher, additionalSubscriptionIdTest, tenant);
        initializeManagers();
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            // Initialize SSL if needed (checks protocol and certificate configuration)
            initializeSslIfNeeded();

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

    @Override
    public void connect() {
        if (!beginConnection()) {
            return;
        }

        try {
            log.info("{} - Connecting Pulsar connector: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            // Build Pulsar client. The client connects lazily, so verify the broker is reachable and accepts the
            // credentials; otherwise the connector would report CONNECTED while every subscription silently fails.
            String unresolvedHost = findUnresolvableHost((String) connectorConfiguration.getProperties().get("serviceUrl"));
            if (unresolvedHost != null) {
                throw new PulsarClientException(unresolvableHostResult(unresolvedHost).message());
            }
            PulsarClient client = buildPulsarClient();
            ConnectionTestResult probe = probeBroker(client);
            if (!probe.success()) {
                try {
                    client.close();
                } catch (PulsarClientException closeError) {
                    log.debug("{} - Error closing Pulsar client after failed probe: {}", tenant, closeError.getMessage());
                }
                throw new PulsarClientException(probe.message());
            }
            pulsarClient = client;

            // Create callback
            pulsarCallback = new PulsarCallback(
                    tenant,
                    serviceRegistry,
                    dispatcher,
                    connectorIdentifier,
                    connectorName);

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

            log.info("{} - Pulsar connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting Pulsar connector: {}", tenant, e.getMessage(), e);
            // setConnected() before updateStatusWithError(): the reverse order let a
            // blank DISCONNECTED event (fired by setConnected's own transition detection)
            // immediately supersede the FAILED event carrying the actual error message.
            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatusWithError(e);
        } finally {
            endConnection();
        }
    }

    @Override
    public boolean supportsConnectionTest() {
        return true;
    }

    /**
     * Builds a throwaway client and does a topic lookup, which forces the connection and
     * authentication without subscribing or producing (no effect on the live consumer's subscription).
     * An authorization error on the probe topic still proves the credentials were accepted.
     */
    @Override
    public ConnectionTestResult testConnection() {
        try {
            // The probe is never initialize()d (that would change connector status), so the trust certificate that
            // buildPulsarClient() installs for a self-signed broker has to be loaded here.
            if (Boolean.TRUE.equals(connectorConfiguration.getProperties().get("trustCustomCertificate"))
                    && cert == null) {
                cert = loadCertificateFromConfiguration();
            }
        } catch (Exception e) {
            return ConnectionTestResult.failed("The configured certificate could not be loaded: " + e.getMessage(), null);
        }
        String unresolvedHost = findUnresolvableHost((String) connectorConfiguration.getProperties().get("serviceUrl"));
        if (unresolvedHost != null) {
            return unresolvableHostResult(unresolvedHost);
        }
        try (PulsarClient probe = buildPulsarClient()) {
            return probeBroker(probe);
        } catch (Exception e) {
            return ConnectionTestResult.failed("Connection failed: " + e.getMessage(), null);
        }
    }

    private ConnectionTestResult unresolvableHostResult(String host) {
        return ConnectionTestResult.failed("Host '" + host
                + "' cannot be resolved from the service. It may be a name that only exists in a private network.", null);
    }

    /**
     * Forces the (otherwise lazy) connection and authentication with a topic lookup on the given client.
     */
    private ConnectionTestResult probeBroker(PulsarClient client) {
        try {
            client.getPartitionsForTopic("persistent://public/default/dm-connection-test").get(15, TimeUnit.SECONDS);
            return ConnectionTestResult.ok("Connected to Pulsar", null);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof PulsarClientException.AuthenticationException) {
                return ConnectionTestResult.failed("Authentication failed: " + cause.getMessage(), null);
            }
            if (cause instanceof PulsarClientException.AuthorizationException) {
                return ConnectionTestResult.ok(
                        "Connected and authenticated (not authorized for the probe topic, which is expected)", null);
            }
            return ConnectionTestResult.failed(explainFailure("Connection failed: " + cause.getMessage()), null);
        } catch (java.util.concurrent.TimeoutException e) {
            return ConnectionTestResult.failed(explainFailure("Connection timed out"), null);
        } catch (Exception e) {
            return ConnectionTestResult.failed("Connection failed: " + e.getMessage(), null);
        }
    }

    /**
     * The Pulsar client swallows connection-level errors (TLS, refused, unreachable) and retries until the operation
     * times out, so the probe only sees a generic timeout while the actual cause is just in the log. Re-diagnose with
     * a plain socket/TLS handshake against the first broker and return its message, or the generic one if the
     * socket-level check finds nothing wrong.
     */
    private String explainFailure(String genericMessage) {
        try {
            String serviceUrl = (String) connectorConfiguration.getProperties().get("serviceUrl");
            if (StringUtils.isEmpty(serviceUrl) || !serviceUrl.contains("://")) {
                return genericMessage;
            }
            boolean tls = isSslRequired();
            String first = serviceUrl.substring(serviceUrl.indexOf("://") + 3).split(",")[0].trim().split("/")[0];
            String host = first;
            int port = tls ? 6651 : 6650;
            int colon = first.lastIndexOf(':');
            if (colon > 0) {
                host = first.substring(0, colon);
                port = Integer.parseInt(first.substring(colon + 1));
            }
            java.net.Socket socket = new java.net.Socket();
            try {
                socket.connect(new java.net.InetSocketAddress(host, port), 5000);
                if (tls) {
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) buildTrustContext().getSocketFactory()
                            .createSocket(socket, host, port, true);
                    ssl.setSoTimeout(5000);
                    ssl.startHandshake();
                }
            } finally {
                socket.close();
            }
            return genericMessage;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            boolean custom = Boolean.TRUE.equals(connectorConfiguration.getProperties().get("trustCustomCertificate"));
            if (isCertificateTrustFailure(e)) {
                return custom
                        ? "The broker's TLS certificate is not signed by the configured custom certificate. Check that "
                        + "the certificate (chain) matches the broker's certificate."
                        : "The broker's TLS certificate is not trusted by the service. Enable 'Trust custom server "
                        + "certificate' and provide the broker's (CA) certificate.";
            }
            return "TLS handshake with the broker failed: " + e.getMessage();
        } catch (java.net.SocketTimeoutException e) {
            return "The broker did not answer within 5 seconds. Check host, port and firewall rules.";
        } catch (java.net.ConnectException e) {
            return "The broker refused the connection: " + e.getMessage() + ". Check the port and that the broker is running.";
        } catch (Exception e) {
            return genericMessage;
        }
    }

    private static boolean isCertificateTrustFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.security.cert.CertPathBuilderException
                    || t instanceof java.security.cert.CertificateException) {
                return true;
            }
        }
        return false;
    }

    /** SSL context trusting the configured custom certificate(s), or the JVM defaults when none is configured. */
    private javax.net.ssl.SSLContext buildTrustContext() throws Exception {
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        if (Boolean.TRUE.equals(connectorConfiguration.getProperties().get("trustCustomCertificate")) && cert != null) {
            java.security.KeyStore store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (java.security.cert.Certificate c : java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(new java.io.ByteArrayInputStream(
                            cert.getCertInPemFormat().getBytes(StandardCharsets.UTF_8)))) {
                store.setCertificateEntry("custom-" + i++, c);
            }
            tmf.init(store);
        } else {
            tmf.init((java.security.KeyStore) null);
        }
        javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * Returns the first host of the service URL (a comma separated list is allowed) that DNS cannot resolve, or
     * null if all resolve. The Pulsar client retries an unresolvable host until the operation times out, which
     * would otherwise surface only as a generic timeout.
     */
    private String findUnresolvableHost(String serviceUrl) {
        if (StringUtils.isEmpty(serviceUrl)) {
            return null;
        }
        String hosts = serviceUrl.substring(serviceUrl.indexOf("://") + 3);
        for (String entry : hosts.split(",")) {
            String host = entry.trim().split("[:/]")[0];
            if (host.isEmpty()) {
                continue;
            }
            try {
                java.net.InetAddress.getByName(host);
            } catch (java.net.UnknownHostException e) {
                return host;
            }
        }
        return null;
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
     * TLS is used when enabled explicitly or when the service URL already uses a TLS scheme. The base class
     * only inspects a "url" property, which Pulsar does not have (it uses "serviceUrl").
     */
    @Override
    protected boolean isSslRequired() {
        Map<String, Object> properties = connectorConfiguration.getProperties();
        if (Boolean.TRUE.equals(properties.get("enableTls"))) {
            return true;
        }
        Object serviceUrl = properties.get("serviceUrl");
        return serviceUrl instanceof String && isUsingTls((String) serviceUrl);
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
    private Boolean isUsingTls(String serviceUrl) {
        return serviceUrl.startsWith("pulsar+ssl://") || serviceUrl.startsWith("https://");
    }

    /**
     * Configure TLS settings
     */
    private void configureTls(ClientBuilder clientBuilder) throws PulsarClientException {
        Boolean trustCustomCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("trustCustomCertificate", false);

        if (trustCustomCertificate && cert != null) {
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
        if (ClientCredentialsAuthentication.AUTH_NAME.equals(authMethod)) {
            String clientId = (String) connectorConfiguration.getProperties().get("clientId");
            String clientSecret = (String) connectorConfiguration.getProperties().get("clientSecret");
            if (StringUtils.isEmpty(clientId) || StringUtils.isEmpty(clientSecret)) {
                throw new PulsarClientException("Client ID and client secret are required for client_creds authentication");
            }
            clientBuilder.authentication(new ClientCredentialsAuthentication(clientId, clientSecret));
            log.debug("{} - Using client credentials authentication", tenant);
            return;
        }
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
                        .messageListener(new QoSAwarePulsarCallback(pulsarCallback, qos, topic))
                        .acknowledgmentGroupTime(qos.requiresAcknowledgement() ? 100 : 0, TimeUnit.MILLISECONDS)
                        .subscribe();
                log.info("{} - Subscribed to pattern: [{}] (MQTT: [{}])", tenant, pulsarPattern, topic);
            } else {
                // Handle regular subscription
                String pulsarTopic = ensurePulsarTopicFormat(topic);
                consumer = pulsarClient.newConsumer()
                        .topic(pulsarTopic)
                        .subscriptionName(subscriptionName)
                        .subscriptionType(subscriptionType)
                        .messageListener(new QoSAwarePulsarCallback(pulsarCallback, qos, topic))
                        .acknowledgmentGroupTime(qos.requiresAcknowledgement() ? 100 : 0, TimeUnit.MILLISECONDS)
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
        if (!beginDisconnection()) {
            return;
        }

        try {
            log.info("{} - Disconnecting Pulsar connector", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

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
        } finally {
            endDisconnection();
        }
    }

    @Override
    protected boolean isPhysicallyConnected() {
        return pulsarClient != null && !pulsarClient.isClosed();
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (pulsarClient == null || pulsarClient.isClosed()) {
            log.warn("{} - Pulsar client is closed, attempting reconnect", tenant);
            reconnect();
            return;
        }

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

            if (topic == null || topic.isEmpty()) {
                log.warn("{} - No topic specified for request ({}/{}), skipping", tenant, i + 1, requests.size());
                request.setError(new Exception("No publish topic specified"));
                continue;
            }

            try {
                Producer<byte[]> producer = getOrCreateProducer(topic, qos);

                if (producer != null && producer.isConnected()) {
                    sendMessageWithQos(producer, payload, qos, context);
                    log.debug("{} - Published message ({}/{}): topic={}", tenant, i + 1, requests.size(), topic);
                } else {
                    log.error("{} - No connected producer for topic: {} ({}/{})", tenant, topic, i + 1, requests.size());
                    request.setError(new Exception("No connected producer for topic: " + topic));
                }

            } catch (Exception e) {
                log.error("{} - Error publishing to topic: {} ({}/{})", tenant, topic, i + 1, requests.size(), e);
                request.setError(e);
                context.addError(new dynamic.mapper.processor.ProcessingException(
                        "Failed to publish message " + (i + 1) + "/" + requests.size(), e));
            }
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
        try {
            return retryOperation(
                "Create Pulsar producer for topic: " + topic,
                MAX_PRODUCER_CREATE_RETRIES,
                PRODUCER_CREATE_RETRY_DELAY_MS,
                () -> {
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
                }
            );
        } catch (ConnectorException e) {
            // Unwrap if it's a PulsarClientException inside ConnectorException
            if (e.getCause() instanceof PulsarClientException) {
                throw (PulsarClientException) e.getCause();
            }
            throw new PulsarClientException(e);
        }
    }

    /**
     * Send message with QoS handling
     */
    private void sendMessageWithQos(Producer<byte[]> producer, String payload, Qos qos, ProcessingContext<?> context)
            throws PulsarClientException {

        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // The message key comes from the mapping ('key' transport field / _CONTEXT_DATA_.key)
        String key = context.getKey();
        boolean async = qos == Qos.AT_MOST_ONCE;
        CompletableFuture<?> result = null;
        if (key != null) {
            TypedMessageBuilder<byte[]> message = producer.newMessage().key(key).value(payloadBytes);
            if (async) {
                result = message.sendAsync();
            } else {
                message.send();
            }
        } else if (async) {
            result = producer.sendAsync(payloadBytes);
        } else {
            // Wait for acknowledgment
            producer.send(payloadBytes);
        }
        if (result != null) {
            // Fire and forget
            result.exceptionally(throwable -> {
                log.debug("{} - AT_MOST_ONCE send failed (expected): {}",
                        tenant, throwable.getMessage());
                return null;
            });
        }

        if (context.getMapping().getDebug() || serviceConfiguration.getLogPayload()) {
            log.info("{} - Published message with QoS {}: topic: [{}], mapping: {}",
                    tenant, qos, context.getResolvedPublishTopic(), context.getMapping().getName());
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        Boolean trustCustomCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("trustCustomCertificate", false);

        if (trustCustomCertificate) {
            // Either an inline PEM chain or a reference (name + fingerprint) to the trusted-certificate store
            Object pem = configuration.getProperties().get("certificateChainInPemFormat");
            boolean hasInlinePem = pem instanceof String && !((String) pem).isBlank();
            boolean hasStoreReference = configuration.getProperties().get("trustedCertificateFingerprint") != null
                    && configuration.getProperties().get("nameCertificate") != null;
            if (!hasInlinePem && !hasStoreReference) {
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
        return readWildcardFlag(direction, true, false);
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
                    log.error("{} - Error closing disconnected consumer: {}", tenant, e.getMessage(), e);
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
     * Build subscription name. By default the configured name is a prefix and the sanitized topic is appended, so
     * every topic gets its own subscription. With "useSubscriptionNameAsIs" the configured name is used unchanged,
     * for brokers that only authorize a pre-provisioned subscription name. A subscription is scoped per topic in
     * Pulsar, so the same name can be used for several topics.
     */
    String buildSubscriptionName(String topic) {
        Map<String, Object> properties = connectorConfiguration.getProperties();
        String baseName = (String) properties.getOrDefault("subscriptionName", "dynamic-mapper-subscription");
        if (Boolean.TRUE.equals(properties.get("useSubscriptionNameAsIs")) && StringUtils.isNotBlank(baseName)) {
            return baseName + (additionalSubscriptionIdTest != null ? additionalSubscriptionIdTest : "");
        }
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
     * Check if topic contains MQTT wildcards
     */
    private Boolean containsMqttWildcards(String topic) {
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
        if (startsWithIgnoreCase(mqttTopic, "persistent://") || startsWithIgnoreCase(mqttTopic, "non-persistent://")) {
            // Already a full Pulsar name: only the wildcards need translating
            return escapeRegexCharacters(mqttTopic).replace("+", "[^/]+").replace("#", ".*");
        }
        String[] location = resolveTopicLocation();
        return buildPulsarTopicRegex(mqttTopic, location[1], location[2], location[0]);
    }

    /**
     * Resolves {domain, tenant, namespace} used to turn a logical topic into a full Pulsar name. The namespace
     * property may hold the combined form {@code tenant/namespace}, which then takes precedence over
     * {@code pulsarTenant}. The domain is "persistent" unless {@code pulsarTopicDomain} is "non-persistent".
     */
    String[] resolveTopicLocation() {
        Map<String, Object> properties = connectorConfiguration.getProperties();
        String tenantName = (String) properties.getOrDefault("pulsarTenant", "public");
        String namespace = (String) properties.getOrDefault("pulsarNamespace", "default");
        if (namespace != null && namespace.contains("/")) {
            String combined = namespace.trim();
            int slash = combined.indexOf('/');
            tenantName = combined.substring(0, slash).trim();
            namespace = combined.substring(slash + 1).trim();
        }
        Object configuredDomain = properties.get("pulsarTopicDomain");
        String domain = "non-persistent".equalsIgnoreCase(String.valueOf(configuredDomain)) ? "non-persistent"
                : "persistent";
        return new String[] { domain, tenantName, namespace };
    }

    /**
     * Builds the regex matching the Pulsar topics an MQTT-style filter stands for. Literal levels
     * are flattened exactly like {@link #convertMqttTopicToPulsarTopicName} does ({@code /} becomes
     * {@code -}); the wildcards are translated afterwards, so they are not mangled by that
     * flattening. {@code +} matches exactly one level (no {@code -} or {@code /}), {@code #} the remaining levels.
     */
    static String buildPulsarTopicRegex(String mqttTopic, String pulsarTenant, String namespace) {
        return buildPulsarTopicRegex(mqttTopic, pulsarTenant, namespace, "persistent");
    }

    static String buildPulsarTopicRegex(String mqttTopic, String pulsarTenant, String namespace, String domain) {
        String[] levels = mqttTopic.split("/", -1);
        StringBuilder local = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if ("#".equals(level)) {
                local.append(i == 0 ? ".*" : "(-.*)?");
                continue;
            }
            if (i > 0) {
                local.append('-');
            }
            if ("+".equals(level)) {
                local.append("[^/-]+");
            } else {
                local.append(level.replace(" ", "_").replaceAll("[^a-zA-Z0-9._-]", "_").replace(".", "\\."));
            }
        }
        return domain + "://" + pulsarTenant.replace(".", "\\.") + "/" + namespace.replace(".", "\\.")
                + "/" + local;
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
     * Case-insensitive variant of String#startsWith, since Pulsar topic schemes
     * (persistent://, non-persistent://) may be configured with any letter casing.
     */
    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Ensure Pulsar topic format
     */
    private String ensurePulsarTopicFormat(String topicPattern) {
        if (startsWithIgnoreCase(topicPattern, "persistent://") || startsWithIgnoreCase(topicPattern, "non-persistent://")) {
            return topicPattern;
        }

        String pulsarTopicName = convertMqttTopicToPulsarTopicName(topicPattern);
        String[] location = resolveTopicLocation();

        return String.format("%s://%s/%s/%s", location[0], location[1], location[2], pulsarTopicName);
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
        return ConnectorSpecificationBuilder
                .create("Apache Pulsar", ConnectorType.PULSAR)
                .description("Connector for connecting to Apache Pulsar message broker. " +
                        "Supports QoS levels, wildcards, and MQTT-style topic format conversion.\n" +
                        "Inbound Smart Functions and Java extensions read the message key from the message's transport fields ('key'), " +
                        "JSONata mappings from '_CONTEXT_DATA_.key'. In outbound mappings the key is set from the transport field 'key' " +
                        "or the string mapped to '_CONTEXT_DATA_.key'.")
                .supportsMessageContext(true)
                .supportedDirections(supportedDirections())

                // Connection settings
                .property("serviceUrl", ConnectorPropertyBuilder.requiredString()
                        .group(ConnectorPropertyGroup.CONNECTION)
                        .label("Service URL")
                        .order(0)
                        .description("This can be in the format: pulsar://localhost:6650 for non-TLS or pulsar+ssl://localhost:6651 for TLS")
                        .defaultValue("pulsar://localhost:6650"))

                // TLS configuration
                .property("enableTls", ConnectorPropertyBuilder.optionalBoolean()
                        .group(ConnectorPropertyGroup.TLS)
                        .label("Enable TLS")
                        .description("Use TLS for the connection. The service URL must then start with pulsar+ssl://.")
                        .order(1)
                        .defaultValue(false))

                .property("trustCustomCertificate", ConnectorPropertyBuilder.optionalBoolean()
                        .group(ConnectorPropertyGroup.TLS)
                        .label("Trust custom server certificate")
                        .description("Trust a broker certificate that is self-signed or issued by a private CA. The certificate must be uploaded to Device Management > Trusted certificates.")
                        .order(2)
                        .defaultValue(false)
                        .condition("enableTls", "true"))

                .property("trustedCertificateFingerprint", ConnectorPropertyBuilder.optionalString()
                        .group(ConnectorPropertyGroup.TLS)
                        .label("Trusted certificate fingerprint")
                        .description("SHA-1 fingerprint of the certificate in the Cumulocity trusted-certificate store.")
                        .order(3)
                        .condition("trustCustomCertificate", "true"))

                .property("nameCertificate", ConnectorPropertyBuilder.optionalString()
                        .group(ConnectorPropertyGroup.TLS)
                        .label("Trusted certificate name")
                        .description("Name of the certificate in the Cumulocity trusted-certificate store.")
                        .order(4)
                        .condition("trustCustomCertificate", "true"))

                .property("certificateChainInPemFormat", ConnectorPropertyBuilder.largeText()
                        .group(ConnectorPropertyGroup.TLS)
                        .label("Certificate chain (PEM)")
                        .order(5)
                        .description("Certificate chain in PEM format. Alternatively, identify the certificate by name and fingerprint; it must be uploaded to Device Management > Trusted certificates.")
                        .condition("trustCustomCertificate", "true"))

                // Authentication
                .property("authenticationMethod", ConnectorPropertyBuilder.optionalOption()
                        .group(ConnectorPropertyGroup.AUTHENTICATION)
                        .label("Authentication method")
                        .description("Authentication method of the broker. Select None for a broker without authentication.")
                        .order(6)
                        .defaultValue("none")
                        .optionsWithLabels("none", "None", "token", "Token", "oauth2", "OAuth2",
                                "tls", "TLS", "basic", "Basic",
                                "client_creds", "Client credentials (custom)"))

                .property("authenticationParams", ConnectorPropertyBuilder.optionalSensitive()
                        .group(ConnectorPropertyGroup.AUTHENTICATION)
                        .label("Authentication parameters")
                        .order(7)
                        .description("Parameters for the selected method. Token: the token string. "
                                + "Basic: JSON with user and password, e.g. {\"userId\":\"myUser\",\"password\":\"myPassword\"}. "
                                + "TLS and OAuth2: the JSON parameters of the corresponding Pulsar authentication plugin.")
                        .condition("authenticationMethod", "token", "oauth2", "tls", "basic"))

                .property("clientId", ConnectorPropertyBuilder.optionalString()
                        .group(ConnectorPropertyGroup.AUTHENTICATION)
                        .order(8)
                        .label("Client ID")
                        .description("Custom authentication method for brokers that register a 'client_creds' provider (not standard Pulsar). Client ID sent to the broker as base64(clientId:clientSecret).")
                        .condition("authenticationMethod", "client_creds"))

                .property("clientSecret", ConnectorPropertyBuilder.optionalSensitive()
                        .group(ConnectorPropertyGroup.AUTHENTICATION)
                        .order(9)
                        .label("Client secret")
                        .description("Client secret for the 'client_creds' authentication method.")
                        .condition("authenticationMethod", "client_creds"))

                // Timeouts
                .property("connectionTimeoutSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .group(ConnectorPropertyGroup.TIMEOUTS)
                        .label("Connection timeout (s)")
                        .description("Maximum time in seconds to wait for the connection to the broker.")
                        .order(10)
                        .defaultValue(DEFAULT_CONNECTION_TIMEOUT))

                .property("operationTimeoutSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .group(ConnectorPropertyGroup.TIMEOUTS)
                        .label("Operation timeout (s)")
                        .description("Maximum time in seconds to wait for a broker operation such as subscribe or publish.")
                        .order(11)
                        .defaultValue(DEFAULT_OPERATION_TIMEOUT))

                .property("keepAliveIntervalSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .group(ConnectorPropertyGroup.TIMEOUTS)
                        .label("Keep-alive interval (s)")
                        .description("Interval in seconds between keep-alive pings to the broker.")
                        .order(12)
                        .defaultValue(DEFAULT_KEEP_ALIVE))

                // Subscription settings
                .property("subscriptionType", ConnectorPropertyBuilder.optionalOption()
                        .group(ConnectorPropertyGroup.SUBSCRIPTION)
                        .label("Subscription type")
                        .description("Pulsar subscription type. Shared distributes messages over all consumers, Exclusive allows one consumer, Failover keeps a standby consumer, and Key Shared routes messages with the same key to the same consumer.")
                        .order(13)
                        .defaultValue("Shared")
                        .optionsWithLabels("Exclusive", "Exclusive", "Shared", "Shared",
                                "Failover", "Failover", "Key_Shared", "Key Shared"))

                .property("subscriptionName", ConnectorPropertyBuilder.optionalString()
                        .group(ConnectorPropertyGroup.SUBSCRIPTION)
                        .label("Subscription name")
                        .order(14)
                        .description("Prefix of the generated Pulsar subscription names, or the exact subscription name if 'Use subscription name as is' is enabled"))

                .property("useSubscriptionNameAsIs", ConnectorPropertyBuilder.optionalBoolean()
                        .group(ConnectorPropertyGroup.SUBSCRIPTION)
                        .order(15)
                        .label("Use subscription name as is")
                        .description("Use the subscription name unchanged instead of appending the topic. Enable this if the broker only allows a pre-provisioned subscription name.")
                        .defaultValue(false))

                // Wildcard support (read-only)
                .property("supportsWildcardInTopicInbound", ConnectorPropertyBuilder.optionalBoolean()
                        .group(ConnectorPropertyGroup.ADVANCED)
                        .label("Wildcards in inbound topics")
                        .description("Allow wildcards (+ and #) in the topic of inbound mappings.")
                        .order(16)
                        .readonly(true)
                        .defaultValue(true))

                .property("supportsWildcardInTopicOutbound", ConnectorPropertyBuilder.optionalBoolean()
                        .group(ConnectorPropertyGroup.ADVANCED)
                        .label("Wildcards in outbound topics")
                        .description("Allow wildcards (+ and #) in the publish topic of outbound mappings.")
                        .order(17)
                        .readonly(true)
                        .defaultValue(false))

                // Pulsar-specific settings
                .property("pulsarTenant", ConnectorPropertyBuilder.optionalString()
                        .group(ConnectorPropertyGroup.TOPICS)
                        .label("Tenant")
                        .description("Pulsar tenant of topics that are not given as a full Pulsar name. A tenant/namespace entry in Namespace overrides it.")
                        .order(18)
                        .defaultValue("public"))

                .property("pulsarNamespace", ConnectorPropertyBuilder.optionalString()
                        .group(ConnectorPropertyGroup.TOPICS)
                        .label("Namespace")
                        .order(19)
                        .description("Namespace for topics that are not given as a full Pulsar name. May also be entered as 'tenant/namespace', which overrides the tenant above.")
                        .defaultValue("default"))

                .property("pulsarTopicDomain", ConnectorPropertyBuilder.optionalOption()
                        .group(ConnectorPropertyGroup.TOPICS)
                        .order(20)
                        .label("Topic type")
                        .description("Applies to topics that are not given as a full Pulsar name.")
                        .defaultValue("persistent")
                        .optionsWithLabels("persistent", "Persistent", "non-persistent", "Non-persistent"))

                .build();
    }

    /**
     * QoS-aware message listener wrapper
     */
    protected static class QoSAwarePulsarCallback implements MessageListener<byte[]> {
        private final PulsarCallback delegate;
        private final Qos qos;
        private final String subscriptionTopic;

        public QoSAwarePulsarCallback(PulsarCallback delegate, Qos qos, String subscriptionTopic) {
            this.delegate = delegate;
            this.qos = qos;
            this.subscriptionTopic = subscriptionTopic;
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

            delegate.received(consumer, message, resolveMessageTopic(message.getTopicName(), subscriptionTopic));
        }

        /**
         * Derives the topic used for mapping resolution from the topic the message was actually
         * received on (not from the subscription, which may contain wildcards).
         *
         * <p>The Pulsar name {@code persistent://tenant/namespace/local-name[-partition-N]} is
         * reduced to its local name. Because {@code /} is flattened to {@code -} when an MQTT-style
         * topic is mapped to a Pulsar topic, the MQTT-style form is restored when it can be
         * verified against the subscription filter; otherwise the local name is used as is.
         */
        static String resolveMessageTopic(String pulsarTopicName, String subscriptionTopic) {
            if (pulsarTopicName == null) {
                return subscriptionTopic;
            }
            String localName = pulsarTopicName.replaceFirst("^(?:non-)?persistent://[^/]+/[^/]+/", "")
                    .replaceFirst("-partition-\\d+$", "");

            if (subscriptionTopic == null || localName.isEmpty()) {
                return localName;
            }
            boolean wildcard = subscriptionTopic.contains("+") || subscriptionTopic.contains("#");
            if (!wildcard) {
                String flattened = subscriptionTopic.replace("/", "-").replace(" ", "_")
                        .replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("^[._-]+|[._-]+$", "");
                return flattened.equals(localName) ? subscriptionTopic : localName;
            }
            String mqttStyle = localName.contains("/") ? localName : localName.replace("-", "/");
            return mqttTopicMatches(subscriptionTopic, mqttStyle) ? mqttStyle : localName;
        }

        /** MQTT-style filter matching: {@code +} is one level, {@code #} the remaining levels. */
        static boolean mqttTopicMatches(String filter, String topic) {
            String[] f = filter.split("/", -1);
            String[] t = topic.split("/", -1);
            for (int i = 0; i < f.length; i++) {
                if ("#".equals(f[i])) {
                    return true;
                }
                if (i >= t.length || (!"+".equals(f[i]) && !f[i].equals(t[i]))) {
                    return false;
                }
            }
            return f.length == t.length;
        }
    }

    /**
     * Initialize Sparkplug Host support if configured
     */
    protected void initializeSparkplugSupport() {
        isSparkplugHost = (Boolean) connectorConfiguration.getProperties().getOrDefault("isSparkplugHost", false);
        sparkplugHostId = (String) connectorConfiguration.getProperties().get("sparkplugHostId");

        if (isSparkplugHost) {
            if (sparkplugHostId == null || sparkplugHostId.trim().isEmpty()) {
                // Use connectorIdentifier as fallback if sparkplugHostId is not provided
                sparkplugHostId = connectorIdentifier;
            }

            if (sparkplugHostId != null && !sparkplugHostId.trim().isEmpty()) {
                sparkplugCertificateManager = new SparkplugCertificateManager(
                        tenant,
                        sparkplugHostId,
                        objectMapper,
                        createSparkplugPublisher());
                log.info("{} - Sparkplug Host support initialized with ID: {}", tenant, sparkplugHostId);
            } else {
                log.warn("{} - Sparkplug Host mode enabled but no Host ID provided", tenant);
                isSparkplugHost = false;
            }
        }
    }

    /**
     * Create a Sparkplug publisher for this connector
     * Subclasses should override to provide protocol-specific implementation
     *
     * @return The SparkplugPublisher implementation
     */
    protected SparkplugCertificateManager.SparkplugPublisher createSparkplugPublisher() {
        return new SparkplugCertificateManager.SparkplugPublisher() {
            @Override
            public void publishCertificate(String topic, byte[] payload) throws Exception {
                if (!isConnected()) {
                    throw new ConnectorException("Cannot publish Sparkplug certificate: not connected");
                }
                log.warn("{} - Sparkplug certificate publishing not implemented for this connector type", tenant);
            }

            @Override
            public void subscribeTopic(String topicPattern) throws Exception {
                if (!isConnected()) {
                    throw new ConnectorException("Cannot subscribe to topic: not connected");
                }
                log.debug("{} - Sparkplug topic subscription not needed for this connector type", tenant);
            }
        };
    }
}
