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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;

import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorPropertyType;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.connector.core.client.ConnectorException;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;

import dynamic.mapping.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ConnectorConfiguration;

@Slf4j
// Use pattern to start/stop polling thread from Stackoverflow
// https://stackoverflow.com/questions/66103052/how-do-i-stop-a-previous-thread-that-is-listening-to-kafka-topic

public class KafkaClient extends AConnectorClient {
    public KafkaClient() {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        configProps.put("bootstrapServersHost",
                new ConnectorProperty(true, 0, ConnectorPropertyType.STRING_PROPERTY, true, null, null));
        configProps.put("bootstrapServersPort",
                new ConnectorProperty(true, 2, ConnectorPropertyType.NUMERIC_PROPERTY, true, null, null));
        configProps.put("username",
                new ConnectorProperty(false, 3, ConnectorPropertyType.STRING_PROPERTY, true, null, null));
        configProps.put("password",
                new ConnectorProperty(false, 4, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, true, null, null));
        configProps.put("groupId",
                new ConnectorProperty(true, 5, ConnectorPropertyType.STRING_PROPERTY, true, null, null));
        String description = "Generic connector for connecting to external Kafka broker.";
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
    }

    protected String additionalSubscriptionIdTest;

    @Override
    public boolean initialize() {
        loadConfiguration();
        return true;
    }

    @Override
    public Boolean supportsWildcardsInTopic() {
        return false;
    }

    @Override
    public void connect() throws InterruptedException {
        String username = (String) connectorConfiguration.getProperties().get("username");
        String password = (String) connectorConfiguration.getProperties().get("password");
        String bootstrapServersHost = (String) connectorConfiguration.getProperties().get("bootstrapServersHost");
        String bootstrapServersPort = (String) connectorConfiguration.getProperties().get("bootstrapServersHost");
        String configBootstrapServers = String.format("%s:s%", bootstrapServersHost, bootstrapServersPort);
        final String configTopic = "NEED_TO_BE_CHANGED";

        final TopicConsumerManager topicManager = new TopicConsumerManager();
        final TopicConsumer configConsumer = new TopicConsumer(
                new TopicConfig(configBootstrapServers,configTopic));

        configConsumer.start(new TopicConsumerListener() {
            @Override
            public void onStarted() {
                System.out.println("Config consuming started.");
            }

            @Override
            public void onStoppedByErrorAndReconnecting(final Exception error) {
                System.out.println("Config consuming stopped by error: " +
                            error.getLocalizedMessage() + " and reconnecting...");
            }

            @Override
            public void onStopped() {
                System.out.println("Config consuming stopped.");
            }

            @Override
            public void onEvent(final byte[] key, final byte[] event) throws Exception {
                final IdentifiableTopicConfig config = new IdentifiableTopicConfig();
                // decode and fill-in IdentifiableTopicConfig from event bytes

                System.out.println("Configuration consumed: " + config);

                topicManager.execute(new MakeTopicConsumer(config, new TopicConsumerListener() {
                    @Override
                    public void onStarted() {
                        System.out.println("Consuming for " + config + " started.");
                    }

                    @Override
                    public void onStoppedByErrorAndReconnecting(final Exception error) {
                        System.out.println("Consuming for " + config + " stopped by error: " + error.getLocalizedMessage() + " and reconnecting...");
                    }

                    @Override
                    public void onStopped() {
                        System.out.println("Consuming for " + config + " stopped.");
                    }

                    @Override
                    public void onEvent(final byte[] key, final byte[] event) {
                        System.out.println("An event consumed for " + config + '.');
                        // ... process your event ...
                    }
                }));
            }
        });

        configConsumer.close();
    }

    @Override
    public boolean isConnected() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isConnected'");
    }

    @Override
    public void disconnect() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'disconnect'");
    }

    @Override
    public void close() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'close'");
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
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'subscribe'");
    }

    @Override
    public void unsubscribe(String topic) throws Exception {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'unsubscribe'");
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isConfigValid'");
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'publishMEAO'");
    }
}
