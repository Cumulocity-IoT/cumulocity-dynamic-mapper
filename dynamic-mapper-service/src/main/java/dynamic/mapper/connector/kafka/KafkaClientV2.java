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
import dynamic.mapper.connector.core.ConnectorProperty;
import dynamic.mapper.connector.core.ConnectorPropertyCondition;
import dynamic.mapper.connector.core.ConnectorPropertyType;
import dynamic.mapper.connector.core.ConnectorSpecification;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
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

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
    private final Map<String, MutableInt> failedSubscriptions = new ConcurrentHashMap<>();

    @Getter
    protected List<Qos> supportedQOS;

    /**
     * Default constructor
     */
    public KafkaClientV2() {
        this.connectorType = ConnectorType.KAFKA;
        this.singleton = false;
        this.supportsMessageContext = true; // Supports context for HTTP methods
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
        log.info("{} - Connecting Kafka connector: {}", tenant, connectorName);

        if (!shouldConnect()) {
            log.info("{} - Connector disabled or invalid configuration", tenant);
            return;
        }

        try {
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTING, true, true);

            // Build properties
            buildKafkaProperties();

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

            connectionStateManager.setConnected(true);
            connectionStateManager.updateStatus(ConnectorStatus.CONNECTED, true, true);

            // Initialize subscriptions after successful connection
            if (isConnected()) {
                initializeSubscriptionsAfterConnect();
            }

            log.info("{} - Kafka connector connected successfully", tenant);

        } catch (Exception e) {
            log.error("{} - Error connecting Kafka connector: {}", tenant, e.getMessage(), e);
            connectionStateManager.updateStatusWithError(e);
            connectionStateManager.setConnected(false);
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
            consumer.subscribe(Collections.singletonList(topic));

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

        // Cancel consumer task
        Future<?> task = consumerTasks.remove(topic);
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }

        // Close consumer
        KafkaConsumerWrapper wrapper = topicConsumers.remove(topic);
        if (wrapper != null) {
            try {
                wrapper.getConsumer().close(Duration.ofSeconds(5));
                log.info("{} - Successfully unsubscribed from Kafka topic: [{}]", tenant, topic);
                sendSubscriptionEvents(topic, "Unsubscribed");
            } catch (Exception e) {
                log.warn("{} - Error closing Kafka consumer for topic: [{}]", tenant, topic, e);
            }
        }
    }

    /**
     * Consume messages from Kafka topic
     */
    private void consumeMessages(KafkaConsumerWrapper wrapper) {
        KafkaConsumer<String, String> consumer = wrapper.getConsumer();
        String topic = wrapper.getTopic();

        log.debug("{} - Starting message consumption for topic: [{}]", tenant, topic);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(CONSUMER_POLL_TIMEOUT_MS));

                for (ConsumerRecord<String, String> record : records) {
                    processKafkaMessage(record);
                }

                // Reset failed count on successful poll
                failedSubscriptions.remove(topic);

            } catch (Exception e) {
                log.error("{} - Error consuming messages from topic: [{}]", tenant, topic, e);
                handleConsumerError(topic, e);

                MutableInt failCount = failedSubscriptions.computeIfAbsent(topic, k -> new MutableInt(0));
                failCount.increment();

                if (failCount.intValue() > MAX_CONSECUTIVE_FAILURES) {
                    log.error("{} - Too many failures for topic: [{}], stopping consumer", tenant, topic);
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

        log.debug("{} - Stopped message consumption for topic: [{}]", tenant, topic);
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

        if (serviceConfiguration.isLogPayload()) {
            log.info("{} - INITIAL: Kafka message on topic: [{}], partition: {}, offset: {}, key: {}, connector: {}",
                    tenant, topic, record.partition(), record.offset(), key, connectorName);
        }

        ProcessingResultWrapper<?> processedResults = dispatcher.onMessage(connectorMessage);

        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getMaxCPUTimeMS();

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
     * Handle successful message processing
     */
    private void handleSuccessfulProcessing(ConsumerRecord<String, String> record) {
        // Manual commit if auto-commit is disabled
        Boolean autoCommit = (Boolean) kafkaConsumerProperties
                .getOrDefault("enable.auto.commit", "true").equals("true");

        if (!autoCommit) {
            KafkaConsumerWrapper wrapper = topicConsumers.get(record.topic());
            if (wrapper != null) {
                try {
                    Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = Collections.singletonMap(
                            new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1));
                    wrapper.getConsumer().commitSync(offsetsToCommit);

                    if (serviceConfiguration.isLogPayload()) {
                        log.debug("{} - Committed offset for topic: [{}], partition: {}, offset: {}",
                                tenant, record.topic(), record.partition(), record.offset() + 1);
                    }
                } catch (Exception e) {
                    log.error("{} - Error committing offset for topic: [{}]",
                            tenant, record.topic(), e);
                }
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
        MutableInt errorCount = failedSubscriptions.computeIfAbsent(errorKey, k -> new MutableInt(0));
        errorCount.increment();

        if (errorCount.intValue() > 10) {
            log.error("{} - Too many processing errors for topic: [{}], considering consumer restart",
                    tenant, record.topic());

            virtualThreadPool.submit(() -> {
                try {
                    restartConsumerForTopic(record.topic());
                    failedSubscriptions.remove(errorKey);
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
        log.info("{} - Disconnecting Kafka connector", tenant);
        connectionStateManager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        // Stop consumer tasks
        consumerTasks.values().forEach(task -> {
            if (!task.isDone()) {
                task.cancel(true);
            }
        });
        consumerTasks.clear();

        // Close consumers
        topicConsumers.values().forEach(wrapper -> {
            try {
                wrapper.getConsumer().close(Duration.ofSeconds(10));
            } catch (Exception e) {
                log.warn("{} - Error closing Kafka consumer: {}", tenant, e.getMessage());
            }
        });
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
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public boolean isConnected() {
        if (!connectionStateManager.isConnected() || kafkaProducer == null) {
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

        String topic = context.getResolvedPublishTopic();
        String payload = context.getCurrentRequest().getRequest();
        String key = context.getKey();

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);

        try {
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get(10, TimeUnit.SECONDS);

            if (context.getMapping().getDebug() || serviceConfiguration.isLogPayload()) {
                log.info("{} - Published to Kafka topic: [{}], partition: {}, offset: {}, mapping: {}",
                        tenant, topic, metadata.partition(), metadata.offset(), context.getMapping().getName());
            }

        } catch (Exception e) {
            String errorMessage = String.format("%s - Error publishing to Kafka topic: [%s]", tenant, topic);
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));
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
        Set<String> failedTopics = new HashSet<>(failedSubscriptions.keySet());

        for (String topic : failedTopics) {
            MutableInt failCount = failedSubscriptions.get(topic);
            if (failCount != null && failCount.intValue() > 0 && failCount.intValue() <= MAX_RETRY_ATTEMPTS) {
                log.warn("{} - Attempting to restart consumer for topic: [{}], fail count: {}",
                        tenant, topic, failCount.intValue());

                try {
                    restartConsumerForTopic(topic);
                    failedSubscriptions.remove(topic);
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
     * Helper class to manage Kafka consumers
     */
    private static class KafkaConsumerWrapper {
        @Getter
        private final KafkaConsumer<String, String> consumer;
        @Getter
        private final String topic;

        public KafkaConsumerWrapper(KafkaConsumer<String, String> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
        }
    }

    /**
     * Create Kafka connector specification
     */
    private ConnectorSpecification createConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new LinkedHashMap<>();

        ConnectorPropertyCondition saslCondition = new ConnectorPropertyCondition("username", new String[] { "*" });

        configProps.put("bootstrapServers",
                new ConnectorProperty(null, true, 0, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, null));

        configProps.put("username",
                new ConnectorProperty(null, false, 1, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, null));

        configProps.put("password",
                new ConnectorProperty(null, false, 2, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY,
                        false, false, null, null, saslCondition));

        configProps.put("saslMechanism",
                new ConnectorProperty(null, false, 3, ConnectorPropertyType.OPTION_PROPERTY,
                        false, false, "SCRAM-SHA-256",
                        Map.of("SCRAM-SHA-256", "SCRAM-SHA-256", "SCRAM-SHA-512", "SCRAM-SHA-512"),
                        saslCondition));

        configProps.put("groupId",
                new ConnectorProperty(null, true, 4, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, null));

        configProps.put("defaultPropertiesProducer",
                new ConnectorProperty("Producer properties", false, 5, ConnectorPropertyType.MAP_PROPERTY,
                        false, false, new HashMap<String, String>(), null, null));

        configProps.put("defaultPropertiesConsumer",
                new ConnectorProperty("Consumer properties", false, 7, ConnectorPropertyType.MAP_PROPERTY,
                        false, false, new HashMap<String, String>(), null, null));

        // Add predefined properties as read-only text
        try {
            StringWriter writerProducer = new StringWriter();
            defaultPropertiesProducer.store(writerProducer,
                    "properties can only be edited in the property file: kafka-producer.properties");
            configProps.put("propertiesProducer",
                    new ConnectorProperty("Predefined producer properties", false, 6,
                            ConnectorPropertyType.STRING_LARGE_PROPERTY,
                            true, false, removeDateCommentLine(writerProducer.getBuffer().toString()),
                            null, null));

            StringWriter writerConsumer = new StringWriter();
            defaultPropertiesConsumer.store(writerConsumer,
                    "properties can only be edited in the property file: kafka-consumer.properties");
            configProps.put("propertiesConsumer",
                    new ConnectorProperty("Predefined consumer properties", false, 8,
                            ConnectorPropertyType.STRING_LARGE_PROPERTY,
                            true, false, removeDateCommentLine(writerConsumer.getBuffer().toString()),
                            null, null));
        } catch (IOException e) {
            log.warn("Could not create properties display: {}", e.getMessage());
        }

        String name = "Kafka";
        String description = "Connector to receive and send messages to an external Kafka broker. " +
                "Inbound mappings allow to extract values from the payload and the key and map these to the Cumulocity payload. "
                +
                "The relevant setting in a mapping is 'supportsMessageContext'.\n" +
                "In outbound mappings any string that is mapped to '_CONTEXT_DATA_.key' is used as the outbound Kafka record key.\n"
                +
                "The connector uses SASL_SSL as security protocol.";

        return new ConnectorSpecification(
                name,
                description,
                ConnectorType.KAFKA,
                false,
                configProps,
                true, // supportsMessageContext
                supportedDirections());
    }

}