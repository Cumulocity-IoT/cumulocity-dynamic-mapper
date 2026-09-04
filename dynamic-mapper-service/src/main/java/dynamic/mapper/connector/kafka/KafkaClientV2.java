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

package dynamic.mapper.connector.kafka;

import com.cumulocity.sdk.client.SDKException;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import org.apache.kafka.common.errors.WakeupException;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kafka Connector Client.
 * Handles both inbound (consumer) and outbound (producer) Kafka operations.
 * Uses separate consumers per topic to handle failures independently.
 */
@Slf4j
public class KafkaClientV2 extends AConnectorClient {

    private static final long CONSUMER_POLL_TIMEOUT_MS = 1000;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long CONSUMER_RESTART_DELAY_MS = 5000;
    // How long unsubscribe()/disconnect() wait for a poll-loop thread to notice wakeup() and
    // exit before giving up on the wait (the thread itself still exits/closes shortly after).
    private static final long CONSUMER_SHUTDOWN_TIMEOUT_MS = 5000;

    private static final String KAFKA_CONSUMER_PROPERTIES = "/kafka-consumer.properties";
    private static final String KAFKA_PRODUCER_PROPERTIES = "/kafka-producer.properties";

    // Kafka clients
    private KafkaProducer<String, String> kafkaProducer;
    private AdminClient adminClient;

    // Properties
    private Properties defaultPropertiesConsumer;
    private Properties defaultPropertiesProducer;
    private Properties kafkaConsumerProperties;
    private Properties kafkaProducerProperties;

    // Consumer management
    private final Map<String, KafkaConsumerWrapper> topicConsumers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> consumerTasks = new ConcurrentHashMap<>();
    // Explorer sessions get their own consumers, tracked separately from topicConsumers/
    // consumerTasks, so an ad-hoc explorer subscription never shares a KafkaConsumerWrapper (or a
    // map slot) with a mapping's long-running one on the same topic — see subscribeExplorer().
    private final Map<String, KafkaConsumerWrapper> explorerConsumers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> explorerConsumerTasks = new ConcurrentHashMap<>();
    // Poll-loop failure counts, keyed by plain topic name — read by monitorSubscriptions() to
    // decide which topics are worth retrying.
    private final Map<String, MutableInt> pollFailureCounts = new ConcurrentHashMap<>();
    // Explorer consumers get their own failure-count bookkeeping, kept out of pollFailureCounts —
    // monitorSubscriptions() treats any entry there as a mapping-style consumer to restart via
    // subscribe() (the shared, persistent-group path), which would be wrong for an ephemeral
    // explorer consumer.
    private final Map<String, MutableInt> explorerPollFailureCounts = new ConcurrentHashMap<>();
    // Per-partition processing-error counts, keyed by "topic-partition" — purely internal to
    // handleProcessingError()'s own restart threshold; never read by monitorSubscriptions() (which
    // treats every key in its map as a bare topic name, so mixing the two key formats in one map
    // previously made monitorSubscriptions() try to "restart" a topic literally named
    // "myTopic-0").
    private final Map<String, MutableInt> processingErrorCounts = new ConcurrentHashMap<>();

    @Getter
    protected List<Qos> supportedQOS;

    /**
     * Default constructor
     */
    public KafkaClientV2() {
        this.connectorType = ConnectorType.KAFKA;
        this.singleton = false;
        this.supportedQOS = Arrays.asList(Qos.AT_MOST_ONCE); // Kafka doesn't have MQTT-like QoS
        loadDefaultProperties();
        this.connectorSpecification = createConnectorSpecification();
    }

