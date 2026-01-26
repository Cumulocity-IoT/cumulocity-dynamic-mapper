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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.model.Direction;

/**
 * Builder for creating ConnectorSpecification instances with a fluent API.
 * Provides a more readable and maintainable way to define connector specifications.
 *
 * <p>Example usage:</p>
 * <pre>
 * ConnectorSpecification spec = ConnectorSpecificationBuilder.create()
 *     .name("MQTT Connector")
 *     .description("Connector for MQTT brokers")
 *     .connectorType(ConnectorType.MQTT)
 *     .supportsDirections(Direction.INBOUND, Direction.OUTBOUND)
 *     .property("host", ConnectorPropertyBuilder.requiredString()
 *         .order(1)
 *         .description("MQTT broker host")
 *         .build())
 *     .property("port", ConnectorPropertyBuilder.requiredNumeric()
 *         .order(2)
 *         .defaultValue(1883)
 *         .build())
 *     .build();
 * </pre>
 */
public class ConnectorSpecificationBuilder {
    private String name;
    private String description;
    private ConnectorType connectorType;
    private boolean singleton = false;
    private Map<String, ConnectorProperty> properties = new LinkedHashMap<>();
    private boolean supportsMessageContext = false;
    private List<Direction> supportedDirections = new ArrayList<>();

    private ConnectorSpecificationBuilder() {}

    /**
     * Creates a new builder instance
     */
    public static ConnectorSpecificationBuilder create() {
        return new ConnectorSpecificationBuilder();
    }

    /**
     * Creates a new builder instance with name and type pre-populated
     */
    public static ConnectorSpecificationBuilder create(String name, ConnectorType connectorType) {
        return new ConnectorSpecificationBuilder()
            .name(name)
            .connectorType(connectorType);
    }

    public ConnectorSpecificationBuilder name(String name) {
        this.name = name;
        return this;
    }

    public ConnectorSpecificationBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ConnectorSpecificationBuilder connectorType(ConnectorType connectorType) {
        this.connectorType = connectorType;
        return this;
    }

    public ConnectorSpecificationBuilder singleton(boolean singleton) {
        this.singleton = singleton;
        return this;
    }

    public ConnectorSpecificationBuilder supportsMessageContext(boolean supportsMessageContext) {
        this.supportsMessageContext = supportsMessageContext;
        return this;
    }

    /**
     * Add a single property to the specification
     */
    public ConnectorSpecificationBuilder property(String key, ConnectorProperty property) {
        this.properties.put(key, property);
        return this;
    }

    /**
     * Add a property using a builder (inline)
     */
    public ConnectorSpecificationBuilder property(String key, ConnectorPropertyBuilder propertyBuilder) {
        this.properties.put(key, propertyBuilder.build());
        return this;
    }

    /**
     * Add multiple properties at once
     */
    public ConnectorSpecificationBuilder properties(Map<String, ConnectorProperty> properties) {
        this.properties.putAll(properties);
        return this;
    }

    /**
     * Add a supported direction
     */
    public ConnectorSpecificationBuilder supportsDirection(Direction direction) {
        this.supportedDirections.add(direction);
        return this;
    }

    /**
     * Add multiple supported directions
     */
    public ConnectorSpecificationBuilder supportsDirections(Direction... directions) {
        this.supportedDirections.addAll(Arrays.asList(directions));
        return this;
    }

    /**
     * Set the supported directions list
     */
    public ConnectorSpecificationBuilder supportedDirections(List<Direction> directions) {
        this.supportedDirections = new ArrayList<>(directions);
        return this;
    }

    /**
     * Build the ConnectorSpecification instance
     */
    public ConnectorSpecification build() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("ConnectorSpecification name must be specified");
        }
        if (connectorType == null) {
            throw new IllegalStateException("ConnectorSpecification connectorType must be specified");
        }
        if (supportedDirections.isEmpty()) {
            throw new IllegalStateException("ConnectorSpecification must support at least one direction");
        }

        return new ConnectorSpecification(
            name,
            description,
            connectorType,
            singleton,
            properties,
            supportsMessageContext,
            supportedDirections
        );
    }
}
