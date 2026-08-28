/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.connector.googlepubsub;

import com.google.api.core.ApiFuture;
import com.google.api.gax.batching.BatchingSettings;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorPropertyBuilder;
import dynamic.mapper.connector.core.ConnectorPropertyType;
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
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import jakarta.ws.rs.NotSupportedException;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Google Cloud Pub/Sub Connector Client.
 * <p>
 * Outbound-only connector that publishes Cumulocity Measurements, Alarms, Events (and, generically,
 * any other API type) to a Google Cloud Pub/Sub topic — e.g. for ingestion into Google's Manufacturing
 * Data Engine (MDE), which reads all data from a single Pub/Sub topic ({@code input-messages} by
 * default) and dispatches it via custom parsers keyed off message attributes.
 * <p>
 * Every published message carries the fixed attributes {@code sourceSystem=cumulocity} and
 * {@code messageType=<measurement|alarm|event|...>} (derived from {@link dynamic.mapper.model.API#toC8yObjectType()})
 * in addition to the mapping-defined JSON body, which is published unchanged as the message data.
 */
@Slf4j
public class GooglePubSubClient extends AConnectorClient {

    private static final String ATTRIBUTE_SOURCE_SYSTEM = "sourceSystem";
    private static final String ATTRIBUTE_SOURCE_SYSTEM_VALUE = "cumulocity";
    private static final String ATTRIBUTE_MESSAGE_TYPE = "messageType";
    private static final int DEFAULT_PUBLISH_TIMEOUT_SECONDS = 30;
    private static final int MAX_PUBLISHER_CREATE_RETRIES = 3;
    private static final int PUBLISHER_CREATE_RETRY_DELAY_MS = 1000;
    /**
     * The Publisher library defaults to a 600-second total retry timeout, which means a stuck
     * gRPC call silently retries for up to 10 minutes. We cap it at publishTimeoutSeconds + a
     * small buffer so the library gives up around the same time our future.get() times out,
     * allowing publisher.awaitTermination() during disconnect to return quickly.
     */
    private static final int PUBLISHER_RETRY_OVERHEAD_SECONDS = 5;

    protected final Map<String, Publisher> publishers = new ConcurrentHashMap<>();
    protected FixedCredentialsProvider credentialsProvider;
    protected String projectId;

    /**
     * Default constructor
     */
    public GooglePubSubClient() {
        this.connectorType = ConnectorType.GOOGLE_PUBSUB;
        this.singleton = false;
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public GooglePubSubClient(ConfigurationRegistry configurationRegistry,
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
            projectId = (String) connectorConfiguration.getProperties().get("projectId");
            credentialsProvider = buildCredentialsProvider();

            log.info("{} - Google Pub/Sub connector initialized successfully", tenant);
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing Google Pub/Sub connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    /**
     * Parse credentials for the Pub/Sub client based on the configured auth mode.
     * <ul>
     *   <li>{@code serviceAccountKey} – parse the pasted Service Account JSON key directly.</li>
     *   <li>{@code applicationDefaultCredentials} – parse the pasted ADC JSON (e.g. the file
     *       produced by {@code gcloud auth application-default login}).</li>
     * </ul>
     */
    private FixedCredentialsProvider buildCredentialsProvider() throws IOException {
        String authMode = (String) connectorConfiguration.getProperties()
                .getOrDefault("authMode", "serviceAccountKey");

        String credentialsJson;
        if ("applicationDefaultCredentials".equals(authMode)) {
            credentialsJson = (String) connectorConfiguration.getProperties().get("adcCredentialsJson");
        } else {
            credentialsJson = (String) connectorConfiguration.getProperties().get("serviceAccountKey");
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
        return FixedCredentialsProvider.create(credentials);
    }

    @Override
    public void connect() {
        if (!beginConnection()) {
            return;
        }

        try {
            log.info("{} - Connecting Google Pub/Sub connector: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            // No persistent connection to open: the Pub/Sub client manages gRPC channels per topic,
            // lazily, on first publish (see getOrCreatePublisher). Just (re-)validate credentials here.
            projectId = (String) connectorConfiguration.getProperties().get("projectId");
            credentialsProvider = buildCredentialsProvider();

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

            log.info("{} - Google Pub/Sub connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting Google Pub/Sub connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            connectionStateManager.setConnected(false);
        } finally {
            endConnection();
        }
    }

    @Override
    public void disconnect() {
        if (!beginDisconnection()) {
            return;
        }

        try {
            log.info("{} - Disconnecting Google Pub/Sub connector", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            publishers.values().forEach(publisher -> {
                try {
                    publisher.shutdown();
                    // Keep well within the 5-second budget that ConnectorRegistry.unregisterClient()
                    // allows for the whole disconnect() call (submitDisconnect().get(5s)).
                    publisher.awaitTermination(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("{} - Error shutting down Pub/Sub publisher: {}", tenant, e.getMessage());
                }
            });
            publishers.clear();
            credentialsProvider = null;

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            log.info("{} - Google Pub/Sub connector disconnected", tenant);

        } catch (Exception e) {
            log.error("{} - Error during disconnect: {}", tenant, e.getMessage(), e);
        } finally {
            endDisconnection();
        }
    }

    @Override
    protected boolean isPhysicallyConnected() {
        // The Pub/Sub client library manages its own gRPC channels lazily, per topic - there is no
        // single persistent socket to probe, so readiness is approximated by credential state.
        return credentialsProvider != null;
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        throw new NotSupportedException("Google Pub/Sub connector does not support inbound mappings");
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        throw new NotSupportedException("Google Pub/Sub connector does not support inbound mappings");
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (credentialsProvider == null) {
            log.warn("{} - Cannot publish: Google Pub/Sub connector not connected", tenant);
            return;
        }

        var requests = context.getRequests();
        if (requests == null || requests.isEmpty()) {
            log.warn("{} - No requests to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }

        String defaultTopic = (String) connectorConfiguration.getProperties().get("topicId");
        int publishTimeoutSeconds = ((Number) connectorConfiguration.getProperties()
                .getOrDefault("publishTimeoutSeconds", DEFAULT_PUBLISH_TIMEOUT_SECONDS)).intValue();

        for (int i = 0; i < requests.size(); i++) {
            // Honour pipeline cancellation: if the processing thread was interrupted (e.g. by a
            // pipeline timeout in CustomWebSocketClient), stop publishing remaining messages.
            if (Thread.currentThread().isInterrupted()) {
                log.warn("{} - Thread interrupted, aborting publish loop at message ({}/{}), connector: {}",
                        tenant, i + 1, requests.size(), connectorName);
                Thread.currentThread().interrupt(); // restore interrupt flag
                break;
            }

            DynamicMapperRequest request = requests.get(i);

            if (request == null || (request.getRequest() == null && request.getBinaryPayload() == null)) {
                log.warn("{} - Skipping null request or payload ({}/{})", tenant, i + 1, requests.size());
                continue;
            }

            String topic = resolveTopic(request, context, defaultTopic);

            if (topic == null || topic.isEmpty()) {
                log.warn("{} - No topic specified for request ({}/{}), skipping", tenant, i + 1, requests.size());
                request.setError(new Exception("No publish topic specified"));
                continue;
            }

            String messageType = request.getApi() != null ? request.getApi().toC8yObjectType() : null;

            try {
                Publisher publisher = getOrCreatePublisher(topic);

                byte[] payloadBytes = request.getBinaryPayload() != null
                        ? request.getBinaryPayload()
                        : request.getRequest().getBytes(StandardCharsets.UTF_8);

                PubsubMessage.Builder messageBuilder = PubsubMessage.newBuilder()
                        .setData(ByteString.copyFrom(payloadBytes))
                        .putAttributes(ATTRIBUTE_SOURCE_SYSTEM, ATTRIBUTE_SOURCE_SYSTEM_VALUE);
                if (messageType != null) {
                    messageBuilder.putAttributes(ATTRIBUTE_MESSAGE_TYPE, messageType);
                }

                ApiFuture<String> future = publisher.publish(messageBuilder.build());
                String messageId = future.get(publishTimeoutSeconds, TimeUnit.SECONDS);

                log.debug("{} - Published message ({}/{}): topic={}, messageId={}, messageType={}",
                        tenant, i + 1, requests.size(), topic, messageId, messageType);

                if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
                    log.info("{} - Published message ({}/{}): topic={}, messageType={}, payload={}",
                            tenant, i + 1, requests.size(), topic, messageType, request.getRequest());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore interrupt flag
                log.warn("{} - Publish interrupted at message ({}/{}), connector: {}", tenant, i + 1, requests.size(), connectorName);
                request.setError(e);
                context.addError(new ProcessingException(
                        "Publish interrupted at message " + (i + 1) + "/" + requests.size(), e));
                break;
            } catch (Exception e) {
                log.error("{} - Error publishing to topic: {} ({}/{})", tenant, topic, i + 1, requests.size(), e);
                request.setError(e);
                context.addError(new ProcessingException(
                        "Failed to publish message " + (i + 1) + "/" + requests.size(), e));
            }
        }
    }

    /**
     * Resolve the Pub/Sub topic for a single request: a mapping's own publish topic takes
     * precedence over the connector's configured default topic.
     * Package-private (not private) so it can be unit tested without a live Pub/Sub client.
     */
    static String resolveTopic(DynamicMapperRequest request, ProcessingContext<?> context, String defaultTopic) {
        String topic = request.getPublishTopic() != null ? request.getPublishTopic() : context.getResolvedPublishTopic();
        return (topic == null || topic.isEmpty()) ? defaultTopic : topic;
    }

    /**
     * Get or create a cached publisher for the given topic, retrying transient creation failures.
     */
    private Publisher getOrCreatePublisher(String topic) throws ConnectorException {
        Publisher publisher = publishers.get(topic);
        if (publisher != null) {
            return publisher;
        }

        publisher = retryOperation("Create Pub/Sub publisher for topic: " + topic,
                MAX_PUBLISHER_CREATE_RETRIES, PUBLISHER_CREATE_RETRY_DELAY_MS,
                () -> Publisher.newBuilder(TopicName.of(projectId, topic))
                        .setCredentialsProvider(credentialsProvider)
                        .build());
        publishers.put(topic, publisher);
        return publisher;
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }
        if (!validateRequiredProperties(configuration, "projectId", "topicId", "authMode")) {
            return false;
        }
        String authMode = (String) configuration.getProperties().getOrDefault("authMode", "serviceAccountKey");
        if ("applicationDefaultCredentials".equals(authMode)) {
            return validateRequiredProperties(configuration, "adcCredentialsJson");
        }
        return validateRequiredProperties(configuration, "serviceAccountKey");
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        // Pub/Sub topic names are plain identifiers; there is no MQTT-style wildcard concept.
        return false;
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // No per-publisher health signal is exposed by the Pub/Sub client library to prune on;
        // publishers are recreated on demand in getOrCreatePublisher() if a publish call fails.
    }

    @Override
    public List<Direction> supportedDirections() {
        return Arrays.asList(Direction.OUTBOUND);
    }

    /**
     * Create Google Pub/Sub connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("Google Cloud Pub/Sub", ConnectorType.GOOGLE_PUBSUB)
                .description("Connector for publishing outbound Cumulocity data (Measurements, Alarms, Events, ...) " +
                        "to a Google Cloud Pub/Sub topic, e.g. for ingestion into Google's Manufacturing Data Engine (MDE). " +
                        "Every published message carries the attributes sourceSystem=cumulocity and " +
                        "messageType=<measurement|alarm|event|...> in addition to the mapping-defined JSON body.")
                .supportedDirections(supportedDirections())

                .property("projectId", ConnectorPropertyBuilder.requiredString()
                        .order(0)
                        .description("Google Cloud project ID that owns the Pub/Sub topic"))

                .property("topicId", ConnectorPropertyBuilder.requiredString()
                        .order(1)
                        .defaultValue("input-messages")
                        .description("Default Pub/Sub topic to publish to. A mapping's own publish topic, " +
                                "if set, overrides this per request."))

                .property("authMode", ConnectorPropertyBuilder.create(ConnectorPropertyType.OPTION_PROPERTY)
                        .required(true)
                        .order(2)
                        .defaultValue("serviceAccountKey")
                        .optionsWithLabels(
                                "serviceAccountKey", "Service Account Key",
                                "applicationDefaultCredentials", "Application Default Credentials (ADC)")
                        .description("Authentication method used to connect to Google Cloud Pub/Sub."))

                .property("serviceAccountKey", ConnectorPropertyBuilder.create(ConnectorPropertyType.SENSITIVE_STRING_LARGE_PROPERTY)
                        .required(false)
                        .order(3)
                        .condition("authMode", "serviceAccountKey")
                        .description("Full JSON key of the Google Cloud Service Account used to authenticate " +
                                "against Pub/Sub (requires the roles/pubsub.publisher role)."))

                .property("adcCredentialsJson", ConnectorPropertyBuilder.create(ConnectorPropertyType.SENSITIVE_STRING_LARGE_PROPERTY)
                        .required(false)
                        .order(4)
                        .condition("authMode", "applicationDefaultCredentials")
                        .description("Contents of the Application Default Credentials JSON file generated by " +
                                "'gcloud auth application-default login' (typically ~/.config/gcloud/application_default_credentials.json)."))

                .property("publishTimeoutSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .order(5)
                        .defaultValue(DEFAULT_PUBLISH_TIMEOUT_SECONDS)
                        .description("How long to wait for a publish acknowledgement before treating the request as failed."))

                .build();
    }
}
