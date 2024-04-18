/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.connector.kafka;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;

@Slf4j
// Use pattern to start/stop polling thread from Stackoverflow
// https://stackoverflow.com/questions/66103052/how-do-i-stop-a-previous-thread-that-is-listening-to-kafka-topic

public class KafkaClient extends AConnectorClient {
    public KafkaClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        configProps.put("bootstrapServers",
                new ConnectorProperty(true, 0, ConnectorPropertyType.STRING_PROPERTY, true, null, null));
        configProps.put("username",
                new ConnectorProperty(false, 1, ConnectorPropertyType.STRING_PROPERTY, true, null, null));
        configProps.put("password",
                new ConnectorProperty(false, 2, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, null, null));
        configProps.put("groupId",
                new ConnectorProperty(false, 3, ConnectorPropertyType.STRING_PROPERTY, true, null, null));
        String description = "Generic connector for connecting to external Kafka broker.";
        connectorType = ConnectorType.KAFKA;
        specification = new ConnectorSpecification(description, connectorType, configProps);
    }

    public KafkaClient(ConfigurationRegistry configurationRegistry,
            ConnectorConfiguration connectorConfiguration,
            AsynchronousDispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant) {
        this();
        this.configurationRegistry = configurationRegistry;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.connectorConfiguration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdent = connectorConfiguration.ident;
        this.connectorName = connectorConfiguration.name;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.dispatcher = dispatcher;
        this.tenant = tenant;
        this.connectionState.setFalse();

        defaultPropertiesProducer = new Properties();
        // String jaasTemplate =
        // "org.apache.kafka.common.security.scram.ScramLoginModule required
        // username=\"%s\" password=\"%s\";";
        // String jaasCfg = String.format(jaasTemplate, username, password);
        String serializer = StringSerializer.class.getName();
        // defaultPropertiesConsumer.put("bootstrap.servers",
        // "glider.srvs.cloudkafka.com:9094");
        defaultPropertiesProducer.put("key.serializer", serializer);
        defaultPropertiesProducer.put("value.serializer", serializer);
        defaultPropertiesProducer.put("security.protocol", "SASL_SSL");
        defaultPropertiesProducer.put("sasl.mechanism", "SCRAM-SHA-256");
        // defaultPropertiesProducer.put("sasl.jaas.config", jaasCfg);
        defaultPropertiesProducer.put("linger.ms", 1);
        defaultPropertiesProducer.put("enable.idempotence", false);

        String deserializer = StringDeserializer.class.getName();
        defaultPropertiesConsumer = new Properties();
        // defaultPropertiesConsumer.put("bootstrap.servers",
        // "glider.srvs.cloudkafka.com:9094");
        // defaultPropertiesConsumer.put("group.id", username + "-consumer");
        // defaultPropertiesConsumer.put("enable.auto.commit", "true");
        // defaultPropertiesConsumer.put("auto.commit.interval.ms", "1000");
        defaultPropertiesConsumer.put("auto.offset.reset", "earliest");
        defaultPropertiesConsumer.put("session.timeout.ms", "30000");
        defaultPropertiesConsumer.put("key.deserializer", deserializer);
        defaultPropertiesConsumer.put("value.deserializer", deserializer);
        defaultPropertiesConsumer.put("key.serializer", serializer);
        defaultPropertiesConsumer.put("value.serializer", serializer);
        defaultPropertiesConsumer.put("security.protocol", "SASL_SSL");
        defaultPropertiesConsumer.put("sasl.mechanism", "SCRAM-SHA-256");
        // defaultPropertiesConsumer.put("sasl.jaas.config", jaasCfg);
        defaultPropertiesConsumer.put("linger.ms", 1);
    }

    private String bootstrapServers;
    private String password;
    private String username;
    private String groupId;

    private HashMap<String, TopicConsumer> consumerList = new HashMap<String, TopicConsumer>();

    private Properties defaultPropertiesConsumer;
    private Properties defaultPropertiesProducer;

    private KafkaProducer<String, String> kafkaProducer;

    @Override
    public boolean initialize() {
        loadConfiguration();
        username = (String) connectorConfiguration.getProperties().get("username");
        password = (String) connectorConfiguration.getProperties().get("password");
        bootstrapServers = (String) connectorConfiguration.getProperties().get("bootstrapServers");
        return true;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return false;
    }

    @Override
    public void connect() {
        connectionState.setTrue();
        log.info("Tenant {} - Trying to connect to {} - phase I: (isConnected:shouldConnect) ({}:{})",
                tenant, getConnectorName(), isConnected(),
                shouldConnect());
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            loadConfiguration();
            username = (String) connectorConfiguration.getProperties().get("username");
            password = (String) connectorConfiguration.getProperties().get("password");
            groupId = (String) connectorConfiguration.getProperties().get("groupId");
            bootstrapServers = (String) connectorConfiguration.getProperties().get("bootstrapServers");
            String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
            String jaasCfg = String.format(jaasTemplate, username, password);
            defaultPropertiesProducer.put("sasl.jaas.config", jaasCfg);
            defaultPropertiesProducer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            defaultPropertiesConsumer.put("group.id", groupId);
            log.info("Tenant {} - Trying to connect {} - phase II: (shouldConnect):{} {}", tenant,
                    getConnectorName(),
                    shouldConnect(), bootstrapServers);
            log.info("Tenant {} - Successfully connected to broker {}", tenant,
                    bootstrapServers);
            updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
            try {
                // test if the mqtt connection is configured and enabled
                if (shouldConnect()) {
                    mappingComponent.rebuildMappingOutboundCache(tenant);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // previously used updatedMappings
                    List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
                    updateActiveSubscriptions(updatedMappings, true);
                }


                kafkaProducer = new KafkaProducer<>(defaultPropertiesProducer);
                successful = true;
            } catch (Exception e) {
                log.error("Tenant {} - Error on reconnect, retrying ... {}: ", tenant, e.getMessage(), e);
                updateConnectorStatusToFailed(e);
                sendConnectorLifecycle();
                if (serviceConfiguration.logConnectorErrorInBackend) {
                    log.error("Tenant {} - Stacktrace: ", tenant, e);
                }
                successful = false;
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connectionState.getValue();
    }

    @Override
    public void disconnect() {
        if (isConnected()) {
            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTING, true, true);
            log.info("Tenant {} - Disconnecting  connector {} from broker: {}", tenant, getConnectorName(),
                    bootstrapServers);
            activeSubscriptions.entrySet().forEach(entry -> {
                // only unsubscribe if still active subscriptions exist
                String topic = entry.getKey();
                MutableInt activeSubs = entry.getValue();
                if (activeSubs.intValue() > 0) {
                    try {
                        unsubscribe(topic);
                    } catch (Exception error) {
                        log.error("Tenant {} - Error unsubscribing topic {} from broker: {}, error {}", tenant, topic,
                                getConnectorName(),
                                error);
                    }
                }
            });

            updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
            updateActiveSubscriptions(null, true);
            log.info("Tenant {} - Disconnected from from broker: {}", tenant, getConnectorName(),
                    bootstrapServers);
        }
    }

    @Override
    public void close() {
    }

    @Override
    public String getConnectorIdent() {
        return connectorIdent;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public void subscribe(String topic, QOS qos) throws ConnectorException {
        TopicConsumer kafkaConsumer = new TopicConsumer(
                new TopicConfig(bootstrapServers, topic, username, password, tenant, defaultPropertiesConsumer));
        consumerList.put(topic, kafkaConsumer);
        TopicConsumerCallback topicConsumerCallback = new TopicConsumerCallback(dispatcher, tenant, getConnectorIdent(),
                topic);
        kafkaConsumer.start(topicConsumerCallback);
    }

    @Override
    public void unsubscribe(String topic) throws Exception {
        TopicConsumer kafkaConsumer = consumerList.remove(topic);
        if (kafkaConsumer != null)
            kafkaConsumer.close();
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        return true;
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        String key = currentRequest.getSource();
        kafkaProducer.send(new ProducerRecord<String, String>(context.getMapping().publishTopic, key, payload));

        log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}, {}", tenant, payload,
                context.getMapping().name, context.getResolvedPublishTopic(), connectorName);
    }
}