    /**
     * Full constructor with dependencies
     */
    public KafkaClientV2(ConfigurationRegistry configurationRegistry,
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

    /**
     * Load default properties from classpath resources
     */
    private void loadDefaultProperties() {
        try {
            Resource resourceProducer = new ClassPathResource(KAFKA_PRODUCER_PROPERTIES);
            defaultPropertiesProducer = PropertiesLoaderUtils.loadProperties(resourceProducer);

            Resource resourceConsumer = new ClassPathResource(KAFKA_CONSUMER_PROPERTIES);
            defaultPropertiesConsumer = PropertiesLoaderUtils.loadProperties(resourceConsumer);

            log.debug("Loaded default Kafka properties from classpath");
        } catch (IOException e) {
            log.warn("Could not load default Kafka properties, using minimal defaults: {}", e.getMessage());
            defaultPropertiesProducer = new Properties();
            defaultPropertiesConsumer = new Properties();
        }
    }

    /**
     * Remove date comment line from properties string
     */
    private static String removeDateCommentLine(String pt) {
        String regex = "(?m)^[ ]*#.*$(\r?\n)?";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(pt);

        int count = 0;
        while (matcher.find()) {
            count++;
            if (count == 2) {
                break;
            }
        }

        if (count == 2) {
            return pt.substring(0, matcher.start()) + pt.substring(matcher.end());
        }
        return pt;
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            buildKafkaProperties();

            // Initialize admin client
            adminClient = AdminClient.create(kafkaProducerProperties);

            // Test connection
            ListTopicsResult listTopics = adminClient.listTopics();
            Set<String> topics = listTopics.names().get(10, TimeUnit.SECONDS);

            log.info("{} - Kafka connector initialized successfully, found {} topics",
                    tenant, topics.size());
            if (isConfigValid(connectorConfiguration)) {
                connectionStateManager.updateStatus(ConnectorStatus.CONFIGURED, true, true);
            }
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing Kafka connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            return false;
        }
    }

