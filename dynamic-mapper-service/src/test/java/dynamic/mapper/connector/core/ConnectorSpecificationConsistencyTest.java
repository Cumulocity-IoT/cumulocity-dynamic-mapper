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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural rules every connector specification has to follow, so that the connector form, the validation in the
 * backend and the stored configurations all interpret a property the same way.
 */
class ConnectorSpecificationConsistencyTest {

    private static Map<String, Supplier<AConnectorClient>> connectors() {
        Map<String, Supplier<AConnectorClient>> connectors = new LinkedHashMap<>();
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
        return connectors;
    }

    private static void forEachProperty(java.util.function.Consumer<Problem> rule) {
        connectors().forEach((name, factory) -> {
            ConnectorSpecification spec = factory.get().getConnectorSpecification();
            spec.getProperties().forEach((key, property) -> rule.accept(new Problem(name, spec, key, property)));
        });
    }

    private record Problem(String connector, ConnectorSpecification spec, String key, ConnectorProperty property) {
        String at() {
            return connector + "." + key;
        }
    }

    private static void assertNoProblems(String rule, List<String> problems) {
        assertTrue(problems.isEmpty(), rule + ": " + String.join("; ", problems));
    }

    @Test
    void defaultValueMatchesThePropertyType() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            Object def = p.property().getDefaultValue();
            if (def == null) {
                return;
            }
            boolean ok = switch (p.property().getType()) {
                case BOOLEAN_PROPERTY -> def instanceof Boolean;
                case NUMERIC_PROPERTY -> def instanceof Number;
                case MAP_PROPERTY -> def instanceof Map;
                case OPTION_PROPERTY -> def instanceof String && p.property().getOptions() != null
                        && p.property().getOptions().containsKey(def);
                default -> def instanceof String;
            };
            if (!ok) {
                problems.add(p.at() + " default " + def + " (" + def.getClass().getSimpleName() + ") for "
                        + p.property().getType());
            }
        });
        assertNoProblems("Default value does not fit the type or the options", problems);
    }

    @Test
    void everyOptionPropertyHasADefault() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            if (p.property().getType() == ConnectorPropertyType.OPTION_PROPERTY && p.property().getDefaultValue() == null) {
                problems.add(p.at());
            }
        });
        assertNoProblems("An option property without a default starts empty and cannot be cleared again", problems);
    }

    @Test
    void onlyOptionPropertiesHaveOptions() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            boolean isOption = p.property().getType() == ConnectorPropertyType.OPTION_PROPERTY;
            boolean hasOptions = p.property().getOptions() != null && !p.property().getOptions().isEmpty();
            if (isOption != hasOptions) {
                problems.add(p.at() + " type " + p.property().getType() + " options " + p.property().getOptions());
            }
        });
        assertNoProblems("An option property needs options and only option properties have them", problems);
    }

    @Test
    void conditionsReferToAnExistingPropertyAndItsValues() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            ConnectorPropertyCondition condition = p.property().getCondition();
            if (condition == null) {
                return;
            }
            ConnectorProperty parent = p.spec().getProperties().get(condition.getKey());
            if (parent == null || condition.getKey().equals(p.key())) {
                problems.add(p.at() + " depends on " + condition.getKey());
                return;
            }
            if (condition.getAnyOf() == null || condition.getAnyOf().length == 0) {
                problems.add(p.at() + " has an empty anyOf");
                return;
            }
            for (String value : condition.getAnyOf()) {
                boolean ok = switch (parent.getType()) {
                    case BOOLEAN_PROPERTY -> "true".equals(value) || "false".equals(value);
                    case OPTION_PROPERTY -> parent.getOptions() != null && parent.getOptions().containsKey(value);
                    default -> true;
                };
                if (!ok) {
                    problems.add(p.at() + " waits for " + condition.getKey() + "=" + value + " which that property cannot have");
                }
            }
        });
        assertNoProblems("Invalid condition", problems);
    }

    @Test
    void conditionsHaveNoCycle() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            Set<String> seen = new HashSet<>();
            String current = p.key();
            while (current != null) {
                if (!seen.add(current)) {
                    problems.add(p.at() + " is part of a condition cycle");
                    return;
                }
                ConnectorProperty property = p.spec().getProperties().get(current);
                current = property == null || property.getCondition() == null ? null : property.getCondition().getKey();
            }
        });
        assertNoProblems("Condition cycle", problems);
    }

    @Test
    void orderIsUniqueWithinAGroup() {
        List<String> problems = new ArrayList<>();
        connectors().forEach((name, factory) -> {
            Map<String, String> seen = new HashMap<>();
            factory.get().getConnectorSpecification().getProperties().forEach((key, property) -> {
                String slot = property.getGroup() + "#" + property.getOrder();
                String other = seen.put(slot, key);
                if (other != null) {
                    problems.add(name + "." + key + " and " + other + " both have order " + property.getOrder()
                            + " in group " + property.getGroup());
                }
            });
        });
        assertNoProblems("Order is ambiguous", problems);
    }

    @Test
    void aRequiredPropertyThatTheUserCannotEditHasADefault() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            ConnectorProperty property = p.property();
            boolean userCannotEdit = Boolean.TRUE.equals(property.getHidden()) || Boolean.TRUE.equals(property.getReadonly());
            if (Boolean.TRUE.equals(property.getRequired()) && userCannotEdit && property.getDefaultValue() == null) {
                problems.add(p.at());
            }
        });
        assertNoProblems("Required, but hidden or read-only without a default", problems);
    }

    @Test
    void aHiddenPropertyIsReadOnly() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            if (Boolean.TRUE.equals(p.property().getHidden()) && !Boolean.TRUE.equals(p.property().getReadonly())) {
                problems.add(p.at());
            }
        });
        assertNoProblems("A hidden property the user cannot see must not be editable either", problems);
    }

    @Test
    void sensitivePropertiesHaveNoDefault() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            if (p.spec().isPropertySensitive(p.key()) && p.property().getDefaultValue() != null) {
                problems.add(p.at());
            }
        });
        assertNoProblems("A secret must not have a default value", problems);
    }

    @Test
    void everyVisiblePropertyHasADescriptionAndATitleCaseSafeLabel() {
        List<String> problems = new ArrayList<>();
        forEachProperty(p -> {
            ConnectorProperty property = p.property();
            if (Boolean.TRUE.equals(property.getHidden())) {
                return;
            }
            if (property.getDescription() == null || property.getDescription().isBlank()) {
                problems.add(p.at() + " has no description");
            }
            boolean camelCase = p.key().matches(".*[a-z][A-Z].*");
            if (camelCase && (property.getLabel() == null || property.getLabel().isBlank())) {
                problems.add(p.at() + " has no label (the UI would show the key in Title Case)");
            }
        });
        assertNoProblems("Visible properties need a description, and a label when the key has several words", problems);
    }
}
