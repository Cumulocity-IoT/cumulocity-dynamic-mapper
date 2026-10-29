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

package dynamic.mapper.connector.core;

import dynamic.mapper.connector.amqp.AMQP10Client;
import dynamic.mapper.connector.amqp.AMQPClient;
import dynamic.mapper.connector.core.client.AConnectorClient;
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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every connector property needs a display group from {@link ConnectorPropertyGroup}; the connector form
 * renders one legend per group and an ungrouped property would end up without one.
 */
class ConnectorPropertyGroupTest {

    private static final Set<String> KNOWN_GROUPS = knownGroups();

    private static Set<String> knownGroups() {
        try {
            java.util.LinkedHashSet<String> groups = new java.util.LinkedHashSet<>();
            for (Field field : ConnectorPropertyGroup.class.getFields()) {
                groups.add((String) field.get(null));
            }
            return groups;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyConnectorPropertyHasAKnownGroup() {
        Map<String, Supplier<AConnectorClient>> connectors = new java.util.LinkedHashMap<>();
        connectors.put("AMQP 0.9.1", AMQPClient::new);
        connectors.put("AMQP 1.0", AMQP10Client::new);
        connectors.put("Google Pub/Sub", GooglePubSubClient::new);
        connectors.put("HTTP", HttpClient::new);
        connectors.put("HTTP polling", HttpPollingConnector::new);
        connectors.put("Kafka", KafkaClientV2::new);
        connectors.put("MQTT 3", MQTT3Client::new);
        connectors.put("MQTT 5", MQTT5Client::new);
        connectors.put("Cumulocity MQTT Service Pulsar", MQTTServicePulsarClient::new);
        connectors.put("Pulsar", PulsarConnectorClient::new);
        connectors.put("Test", TestClient::new);
        connectors.put("Webhook", WebHook::new);
        connectors.put("Webhook internal", WebHookInternal::new);

        List<String> problems = new ArrayList<>();
        connectors.forEach((name, factory) -> {
            ConnectorSpecification spec = factory.get().getConnectorSpecification();
            spec.getProperties().forEach((key, property) -> {
                if (property.getGroup() == null || !KNOWN_GROUPS.contains(property.getGroup())) {
                    problems.add(name + "." + key + " -> " + property.getGroup());
                }
            });
        });

        assertTrue(problems.isEmpty(), "Properties without a known group: " + problems.stream()
                .collect(Collectors.joining(", ")));
    }
}