    /**
     * Build Kafka properties from configuration
     */
    private void buildKafkaProperties() {
        Properties consumerProps = new Properties();
        if (defaultPropertiesConsumer != null) {
            consumerProps.putAll(defaultPropertiesConsumer);
        }

        Properties producerProps = new Properties();
        if (defaultPropertiesProducer != null) {
            producerProps.putAll(defaultPropertiesProducer);
        }

        // Get configuration values
        String bootstrapServers = (String) connectorConfiguration.getProperties().get("bootstrapServers");
        String username = (String) connectorConfiguration.getProperties().get("username");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String saslMechanism = (String) connectorConfiguration.getProperties()
                .getOrDefault("saslMechanism", "SCRAM-SHA-256");
        String groupId = (String) connectorConfiguration.getProperties().get("groupId");

        // Generate default groupId if not provided
        if (groupId == null || groupId.trim().isEmpty()) {
            groupId = "dynamic-mapper-" + connectorIdentifier +
                    (additionalSubscriptionIdTest != null ? additionalSubscriptionIdTest : "");
            log.info("{} - No groupId provided, using default: {}", tenant, groupId);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> customProducerProps = (Map<String, String>) connectorConfiguration.getProperties()
                .get("defaultPropertiesProducer");
        @SuppressWarnings("unchecked")
        Map<String, String> customConsumerProps = (Map<String, String>) connectorConfiguration.getProperties()
                .get("defaultPropertiesConsumer");

        // Apply common settings
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // Configure security if credentials provided
        if (username != null && !username.trim().isEmpty() &&
                password != null && !password.trim().isEmpty()) {

            log.info("{} - Configuring SASL authentication with mechanism: {}", tenant, saslMechanism);

            String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
            String jaasCfg = String.format(jaasTemplate, username, password);

            consumerProps.put("sasl.jaas.config", jaasCfg);
            consumerProps.put("sasl.mechanism", saslMechanism);
            consumerProps.put("security.protocol", "SASL_SSL");

            producerProps.put("sasl.jaas.config", jaasCfg);
            producerProps.put("sasl.mechanism", saslMechanism);
            producerProps.put("security.protocol", "SASL_SSL");
        } else {
            log.info("{} - Using PLAINTEXT security protocol (no authentication)", tenant);
            consumerProps.put("security.protocol", "PLAINTEXT");
            producerProps.put("security.protocol", "PLAINTEXT");
        }

        // Add serializers/deserializers
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Apply custom properties last (they override defaults)
        if (customConsumerProps != null) {
            log.info("{} - Applying {} custom consumer properties", tenant, customConsumerProps.size());
            consumerProps.putAll(customConsumerProps);
        }
        if (customProducerProps != null) {
            log.info("{} - Applying {} custom producer properties", tenant, customProducerProps.size());
            producerProps.putAll(customProducerProps);
        }

        this.kafkaConsumerProperties = consumerProps;
        this.kafkaProducerProperties = producerProps;

        log.debug("{} - Kafka properties configured for: {}", tenant, bootstrapServers);
    }

    @Override
    public void connect() {
        if (!beginConnection()) {
            return;
        }

        try {
            log.info("{} - Connecting Kafka connector: {}", tenant, connectorName);

            if (!shouldConnect()) {
                log.info("{} - Connector disabled or invalid configuration", tenant);
                return;
            }

            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            // Build properties
            buildKafkaProperties();

            // Create and test connectivity with retry logic
            retryOperation("Kafka connection", 3, 2000, () -> {
                // Test connectivity with admin client
                if (adminClient == null) {
                    adminClient = AdminClient.create(kafkaProducerProperties);
                }

                log.info("{} - Testing Kafka connectivity...", tenant);
                ListTopicsResult listTopics = adminClient.listTopics();
                listTopics.names().get(10, TimeUnit.SECONDS);
                log.info("{} - Kafka connectivity test passed", tenant);

                // Create producer
                kafkaProducer = new KafkaProducer<>(kafkaProducerProperties);
                return null;
            });

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

            log.info("{} - Kafka connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting Kafka connector: {}", tenant, e.getMessage(), e);
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
    protected void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Kafka connector is not connected");
        }

        log.debug("{} - Subscribing to Kafka topic: [{}]", tenant, topic);

        try {
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(kafkaConsumerProperties);
            if (isMqttWildcardTopic(topic)) {
                consumer.subscribe(mqttWildcardToPattern(topic));
            } else {
                consumer.subscribe(Collections.singletonList(topic));
            }

            KafkaConsumerWrapper wrapper = new KafkaConsumerWrapper(consumer, topic);
            topicConsumers.put(topic, wrapper);

            // Start consumer task
            Future<?> consumerTask = virtualThreadPool.submit(() -> consumeMessages(wrapper));
            consumerTasks.put(topic, consumerTask);

            log.info("{} - Successfully subscribed to Kafka topic: [{}]", tenant, topic);
            sendSubscriptionEvents(topic, "Subscribed");

        } catch (Exception e) {
            throw new ConnectorException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    protected void unsubscribe(String topic) throws ConnectorException {
        log.debug("{} - Unsubscribing from Kafka topic: [{}]", tenant, topic);

        KafkaConsumerWrapper wrapper = topicConsumers.remove(topic);
        Future<?> task = consumerTasks.remove(topic);

        if (wrapper != null) {
            // Signal the owning poll-loop thread to stop; only that thread may ever call
            // poll()/commitSync()/close() on the consumer (KafkaConsumer is not thread-safe) —
            // see consumeMessages(), which does the actual close in its finally block.
            wrapper.requestClose();
        }

        if (task != null) {
            try {
                task.get(CONSUMER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("{} - Timed out waiting for consumer task to stop for topic: [{}]; " +
                        "it will finish closing shortly on its own", tenant, topic);
            } catch (Exception e) {
                log.debug("{} - Consumer task for topic [{}] ended while unsubscribing: {}",
                        tenant, topic, e.getMessage());
            }
        }

        log.info("{} - Successfully unsubscribed from Kafka topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribed");
    }

    /**
     * Explorer sessions must not share {@link #kafkaConsumerProperties}' static, connector-wide
     * {@code group.id} — Kafka persists committed offsets per group, so a second explorer session
     * (or a mapping) reusing that group would silently resume from wherever the previous
     * subscriber left off instead of tailing from "now", and concurrent sessions on the same topic
     * would split partitions between them via normal consumer-group rebalancing instead of each
     * seeing every message. Use a fresh, never-reused group id per explorer subscription instead.
     */
    @Override
    protected void subscribeExplorer(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Kafka connector is not connected");
        }

        log.debug("{} - Subscribing (explorer) to Kafka topic: [{}]", tenant, topic);

        try {
            Properties explorerProps = new Properties();
            explorerProps.putAll(kafkaConsumerProperties);
            String ephemeralGroupId = "dynamic-mapper-explorer-" + UUID.randomUUID();
            explorerProps.put(ConsumerConfig.GROUP_ID_CONFIG, ephemeralGroupId);
            explorerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(explorerProps);
            if (isMqttWildcardTopic(topic)) {
                consumer.subscribe(mqttWildcardToPattern(topic));
            } else {
                consumer.subscribe(Collections.singletonList(topic));
            }

            KafkaConsumerWrapper wrapper = new KafkaConsumerWrapper(consumer, topic);
            explorerConsumers.put(topic, wrapper);

            Future<?> consumerTask = virtualThreadPool.submit(
                    () -> consumeMessages(wrapper, explorerConsumers, explorerConsumerTasks, explorerPollFailureCounts));
            explorerConsumerTasks.put(topic, consumerTask);

            log.info("{} - Successfully subscribed (explorer) to Kafka topic: [{}] with ephemeral group [{}]",
                    tenant, topic, ephemeralGroupId);

        } catch (Exception e) {
            throw new ConnectorException("Failed to subscribe (explorer) to topic: " + topic, e);
        }
    }

    @Override
    protected void unsubscribeExplorer(String topic) throws ConnectorException {
        log.debug("{} - Unsubscribing (explorer) from Kafka topic: [{}]", tenant, topic);

        KafkaConsumerWrapper wrapper = explorerConsumers.remove(topic);
        Future<?> task = explorerConsumerTasks.remove(topic);

        if (wrapper != null) {
            wrapper.requestClose();
        }

        if (task != null) {
            try {
                task.get(CONSUMER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("{} - Timed out waiting for explorer consumer task to stop for topic: [{}]; " +
                        "it will finish closing shortly on its own", tenant, topic);
            } catch (Exception e) {
                log.debug("{} - Explorer consumer task for topic [{}] ended while unsubscribing: {}",
                        tenant, topic, e.getMessage());
            }
        }

        log.info("{} - Successfully unsubscribed (explorer) from Kafka topic: [{}]", tenant, topic);
    }

    /** Returns {@code true} if the topic filter uses MQTT-style wildcards ({@code +}, {@code #}). */
    static boolean isMqttWildcardTopic(String topic) {
        return topic != null && (topic.contains("+") || topic.contains("#"));
    }

    /**
     * Translates an MQTT-style topic filter ({@code +} matches a single {@code /}-delimited
     * level, {@code #} matches the rest) into a Java regex {@link Pattern} suitable for
     * {@link KafkaConsumer#subscribe(Pattern)}, so explorer sessions and mappings can subscribe
     * to Kafka topics using the same wildcard syntax used for MQTT.
     */
    static Pattern mqttWildcardToPattern(String mqttFilter) {
        String[] segments = mqttFilter.split("/", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i > 0) {
                regex.append('/');
            }
            if ("#".equals(segment)) {
                regex.append(".*");
                break;
            } else if ("+".equals(segment)) {
                regex.append("[^/]+");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    /**
     * Consume messages from Kafka topic.
     * <p>
     * This method's thread is the sole owner of {@code wrapper.getConsumer()} for its entire
     * lifetime: it is the only thread that ever calls {@code poll()}, {@code commitSync()}, or
     * {@code close()} on it. Other threads request work (committing an offset, closing the
     * consumer) via {@link KafkaConsumerWrapper#requestCommit} / {@link KafkaConsumerWrapper#requestClose},
     * which this loop drains/honours on its own thread.
     */
    private void consumeMessages(KafkaConsumerWrapper wrapper) {
        consumeMessages(wrapper, topicConsumers, consumerTasks, pollFailureCounts);
    }

    /**
     * @param consumersMap    map this consumer's wrapper was registered in by the caller (e.g.
     *                        {@link #topicConsumers} or {@link #explorerConsumers}); cleared on exit.
     * @param tasksMap        the matching task map (e.g. {@link #consumerTasks} or
     *                        {@link #explorerConsumerTasks}); cleared on exit.
     * @param failureCountsMap failure-count bookkeeping to use; kept separate for explorer
     *                        consumers so {@link #monitorSubscriptions()} never mistakes an
     *                        ephemeral explorer consumer for a mapping-style one to restart.
     */
    private void consumeMessages(KafkaConsumerWrapper wrapper, Map<String, KafkaConsumerWrapper> consumersMap,
            Map<String, Future<?>> tasksMap, Map<String, MutableInt> failureCountsMap) {
        KafkaConsumer<String, String> consumer = wrapper.getConsumer();
        String topic = wrapper.getTopic();

        log.debug("{} - Starting message consumption for topic: [{}]", tenant, topic);

        try {
            while (!wrapper.isCloseRequested()) {
                try {
                    drainPendingCommits(wrapper);

                    ConsumerRecords<String, String> records = consumer
                            .poll(Duration.ofMillis(CONSUMER_POLL_TIMEOUT_MS));

                    for (ConsumerRecord<String, String> record : records) {
                        processKafkaMessage(record);
                    }

                    // Reset failed count on successful poll
                    failureCountsMap.remove(topic);

                } catch (WakeupException we) {
                    // Only triggered by our own requestClose(); the while condition will exit next.
                    break;
                } catch (Exception e) {
                    log.error("{} - Error consuming messages from topic: [{}]", tenant, topic, e);
                    handleConsumerError(topic, e);

                    MutableInt failCount = failureCountsMap.computeIfAbsent(topic, k -> new MutableInt(0));
                    failCount.increment();

                    if (failCount.intValue() > MAX_CONSECUTIVE_FAILURES) {
                        log.error("{} - Too many consecutive failures for topic: [{}], stopping this consumer; " +
                                "housekeeping will retry it", tenant, topic);
                        // Hand off to the slower housekeeping-driven retry cycle (monitorSubscriptions())
                        // instead of leaving the count above MAX_RETRY_ATTEMPTS, where it would never
                        // be picked up again.
                        failCount.setValue(1);
                        break;
                    }

                    try {
                        Thread.sleep(CONSUMER_RESTART_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            drainPendingCommits(wrapper);
            try {
                consumer.close();
            } catch (Exception e) {
                log.warn("{} - Error closing Kafka consumer for topic: [{}]", tenant, topic, e);
            }
            // Conditional remove: don't clobber a newer wrapper/task a concurrent subscribe()
            // may already have installed for this topic.
            consumersMap.remove(topic, wrapper);
            tasksMap.remove(topic);
            log.debug("{} - Stopped message consumption for topic: [{}]", tenant, topic);
        }
    }

    /**
     * Commit any offsets queued via {@link KafkaConsumerWrapper#requestCommit}. Must only ever be
     * called from the consumer's own owning poll-loop thread (see {@link #consumeMessages}).
     */
    private void drainPendingCommits(KafkaConsumerWrapper wrapper) {
        Map<TopicPartition, OffsetAndMetadata> toCommit = wrapper.drainPendingCommits();
        if (toCommit.isEmpty()) {
            return;
        }
        try {
            wrapper.getConsumer().commitSync(toCommit);
            if (serviceConfiguration.getLogPayload()) {
                log.debug("{} - Committed {} offset(s) for topic: [{}]", tenant, toCommit.size(), wrapper.getTopic());
            }
        } catch (Exception e) {
            log.error("{} - Error committing offsets for topic: [{}]", tenant, wrapper.getTopic(), e);
        }
    }

    /**
     * Process individual Kafka message
     */
    private void processKafkaMessage(ConsumerRecord<String, String> record) {
        String topic = record.topic();
        String value = record.value();
        String key = record.key();
        byte[] payloadBytes = value != null ? value.getBytes() : null;

        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .key(key)
                .build();

        if (serviceConfiguration.getLogPayload()) {
            log.info("{} - INITIAL: Kafka message on topic: [{}], partition: {}, offset: {}, key: {}, connector: {}",
                    tenant, topic, record.partition(), record.offset(), key, connectorName);
        }

        ProcessingResultWrapper<?> processedResults = dispatcher.onMessage(connectorMessage);

        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getPipelineTimeoutMS();

        if (mappingQos > 0) {
            virtualThreadPool.submit(() -> processMessageWithQos(record, processedResults, timeout));
        } else {
            handleSuccessfulProcessing(record);
        }
    }

    /**
     * Process message with QoS handling
     */
    private Void processMessageWithQos(ConsumerRecord<String, String> record,
            ProcessingResultWrapper<?> processedResults,
            int timeout) {
        String topic = record.topic();

        try {
            List<? extends ProcessingContext<?>> results;
            if (timeout > 0) {
                results = processedResults.getProcessingResult().get(timeout, TimeUnit.MILLISECONDS);
            } else {
                results = processedResults.getProcessingResult().get();
            }

            boolean hasErrors = false;
            int httpStatusCode = 0;

            if (results != null) {
                for (ProcessingContext<?> context : results) {
                    if (context.hasError()) {
                        for (Exception error : context.getErrors()) {
                            if (error instanceof ProcessingException) {
                                Throwable origin = ((ProcessingException) error).getOriginException();
                                if (origin instanceof SDKException) {
                                    int status = ((SDKException) origin).getHttpStatus();
                                    if (status > httpStatusCode) {
                                        httpStatusCode = status;
                                    }
                                }
                            }
                        }
                        hasErrors = true;
                        break;
                    }
                }
            }

            if (!hasErrors || httpStatusCode < 500) {
                handleSuccessfulProcessing(record);
            } else {
                handleProcessingError(record, httpStatusCode);
            }

        } catch (InterruptedException | ExecutionException e) {
            log.warn("{} - Processing interrupted for topic: [{}], offset: {}",
                    tenant, topic, record.offset(), e);
            handleProcessingError(record, 0);
        } catch (TimeoutException e) {
            processedResults.getProcessingResult().cancel(true);
            log.warn("{} - Processing timed out for topic: [{}], offset: {}",
                    tenant, topic, record.offset());
            handleProcessingTimeout(record);
        }

        return null;
    }

    /**
     * Handle successful message processing.
     * <p>
     * Queues the offset to commit rather than committing directly: this method can run either on
     * the topic's own poll-loop thread (QoS 0, called synchronously from {@link #processKafkaMessage})
     * or on a separate virtual thread (QoS &gt; 0, called from {@link #processMessageWithQos}) —
     * calling {@code commitSync} directly from the latter would touch the KafkaConsumer from a
     * foreign thread, which is not thread-safe. The queued offset is committed by
     * {@link #drainPendingCommits} on the consumer's own thread on its next loop iteration.
     */
    private void handleSuccessfulProcessing(ConsumerRecord<String, String> record) {
        // Manual commit if auto-commit is disabled
        Boolean autoCommit = (Boolean) kafkaConsumerProperties
                .getOrDefault("enable.auto.commit", "true").equals("true");

        if (!autoCommit) {
            KafkaConsumerWrapper wrapper = topicConsumers.get(record.topic());
            if (wrapper != null) {
                wrapper.requestCommit(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1));
            }
        }
    }

    /**
     * Handle processing error
     */
    private void handleProcessingError(ConsumerRecord<String, String> record, int httpStatusCode) {
        log.error("{} - Processing error for topic: [{}], partition: {}, offset: {}, HTTP status: {}",
                tenant, record.topic(), record.partition(), record.offset(), httpStatusCode);

        String errorKey = record.topic() + "-" + record.partition();
        MutableInt errorCount = processingErrorCounts.computeIfAbsent(errorKey, k -> new MutableInt(0));
        errorCount.increment();

        if (errorCount.intValue() > 10) {
            log.error("{} - Too many processing errors for topic: [{}], considering consumer restart",
                    tenant, record.topic());

            virtualThreadPool.submit(() -> {
                try {
                    restartConsumerForTopic(record.topic());
                    processingErrorCounts.remove(errorKey);
                } catch (ConnectorException e) {
                    log.error("{} - Failed to restart consumer for topic: [{}]", tenant, record.topic(), e);
                }
            });
        }
    }

    /**
     * Handle processing timeout
     */
    private void handleProcessingTimeout(ConsumerRecord<String, String> record) {
        log.warn("{} - Processing timeout for topic: [{}], partition: {}, offset: {}",
                tenant, record.topic(), record.partition(), record.offset());
        handleProcessingError(record, 0);
    }

    /**
     * Handle consumer error
     */
    private void handleConsumerError(String topic, Exception e) {
        if (e instanceof KafkaException) {
            log.error("{} - Kafka error for topic [{}]: {}", tenant, topic, e.getMessage());
        } else {
            log.error("{} - Unexpected error for topic [{}]: {}", tenant, topic, e.getMessage(), e);
        }
    }

    /**
     * Restart consumer for a topic
     */
    private void restartConsumerForTopic(String topic) throws ConnectorException {
        try {
            unsubscribe(topic);
        } catch (Exception e) {
            log.warn("{} - Error stopping consumer for topic: [{}]", tenant, topic, e);
        }

        subscribe(topic, Qos.AT_MOST_ONCE);
    }

    @Override
    public void disconnect() {
        if (!beginDisconnection()) {
            return;
        }

        try {
            log.info("{} - Disconnecting Kafka connector", tenant);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

            // Signal every poll-loop thread to stop and close its own consumer (see
            // consumeMessages()'s finally block) — never call KafkaConsumer methods from this
            // thread, it is not safe for multi-threaded access.
            topicConsumers.values().forEach(KafkaConsumerWrapper::requestClose);
            for (Future<?> task : consumerTasks.values()) {
                try {
                    task.get(CONSUMER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    log.debug("{} - Consumer task did not stop cleanly during disconnect: {}",
                            tenant, e.getMessage());
                }
            }
            consumerTasks.clear();
            topicConsumers.clear();

            // Close producer
            if (kafkaProducer != null) {
                try {
                    kafkaProducer.close(Duration.ofSeconds(10));
                } catch (Exception e) {
                    log.warn("{} - Error closing Kafka producer: {}", tenant, e.getMessage());
                }
            }

            // Close admin client
            if (adminClient != null) {
                try {
                    adminClient.close(Duration.ofSeconds(10));
                } catch (Exception e) {
                    log.warn("{} - Error closing Kafka admin client: {}", tenant, e.getMessage());
                }
            }

            connectionStateManager.setConnected(false);
            connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTED, true, true);

            log.info("{} - Kafka connector disconnected", tenant);

        } finally {
            endDisconnection();
        }
    }

    @Override
    protected boolean isPhysicallyConnected() {
        if (kafkaProducer == null) {
            return false;
        }

        // Test actual connectivity
        try {
            if (adminClient != null) {
                adminClient.listTopics().names().get(1, TimeUnit.SECONDS);
                return true;
            }
        } catch (Exception e) {
            log.warn("{} - Kafka broker connectivity test failed: {}", tenant, e.getMessage());
            connectionStateManager.setConnected(false);
            return false;
        }

        return true;
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (kafkaProducer == null) {
            log.error("{} - Kafka producer is not initialized", tenant);
            return;
        }

        var requests = context.getRequests();
        if (requests == null || requests.isEmpty()) {
            log.warn("{} - No requests to publish for mapping: {}", tenant, context.getMapping().getName());
            return;
        }

        String key = context.getKey();

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

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);

            try {
                Future<RecordMetadata> future = kafkaProducer.send(record);
                RecordMetadata metadata = future.get(10, TimeUnit.SECONDS);

                if (context.getMapping().getDebug() || serviceConfiguration.getLogPayload()) {
                    log.info("{} - Published to Kafka ({}/{}): topic=[{}], partition: {}, offset: {}, mapping: {}",
                            tenant, i + 1, requests.size(), topic, metadata.partition(), metadata.offset(), context.getMapping().getName());
                } else {
                    log.debug("{} - Published to Kafka ({}/{}): topic=[{}]", tenant, i + 1, requests.size(), topic);
                }

            } catch (Exception e) {
                String errorMessage = String.format("%s - Error publishing to Kafka topic: [%s] (%d/%d)", tenant, topic, i + 1, requests.size());
                log.error(errorMessage, e);
                request.setError(e);
                context.addError(new ProcessingException(errorMessage, e));
            }
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        String bootstrapServers = (String) configuration.getProperties().get("bootstrapServers");
        if (bootstrapServers == null || bootstrapServers.trim().isEmpty()) {
            return false;
        }

        String username = (String) configuration.getProperties().get("username");
        String password = (String) configuration.getProperties().get("password");

        if (username != null && !username.trim().isEmpty()) {
            if (password == null || password.trim().isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        return false; // Kafka doesn't support wildcards
    }

    @Override
    public void monitorSubscriptions() {
        Set<String> failedTopics = new HashSet<>(pollFailureCounts.keySet());

        for (String topic : failedTopics) {
            MutableInt failCount = pollFailureCounts.get(topic);
            if (failCount != null && failCount.intValue() > 0 && failCount.intValue() <= MAX_RETRY_ATTEMPTS) {
                log.warn("{} - Attempting to restart consumer for topic: [{}], fail count: {}",
                        tenant, topic, failCount.intValue());

                try {
                    restartConsumerForTopic(topic);
                    pollFailureCounts.remove(topic);
                    log.info("{} - Successfully restarted consumer for topic: [{}]", tenant, topic);
                } catch (Exception e) {
                    log.error("{} - Failed to restart consumer for topic: [{}]", tenant, topic, e);
                }
            }
        }
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Clean up completed tasks
        consumerTasks.entrySet().removeIf(entry -> {
            if (entry.getValue().isDone() || entry.getValue().isCancelled()) {
                log.debug("{} - Cleaning up completed consumer task for topic: [{}]", tenant, entry.getKey());
                return true;
            }
            return false;
        });

        // Log consumer health
        if (log.isDebugEnabled()) {
            topicConsumers
                    .forEach((topic, wrapper) -> log.debug("{} - Consumer for topic [{}] is active", tenant, topic));
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
     * Helper class to manage a Kafka consumer.
     * <p>
     * The wrapped {@link KafkaConsumer} is only ever touched by the single poll-loop thread that
     * owns it (see {@link #consumeMessages}) — KafkaConsumer is explicitly not safe for
     * multi-threaded access. Other threads that want to commit an offset or close the consumer go
     * through {@link #requestCommit}/{@link #requestClose}, which queue the request (or, for
     * close, use {@code consumer.wakeup()} — the one KafkaConsumer method documented as safe to
     * call from another thread) for the owning thread to act on.
     */
    private static class KafkaConsumerWrapper {
        @Getter
        private final KafkaConsumer<String, String> consumer;
        @Getter
        private final String topic;
        private final Map<TopicPartition, OffsetAndMetadata> pendingCommits = new ConcurrentHashMap<>();
        private final AtomicBoolean closeRequested = new AtomicBoolean(false);

        public KafkaConsumerWrapper(KafkaConsumer<String, String> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
        }

        /** Queue an offset to be committed by the owning poll-loop thread. */
        void requestCommit(TopicPartition partition, OffsetAndMetadata offset) {
            pendingCommits.merge(partition, offset,
                    (existing, incoming) -> incoming.offset() > existing.offset() ? incoming : existing);
        }

        /** Drain and return all queued offsets. Must only be called from the owning thread. */
        Map<TopicPartition, OffsetAndMetadata> drainPendingCommits() {
            if (pendingCommits.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<TopicPartition, OffsetAndMetadata> drained = new HashMap<>();
            for (TopicPartition partition : new ArrayList<>(pendingCommits.keySet())) {
                OffsetAndMetadata offset = pendingCommits.remove(partition);
                if (offset != null) {
                    drained.put(partition, offset);
                }
            }
            return drained;
        }

        boolean isCloseRequested() {
            return closeRequested.get();
        }

        /** Signal the owning poll-loop thread to stop and close the consumer itself. */
        void requestClose() {
            closeRequested.set(true);
            consumer.wakeup();
        }
    }

    /**
     * Create Kafka connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        // Create builder-based specification
        ConnectorSpecificationBuilder builder = ConnectorSpecificationBuilder
                .create("Kafka", ConnectorType.KAFKA)
                .description("Connector to receive and send messages to an external Kafka broker. " +
                        "Inbound mappings allow to extract values from the payload and the key and map these to the Cumulocity payload. " +
                        "The relevant setting in a mapping is 'supportsMessageContext'.\n" +
                        "In outbound mappings any string that is mapped to '_CONTEXT_DATA_.key' is used as the outbound Kafka record key.\n" +
                        "The connector uses SASL_SSL as security protocol.")
                .supportsMessageContext(true)
                .supportedDirections(supportedDirections())

                // Basic connection
                .property("bootstrapServers", ConnectorPropertyBuilder.requiredString()
                        .order(0))

                // SASL authentication (optional)
                .property("username", ConnectorPropertyBuilder.optionalString()
                        .order(1))

                .property("password", ConnectorPropertyBuilder.optionalSensitive()
                        .order(2)
                        .condition("username", "*"))

                .property("saslMechanism", ConnectorPropertyBuilder.optionalOption()
                        .order(3)
                        .defaultValue("SCRAM-SHA-256")
                        .options("SCRAM-SHA-256", "SCRAM-SHA-512")
                        .condition("username", "*"))

                // Consumer group
                .property("groupId", ConnectorPropertyBuilder.requiredString()
                        .order(4))

                // Custom properties
                .property("defaultPropertiesProducer", ConnectorPropertyBuilder.create(ConnectorPropertyType.MAP_PROPERTY)
                        .order(5)
                        .description("Producer properties")
                        .required(false)
                        .defaultValue(new HashMap<String, String>()))

                .property("defaultPropertiesConsumer", ConnectorPropertyBuilder.create(ConnectorPropertyType.MAP_PROPERTY)
                        .order(7)
                        .description("Consumer properties")
                        .required(false)
                        .defaultValue(new HashMap<String, String>()));

        // Add predefined properties as read-only text
        try {
            StringWriter writerProducer = new StringWriter();
            defaultPropertiesProducer.store(writerProducer,
                    "properties can only be edited in the property file: kafka-producer.properties");
            builder.property("propertiesProducer", ConnectorPropertyBuilder.largeText()
                    .order(6)
                    .description("Predefined producer properties")
                    .readonly(true)
                    .defaultValue(removeDateCommentLine(writerProducer.getBuffer().toString())));

            StringWriter writerConsumer = new StringWriter();
            defaultPropertiesConsumer.store(writerConsumer,
                    "properties can only be edited in the property file: kafka-consumer.properties");
            builder.property("propertiesConsumer", ConnectorPropertyBuilder.largeText()
                    .order(8)
                    .description("Predefined consumer properties")
                    .readonly(true)
                    .defaultValue(removeDateCommentLine(writerConsumer.getBuffer().toString())));
        } catch (IOException e) {
            log.warn("Could not create properties display: {}", e.getMessage());
        }

        return builder.build();
    }

}