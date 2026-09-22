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
import dynamic.mapper.connector.googlepubsub.GooglePubSubClient;
import dynamic.mapper.connector.http.HttpClient;
import dynamic.mapper.connector.httppolling.HttpPollingConnector;
import dynamic.mapper.connector.kafka.KafkaClientV2;
import dynamic.mapper.connector.mqtt.MQTT3Client;
import dynamic.mapper.connector.mqtt.MQTT5Client;
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
 * <p>Extracted from {@link ServiceRegistry} so that connector creation
 * logic lives in a dedicated component with a clear single responsibility.
 * {@link ServiceRegistry} no longer needs to import every connector class.
 */
@Slf4j
@Component
public class ConnectorClientFactory {

    private final ServiceRegistry serviceRegistry;
    private final ConnectorRegistry connectorRegistry;

    @Value("${C8Y_BASEURL_PULSAR:}")
    private String mqttServicePulsarUrl;

    public ConnectorClientFactory(ServiceRegistry serviceRegistry, ConnectorRegistry connectorRegistry) {
        this.serviceRegistry = serviceRegistry;
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
                    connectorClient = new MQTT3Client(serviceRegistry, connectorRegistry,
                            connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                } else {
                    connectorClient = new MQTT5Client(serviceRegistry, connectorRegistry,
                            connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                }
                log.info("{} - MQTT Connector {} created, identifier: {}", tenant, version,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE:
                log.warn("{} - Connector type CUMULOCITY_MQTT_SERVICE (identifier: {}) is no longer supported and has been removed. Use CUMULOCITY_MQTT_SERVICE_PULSAR instead.",
                        tenant, connectorConfiguration.getIdentifier());
                break;

            case KAFKA:
                connectorClient = new KafkaClientV2(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - Kafka Connector V2 created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case HTTP:
                connectorClient = new HttpClient(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - HTTP Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case WEB_HOOK:
                connectorClient = new WebHook(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - WebHook Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case WEB_HOOK_INTERNAL:
                connectorClient = new WebHookInternal(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - WebHook Internal Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case PULSAR:
                connectorClient = new PulsarConnectorClient(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - Pulsar Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case CUMULOCITY_MQTT_SERVICE_PULSAR:
                if (isPulsarAvailable(tenant)) {
                    connectorClient = new MQTTServicePulsarClient(serviceRegistry, connectorRegistry,
                            connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                    log.info("{} - MQTTService Pulsar Connector created, identifier: {}", tenant,
                            connectorConfiguration.getIdentifier());
                }
                break;

            case AMQP_091:
                connectorClient = new AMQPClient(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - AMQP Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case AMQP_10:
                connectorClient = new AMQP10Client(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - AMQP 1.0 Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case TEST:
                connectorClient = new TestClient(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - TestClient Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case GOOGLE_PUBSUB:
                connectorClient = new GooglePubSubClient(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - Google Pub/Sub Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            case REST_POLLING:
                connectorClient = new HttpPollingConnector(serviceRegistry, connectorRegistry,
                        connectorConfiguration, null, additionalSubscriptionIdTest, tenant);
                log.info("{} - REST Polling Connector created, identifier: {}", tenant,
                        connectorConfiguration.getIdentifier());
                break;

            default:
                log.warn("{} - Unknown connector type: {}", tenant, connectorConfiguration.getConnectorType());
                break;
        }

        return connectorClient;
    }
}
