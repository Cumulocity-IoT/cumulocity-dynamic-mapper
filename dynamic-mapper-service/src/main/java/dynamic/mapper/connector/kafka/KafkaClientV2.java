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

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.core.ConnectorStatusEvent;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.CamelDispatcherInbound;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;

@Slf4j
public class KafkaClientV2 extends AConnectorClient {

    private static final long CONSUMER_POLL_TIMEOUT_MS = 1000;

    // Kafka clients
    private KafkaProducer<String, String> kafkaProducer;
    private AdminClient adminClient;

    private String KAFKA_CONSUMER_PROPERTIES = "/kafka-consumer.properties";
    private String KAFKA_PRODUCER_PROPERTIES = "/kafka-producer.properties";

    private Properties defaultPropertiesConsumer;
    private Properties defaultPropertiesProducer;

    // Consumer management
    private final Map<String, KafkaConsumerWrapper> topicConsumers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> consumerTasks = new ConcurrentHashMap<>();
    private final Map<String, MutableInt> failedSubscriptions = new ConcurrentHashMap<>();

    @Getter
    protected List<Qos> supportedQOS;

    // Configuration properties
    private Properties kafkaProperties;

    public KafkaClientV2() {
        initializeConnectorSpecification();
        this.supportedQOS = Arrays.asList(Qos.AT_MOST_ONCE); // Kafka doesn't support QoS like MQTT
        this.connectorType = ConnectorType.KAFKA_V2;
        this.singleton = false;
    }

