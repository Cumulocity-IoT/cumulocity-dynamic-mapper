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
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.ConnectorPropertyBuilder;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.ConnectorSpecificationBuilder;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
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
import dynamic.mapper.processor.model.ProcessingResultWrapper;
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
 * Bidirectional connector that publishes Cumulocity Measurements, Alarms, Events (and, generically,
 * any other API type) to a Google Cloud Pub/Sub topic — e.g. for ingestion into Google's Manufacturing
 * Data Engine (MDE) — and subscribes to an existing Pub/Sub subscription to receive inbound messages.
 * <p>
 * Every published message carries the fixed attributes {@code sourceSystem=cumulocity} and
 * {@code messageType=<measurement|alarm|event|...>} (derived from {@link dynamic.mapper.model.API#toC8yObjectType()})
 * in addition to the mapping-defined JSON body, which is published unchanged as the message data.
 * <p>
 * Inbound consumption requires a pre-created Pub/Sub subscription. The subscription name is
 * configured via the {@code subscriptionId} property. The service account must have
 * {@code roles/pubsub.subscriber} in addition to {@code roles/pubsub.publisher}.
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
    protected final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
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

            subscribers.forEach((topic, subscriber) -> stopSubscriberQuietly(subscriber, topic));
            subscribers.clear();

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
        // single persistent socket to probe, so readiness is approximated by credential state and
        // whether all running subscribers are still in a running/healthy state.
        if (credentialsProvider == null) {
            return false;
        }
        for (Subscriber subscriber : subscribers.values()) {
            if (!subscriber.isRunning()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Google Pub/Sub connector is not connected");
        }

        String subscriptionId = (String) connectorConfiguration.getProperties().get("subscriptionId");
        if (subscriptionId == null || subscriptionId.isEmpty()) {
            throw new ConnectorException(
                    "Google Pub/Sub connector requires 'subscriptionId' configuration for inbound mappings");
        }

        log.debug("{} - Subscribing to Pub/Sub subscription: [{}] for topic: [{}]", tenant, subscriptionId, topic);

        try {
            ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(projectId, subscriptionId);

            MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) ->
                    processPubSubMessage(message, consumer, topic, qos);

            Subscriber subscriber = Subscriber.newBuilder(subscriptionName, receiver)
                    .setCredentialsProvider(credentialsProvider)
                    .build();

            subscriber.startAsync().awaitRunning();

            Subscriber existing = subscribers.put(topic, subscriber);
            if (existing != null) {
                stopSubscriberQuietly(existing, topic);
            }

            log.info("{} - Successfully subscribed to Pub/Sub subscription: [{}] for topic: [{}]",
                    tenant, subscriptionId, topic);
            sendSubscriptionEvents(topic, "Subscribed");

        } catch (Exception e) {
            throw new ConnectorException("Failed to subscribe to Pub/Sub subscription: " + subscriptionId, e);
        }
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        log.debug("{} - Unsubscribing from Pub/Sub topic: [{}]", tenant, topic);

        Subscriber subscriber = subscribers.remove(topic);
        if (subscriber != null) {
            stopSubscriberQuietly(subscriber, topic);
        }

        log.info("{} - Successfully unsubscribed from Pub/Sub topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribed");
    }

    /**
     * Process an inbound Pub/Sub message: build a {@link ConnectorMessage}, dispatch it through the
     * inbound pipeline, and ack or nack based on the result and configured QoS.
     * <ul>
     *   <li>QoS 0 (at-most-once): ack immediately before processing.</li>
     *   <li>QoS &gt; 0 (at-least-once): ack after successful processing; nack on error so the
     *       message is redelivered.</li>
     * </ul>
     * Package-private to allow unit testing without a live Pub/Sub client.
     */
    void processPubSubMessage(PubsubMessage message, AckReplyConsumer consumer,
            String topic, Qos qos) {
        byte[] payload = message.getData().toByteArray();
        String messageId = message.getMessageId();

        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payload)
                .build();

        if (serviceConfiguration.getLogPayload()) {
            log.info("{} - INITIAL: Pub/Sub message on topic: [{}], messageId: {}, connector: {}",
                    tenant, topic, messageId, connectorName);
        }

        if (qos == Qos.AT_MOST_ONCE) {
            // Ack immediately — at-most-once delivery
            consumer.ack();
            dispatcher.onMessage(connectorMessage);
            return;
        }

        // At-least-once: ack only after successful processing
        try {
            ProcessingResultWrapper<?> result = dispatcher.onMessage(connectorMessage);
            int timeout = result.getPipelineTimeoutMS();

            List<? extends ProcessingContext<?>> contexts;
            if (timeout > 0) {
                contexts = result.getProcessingResult().get(timeout, TimeUnit.MILLISECONDS);
            } else {
                contexts = result.getProcessingResult().get();
            }

            boolean hasError = contexts != null && contexts.stream().anyMatch(ProcessingContext::hasError);
            if (hasError) {
                log.warn("{} - Processing error for Pub/Sub message: topic=[{}], messageId={} — nacking",
                        tenant, topic, messageId);
                consumer.nack();
            } else {
                consumer.ack();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} - Processing interrupted for Pub/Sub message: topic=[{}], messageId={} — nacking",
                    tenant, topic, messageId);
            consumer.nack();
        } catch (Exception e) {
            log.error("{} - Processing failed for Pub/Sub message: topic=[{}], messageId={} — nacking",
                    tenant, topic, messageId, e);
            consumer.nack();
        }
    }

    private void stopSubscriberQuietly(Subscriber subscriber, String topic) {
        try {
            subscriber.stopAsync().awaitTerminated(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("{} - Error stopping Pub/Sub subscriber for topic: [{}]: {}", tenant, topic, e.getMessage());
        }
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

        Publisher created = retryOperation("Create Pub/Sub publisher for topic: " + topic,
                MAX_PUBLISHER_CREATE_RETRIES, PUBLISHER_CREATE_RETRY_DELAY_MS,
                () -> Publisher.newBuilder(TopicName.of(projectId, topic))
                        .setCredentialsProvider(credentialsProvider)
                        .build());

        Publisher existing = publishers.putIfAbsent(topic, created);
        if (existing != null) {
            created.shutdown();
            return existing;
        }

        return created;
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }
        if (!validateRequiredProperties(configuration, "projectId", "topicId")) {
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
        return Arrays.asList(Direction.INBOUND, Direction.OUTBOUND);
    }

    /**
     * Create Google Pub/Sub connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        return ConnectorSpecificationBuilder
                .create("Google Cloud Pub/Sub", ConnectorType.GOOGLE_PUBSUB)
                .description("Bidirectional connector for Google Cloud Pub/Sub. Publishes outbound Cumulocity data " +
                        "(Measurements, Alarms, Events, ...) to a Pub/Sub topic and subscribes to a Pub/Sub " +
                        "subscription to receive inbound messages. Inbound messages are dispatched through the " +
                        "configured inbound mappings. The service account must have roles/pubsub.publisher for " +
                        "outbound and roles/pubsub.subscriber for inbound usage.")
                .supportedDirections(supportedDirections())

                .property("projectId", ConnectorPropertyBuilder.requiredString()
                        .order(0)
                        .description("Google Cloud project ID that owns the Pub/Sub topic and subscription"))

                .property("topicId", ConnectorPropertyBuilder.requiredString()
                        .order(1)
                        .defaultValue("input-messages")
                        .description("Default Pub/Sub topic to publish to (outbound). A mapping's own publish topic, " +
                                "if set, overrides this per request."))

                .property("subscriptionId", ConnectorPropertyBuilder.create(ConnectorPropertyType.STRING_PROPERTY)
                        .required(false)
                        .order(2)
                        .description("Name of the pre-existing Pub/Sub subscription to consume from (inbound). " +
                                "Required when inbound mappings are configured. The subscription must be created " +
                                "in advance and bound to the inbound topic. " +
                                "The service account needs the roles/pubsub.subscriber role."))

                .property("authMode", ConnectorPropertyBuilder.create(ConnectorPropertyType.OPTION_PROPERTY)
                        .required(true)
                        .order(3)
                        .defaultValue("serviceAccountKey")
                        .optionsWithLabels(
                                "serviceAccountKey", "Service Account Key",
                                "applicationDefaultCredentials", "Application Default Credentials (ADC)")
                        .description("Authentication method used to connect to Google Cloud Pub/Sub."))

                .property("serviceAccountKey", ConnectorPropertyBuilder.create(ConnectorPropertyType.SENSITIVE_STRING_LARGE_PROPERTY)
                        .required(true)
                        .order(4)
                        .condition("authMode", "serviceAccountKey")
                        .description("Full JSON key of the Google Cloud Service Account used to authenticate " +
                                "against Pub/Sub (requires roles/pubsub.publisher and/or roles/pubsub.subscriber)."))

                .property("adcCredentialsJson", ConnectorPropertyBuilder.create(ConnectorPropertyType.SENSITIVE_STRING_LARGE_PROPERTY)
                        .required(false)
                        .order(5)
                        .condition("authMode", "applicationDefaultCredentials")
                        .description("Contents of the Application Default Credentials JSON file generated by " +
                                "'gcloud auth application-default login' (typically ~/.config/gcloud/application_default_credentials.json)."))

                .property("publishTimeoutSeconds", ConnectorPropertyBuilder.requiredNumeric()
                        .order(6)
                        .defaultValue(DEFAULT_PUBLISH_TIMEOUT_SECONDS)
                        .description("How long to wait for a publish acknowledgement before treating the request as failed."))

                .build();
    }
}
