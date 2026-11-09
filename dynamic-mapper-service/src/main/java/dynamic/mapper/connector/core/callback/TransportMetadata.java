/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
 * @authors Christof Strack
 */

package dynamic.mapper.connector.core.callback;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects broker-specific message metadata (Kafka record headers, Pulsar message properties,
 * AMQP headers, MQTT 5 user properties, ...) into the flat string map carried by
 * {@link ConnectorMessage#getMetadata()} and delivered to Smart Functions and Java extensions as
 * {@code transportFields}.
 *
 * <p>Names are prefixed by origin so they cannot collide with the reserved {@code key} field:
 * {@code header.<name>}, {@code property.<name>}, {@code attribute.<name>} or a bare well-known
 * name such as {@code routingKey}. The collector is bounded (entry count and value length) because
 * the values come from the broker, not from us.
 */
public final class TransportMetadata {

    public static final String HEADER = "header.";
    public static final String PROPERTY = "property.";
    public static final String ATTRIBUTE = "attribute.";

    static final int MAX_ENTRIES = 50;
    static final int MAX_VALUE_LENGTH = 1024;

    private final Map<String, String> fields = new LinkedHashMap<>();

    public TransportMetadata put(String prefix, String name, String value) {
        if (name == null || value == null || fields.size() >= MAX_ENTRIES) {
            return this;
        }
        fields.put(prefix + name, value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value);
        return this;
    }

    /** Raw byte values (e.g. Kafka headers) are read as UTF-8 text. */
    public TransportMetadata put(String prefix, String name, byte[] value) {
        return value == null ? this : put(prefix, name, new String(value, StandardCharsets.UTF_8));
    }

    public TransportMetadata put(String prefix, String name, Object value) {
        if (value instanceof byte[] bytes) {
            return put(prefix, name, bytes);
        }
        return value == null ? this : put(prefix, name, value.toString());
    }

    /** @return the collected fields, or {@code null} if none, so an unset value stays cheap. */
    public Map<String, String> build() {
        return fields.isEmpty() ? null : Map.copyOf(fields);
    }
}