    public KafkaClientV2(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            CamelDispatcherInbound dispatcher,
            String additionalSubscriptionIdTest,
            String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingService = configurationRegistry.getMappingService();
        this.serviceConfigurationService = configurationRegistry.getServiceConfigurationService();
        this.connectorConfigurationService = configurationRegistry.getConnectorConfigurationService();
        this.connectorConfiguration = connectorConfiguration;
        this.connectorName = connectorConfiguration.name;
        this.connectorIdentifier = connectorConfiguration.identifier;
        this.connectorId = new ConnectorId(connectorConfiguration.name,
                connectorConfiguration.identifier,
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
    }

    private void initializeConnectorSpecification() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();

        // Security condition for SASL properties - only when username/password are used
        ConnectorPropertyCondition saslCondition = new ConnectorPropertyCondition("username",
                new String[] { "*" }); // Any non-null username value

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
                        Map.of("SCRAM-SHA-256", "SCRAM-SHA-256",
                                "SCRAM-SHA-512", "SCRAM-SHA-512"),
                        saslCondition));

        configProps.put("groupId",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY,
                        false, false, null, null, null));

        // Load default properties from files like the original implementation
        try {
            Resource resourceProducer = new ClassPathResource(KAFKA_PRODUCER_PROPERTIES);
            defaultPropertiesProducer = PropertiesLoaderUtils.loadProperties(resourceProducer);
            StringWriter writerProducer = new StringWriter();
            defaultPropertiesProducer.store(writerProducer,
                    "properties can only be edited in the property file: kafka-producer.properties");
            configProps.put("propertiesProducer",
                    new ConnectorProperty(null, false, 5, ConnectorPropertyType.STRING_LARGE_PROPERTY,
                            true, false, removeDateCommentLine(writerProducer.getBuffer().toString()),
                            null, null));

            Resource resourceConsumer = new ClassPathResource(KAFKA_CONSUMER_PROPERTIES);
            defaultPropertiesConsumer = PropertiesLoaderUtils.loadProperties(resourceConsumer);
            StringWriter writerConsumer = new StringWriter();
            defaultPropertiesConsumer.store(writerConsumer,
                    "properties can only be edited in the property file: kafka-consumer.properties");
            configProps.put("propertiesConsumer",
                    new ConnectorProperty(null, false, 6, ConnectorPropertyType.STRING_LARGE_PROPERTY,
                            true, false, removeDateCommentLine(writerConsumer.getBuffer().toString()),
                            null, null));

        } catch (IOException e) {
            throw new RuntimeException("Failed to load Kafka properties files", e);
        }

        String name = "Kafka V2";
        String description = "Connector to receive and send messages to a external Kafka broker. Inbound mappings allow to extract values from the payload and the key and map these to the Cumulocity payload. The relevant setting in a mapping is 'supportsMessageContext'.\n In outbound mappings the any string that is mapped to '_CONTEXT_DATA_.key' is used as the outbound Kafka record.\n The connector uses SASL_SSL as security protocol.";
        connectorSpecification = new ConnectorSpecification(name, description, ConnectorType.KAFKA_V2,
                singleton, configProps, true, // supportsMessageContext = true
                supportedDirections());
    }

    // Add the helper method from the original implementation
    private static String removeDateCommentLine(String pt) {
        String result = pt;
        String regex = "(?m)^[ ]*#.*$(\r?\n)?";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(pt);
        // Find the second occurrence of the pattern
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count == 2) {
                break;
            }
        }
        // Remove the second line starting with "#"
        if (count == 2) {
            result = pt.substring(0, matcher.start()) + pt.substring(matcher.end());
        }
        return result;
    }

    @Override
    public boolean initialize() {
        loadConfiguration();

        try {
            kafkaProperties = buildKafkaProperties();

            // Initialize admin client for topic operations
            adminClient = AdminClient.create(kafkaProperties);

            // Test connection by listing topics
            ListTopicsResult listTopics = adminClient.listTopics();
            listTopics.names().get(10, TimeUnit.SECONDS);

            log.info("{} - Kafka connector {} initialized successfully", tenant, getConnectorName());
            return true;

        } catch (Exception e) {
            log.error("{} - Error initializing Kafka connector: {}", tenant, getConnectorName(), e);
            updateConnectorStatusToFailed(e);
            return false;
        }
    }

    private Properties buildKafkaProperties() {
        Properties props = new Properties();

        // Basic configuration
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                connectorConfiguration.getProperties().get("bootstrapServers"));
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                connectorConfiguration.getProperties().get("bootstrapServers"));

        // Security configuration
        String securityProtocol = (String) connectorConfiguration.getProperties()
                .getOrDefault("securityProtocol", "PLAINTEXT");
        props.put("security.protocol", securityProtocol);

        if (securityProtocol.contains("SASL")) {
            configureSaslProperties(props);
        }

        if (securityProtocol.contains("SSL")) {
            configureSslProperties(props);
        }

        // Consumer specific properties
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                connectorConfiguration.getProperties().get("groupId") + additionalSubscriptionIdTest);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                connectorConfiguration.getProperties().getOrDefault("autoOffsetReset", "latest"));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                connectorConfiguration.getProperties().getOrDefault("enableAutoCommit", true));
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
                connectorConfiguration.getProperties().getOrDefault("sessionTimeoutMs", 30000));
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                connectorConfiguration.getProperties().getOrDefault("requestTimeoutMs", 40000));

        // Serialization
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        return props;
    }

    private void configureSaslProperties(Properties props) {
        String saslMechanism = (String) connectorConfiguration.getProperties()
                .getOrDefault("saslMechanism", "PLAIN");
        String username = (String) connectorConfiguration.getProperties().get("saslUsername");
        String password = (String) connectorConfiguration.getProperties().get("saslPassword");

        props.put("sasl.mechanism", saslMechanism);

        if (username != null && password != null) {
            String jaasTemplate = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";";
            String jaasConfig = String.format(jaasTemplate, username, password);
            props.put("sasl.jaas.config", jaasConfig);
        }
    }

    private void configureSslProperties(Properties props) {
        String truststoreLocation = (String) connectorConfiguration.getProperties().get("sslTruststoreLocation");
        String truststorePassword = (String) connectorConfiguration.getProperties().get("sslTruststorePassword");
        String keystoreLocation = (String) connectorConfiguration.getProperties().get("sslKeystoreLocation");
        String keystorePassword = (String) connectorConfiguration.getProperties().get("sslKeystorePassword");

        if (!StringUtils.isEmpty(truststoreLocation)) {
            props.put("ssl.truststore.location", truststoreLocation);
        }
        if (!StringUtils.isEmpty(truststorePassword)) {
            props.put("ssl.truststore.password", truststorePassword);
        }
        if (!StringUtils.isEmpty(keystoreLocation)) {
            props.put("ssl.keystore.location", keystoreLocation);
        }
        if (!StringUtils.isEmpty(keystorePassword)) {
            props.put("ssl.keystore.password", keystorePassword);
        }
    }

    @Override
    public void connect() {
        log.info("{} - Phase I: {} connecting, isConnected: {}, shouldConnect: {}",
                tenant, getConnectorName(), isConnected(),
                shouldConnect());
        if (shouldConnect())
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            String username = (String) connectorConfiguration.getProperties().get("username");
            String password = (String) connectorConfiguration.getProperties().get("password");
            String saslMechanism = (String) connectorConfiguration.getProperties().get("saslMechanism");
            String groupId = (String) connectorConfiguration.getProperties().get("groupId");
            String bootstrapServers = (String) connectorConfiguration.getProperties().get("bootstrapServers");

            // Add null checks and default values
            if (bootstrapServers == null) {
                log.error("{} - bootstrapServers is null, cannot connect", tenant);
                updateConnectorStatusToFailed(new IllegalArgumentException("bootstrapServers cannot be null"));
                return;
            }

            // Provide default groupId if not set
            if (groupId == null) {
                groupId = "dynamic-mapper-" + connectorIdentifier + additionalSubscriptionIdTest;
                log.warn("{} - groupId is null, using default: {}", tenant, groupId);
            }

            String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
            String jaasCfg = String.format(jaasTemplate, username, password);

            // Only set SASL config if username and password are provided
            if (username != null && password != null) {
                defaultPropertiesProducer.put("sasl.jaas.config", jaasCfg);
                defaultPropertiesProducer.put("sasl.mechanism",
                        saslMechanism != null ? saslMechanism : "SCRAM-SHA-256");
            }

            defaultPropertiesProducer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            defaultPropertiesProducer.put("group.id", groupId);

            log.info("{} - Phase II: {} connecting, shouldConnect: {}, server: {}", tenant,
                    getConnectorName(),
                    shouldConnect(), bootstrapServers);
            try {
                // test if the mqtt connection is configured and enabled
                if (shouldConnect()) {
                    mappingService.rebuildMappingOutboundCache(tenant, connectorId);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // previously used updatedMappings
                    kafkaProducer = new KafkaProducer<>(defaultPropertiesProducer);
                    connectionState.setTrue();
                    updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
                    List<Mapping> updatedMappings = mappingService.rebuildMappingInboundCache(tenant, connectorId);
                    initializeSubscriptionsInbound(updatedMappings, true, true);
                    log.info("{} - Phase III: {} connected, bootstrapServers: {}", tenant, getConnectorName(),
                            bootstrapServers);
                }
                successful = true;
            } catch (Exception e) {
                log.error("{} - Error on reconnect, retrying ... {}: ", tenant, e.getMessage(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                if (serviceConfiguration.logConnectorErrorInBackend) {
                    log.error("{} - Stacktrace: ", tenant, e);
                }
                successful = false;
            }
        }
    }

    @Override
    public void disconnect() {
        log.info("{} - Disconnecting Kafka connector: {}", tenant, getConnectorName());
        updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTING, true, true);

        // Stop all consumer tasks
        consumerTasks.values().forEach(task -> {
            if (!task.isDone()) {
                task.cancel(true);
            }
        });
        consumerTasks.clear();

        // Close all consumers
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

        connectionState.setFalse();
        updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);

        // Rebuild caches
        List<Mapping> updatedMappingsInbound = mappingService.rebuildMappingInboundCache(tenant, connectorId);
        initializeSubscriptionsInbound(updatedMappingsInbound, true, true);

        List<Mapping> updatedMappingsOutbound = mappingService.rebuildMappingOutboundCache(tenant, connectorId);
        initializeSubscriptionsOutbound(updatedMappingsOutbound);

        log.info("{} - Disconnected Kafka connector: {}", tenant, getConnectorName());
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public boolean isConnected() {
        return connectionState.booleanValue() && kafkaProducer != null;
    }

    @Override
    public String getConnectorIdentifier() {
        return connectorIdentifier;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public void subscribe(String topic, Qos qos) throws ConnectorException {
        if (!isConnected()) {
            throw new ConnectorException("Kafka connector is not connected");
        }

        log.debug("{} - Subscribing to Kafka topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Subscribing");

        try {
            // Create consumer for this topic
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(kafkaProperties);
            consumer.subscribe(Collections.singletonList(topic));

            KafkaConsumerWrapper wrapper = new KafkaConsumerWrapper(consumer, topic);
            topicConsumers.put(topic, wrapper);

            // Start consumer task
            Future<?> consumerTask = virtualThreadPool.submit(() -> consumeMessages(wrapper));
            consumerTasks.put(topic, consumerTask);

            log.info("{} - Successfully subscribed to Kafka topic: [{}]", tenant, topic);

        } catch (Exception e) {
            log.error("{} - Error subscribing to Kafka topic: [{}]", tenant, topic, e);
            throw new ConnectorException("Failed to subscribe to topic: " + topic, e);
        }
    }

    @Override
    public void unsubscribe(String topic) throws Exception {
        log.debug("{} - Unsubscribing from Kafka topic: [{}]", tenant, topic);
        sendSubscriptionEvents(topic, "Unsubscribing");

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
            } catch (Exception e) {
                log.warn("{} - Error closing Kafka consumer for topic: [{}]", tenant, topic, e);
            }
        }
    }

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

                // Break the loop if we have too many consecutive failures
                MutableInt failCount = failedSubscriptions.computeIfAbsent(topic, k -> new MutableInt(0));
                failCount.increment();

                if (failCount.intValue() > 5) {
                    log.error("{} - Too many failures for topic: [{}], stopping consumer", tenant, topic);
                    break;
                }

                // Wait before retrying
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.debug("{} - Stopped message consumption for topic: [{}]", tenant, topic);
    }

    private void processKafkaMessage(ConsumerRecord<String, String> record) {
        String topic = record.topic();
        String value = record.value();
        byte[] payloadBytes = value != null ? value.getBytes() : null;

        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .supportsMessageContext(supportsMessageContext)
                .topic(topic)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .build();

        if (serviceConfiguration.logPayload) {
            log.info("{} - INITIAL: Kafka message on topic: [{}], partition: {}, offset: {}, connector: {}",
                    tenant, topic, record.partition(), record.offset(), connectorName);
        }

        // Process the message
        ProcessingResult<?> processedResults = dispatcher.onMessage(connectorMessage);

        // For Kafka, we don't have QoS levels like MQTT, but we can still use the
        // consolidated QoS
        // to determine if we need to wait for processing completion
        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getMaxCPUTimeMS();
        int effectiveQos = mappingQos; // Kafka doesn't have message-level QoS, so use mapping QoS

        if (serviceConfiguration.logPayload) {
            log.info("{} - WAIT_ON_RESULTS: Kafka message on topic: [{}], partition: {}, offset: {}, " +
                    "QoS effective: {}, QoS mappings: {}, connector: {}",
                    tenant, topic, record.partition(), record.offset(), effectiveQos, mappingQos, connectorIdentifier);
        }

        if (effectiveQos > 0) {
            // Use the provided virtualThreadPool for processing
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete
                    List<? extends ProcessingContext<?>> results;
                    if (timeout > 0) {
                        results = processedResults.getProcessingResult().get(timeout, TimeUnit.MILLISECONDS);
                    } else {
                        results = processedResults.getProcessingResult().get();
                    }

                    // Check for errors in results
                    boolean hasErrors = false;
                    int httpStatusCode = 0;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            if (context.hasError()) {
                                for (Exception error : context.getErrors()) {
                                    if (error instanceof ProcessingException) {
                                        if (((ProcessingException) error)
                                                .getOriginException() instanceof SDKException) {
                                            if (((SDKException) ((ProcessingException) error).getOriginException())
                                                    .getHttpStatus() > httpStatusCode) {
                                                httpStatusCode = ((SDKException) ((ProcessingException) error)
                                                        .getOriginException()).getHttpStatus();
                                            }
                                        }
                                    }
                                }
                                hasErrors = true;
                                log.error(
                                        "{} - Error in processing context for Kafka topic: [{}], partition: {}, offset: {}",
                                        tenant, topic, record.partition(), record.offset());
                                break;
                            }
                        }
                    }

                    if (!hasErrors) {
                        // No errors found - for Kafka, we can commit the offset if auto-commit is
                        // disabled
                        if (serviceConfiguration.logPayload) {
                            log.info("{} - END: Successfully processed Kafka message: topic: [{}], partition: {}, " +
                                    "offset: {}, connector: {}",
                                    tenant, topic, record.partition(), record.offset(), connectorIdentifier);
                        }
                        handleSuccessfulProcessing(record);
                    } else if (httpStatusCode < 500) {
                        // Errors found but not a server error - still consider message processed
                        log.warn(
                                "{} - END: Processed Kafka message with non-server error: topic: [{}], partition: {}, "
                                        +
                                        "offset: {}, connector: {}",
                                tenant, topic, record.partition(), record.offset(), connectorIdentifier);
                        handleSuccessfulProcessing(record);
                    } else {
                        // Server error - might want to retry or handle differently
                        log.error("{} - END: Server error processing Kafka message: topic: [{}], partition: {}, " +
                                "offset: {}, connector: {}",
                                tenant, topic, record.partition(), record.offset(), connectorIdentifier);
                        handleProcessingError(record, httpStatusCode);
                    }

                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed
                    log.warn("{} - END: Processing was interrupted for Kafka message: topic: [{}], partition: {}, " +
                            "offset: {}, connector: {}",
                            tenant, topic, record.partition(), record.offset(), connectorIdentifier, e);
                    handleProcessingError(record, 0);
                } catch (TimeoutException e) {
                    var cancelResult = processedResults.getProcessingResult().cancel(true);
                    log.warn("{} - END: Processing timed out with: {} milliseconds for Kafka message: topic: [{}], " +
                            "partition: {}, offset: {}, connector: {}, result of cancelling: {}",
                            tenant, timeout, topic, record.partition(), record.offset(), connectorIdentifier,
                            cancelResult);
                    handleProcessingTimeout(record);
                }
                return null; // Proper return for Callable<Void>
            });
        } else {
            // For QoS 0 (or equivalent), process immediately without waiting
            if (serviceConfiguration.logPayload) {
                log.info(
                        "{} - END: Immediate processing for Kafka message: topic: [{}], partition: {}, offset: {}, connector: {}",
                        tenant, topic, record.partition(), record.offset(), connectorIdentifier);
            }
            handleSuccessfulProcessing(record);
        }
    }

    private void handleSuccessfulProcessing(ConsumerRecord<String, String> record) {
        // For Kafka, if auto-commit is disabled, we might want to manually commit
        // This depends on your Kafka consumer configuration
        Boolean autoCommit = (Boolean) connectorConfiguration.getProperties()
                .getOrDefault("enableAutoCommit", true);

        if (!autoCommit) {
            // Get the consumer for this topic and commit the offset
            KafkaConsumerWrapper wrapper = topicConsumers.get(record.topic());
            if (wrapper != null) {
                try {
                    // Commit this specific offset
                    Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsetsToCommit = Collections
                            .singletonMap(
                                    new TopicPartition(record.topic(), record.partition()),
                                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(record.offset() + 1));
                    wrapper.getConsumer().commitSync(offsetsToCommit);

                    if (serviceConfiguration.logPayload) {
                        log.debug("{} - Manually committed offset for topic: [{}], partition: {}, offset: {}",
                                tenant, record.topic(), record.partition(), record.offset() + 1);
                    }
                } catch (Exception e) {
                    log.error("{} - Error committing offset for topic: [{}], partition: {}, offset: {}",
                            tenant, record.topic(), record.partition(), record.offset(), e);
                }
            }
        }
    }

    private void handleProcessingError(ConsumerRecord<String, String> record, int httpStatusCode) {
        // For Kafka, we might want to:
        // 1. Log the error
        // 2. Optionally seek back to retry the message
        // 3. Or skip the message and continue

        log.error("{} - Processing error for Kafka message: topic: [{}], partition: {}, offset: {}, HTTP status: {}",
                tenant, record.topic(), record.partition(), record.offset(), httpStatusCode);

        // Increment error count for this topic
        String errorKey = record.topic() + "-" + record.partition();
        MutableInt errorCount = failedSubscriptions.computeIfAbsent(errorKey, k -> new MutableInt(0));
        errorCount.increment();

        // If too many errors, we might want to pause the consumer or take other action
        if (errorCount.intValue() > 10) {
            log.error("{} - Too many processing errors for topic: [{}], partition: {}, considering consumer restart",
                    tenant, record.topic(), record.partition());

            // You might want to trigger a consumer restart here
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

    private void handleProcessingTimeout(ConsumerRecord<String, String> record) {
        log.warn("{} - Processing timeout for Kafka message: topic: [{}], partition: {}, offset: {}",
                tenant, record.topic(), record.partition(), record.offset());

        // Similar handling to processing error, but might have different logic
        handleProcessingError(record, 0);
    }

    private void handleConsumerError(String topic, Exception e) {
        if (e instanceof KafkaException) {
            log.error("{} - Kafka error for topic [{}]: {}", tenant, topic, e.getMessage());
        } else {
            log.error("{} - Unexpected error for topic [{}]: {}", tenant, topic, e.getMessage(), e);
        }
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        if (kafkaProducer == null) {
            log.error("{} - Kafka producer is not initialized", tenant);
            return;
        }

        String topic = context.getResolvedPublishTopic();
        String payload = context.getCurrentRequest().getRequest();

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, payload);

        try {
            Future<RecordMetadata> future = kafkaProducer.send(record);
            RecordMetadata metadata = future.get(10, TimeUnit.SECONDS);

            if (context.getMapping().getDebug() || serviceConfiguration.logPayload) {
                log.info("{} - Published outbound message to Kafka topic: [{}], partition: {}, offset: {}, mapping: {}",
                        tenant, topic, metadata.partition(), metadata.offset(), context.getMapping().name);
            }

        } catch (Exception e) {
            String errorMessage = String.format("%s - Error publishing message to Kafka topic: [%s], mapping: %s",
                    tenant, topic, context.getMapping().name);
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));
        }
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null) {
            return false;
        }

        // Check if bootstrapServers is set (this is the only truly required property)
        String bootstrapServers = (String) configuration.getProperties().get("bootstrapServers");
        if (bootstrapServers == null || bootstrapServers.trim().isEmpty()) {
            log.error("bootstrapServers is required but not set");
            return false;
        }

        // If username is provided, password should also be provided
        String username = (String) configuration.getProperties().get("username");
        String password = (String) configuration.getProperties().get("password");

        if (username != null && !username.trim().isEmpty()) {
            if (password == null || password.trim().isEmpty()) {
                log.error("Password is required when username is provided");
                return false;
            }
        }

        return true;
    }

    @Override
    public Boolean supportsWildcardInTopic(Direction direction) {
        // Kafka doesn't support wildcards in topic subscriptions like MQTT
        return false;
    }

    @Override
    public void monitorSubscriptions() {
        // Monitor failed subscriptions and attempt to restart them
        Set<String> failedTopics = new HashSet<>(failedSubscriptions.keySet());

        for (String topic : failedTopics) {
            MutableInt failCount = failedSubscriptions.get(topic);
            if (failCount != null && failCount.intValue() > 0) {
                log.warn("{} - Monitoring failed subscription for topic: [{}], fail count: {}",
                        tenant, topic, failCount.intValue());

                // Check if we should retry
                if (failCount.intValue() <= 3) {
                    try {
                        // Try to recreate the subscription
                        restartConsumerForTopic(topic);
                        failedSubscriptions.remove(topic);
                        log.info("{} - Successfully restarted consumer for topic: [{}]", tenant, topic);
                    } catch (Exception e) {
                        log.error("{} - Failed to restart consumer for topic: [{}]", tenant, topic, e);
                    }
                }
            }
        }
    }

    private void restartConsumerForTopic(String topic) throws ConnectorException {
        // Stop existing consumer
        try {
            unsubscribe(topic);
        } catch (Exception e) {
            log.warn("{} - Error stopping existing consumer for topic: [{}]", tenant, topic, e);
        }

        // Start new consumer
        subscribe(topic, Qos.AT_MOST_ONCE);
    }

    @Override
    protected void connectorSpecificHousekeeping(String tenant) {
        // Clean up completed tasks
        consumerTasks.entrySet().removeIf(entry -> {
            Future<?> task = entry.getValue();
            if (task.isDone() || task.isCancelled()) {
                String topic = entry.getKey();
                log.debug("{} - Cleaning up completed consumer task for topic: [{}]", tenant, topic);
                return true;
            }
            return false;
        });

        // Monitor consumer health
        topicConsumers.forEach((topic, wrapper) -> {
            // Add any specific health checks here if needed
            log.debug("{} - Consumer for topic [{}] is active", tenant, topic);
        });
    }

    @Override
    public List<Direction> supportedDirections() {
        return Arrays.asList(Direction.INBOUND, Direction.OUTBOUND);
    }

    // Helper class to manage consumers
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
}
