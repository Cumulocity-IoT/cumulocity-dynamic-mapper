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

package dynamic.mapper.connector.core;

import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.Direction;

/**
 * Example demonstrating the old vs new way of creating ConnectorSpecifications
 */
public class ConnectorSpecificationBuilderExample {

    // ========== OLD WAY (VERBOSE) ==========
    public ConnectorSpecification oldWayExample() {
        var configProps = new java.util.LinkedHashMap<String, ConnectorProperty>();

        var tlsCondition = new ConnectorPropertyCondition("protocol",
                new String[] { "mqtts://", "wss://" });

        configProps.put("protocol",
                new ConnectorProperty(null, true, 1, ConnectorPropertyType.OPTION_PROPERTY, false, false,
                        "mqtt://",
                        java.util.Map.of(
                                "mqtt://", "mqtt://",
                                "mqtts://", "mqtts://",
                                "ws://", "ws://",
                                "wss://", "wss://"),
                        null));

        configProps.put("mqttHost",
                new ConnectorProperty(null, true, 2, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("mqttPort",
                new ConnectorProperty(null, true, 3, ConnectorPropertyType.NUMERIC_PROPERTY, false, false,
                        null, null, null));

        configProps.put("user",
                new ConnectorProperty(null, false, 4, ConnectorPropertyType.STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("password",
                new ConnectorProperty(null, false, 5, ConnectorPropertyType.SENSITIVE_STRING_PROPERTY, false, false,
                        null, null, null));

        configProps.put("useSelfSignedCertificate",
                new ConnectorProperty(null, false, 6, ConnectorPropertyType.BOOLEAN_PROPERTY, false, false,
                        false, null, tlsCondition));

        return new ConnectorSpecification(
                "MQTT Connector",
                "Connector for MQTT brokers",
                ConnectorType.MQTT,
                false,
                configProps,
                false,
                java.util.Arrays.asList(Direction.INBOUND, Direction.OUTBOUND));
    }

    // ========== NEW WAY (ELEGANT) ==========
    public ConnectorSpecification newWayExample() {
        return ConnectorSpecificationBuilder.create("MQTT Connector", ConnectorType.MQTT)
                .description("Connector for MQTT brokers")
                .supportsDirections(Direction.INBOUND, Direction.OUTBOUND)

                .property("protocol", ConnectorPropertyBuilder.requiredOption()
                        .order(1)
                        .defaultValue("mqtt://")
                        .options("mqtt://", "mqtts://", "ws://", "wss://"))

                .property("mqttHost", ConnectorPropertyBuilder.requiredString()
                        .order(2))

                .property("mqttPort", ConnectorPropertyBuilder.requiredNumeric()
                        .order(3))

                .property("user", ConnectorPropertyBuilder.optionalString()
                        .order(4))

                .property("password", ConnectorPropertyBuilder.optionalSensitive()
                        .order(5))

                .property("useSelfSignedCertificate", ConnectorPropertyBuilder.optionalBoolean()
                        .order(6)
                        .defaultValue(false)
                        .condition("protocol", "mqtts://", "wss://"))

                .build();
    }

    // ========== EVEN MORE CONCISE FOR SIMPLE PROPERTIES ==========
    public ConnectorSpecification ultraConciseExample() {
        return ConnectorSpecificationBuilder.create("Simple Connector", ConnectorType.MQTT)
                .description("Ultra concise example")
                .supportsDirections(Direction.INBOUND, Direction.OUTBOUND)
                .property("host", ConnectorPropertyBuilder.requiredString().order(1))
                .property("port", ConnectorPropertyBuilder.requiredNumeric().order(2).defaultValue(1883))
                .property("username", ConnectorPropertyBuilder.optionalString().order(3))
                .property("password", ConnectorPropertyBuilder.optionalSensitive().order(4))
                .property("secure", ConnectorPropertyBuilder.optionalBoolean().order(5).defaultValue(false))
                .build();
    }

    // ========== COMPLEX EXAMPLE WITH CONDITIONS ==========
    public ConnectorSpecification complexExample() {
        return ConnectorSpecificationBuilder.create("Advanced MQTT", ConnectorType.MQTT)
                .description("Advanced connector with complex properties")
                .supportsDirections(Direction.INBOUND, Direction.OUTBOUND)
                .supportsMessageContext(true)

                // Protocol selection
                .property("protocol", ConnectorPropertyBuilder.requiredOption()
                        .order(1)
                        .description("Transport protocol to use")
                        .defaultValue("mqtt://")
                        .options("mqtt://", "mqtts://", "ws://", "wss://"))

                // Basic connection properties
                .property("host", ConnectorPropertyBuilder.requiredString()
                        .order(2)
                        .description("Broker hostname or IP address"))

                .property("port", ConnectorPropertyBuilder.requiredNumeric()
                        .order(3)
                        .defaultValue(1883))

                // Authentication
                .property("username", ConnectorPropertyBuilder.optionalString()
                        .order(4)
                        .description("Username for authentication"))

                .property("password", ConnectorPropertyBuilder.optionalSensitive()
                        .order(5)
                        .description("Password for authentication"))

                // TLS Configuration (only visible when using secure protocols)
                .property("useSelfSignedCert", ConnectorPropertyBuilder.optionalBoolean()
                        .order(6)
                        .defaultValue(false)
                        .description("Enable self-signed certificate")
                        .condition("protocol", "mqtts://", "wss://"))

                .property("certificate", ConnectorPropertyBuilder.largeText()
                        .order(7)
                        .description("PEM formatted certificate chain")
                        .condition("useSelfSignedCert", "true"))

                // WebSocket path (only for WS/WSS)
                .property("wsPath", ConnectorPropertyBuilder.optionalString()
                        .order(8)
                        .description("WebSocket server path")
                        .defaultValue("/mqtt")
                        .condition("protocol", "ws://", "wss://"))

                .build();
    }
}
