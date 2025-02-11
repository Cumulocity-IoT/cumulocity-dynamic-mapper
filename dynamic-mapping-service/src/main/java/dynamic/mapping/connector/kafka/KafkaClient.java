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

package dynamic.mapping.connector.kafka;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
// import org.apache.kafka.common.serialization.StringDeserializer;
// import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.connector.core.client.ConnectorType;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.core.ConnectorStatusEvent;
import dynamic.mapping.model.Direction;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.DispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringWriter;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;

@Slf4j
// Use pattern to start/stop polling thread from Stackoverflow
// https://stackoverflow.com/questions/66103052/how-do-i-stop-a-previous-thread-that-is-listening-to-kafka-topic

public class KafkaClient extends AConnectorClient {
	public KafkaClient() throws ConnectorException {
		try {
            Map<String, ConnectorProperty> configProps = new HashMap<>();
            configProps.put("bootstrapServers",
            		new ConnectorProperty(true, 0, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null));
            configProps.put("username",
            		new ConnectorProperty(false, 1, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null));
            configProps.put("password",
            		new ConnectorProperty(false, 2, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false, null,
            				null));
            configProps.put("saslMechanism",
            		new ConnectorProperty(false, 3, ConnectorPropertyType.OPTION_PROPERTY, false, false, "SCRAM-SHA-256",
            				Map.ofEntries(
            						new AbstractMap.SimpleEntry<String, String>("SCRAM-SHA-256", "SCRAM-SHA-256"),
            						new AbstractMap.SimpleEntry<String, String>("SCRAM-SHA-512", "SCRAM-SHA-512"))));
            configProps.put("groupId",
            		new ConnectorProperty(false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false, null, null));

            Resource resourceProducer = new ClassPathResource(KAFKA_PRODUCER_PROPERTIES);
            defaultPropertiesProducer = PropertiesLoaderUtils.loadProperties(resourceProducer);
            StringWriter writerProducer = new StringWriter();
            defaultPropertiesProducer.store(writerProducer,
            		"properties can only be edited in the property file: kafka-producer.properties");
            configProps.put("propertiesProducer",
            		new ConnectorProperty(false, 5, ConnectorPropertyType.STRING_LARGE_PROPERTY, true, false,
            				removeDateCommentLine(writerProducer.getBuffer().toString()), null));

            Resource resourceConsumer = new ClassPathResource(KAFKA_CONSUMER_PROPERTIES);
            defaultPropertiesConsumer = PropertiesLoaderUtils.loadProperties(resourceConsumer);
            StringWriter writerConsumer = new StringWriter();
            defaultPropertiesConsumer.store(writerConsumer,
            		"properties can only be edited in the property file: kafka-consumer.properties");
            configProps.put("propertiesConsumer",
            		new ConnectorProperty(false, 6, ConnectorPropertyType.STRING_LARGE_PROPERTY, true, false,
            				removeDateCommentLine(writerConsumer.getBuffer().toString()), null));

            String name = "Kafka";
            String description = "Generic connector to receive and send messages to a external Kafka broker. Inbound mappings allow to extract values from the payload and the  key and map these to the Cumulocity payload. The relevant setting in a mapping is 'supportsMessageContext'.\n In outbound mappings the any string that is mapped to '_CONTEXT_DATA_.key' is used as the outbound Kafka record.\n The connector uses SASL_SSL as security protocol.";
            connectorType = ConnectorType.KAFKA;
            supportsMessageContext = true;
            connectorSpecification = new ConnectorSpecification(name, description, connectorType, configProps, true, supportedDirections());
        } catch (IOException e) {
            throw new ConnectorException(e.getMessage());
        }
	}

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

