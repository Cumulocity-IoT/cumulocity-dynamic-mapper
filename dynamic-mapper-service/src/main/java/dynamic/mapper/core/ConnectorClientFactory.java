/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.core;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.amqp.AMQPClient;
import dynamic.mapper.connector.amqp.AMQP10Client;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorException;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.kafka.KafkaClientV2;
import dynamic.mapper.connector.mqtt.MQTT3Client;
import dynamic.mapper.connector.mqtt.MQTT5Client;
import dynamic.mapper.connector.mqtt.MQTTServiceClient;
import dynamic.mapper.connector.pulsar.MQTTServicePulsarClient;
import dynamic.mapper.connector.pulsar.PulsarConnectorClient;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.connector.webhook.WebHook;
import dynamic.mapper.connector.webhook.WebHookInternal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for creating {@link AConnectorClient} instances from a
 * {@link ConnectorConfiguration}.
 *
 * <p>Extracted from {@link ConfigurationRegistry} so that connector creation
 * logic lives in a dedicated component with a clear single responsibility.
 * {@link ConfigurationRegistry} no longer needs to import every connector class.
 */
@Slf4j
@Component
public class ConnectorClientFactory {

    private final ConfigurationRegistry configurationRegistry;
    private final ConnectorRegistry connectorRegistry;

    @Value("${C8Y_BASEURL_PULSAR:}")
    private String mqttServicePulsarUrl;

    public ConnectorClientFactory(ConfigurationRegistry configurationRegistry, ConnectorRegistry connectorRegistry) {
        this.configurationRegistry = configurationRegistry;
        this.connectorRegistry = connectorRegistry;
    }

    private boolean isPulsarAvailable(String tenant) {
        if (mqttServicePulsarUrl == null || mqttServicePulsarUrl.trim().isEmpty()) {
            log.warn("{} - C8Y_BASEURL_PULSAR is not configured for Pulsar connector. Disabling MQTT Service Pulsar.",
                    tenant);
            return false;
        }
        return true;
    }

    public AConnectorClient createConnectorClient(ConnectorConfiguration connectorConfiguration,
            String additionalSubscriptionIdTest, String tenant) throws ConnectorException {
        AConnectorClient connectorClient = null;

        switch (connectorConfiguration.getConnectorType()) {
            case MQTT:
                String version = ((String) connectorConfiguration.getProperties().getOrDefault("version",
                        AConnectorClient.MQTT_VERSION_3_1_1));
                if (AConnectorClient.MQTT_VERSION_3_1_1.equals(version)) {
                    connectorClient = new MQTT3Client(configurationRegistry, connectorRegistry,
                            connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                } else {
                    connectorClient = new MQTT5Client(configurationRegistry, connectorRegistry,
                            connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                }
                log.info("{} - MQTT Connector {} created, identifier: {}", tenant, version,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE:
                connectorClient = new MQTTServiceClient(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - MQTTService Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case KAFKA:
                connectorClient = new KafkaClientV2(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - Kafka Connector V2 created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case HTTP:
                connectorClient = new HttpClient(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - HTTP Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case WEB_HOOK:
                connectorClient = new WebHook(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - WebHook Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case WEB_HOOK_INTERNAL:
                connectorClient = new WebHookInternal(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - WebHook Internal Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case PULSAR:
                connectorClient = new PulsarConnectorClient(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - Pulsar Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE_PULSAR:
                if (isPulsarAvailable(tenant)) {
                    connectorClient = new MQTTServicePulsarClient(configurationRegistry, connectorRegistry,
                            connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                    log.info("{} - MQTTService Pulsar Connector created, identifier: {}", tenant,
                            connectorConfiguration.getIdentifier());
                }
                break;

            case AMQP_091:
                connectorClient = new AMQPClient(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - AMQP Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case AMQP_10:
                connectorClient = new AMQP10Client(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - AMQP 1.0 Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case TEST:
                connectorClient = new TestClient(configurationRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - TestClient Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            default:
                log.warn("{} - Unknown connector type: {}", tenant, connectorConfiguration.getConnectorType());
                break;
        }

        return connectorClient;
    }
}
