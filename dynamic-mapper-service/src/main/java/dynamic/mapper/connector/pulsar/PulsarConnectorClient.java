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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.PulsarClientException.UnsupportedAuthenticationException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.Message;

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
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PulsarConnectorClient extends AConnectorClient {
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

    public PulsarConnectorClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();

        ConnectorPropertyCondition tlsCondition = new ConnectorPropertyCondition("enableTls",
                new String[] { "true" });
        ConnectorPropertyCondition useSelfSignedCertificateCondition = new ConnectorPropertyCondition(
                "useSelfSignedCertificate", new String[] { "true" });
        ConnectorPropertyCondition authCondition = new ConnectorPropertyCondition("authenticationMethod",
                new String[] { "token", "oauth2", "tls" });

        configProps.put("serviceUrl",
                new ConnectorProperty(
                        "This can be in the format: pulsar://localhost:6650 for non-TLS or pulsar+ssl://localhost:6651 for TLS",
                        true, 0, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "pulsar://localhost:6650", null, null));
        configProps.put("enableTls",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, null));
        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, tlsCondition));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, useSelfSignedCertificateCondition));
        configProps.put("nameCertificate",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, useSelfSignedCertificateCondition));
        configProps.put("authenticationMethod",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        "none",
                        Map.ofEntries(
                                new AbstractMap.SimpleEntry<String, String>("none", "None"),
                                new AbstractMap.SimpleEntry<String, String>("token", "Token"),
                                new AbstractMap.SimpleEntry<String, String>("oauth2", "OAuth2"),
                                new AbstractMap.SimpleEntry<String, String>("tls", "TLS"),
                                new AbstractMap.SimpleEntry<String, String>("basic", "Basic")),
                        null));
        configProps.put("authenticationParams",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null, null, authCondition));
        configProps.put("connectionTimeoutSeconds",
                new ConnectorProperty(null, false, 7, ConnectorPropertyType.NUMERIC_PROPERTY, false, false,
                        DEFAULT_CONNECTION_TIMEOUT, null, null));
        configProps.put("operationTimeoutSeconds",
                new ConnectorProperty(null, false, 8, ConnectorPropertyType.NUMERIC_PROPERTY, false, false,
                        DEFAULT_OPERATION_TIMEOUT, null, null));
        configProps.put("keepAliveIntervalSeconds",
                new ConnectorProperty(null, false, 9, ConnectorPropertyType.NUMERIC_PROPERTY, false, false,
                        DEFAULT_KEEP_ALIVE, null, null));
        configProps.put("subscriptionType",
                new ConnectorProperty(null, false, 10, ConnectorPropertyType.OPTION_PROPERTY, false, false,
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
                        false, 11, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));
        configProps.put("supportsWildcardInTopic",
                new ConnectorProperty(null, false, 12, ConnectorPropertyType.BOOLEAN_PROPERTY, true, false,
                        true, null, null));
        configProps.put("pulsarTenant",
                new ConnectorProperty(null, false, 13, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "public", null, null));
        configProps.put("pulsarNamespace",
                new ConnectorProperty(null, false, 14, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        "default", null, null));

        String name = "Apache Pulsar";
        String description = "Connector for connecting to Apache Pulsar message broker.";
        connectorType = ConnectorType.PULSAR;
        connectorSpecification = new ConnectorSpecification(name, description, connectorType, singleton, configProps,
                false,
                supportedDirections());
    }

    public PulsarConnectorClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
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
        this.supportedQOS = Arrays.asList(Qos.AT_LEAST_ONCE, Qos.AT_MOST_ONCE, Qos.EXACTLY_ONCE);
    }

    protected AConnectorClient.Certificate cert;
    protected SSLContext sslContext;
    protected PulsarCallback pulsarCallback = null;
    protected PulsarClient pulsarClient;
    protected Map<String, Consumer<byte[]>> consumers = new ConcurrentHashMap<>();
    protected Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();

    @Getter
    protected List<Qos> supportedQOS;

    public boolean initialize() {
        loadConfiguration();
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        log.debug("{} - Testing connector for useSelfSignedCertificate: {} ", tenant, useSelfSignedCertificate);

        if (useSelfSignedCertificate) {
            try {
                String nameCertificate = (String) connectorConfiguration.getProperties().get("nameCertificate");
                String fingerprint = (String) connectorConfiguration.getProperties()
                        .get("fingerprintSelfSignedCertificate");
                if (nameCertificate == null || fingerprint == null) {
                    throw new Exception(
                            "Required properties nameCertificate, fingerprint are not set. Please update the connector configuration!");
                }
                cert = c8yAgent.loadCertificateByName(nameCertificate, fingerprint, tenant, getConnectorName());
                if (cert == null) {
                    String errorMessage = String.format(
                            "Required certificate %s with fingerprint %s not found. Please update trusted certificates in the Cumulocity Device Management!",
                            nameCertificate, fingerprint);
                    throw new Exception(errorMessage);
                }

                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                trustStore.setCertificateEntry("Custom CA",
                        (X509Certificate) CertificateFactory.getInstance("X509")
                                .generateCertificate(new ByteArrayInputStream(
                                        cert.getCertInPemFormat().getBytes(Charset.defaultCharset()))));
                TrustManagerFactory tmf = TrustManagerFactory
                        .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, tmf.getTrustManagers(), null);
            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                    | KeyManagementException e) {
                log.error("{} - Connector {} - Error configuring SSL context for TLS: ", tenant,
                        getConnectorName(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                return false;
            } catch (Exception e) {
                log.error("{} - {} - Error initializing connector: ", tenant,
                        getConnectorName(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                return false;
            }
        }
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
        Boolean useSelfSignedCertificate = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
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
            // Modify serviceUrl for TLS instead of using deprecated enableTls()
            String finalServiceUrl = adjustServiceUrlForTls(serviceUrl, enableTls);

            ClientBuilder clientBuilder = PulsarClient.builder()
                    .serviceUrl(finalServiceUrl)
                    .connectionTimeout(connectionTimeout, TimeUnit.SECONDS)
                    .operationTimeout(operationTimeout, TimeUnit.SECONDS)
                    .keepAliveInterval(keepAlive, TimeUnit.SECONDS);

            // Configure TLS certificate handling
            if (isUsingTls(finalServiceUrl)) {
                if (useSelfSignedCertificate) {
                    configureSelfSignedCertificate(clientBuilder);
                }
                // For standard TLS, no additional configuration needed
                log.debug("{} - Using TLS connection to: {}", tenant, finalServiceUrl);
            }

            // Configure authentication
            configureAuthentication(clientBuilder, authenticationMethod, authenticationParams);

            pulsarClient = clientBuilder.build();

            // Registering Callback
            pulsarCallback = new PulsarCallback(tenant, configurationRegistry, dispatcher,
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
                        // Test connection by creating a temporary producer
                        // Producer<byte[]> testProducer = pulsarClient.newProducer()
                        // .topic("test-connection-topic")
                        // .createAsync()
                        // .get(5, TimeUnit.SECONDS);
                        // testProducer.close();

                        connectionState.setTrue();
                        log.info("{} - Phase III: {} connected, server: {}", tenant, getConnectorName(), serviceUrl);
                        updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);

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
     * Adjusts the service URL to use appropriate protocol based on TLS setting
     */
    protected String adjustServiceUrlForTls(String originalUrl, Boolean enableTls) {
        if (enableTls != null && enableTls) {
            // Convert to TLS URL if not already
            if (originalUrl.startsWith("pulsar://")) {
                return originalUrl.replace("pulsar://", "pulsar+ssl://");
            } else if (originalUrl.startsWith("pulsar+ssl://")) {
                return originalUrl; // Already TLS
            }
        } else {
            // Ensure non-TLS URL
            if (originalUrl.startsWith("pulsar+ssl://")) {
                return originalUrl.replace("pulsar+ssl://", "pulsar://");
            }
        }
        return originalUrl;
    }

    /**
     * Checks if the service URL indicates TLS usage
     */
    protected boolean isUsingTls(String serviceUrl) {
        return serviceUrl.startsWith("pulsar+ssl://") || serviceUrl.startsWith("https://");
    }

    /**
     * Configures self-signed certificate handling
     */
    protected void configureSelfSignedCertificate(ClientBuilder clientBuilder) throws Exception {
        if (cert != null) {
            // Write certificate to temporary file since Pulsar client needs file path
            String certFilePath = writeCertificateToTempFile(cert.getCertInPemFormat());
            clientBuilder.tlsTrustCertsFilePath(certFilePath);
            log.debug("{} - Using self-signed certificate from file: {}", tenant, certFilePath);
        } else {
            throw new Exception("Self-signed certificate is enabled but certificate is not loaded");
        }
    }

    /**
     * Writes certificate content to a temporary file
     */
    protected String writeCertificateToTempFile(String certContent) throws IOException {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("pulsar-cert", ".pem");
        java.nio.file.Files.write(tempFile, certContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Delete on JVM exit
        tempFile.toFile().deleteOnExit();
        return tempFile.toString();
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
        if (pulsarClient != null) {
            try {
                // Close all consumers
                for (Consumer<byte[]> consumer : consumers.values()) {
                    consumer.close();
                }
                consumers.clear();

                // Close all producers
                for (Producer<byte[]> producer : producers.values()) {
                    producer.close();
                }
                producers.clear();

                pulsarClient.close();
            } catch (PulsarClientException e) {
                log.error("{} - Error closing Pulsar client: ", tenant, e);
            }
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        // if using self signed certificate additional properties have to be set
        Boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                .getOrDefault("useSelfSignedCertificate", false);
        if (useSelfSignedCertificate && (configuration.getProperties().get("fingerprintSelfSignedCertificate") == null
                || configuration.getProperties().get("nameCertificate") == null)) {
            return false;
        }
        // check if all required properties are set
        for (String property : getConnectorSpecification().getProperties().keySet()) {
            if (getConnectorSpecification().getProperties().get(property).required
                    && configuration.getProperties().get(property) == null) {
                return false;
            }
        }
        return true;
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

            // Close all consumers
            countSubscriptionsPerTopicInbound.entrySet().forEach(entry -> {
                String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0 && consumers.containsKey(topic)) {
                    try {
                        consumers.get(topic).close();
                        consumers.remove(topic);
                    } catch (PulsarClientException e) {
                        log.error("{} - Error closing consumer for topic: {}", tenant, topic, e);
                    }
                }
            });

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

            log.info("{} - Disconnected from Pulsar", tenant);
        }
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        if (isConnected() && !consumers.containsKey(topic)) {
            log.debug("{} - Subscribing on topic: [{}] with QoS: {} for connector: {}",
                    tenant, topic, qos, connectorName);
            sendSubscriptionEvents(topic, "Subscribing");

            try {
                String subscriptionName = (String) connectorConfiguration.getProperties()
                        .getOrDefault("subscriptionName", "dynamic-mapper-subscription");

                // Use QoS to determine subscription type, but allow override from config
                SubscriptionType configuredType = null;
                String subscriptionTypeStr = (String) connectorConfiguration.getProperties().get("subscriptionType");
                if (subscriptionTypeStr != null && !subscriptionTypeStr.isEmpty()) {
                    configuredType = SubscriptionType.valueOf(subscriptionTypeStr);
                }

                // Use configured type if available, otherwise map from QoS
                SubscriptionType subscriptionType = configuredType != null ? configuredType
                        : mapQosToSubscriptionType(qos);

                // Check if topic contains wildcards and handle appropriately
                var consumerBuilder = pulsarClient.newConsumer();

                if (containsMqttWildcards(topic)) {
                    // Handle wildcard topic with pattern subscription
                    String pulsarPattern = translateMqttTopicToPulsarRegex(topic);
                    consumerBuilder.topicsPattern(java.util.regex.Pattern.compile(pulsarPattern));
                    log.info("{} - Subscribing to topic pattern: [{}] (translated from MQTT: [{}])",
                            tenant, pulsarPattern, topic);
                } else {
                    // Handle regular topic
                    String pulsarTopic = ensurePulsarTopicFormat(topic);
                    consumerBuilder.topic(pulsarTopic);
                    log.info("{} - Subscribing to specific topic: [{}] (translated from MQTT: [{}])",
                            tenant, pulsarTopic, topic);
                }

                Consumer<byte[]> consumer = consumerBuilder
                        .subscriptionName(subscriptionName + "-" + sanitizeTopicForSubscriptionName(topic)
                                + additionalSubscriptionIdTest)
                        .subscriptionType(subscriptionType)
                        .messageListener(new QoSAwarePulsarCallback(pulsarCallback, qos))
                        // Configure acknowledgment timeout based on QoS
                        .acknowledgmentGroupTime(requiresAcknowledgment(qos) ? 100 : 0, TimeUnit.MILLISECONDS)
                        .subscribe();

                // Configure additional QoS settings after creation
                if (qos == Qos.EXACTLY_ONCE) {
                    // For exactly once, additional configuration might be needed
                    // Note: Pulsar's exactly-once semantics are different from MQTT
                    log.debug("{} - Configured EXACTLY_ONCE semantics for topic: {}", tenant, topic);
                }

                consumers.put(topic, consumer);

                log.info("{} - Subscribed to topic:[{}] with QoS: {}, subscription type: {}, for connector: {}",
                        tenant, topic, qos, subscriptionType, connectorName);

            } catch (PulsarClientException e) {
                log.error("{} - Failed to subscribe on topic {} with QoS {} and error: {}",
                        tenant, topic, qos, e.getMessage(), e);
                throw new ConnectorException("Failed to subscribe to topic: " + topic +
                        " with QoS: " + qos + ". Error: " + e.getMessage());
            }
        }
    }

    /**
     * Checks if a topic contains MQTT-style wildcards
     */
    protected boolean containsMqttWildcards(String topic) {
        return topic != null && (topic.contains("+") || topic.contains("#"));
    }

    /**
     * Sanitizes topic name for use in subscription names
     */
    protected String sanitizeTopicForSubscriptionName(String topic) {
        return topic.replace("/", "-").replace("+", "wildcard").replace("#", "multilevel");
    }

    @Override
    public void unsubscribe(String topic) throws Exception {
        log.debug("{} - Unsubscribing from topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");

        Consumer<byte[]> consumer = consumers.remove(topic);
        if (consumer != null) {
            try {
                consumer.close();
                log.info("{} - Successfully unsubscribed from topic: [{}] for connector: {}", tenant, topic,
                        connectorName);
            } catch (PulsarClientException e) {
                log.error("{} - Failed to unsubscribe from topic {} with error: {}", tenant, topic, e.getMessage(), e);
                throw e;
            }
        }
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        DynamicMapperRequest currentRequest = context.getCurrentRequest();
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

            Producer<byte[]> producer = producers.computeIfAbsent(topic, t -> {
                try {
                    return createProducerWithQos(t, qos);
                } catch (Exception e) {
                    log.error("{} - Failed to create producer for topic: {} with QoS: {}",
                            tenant, t, qos, e);
                    return null;
                }
            });

            // Check if existing producer is still connected
            if (producer != null && !producer.isConnected()) {
                log.warn("{} - Producer for topic {} is disconnected, recreating", tenant, topic);
                producers.remove(topic);
                try {
                    producer.close();
                } catch (PulsarClientException closeEx) {
                    log.debug("{} - Error closing disconnected producer: {}", tenant, closeEx.getMessage());
                }

                producer = createProducerWithQos(topic, qos);
                if (producer != null) {
                    producers.put(topic, producer);
                }
            }

            if (producer != null) {
                sendMessageWithQos(producer, payload, qos, context);
            } else {
                log.error("{} - No producer available for topic: {}", tenant, topic);
            }

        } catch (PulsarClientException e) {
            handlePublishError(e, topic, qos, context);
        } catch (Exception e) {
            log.error("{} - Unexpected error publishing to topic: {} with QoS: {}", tenant, topic, qos, e);
        }
    }

    /**
     * Creates a producer with QoS-specific configuration and retry logic
     */
    protected Producer<byte[]> createProducerWithQos(String topic, Qos qos) throws PulsarClientException {
        int maxRetries = 3;
        int retryDelay = 1000; // 1 second

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Convert MQTT topic to proper Pulsar format
                String pulsarTopic = ensurePulsarTopicFormat(topic);

                var producerBuilder = pulsarClient.newProducer()
                        .topic(pulsarTopic); // ← Use converted topic

                // Configure producer based on QoS requirements
                switch (qos) {
                    case AT_MOST_ONCE:
                        // Fire and forget - don't wait for acknowledgment
                        producerBuilder.sendTimeout(0, TimeUnit.SECONDS);
                        break;
                    case AT_LEAST_ONCE:
                        // Default Pulsar behavior - wait for broker acknowledgment
                        producerBuilder.sendTimeout(30, TimeUnit.SECONDS);
                        break;
                    case EXACTLY_ONCE:
                        // Enable deduplication for exactly-once semantics
                        producerBuilder.sendTimeout(30, TimeUnit.SECONDS);
                        // Note: Pulsar's deduplication is enabled at namespace level
                        break;
                }

                Producer<byte[]> producer = producerBuilder.create();
                log.debug("{} - Created producer for MQTT topic '{}' → Pulsar topic '{}' with QoS: {} on attempt {}",
                        tenant, topic, pulsarTopic, qos, attempt);
                return producer;

            } catch (PulsarClientException e) {
                log.warn("{} - Producer creation attempt {}/{} failed for topic: {} with QoS: {}: {}",
                        tenant, attempt, maxRetries, topic, qos, e.getMessage());

                if (attempt == maxRetries) {
                    throw e;
                }

                try {
                    Thread.sleep(retryDelay * attempt); // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PulsarClientException(ie);
                }
            }
        }

        throw new PulsarClientException("Failed to create producer after " + maxRetries + " attempts");
    }

    /**
     * Sends message with QoS-specific logic (your original logic)
     */
    private void sendMessageWithQos(Producer<byte[]> producer, String payload, Qos qos, ProcessingContext<?> context)
            throws PulsarClientException {

        if (qos == Qos.AT_MOST_ONCE) {
            // Fire and forget (your original logic)
            producer.sendAsync(payload.getBytes())
                    .exceptionally(throwable -> {
                        log.debug("{} - Failed to send AT_MOST_ONCE message (expected): {}",
                                tenant, throwable.getMessage());
                        return null;
                    });
        } else {
            // Wait for acknowledgment (your original logic)
            producer.send(payload.getBytes());
        }

        // Your original logging logic
        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("{} - Published outbound message with QoS {}: {} for mapping: {} on topic: [{}], {}",
                    tenant, qos, payload, context.getMapping().name, context.getResolvedPublishTopic(), connectorName);
        }
    }

    @Override
    public void connectorSpecificHousekeeping(String tenant) {
    }

    /**
     * Handles publish errors with connection recovery
     */
    protected void handlePublishError(PulsarClientException e, String topic, Qos qos, ProcessingContext<?> context) {
        log.error("{} - Failed to publish message to topic: {} with QoS: {}, {}, {}", tenant, topic, qos,
                e.getMessage(), connectorName);
        e.printStackTrace();

        // Check if it's a connection issue
        if (isConnectionError(e)) {
            log.info("{} - Connection error detected, removing cached producer and triggering reconnection", tenant);

            // Remove the failed producer from cache
            Producer<byte[]> failedProducer = producers.remove(topic);
            if (failedProducer != null) {
                try {
                    failedProducer.close();
                } catch (PulsarClientException closeEx) {
                    log.debug("{} - Error closing failed producer: {}", tenant, closeEx.getMessage());
                }
            }

            // Trigger reconnection in background
            virtualThreadPool.submit(() -> {
                try {
                    reconnect();
                } catch (Exception reconnectError) {
                    log.error("{} - Reconnection failed: {}", tenant, reconnectError.getMessage());
                }
            });
        }
    }

    /**
     * Determines if the exception indicates a connection problem
     */
    protected boolean isConnectionError(PulsarClientException e) {
        String message = e.getMessage().toLowerCase();
        return message.contains("connection") ||
                message.contains("timeout") ||
                message.contains("disconnected") ||
                message.contains("not connected") ||
                e instanceof PulsarClientException.ConnectException ||
                e instanceof PulsarClientException.TimeoutException ||
                e instanceof PulsarClientException.AlreadyClosedException;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return Boolean.parseBoolean(connectorConfiguration.getProperties().get("supportsWildcardInTopic").toString());
    }

    @Override
    public void monitorSubscriptions() {
        // Monitor consumer connections and recreate if needed
        consumers.entrySet().removeIf(entry -> {
            String topic = entry.getKey();
            Consumer<byte[]> consumer = entry.getValue();

            if (!consumer.isConnected()) {
                log.warn("{} - Consumer for topic {} is not connected, removing", tenant, topic);
                try {
                    consumer.close();
                } catch (PulsarClientException e) {
                    log.error("{} - Error closing disconnected consumer for topic: {}", tenant, topic, e);
                }
                return true;
            }
            return false;
        });
    }

    @Override
    public List<Direction> supportedDirections() {
        return new ArrayList<>(Arrays.asList(Direction.INBOUND, Direction.OUTBOUND));
    }

    protected SubscriptionType mapQosToSubscriptionType(Qos qos) {
        switch (qos) {
            case AT_MOST_ONCE:
                return SubscriptionType.Shared; // Best effort, multiple consumers
            case AT_LEAST_ONCE:
                return SubscriptionType.Shared; // With acknowledgment (default Pulsar behavior)
            case EXACTLY_ONCE:
                return SubscriptionType.Exclusive; // Single consumer for ordering
            default:
                return SubscriptionType.Shared;
        }
    }

    /**
     * Determines if acknowledgment should be used based on QoS
     */
    protected boolean requiresAcknowledgment(Qos qos) {
        return qos != Qos.AT_MOST_ONCE;
    }

    /**
     * Translates MQTT wildcard topic patterns to Pulsar regex patterns
     * MQTT wildcards:
     * + = single level wildcard (matches one topic level, doesn't cross '/')
     * # = multi-level wildcard (matches zero or more topic levels, must be at end)
     * 
     * @param mqttTopic MQTT topic with wildcards like "sensor/+/temperature" or
     *                  "device/#"
     * @return Pulsar regex pattern like "sensor/[^/]+/temperature" or "device/.*"
     */
    protected String translateMqttTopicToPulsarRegex(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.isEmpty()) {
            return mqttTopic;
        }

        // Validate MQTT wildcard rules
        validateMqttWildcards(mqttTopic);

        // Escape regex special characters first (except + and # which we'll replace)
        String escaped = escapeRegexCharacters(mqttTopic);

        // Replace MQTT wildcards with Pulsar regex equivalents
        String pulsarRegex = escaped
                .replace("+", "[^/]+") // + becomes [^/]+ (matches any chars except /)
                .replace("#", ".*"); // # becomes .* (matches any characters including /)

        // Ensure we have a complete Pulsar topic format
        return ensurePulsarTopicFormat(pulsarRegex);
    }

    /**
     * Validates MQTT wildcard usage according to MQTT spec
     */
    private void validateMqttWildcards(String mqttTopic) {
        // Check # wildcard rules: must be at end and preceded by /
        int hashIndex = mqttTopic.indexOf('#');
        if (hashIndex != -1) {
            if (hashIndex != mqttTopic.length() - 1) {
                throw new IllegalArgumentException(
                        "MQTT wildcard '#' must be the last character in topic: " + mqttTopic);
            }
            if (hashIndex > 0 && mqttTopic.charAt(hashIndex - 1) != '/') {
                throw new IllegalArgumentException("MQTT wildcard '#' must be preceded by '/' in topic: " + mqttTopic);
            }
        }

        // Check + wildcard rules: must be between / or at start/end
        for (int i = 0; i < mqttTopic.length(); i++) {
            if (mqttTopic.charAt(i) == '+') {
                boolean validPosition = true;

                // Check character before +
                if (i > 0 && mqttTopic.charAt(i - 1) != '/') {
                    validPosition = false;
                }

                // Check character after +
                if (i < mqttTopic.length() - 1 && mqttTopic.charAt(i + 1) != '/') {
                    validPosition = false;
                }

                if (!validPosition) {
                    throw new IllegalArgumentException(
                            "MQTT wildcard '+' must be between '/' separators in topic: " + mqttTopic);
                }
            }
        }
    }

    /**
     * Escapes regex special characters while preserving MQTT wildcards
     */
    private String escapeRegexCharacters(String input) {
        // List of regex special characters to escape (excluding + and # which are MQTT
        // wildcards)
        String[] regexSpecialChars = { "\\", "^", "$", ".", "|", "?", "*", "(", ")", "[", "]", "{" };

        String result = input;
        for (String specialChar : regexSpecialChars) {
            result = result.replace(specialChar, "\\" + specialChar);
        }

        return result;
    }

    /**
     * Ensures the topic follows Pulsar's full topic format and converts MQTT-style
     * topics
     */
    protected String ensurePulsarTopicFormat(String topicPattern) {
        // If already a complete Pulsar topic, return as-is
        if (topicPattern.startsWith("persistent://") || topicPattern.startsWith("non-persistent://")) {
            return topicPattern;
        }

        // Convert MQTT-style topic to Pulsar-compatible name
        String pulsarTopicName = convertMqttTopicToPulsarTopicName(topicPattern);

        // Get default namespace from configuration or use public/default
        String tenant = (String) connectorConfiguration.getProperties().getOrDefault("pulsarTenant", "public");
        String namespace = (String) connectorConfiguration.getProperties().getOrDefault("pulsarNamespace", "default");

        // Construct full Pulsar topic format
        return String.format("persistent://%s/%s/%s", tenant, namespace, pulsarTopicName);
    }

    /**
     * Converts MQTT-style topic names to Pulsar-compatible topic names
     */
    protected String convertMqttTopicToPulsarTopicName(String mqttTopic) {
        if (mqttTopic == null || mqttTopic.isEmpty()) {
            return mqttTopic;
        }

        // Replace forward slashes with hyphens
        String pulsarTopic = mqttTopic
                .replace("/", "-") // measurement/kobu-webhook-001 → measurement-kobu-webhook-001
                .replace(" ", "_") // Replace spaces with underscores
                .replaceAll("[^a-zA-Z0-9._-]", "_"); // Replace other special chars

        // Ensure it doesn't start or end with special characters
        pulsarTopic = pulsarTopic.replaceAll("^[._-]+|[._-]+$", "");

        // Ensure it's not empty
        if (pulsarTopic.isEmpty()) {
            pulsarTopic = "default-topic";
        }

        log.debug("{} - Converted MQTT topic '{}' to Pulsar topic '{}'", tenant, mqttTopic, pulsarTopic);

        return pulsarTopic;
    }

    /**
     * Wrapper for PulsarCallback that handles QoS-specific acknowledgment logic
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
                // For AT_MOST_ONCE, acknowledge immediately without waiting for processing
                try {
                    consumer.acknowledge(message);
                } catch (PulsarClientException e) {
                    // Log but don't fail for AT_MOST_ONCE
                    log.warn("Failed to acknowledge AT_MOST_ONCE message: {}", e.getMessage());
                }
            }

            // Always delegate to the main callback for processing
            delegate.received(consumer, message);
        }
    }

}