	public KafkaClient(ConfigurationRegistry configurationRegistry,
			ConnectorConfiguration connectorConfiguration,
			DispatcherInbound dispatcher, String additionalSubscriptionIdTest, String tenant)
			throws ConnectorException {
		this();
		this.configurationRegistry = configurationRegistry;
		this.mappingComponent = configurationRegistry.getMappingComponent();
		this.serviceConfigurationComponent = configurationRegistry.getServiceConfigurationComponent();
		this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
		this.connectorConfiguration = connectorConfiguration;
		// ensure the client knows its identity even if configuration is set to null
		this.connectorName = connectorConfiguration.name;
		this.connectorIdentifier = connectorConfiguration.identifier;
		this.connectorStatus = ConnectorStatusEvent.unknown(connectorConfiguration.name, connectorConfiguration.identifier);
		this.c8yAgent = configurationRegistry.getC8yAgent();
		this.virtThreadPool = configurationRegistry.getVirtThreadPool();
		this.objectMapper = configurationRegistry.getObjectMapper();
		this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
		this.mappingServiceRepresentation = configurationRegistry.getMappingServiceRepresentations().get(tenant);
		this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
		this.dispatcher = dispatcher;
		this.tenant = tenant;
		this.connectionState.setFalse();

		// defaultPropertiesProducer = new Properties();
		// String jaasTemplate =
		// "org.apache.kafka.common.security.scram.ScramLoginModule required
		// username=\"%s\" password=\"%s\";";
		// String jaasCfg = String.format(jaasTemplate, username, password);
		// String serializer = StringSerializer.class.getName();
		// defaultPropertiesConsumer.put("bootstrap.servers",
		// "glider.srvs.cloudkafka.com:9094");
		// defaultPropertiesProducer.put("key.serializer", serializer);
		// defaultPropertiesProducer.put("value.serializer", serializer);
		// defaultPropertiesProducer.put("security.protocol", "SASL_SSL");
		// defaultPropertiesProducer.put("sasl.mechanism", "SCRAM-SHA-256");
		// defaultPropertiesProducer.put("sasl.jaas.config", jaasCfg);
		// defaultPropertiesProducer.put("linger.ms", 1);
		// defaultPropertiesProducer.put("enable.idempotence", false);

		// String deserializer = StringDeserializer.class.getName();
		// defaultPropertiesConsumer = new Properties();
		// defaultPropertiesConsumer.put("bootstrap.servers","glider.srvs.cloudkafka.com:9094");
		// defaultPropertiesConsumer.put("group.id", username + "-consumer");
		// defaultPropertiesConsumer.put("enable.auto.commit", "true");
		// defaultPropertiesConsumer.put("auto.commit.interval.ms", "1000");
		// defaultPropertiesConsumer.put("auto.offset.reset", "earliest");
		// defaultPropertiesConsumer.put("session.timeout.ms", "30000");
		// defaultPropertiesConsumer.put("key.deserializer", deserializer);
		// defaultPropertiesConsumer.put("value.deserializer", deserializer);
		// defaultPropertiesConsumer.put("key.serializer", serializer);
		// defaultPropertiesConsumer.put("value.serializer", serializer);
		// defaultPropertiesConsumer.put("security.protocol", "SASL_SSL");
		// defaultPropertiesConsumer.put("sasl.mechanism", "SCRAM-SHA-256");
		// defaultPropertiesConsumer.put("sasl.jaas.config", jaasCfg);
		// defaultPropertiesConsumer.put("linger.ms", 1);
		// updateConnectorStatusAndSend(ConnectorStatus.UNKNOWN, true, true);
	}

	private String bootstrapServers;
	private String password;
	private String username;
	private String saslMechanism;
	private String groupId;

	private HashMap<String, TopicConsumer> consumerList = new HashMap<String, TopicConsumer>();

	private Properties defaultPropertiesConsumer;
	private Properties defaultPropertiesProducer;

	private KafkaProducer<String, String> kafkaProducer;

	private String KAFKA_CONSUMER_PROPERTIES = "/kafka-consumer.properties";
	private String KAFKA_PRODUCER_PROPERTIES = "/kafka-producer.properties";

	@Override
	public boolean initialize() {
		loadConfiguration();
		username = (String) connectorConfiguration.getProperties().get("username");
		password = (String) connectorConfiguration.getProperties().get("password");
		saslMechanism = (String) connectorConfiguration.getProperties().get("saslMechanism");
		bootstrapServers = (String) connectorConfiguration.getProperties().get("bootstrapServers");
		return true;
	}

	@Override
	public Boolean supportsWildcardsInTopic() {
		return false;
	}

