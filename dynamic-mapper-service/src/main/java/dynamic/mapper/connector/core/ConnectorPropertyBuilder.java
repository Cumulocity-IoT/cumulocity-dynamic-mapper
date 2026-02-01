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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder for creating ConnectorProperty instances with a fluent API.
 * Provides a more readable and maintainable way to define connector properties.
 *
 * <p>Example usage:</p>
 * <pre>
 * ConnectorProperty hostProperty = ConnectorPropertyBuilder.create()
 *     .type(ConnectorPropertyType.STRING_PROPERTY)
 *     .required(true)
 *     .order(1)
 *     .description("The host address of the broker")
 *     .build();
 * </pre>
 */
public class ConnectorPropertyBuilder {
    private String description;
    private Boolean required = false;
    private Integer order = 0;
    private ConnectorPropertyType type;
    private Boolean readonly = false;
    private Boolean hidden = false;
    private Object defaultValue;
    private Map<String, String> options;
    private ConnectorPropertyCondition condition;

    private ConnectorPropertyBuilder() {}

    /**
     * Creates a new builder instance
     */
    public static ConnectorPropertyBuilder create() {
        return new ConnectorPropertyBuilder();
    }

    /**
     * Creates a new builder instance with a specific type
     */
    public static ConnectorPropertyBuilder create(ConnectorPropertyType type) {
        return new ConnectorPropertyBuilder().type(type);
    }

    /**
     * Creates a required string property builder
     */
    public static ConnectorPropertyBuilder requiredString() {
        return create(ConnectorPropertyType.STRING_PROPERTY).required(true);
    }

    /**
     * Creates an optional string property builder
     */
    public static ConnectorPropertyBuilder optionalString() {
        return create(ConnectorPropertyType.STRING_PROPERTY).required(false);
    }

    /**
     * Creates a required numeric property builder
     */
    public static ConnectorPropertyBuilder requiredNumeric() {
        return create(ConnectorPropertyType.NUMERIC_PROPERTY).required(true);
    }

    /**
     * Creates a required sensitive string property builder (for passwords, tokens, etc.)
     */
    public static ConnectorPropertyBuilder requiredSensitive() {
        return create(ConnectorPropertyType.SENSITIVE_STRING_PROPERTY).required(true);
    }

    /**
     * Creates an optional sensitive string property builder
     */
    public static ConnectorPropertyBuilder optionalSensitive() {
        return create(ConnectorPropertyType.SENSITIVE_STRING_PROPERTY).required(false);
    }

    /**
     * Creates a required boolean property builder
     */
    public static ConnectorPropertyBuilder requiredBoolean() {
        return create(ConnectorPropertyType.BOOLEAN_PROPERTY).required(true);
    }

    /**
     * Creates an optional boolean property builder
     */
    public static ConnectorPropertyBuilder optionalBoolean() {
        return create(ConnectorPropertyType.BOOLEAN_PROPERTY).required(false);
    }

    /**
     * Creates a required option/dropdown property builder
     */
    public static ConnectorPropertyBuilder requiredOption() {
        return create(ConnectorPropertyType.OPTION_PROPERTY).required(true);
    }

    /**
     * Creates an optional option/dropdown property builder
     */
    public static ConnectorPropertyBuilder optionalOption() {
        return create(ConnectorPropertyType.OPTION_PROPERTY).required(false);
    }

    /**
     * Creates a large text area property builder
     */
    public static ConnectorPropertyBuilder largeText() {
        return create(ConnectorPropertyType.STRING_LARGE_PROPERTY).required(false);
    }

    public ConnectorPropertyBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ConnectorPropertyBuilder required(boolean required) {
        this.required = required;
        return this;
    }

    public ConnectorPropertyBuilder order(int order) {
        this.order = order;
        return this;
    }

    public ConnectorPropertyBuilder type(ConnectorPropertyType type) {
        this.type = type;
        return this;
    }

    public ConnectorPropertyBuilder readonly(boolean readonly) {
        this.readonly = readonly;
        return this;
    }

    public ConnectorPropertyBuilder hidden(boolean hidden) {
        this.hidden = hidden;
        return this;
    }

    public ConnectorPropertyBuilder defaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public ConnectorPropertyBuilder options(Map<String, String> options) {
        this.options = options;
        return this;
    }

    /**
     * Add multiple options as varargs
     * Example: .options("mqtt://", "mqtts://", "ws://", "wss://")
     */
    public ConnectorPropertyBuilder options(String... optionValues) {
        this.options = new LinkedHashMap<>();
        for (String value : optionValues) {
            this.options.put(value, value);
        }
        return this;
    }

    /**
     * Add options with custom keys and values
     * Example: .options("mqtt", "MQTT Protocol", "mqtts", "MQTT over TLS")
     */
    public ConnectorPropertyBuilder optionsWithLabels(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value pairs must have even number of arguments");
        }
        this.options = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            this.options.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return this;
    }

    public ConnectorPropertyBuilder condition(ConnectorPropertyCondition condition) {
        this.condition = condition;
        return this;
    }

    /**
     * Convenience method to create a condition inline
     */
    public ConnectorPropertyBuilder condition(String key, String... anyOf) {
        this.condition = new ConnectorPropertyCondition(key, anyOf);
        return this;
    }

    /**
     * Build the ConnectorProperty instance
     */
    public ConnectorProperty build() {
        if (type == null) {
            throw new IllegalStateException("ConnectorProperty type must be specified");
        }
        return new ConnectorProperty(
            description,
            required,
            order,
            type,
            readonly,
            hidden,
            defaultValue,
            options,
            condition
        );
    }
}
