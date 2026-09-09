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
package dynamic.mapper.processor.model;

import java.util.Collections;
import java.util.Map;

/**
 * Input message wrapper passed to SMART_FUNCTION JavaScript code as the first argument ({@code msg}).
 *
 * <p>Intentionally avoids Lombok so that GraalVM's {@code allowPublicAccess(true)} can reliably
 * discover and expose both public fields and public getter methods to JavaScript.</p>
 *
 * <p>Fields are {@code public} so that both access styles work in JavaScript:</p>
 * <ul>
 *   <li>{@code msg.payload} — direct field access</li>
 *   <li>{@code msg.getPayload()} — Java-style getter (backward-compatible alias)</li>
 * </ul>
 *
 * <p>JavaScript usage:</p>
 * <pre>
 *   function onMessage(msg, context) {
 *       var payload        = msg.payload;          // or msg.getPayload()
 *       var topic          = msg.topic;            // or msg.getTopic()
 *       var client         = msg.clientId;         // inbound: MQTT client id; outbound: null
 *       var source         = msg.sourceId;         // outbound: C8Y device id; inbound: null
 *       var c8yType        = msg.cumulocityType;   // outbound: e.g. "measurement"; inbound: null
 *       var time           = msg.time;             // ISO-8601 receive timestamp (both directions)
 *       var transport      = msg.transportId;      // connector identifier, e.g. "my-mqtt-connector"
 *       var transportKey   = msg.transportFields["key"]; // e.g. the Kafka record key
 *   }
 * </pre>
 *
 * <p>{@code cumulocityType} is set by the outbound processor so that V2 Smart Functions can
 * perform discriminant narrowing on {@code msg.cumulocityType} (e.g. via a {@code switch}
 * statement) without casting. It is {@code null} for inbound messages.</p>
 */
public class InputMessage {

    public final Object payload;
    public final String topic;
    public final String clientId;
    public final String sourceId;
    /**
     * Lowercase C8y object type string that matches the TypeScript {@code C8yObjectType} union:
     * {@code "measurement"}, {@code "event"}, {@code "alarm"}, {@code "operation"}, or
     * {@code "managedObject"}.
     * Set by the outbound processor; {@code null} for inbound messages.
     */
    public final String cumulocityType;

    /** ISO-8601 timestamp captured when the message was received by the connector. */
    public final String time;

    /**
     * Identifier of the connector that delivered this message (e.g. the connector's configured name).
     * Set for inbound messages; {@code null} for outbound messages (which originate from C8Y).
     */
    public final String transportId;

    /**
     * Transport-specific key/value pairs (e.g. MQTT 5 user properties).
     * <p>
     * For Kafka this carries the consumed record's <b>key</b> under {@code "key"} — the record
     * key, not headers. It is delivered here rather than inside the payload, so it never appears
     * in the mapping's source template.
     * <p>
     * Never {@code null} — an empty map is returned when no transport fields are available.
     */
    public final Map<String, String> transportFields;

    /**
     * Full constructor.
     */
    public InputMessage(Object payload, String topic, String clientId, String sourceId, String cumulocityType,
                        String time, String transportId, Map<String, String> transportFields) {
        this.payload = payload;
        this.topic = topic;
        this.clientId = clientId;
        this.sourceId = sourceId;
        this.cumulocityType = cumulocityType;
        this.time = time;
        this.transportId = transportId;
        this.transportFields = transportFields != null ? transportFields : Collections.emptyMap();
    }

    /**
     * Backward-compatible constructor — {@code time}, {@code transportId}, and {@code transportFields}
     * default to {@code null} / empty map.
     */
    public InputMessage(Object payload, String topic, String clientId, String sourceId, String cumulocityType) {
        this(payload, topic, clientId, sourceId, cumulocityType, null, null, null);
    }

    /**
     * Backward-compatible constructor for inbound messages where {@code cumulocityType} is not
     * applicable.
     */
    public InputMessage(Object payload, String topic, String clientId, String sourceId) {
        this(payload, topic, clientId, sourceId, null, null, null, null);
    }

    /** Alias for {@link #payload}. Supports {@code msg.getPayload()} in JS mapping templates. */
    public Object getPayload() { return payload; }

    /** Alias for {@link #topic}. Supports {@code msg.getTopic()} in JS mapping templates. */
    public String getTopic() { return topic; }

    /** Alias for {@link #clientId}. Supports {@code msg.getClientId()} in JS mapping templates. */
    public String getClientId() { return clientId; }

    /** Alias for {@link #sourceId}. Supports {@code msg.getSourceId()} in JS mapping templates. */
    public String getSourceId() { return sourceId; }

    /** Alias for {@link #cumulocityType}. Supports {@code msg.getCumulocityType()} in JS mapping templates. */
    public String getCumulocityType() { return cumulocityType; }

    /** Alias for {@link #time}. Supports {@code msg.getTime()} in JS mapping templates. */
    public String getTime() { return time; }

    /** Alias for {@link #transportId}. Supports {@code msg.getTransportId()} in JS mapping templates. */
    public String getTransportId() { return transportId; }

    /** Alias for {@link #transportFields}. Supports {@code msg.getTransportFields()} in JS mapping templates. */
    public Map<String, String> getTransportFields() { return transportFields; }
}