	@Override
	public void connect() {
		log.info("Tenant {} - Trying to connect to {} - phase I: (isConnected:shouldConnect) ({}:{})",
				tenant, getConnectorName(), isConnected(),
				shouldConnect());
		if (shouldConnect())
			updateConnectorStatusAndSend(ConnectorStatus.CONNECTING, true, shouldConnect());
		// stay in the loop until successful
		boolean successful = false;
		while (!successful) {
			loadConfiguration();
			username = (String) connectorConfiguration.getProperties().get("username");
			password = (String) connectorConfiguration.getProperties().get("password");
			saslMechanism = (String) connectorConfiguration.getProperties().get("saslMechanism");
			groupId = (String) connectorConfiguration.getProperties().get("groupId");
			bootstrapServers = (String) connectorConfiguration.getProperties().get("bootstrapServers");
			String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
			String jaasCfg = String.format(jaasTemplate, username, password);
			defaultPropertiesProducer.put("sasl.jaas.config", jaasCfg);
			defaultPropertiesProducer.put("sasl.mechanism", saslMechanism);
			defaultPropertiesProducer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
			defaultPropertiesProducer.put("group.id", groupId);
			log.info("Tenant {} - Trying to connect {} - phase II: (shouldConnect):{} {}", tenant,
					getConnectorName(),
					shouldConnect(), bootstrapServers);
			log.info("Tenant {} - Connected to broker {}", tenant,
					bootstrapServers);
			try {
				// test if the mqtt connection is configured and enabled
				if (shouldConnect()) {
					mappingComponent.rebuildMappingOutboundCache(tenant);
					// in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
					// sync, the ActiveSubscriptionMappingInbound is build on the
					// previously used updatedMappings
					kafkaProducer = new KafkaProducer<>(defaultPropertiesProducer);
					connectionState.setTrue();
					updateConnectorStatusAndSend(ConnectorStatus.CONNECTED, true, true);
					List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
					updateActiveSubscriptionsInbound(updatedMappings, true);
				}
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

			connectionState.setFalse();
			updateConnectorStatusAndSend(ConnectorStatus.DISCONNECTED, true, true);
			List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
			updateActiveSubscriptionsInbound(updatedMappings, true);
			kafkaProducer.close();
			log.info("Tenant {} - Disconnected from from broker: {}", tenant, getConnectorName(),
					bootstrapServers);
		}
	}

	@Override
	public void close() {
	}

	@Override
	public String getConnectorIdent() {
		return connectorIdentifier;
	}

	@Override
	public String getConnectorName() {
		return connectorName;
	}

	@Override
	public void subscribe(String topic, QOS qos) throws ConnectorException {
		TopicConsumer kafkaConsumer = new TopicConsumer(
				new TopicConfig(tenant, bootstrapServers, topic, username, password, saslMechanism, groupId,
						defaultPropertiesConsumer),
				connectorStatus);
		consumerList.put(topic, kafkaConsumer);
		TopicConsumerCallback topicConsumerCallback = new TopicConsumerCallback(dispatcher, tenant, getConnectorIdent(),
				topic, true);
		kafkaConsumer.start(topicConsumerCallback);
	}

	@Override
	public void monitorSubscriptions() {

		// for (Iterator<Map.Entry<String, Mapping>> me =
		// getMappingsDeployed().entrySet().iterator(); me.hasNext();) {
		Iterator<String> it = getMappingsDeployedInbound().keySet().iterator();
		while (it.hasNext()) {
			String mapIdent = it.next();
			Mapping map = getMappingsDeployedInbound().get(mapIdent);
			// test if topicConsumer was started successfully
			if (consumerList.containsKey(map.mappingTopic)) {
				TopicConsumer kafkaConsumer = consumerList.get(map.mappingTopic);
				if (kafkaConsumer.shouldStop()) {
					try {
						// kafkaConsumer.close();
						unsubscribe(mapIdent);
						getMappingsDeployedInbound().remove(map.identifier);
						log.warn(
								"Tenant {} - Failed to subscribe to mappingTopic {} for mapping {} in connector {}!",
								tenant, map.mappingTopic, map, getConnectorName());
					} catch (Exception e) {
						// ignore interrupt
					}
				}
			}
		}
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
		String key = currentRequest.getSourceId();
		if (context.isSupportsMessageContext() && context.getKey() != null) {
			key = new String(context.getKey());
		}
		kafkaProducer.send(new ProducerRecord<String, String>(context.getMapping().publishTopic, key, payload));

		log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}, {}", tenant, payload,
				context.getMapping().name, context.getResolvedPublishTopic(), connectorName);
	}

    @Override
    public List<Direction>  supportedDirections() {
        return new ArrayList<>( Arrays.asList(Direction.INBOUND, Direction.OUTBOUND));
    }
}
