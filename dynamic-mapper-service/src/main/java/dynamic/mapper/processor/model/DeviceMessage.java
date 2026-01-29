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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * A (Pulsar) message received from a device or sent to a device
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DeviceMessage {

    /**
     * Cloud IDP and first step of tedge always gets an ArrayBuffer, but might be a
     * JS object if passing intermediate messages between steps in thin-edge
     */
    private Object payload;

    /**
     * What kind of operation is being performed, e.g. "create", "update", "delete",
     * "patch"
     */
    private String action;

    /**
     * Optional: Specifies which Cumulocity API type this device message should map to.
     * When set, this helps determine the target API endpoint for the message.
     * If not specified, the target API is derived from the topic or mapping configuration.
     *
     * Available values:
     * - MEASUREMENT ("measurement") - Time-series measurement data
     * - EVENT ("event") - Events from devices
     * - ALARM ("alarm") - Alarm notifications
     * - OPERATION ("operation") - Device operations/commands
     * - MANAGED_OBJECT ("managedObject") - Inventory/device objects
     *
     * @see CumulocityType
     */
    private CumulocityType cumulocityType;

    /** External ID to lookup (and optionally create), optional */
    private Object externalSource; // ExternalSource[] | ExternalSource

    /** Identifier for the source/dest transport e.g. "mqtt", "opc-ua" etc. */
    private String transportId;

    /** The topic on the transport (e.g. MQTT topic) */
    private String topic;

    /** Transport/MQTT client ID */
    private String clientId;

    /** Set retain flag */
    private Boolean retain;

    /** Dictionary of transport/MQTT-specific fields/properties/headers */
    private Map<String, String> transportFields;

    /** Timestamp of incoming Pulsar message; does nothing when sending */
    private Instant time;

    // ==================== Builder Factory Methods ====================

    /**
     * Create a builder for a device message with the specified topic.
     *
     * @param topic The target topic
     * @return A new Builder instance
     */
    public static Builder forTopic(String topic) {
        return new Builder(topic);
    }

    /**
     * Create a builder for a device message.
     *
     * @return A new Builder instance
     */
    public static Builder create() {
        return new Builder();
    }

    // ==================== Device Message Builder ====================

    /**
     * Builder for creating DeviceMessage objects.
     *
     * <p>Example usage:</p>
     * <pre>
     * DeviceMessage msg = DeviceMessage.forTopic("device/messages")
     *     .payload("{\"temperature\": 25.5}")
     *     .retain(false)
     *     .clientId("device-001")
     *     .transportField("qos", "1")
     *     .build();
     * </pre>
     */
    public static class Builder {
        private Object payload;
        private String action;
        private CumulocityType cumulocityType;
        private Object externalSource;
        private String transportId;
        private String topic;
        private String clientId;
        private Boolean retain;
        private Map<String, String> transportFields = new HashMap<>();
        private Instant time;

        /**
         * Create a builder with no topic.
         */
        public Builder() {
        }

        /**
         * Create a builder with the specified topic.
         *
         * @param topic The target topic
         */
        public Builder(String topic) {
            this.topic = topic;
        }

        /**
         * Set the message payload.
         *
         * @param payload The payload (can be String, byte[], Map, or any object)
         * @return This builder
         */
        public Builder payload(Object payload) {
            this.payload = payload;
            return this;
        }

        /**
         * Set the action.
         *
         * @param action The action (create, update, delete, patch)
         * @return This builder
         */
        public Builder action(String action) {
            this.action = action;
            return this;
        }

        /**
         * Set the Cumulocity type this message maps to.
         *
         * @param cumulocityType The Cumulocity type
         * @return This builder
         */
        public Builder cumulocityType(CumulocityType cumulocityType) {
            this.cumulocityType = cumulocityType;
            return this;
        }

        /**
         * Set the external source for device lookup.
         *
         * @param externalSource The external source
         * @return This builder
         */
        public Builder externalSource(Object externalSource) {
            this.externalSource = externalSource;
            return this;
        }

        /**
         * Set the transport ID.
         *
         * @param transportId The transport ID (e.g., "mqtt", "opc-ua")
         * @return This builder
         */
        public Builder transportId(String transportId) {
            this.transportId = transportId;
            return this;
        }

        /**
         * Set the topic.
         *
         * @param topic The topic
         * @return This builder
         */
        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        /**
         * Set the client ID.
         *
         * @param clientId The client ID
         * @return This builder
         */
        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        /**
         * Set the retain flag.
         *
         * @param retain Whether to retain the message
         * @return This builder
         */
        public Builder retain(Boolean retain) {
            this.retain = retain;
            return this;
        }

        /**
         * Set the retain flag to true.
         *
         * @return This builder
         */
        public Builder retain() {
            this.retain = true;
            return this;
        }

        /**
         * Add a transport field.
         *
         * @param key The field key
         * @param value The field value
         * @return This builder
         */
        public Builder transportField(String key, String value) {
            this.transportFields.put(key, value);
            return this;
        }

        /**
         * Set all transport fields.
         *
         * @param transportFields The transport fields map
         * @return This builder
         */
        public Builder transportFields(Map<String, String> transportFields) {
            this.transportFields = new HashMap<>(transportFields);
            return this;
        }

        /**
         * Set the message time.
         *
         * @param time The message timestamp
         * @return This builder
         */
        public Builder time(Instant time) {
            this.time = time;
            return this;
        }

        /**
         * Build the DeviceMessage.
         *
         * @return A new DeviceMessage instance
         */
        public DeviceMessage build() {
            DeviceMessage msg = new DeviceMessage();
            msg.setPayload(payload);
            msg.setAction(action);
            msg.setCumulocityType(cumulocityType);
            msg.setExternalSource(externalSource);
            msg.setTransportId(transportId);
            msg.setTopic(topic);
            msg.setClientId(clientId);
            msg.setRetain(retain);
            msg.setTransportFields(transportFields.isEmpty() ? null : transportFields);
            msg.setTime(time);
            return msg;
        }
    }
}
