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
package dynamic.mapper.processor.flow;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A create/update to a Cumulocity domain object, sent to/from core or
 * IceFlow/offloading.
 *
 * It's important to note these are used in both directions, so some fields that
 * are
 * always set when receiving may not be permitted to set when sending (e.g. for
 * an update).
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CumulocityObject {

    /**
     * A payload similar to the WebSDK and/or to that used in the C8Y REST/SmartREST
     * API.
     * Main difference is ID handling - when providing an externalSource you don't
     * need to
     * provide an "id" in the payload as you would in those APIs.
     */
    private Object payload;

    /**
     * Which type in the C8Y API is being modified. Singular not plural.
     * The presence of this field also serves as a discriminator to identify this
     * object as a Cumulocity object.
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

    /** What kind of operation is being performed on this type */
    private String action; // "create" | "update" | "delete" | "patch"

    /**
     * Since we usually don't know the C8Y ID to put in the payload, the flow can
     * specify
     * a single external ID to lookup (and optionally create).
     * Mandatory to include one item when sending this (unless the internal C8Y "id"
     * is
     * known and passed in the payload, or it's an operation where there's a
     * dedicated field).
     *
     * When a Cumulocity message (e.g. operation) is received, this will contain a
     * list of
     * ALL external ids for this Cumulocity device.
     */
    private List<ExternalId> externalSource;

    /**
     * For messages sent by the flow, this is "cumulocity" by default, but can be
     * set to
     * other options for other destinations.
     * For messages received by the flow this is not set.
     */
    private Destination destination;

    /**
     * Dictionary of contextData, contains additional properties for processing a
     * mapping
     * deviceName, deviceType: specify deviceName, deviceType for creating a new
     * device
     * processingMode: specify processing mode, either 'PERSISTENT', 'TRANSIENT'
     * attachmentName: specify name of attachment, when processing an EVENT with
     * attachment
     * attachmentType: specify type of attachment, when processing an EVENT with
     * attachment
     * attachmentData: specify data of attachment, when processing an EVENT with
     * attachment
     *
     */
    private Map<String, String> contextData;

    // ==================== Builder Factory Methods ====================

    /**
     * Create a builder for a measurement.
     *
     * @return A new MeasurementBuilder instance
     */
    public static MeasurementBuilder measurement() {
        return new MeasurementBuilder();
    }

    /**
     * Create a builder for an event.
     *
     * @return A new EventBuilder instance
     */
    public static EventBuilder event() {
        return new EventBuilder();
    }

    /**
     * Create a builder for an alarm.
     *
     * @return A new AlarmBuilder instance
     */
    public static AlarmBuilder alarm() {
        return new AlarmBuilder();
    }

    /**
     * Create a builder for an operation.
     *
     * @return A new OperationBuilder instance
     */
    public static OperationBuilder operation() {
        return new OperationBuilder();
    }

    /**
     * Create a builder for a managed object (inventory).
     *
     * @return A new ManagedObjectBuilder instance
     */
    public static ManagedObjectBuilder managedObject() {
        return new ManagedObjectBuilder();
    }

    // ==================== Base Builder ====================

    /**
     * Base builder class with common functionality for all Cumulocity object types.
     */
    public static abstract class BaseBuilder<T extends BaseBuilder<T>> {
        protected Map<String, Object> payload = new HashMap<>();
        protected String action = "create";
        protected List<ExternalId> externalSource = new ArrayList<>();
        protected Destination destination = Destination.CUMULOCITY;
        protected Map<String, String> contextData = new HashMap<>();

        @SuppressWarnings("unchecked")
        protected T self() {
            return (T) this;
        }

        /**
         * Set the action to perform (create, update, delete, patch).
         * Default is "create".
         *
         * @param action The action to perform
         * @return This builder
         */
        public T action(String action) {
            this.action = action;
            return self();
        }

        /**
         * Add an external ID for device lookup.
         *
         * @param externalId The external device identifier
         * @param type The external ID type (e.g., "c8y_Serial")
         * @return This builder
         */
        public T externalId(String externalId, String type) {
            this.externalSource.add(new ExternalId(externalId, type));
            return self();
        }

        /**
         * Set the destination for this object.
         * Default is CUMULOCITY.
         *
         * @param destination The destination
         * @return This builder
         */
        public T destination(Destination destination) {
            this.destination = destination;
            return self();
        }

        /**
         * Add a context data entry.
         *
         * @param key The context data key
         * @param value The context data value
         * @return This builder
         */
        public T contextData(String key, String value) {
            this.contextData.put(key, value);
            return self();
        }

        /**
         * Set the device name for implicit device creation.
         *
         * @param deviceName The device name
         * @return This builder
         */
        public T deviceName(String deviceName) {
            return contextData("deviceName", deviceName);
        }

        /**
         * Set the device type for implicit device creation.
         *
         * @param deviceType The device type
         * @return This builder
         */
        public T deviceType(String deviceType) {
            return contextData("deviceType", deviceType);
        }

        /**
         * Set the processing mode.
         *
         * @param processingMode Either "PERSISTENT" or "TRANSIENT"
         * @return This builder
         */
        public T processingMode(String processingMode) {
            return contextData("processingMode", processingMode);
        }

        /**
         * Build the CumulocityObject.
         *
         * @param cumulocityType The Cumulocity type
         * @return A new CumulocityObject instance
         */
        protected CumulocityObject build(CumulocityType cumulocityType) {
            CumulocityObject obj = new CumulocityObject();
            obj.setPayload(payload);
            obj.setCumulocityType(cumulocityType);
            obj.setAction(action);
            obj.setExternalSource(externalSource);
            obj.setDestination(destination);
            obj.setContextData(contextData.isEmpty() ? null : contextData);
            return obj;
        }
    }

    // ==================== Measurement Builder ====================

    /**
     * Builder for creating measurement objects.
     */
    public static class MeasurementBuilder extends BaseBuilder<MeasurementBuilder> {

        /**
         * Set the measurement type.
         *
         * @param type The measurement type (e.g., "c8y_TemperatureMeasurement")
         * @return This builder
         */
        public MeasurementBuilder type(String type) {
            payload.put("type", type);
            return this;
        }

        /**
         * Set the measurement time.
         *
         * @param time The measurement time (ISO 8601 format)
         * @return This builder
         */
        public MeasurementBuilder time(String time) {
            payload.put("time", time);
            return this;
        }

        /**
         * Set the measurement time.
         *
         * @param time The measurement time
         * @return This builder
         */
        public MeasurementBuilder time(Object time) {
            payload.put("time", time);
            return this;
        }

        /**
         * Add a measurement fragment with series and values.
         *
         * @param fragment The fragment name (e.g., "c8y_Temperature")
         * @param series The series name (e.g., "T")
         * @param value The measurement value
         * @param unit The measurement unit (e.g., "C")
         * @return This builder
         */
        public MeasurementBuilder fragment(String fragment, String series, Object value, String unit) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fragmentMap = (Map<String, Object>) payload.computeIfAbsent(fragment, k -> new HashMap<>());
            Map<String, Object> seriesMap = new HashMap<>();
            seriesMap.put("value", value);
            seriesMap.put("unit", unit);
            fragmentMap.put(series, seriesMap);
            return this;
        }

        /**
         * Set the entire payload as a map.
         *
         * @param payload The complete payload
         * @return This builder
         */
        public MeasurementBuilder payload(Map<String, Object> payload) {
            this.payload = new HashMap<>(payload);
            return this;
        }

        /**
         * Build the measurement CumulocityObject.
         *
         * @return A new CumulocityObject for a measurement
         */
        public CumulocityObject build() {
            return build(CumulocityType.MEASUREMENT);
        }
    }

    // ==================== Event Builder ====================

    /**
     * Builder for creating event objects.
     */
    public static class EventBuilder extends BaseBuilder<EventBuilder> {

        /**
         * Set the event type.
         *
         * @param type The event type (e.g., "c8y_LocationUpdate")
         * @return This builder
         */
        public EventBuilder type(String type) {
            payload.put("type", type);
            return this;
        }

        /**
         * Set the event text.
         *
         * @param text The event text/description
         * @return This builder
         */
        public EventBuilder text(String text) {
            payload.put("text", text);
            return this;
        }

        /**
         * Set the event time.
         *
         * @param time The event time (ISO 8601 format or DateTime)
         * @return This builder
         */
        public EventBuilder time(Object time) {
            payload.put("time", time);
            return this;
        }

        /**
         * Add a custom property to the event.
         *
         * @param key The property key
         * @param value The property value
         * @return This builder
         */
        public EventBuilder property(String key, Object value) {
            payload.put(key, value);
            return this;
        }

        /**
         * Set the entire payload as a map.
         *
         * @param payload The complete payload
         * @return This builder
         */
        public EventBuilder payload(Map<String, Object> payload) {
            this.payload = new HashMap<>(payload);
            return this;
        }

        /**
         * Add an attachment to the event.
         *
         * @param name The attachment filename
         * @param type The attachment MIME type
         * @param data The attachment data (base64 encoded)
         * @return This builder
         */
        public EventBuilder attachment(String name, String type, String data) {
            contextData("attachmentName", name);
            contextData("attachmentType", type);
            contextData("attachmentData", data);
            return this;
        }

        /**
         * Build the event CumulocityObject.
         *
         * @return A new CumulocityObject for an event
         */
        public CumulocityObject build() {
            return build(CumulocityType.EVENT);
        }
    }

    // ==================== Alarm Builder ====================

    /**
     * Builder for creating alarm objects.
     */
    public static class AlarmBuilder extends BaseBuilder<AlarmBuilder> {

        /**
         * Set the alarm type.
         *
         * @param type The alarm type (e.g., "c8y_TemperatureAlarm")
         * @return This builder
         */
        public AlarmBuilder type(String type) {
            payload.put("type", type);
            return this;
        }

        /**
         * Set the alarm text.
         *
         * @param text The alarm text/description
         * @return This builder
         */
        public AlarmBuilder text(String text) {
            payload.put("text", text);
            return this;
        }

        /**
         * Set the alarm severity.
         *
         * @param severity The alarm severity (CRITICAL, MAJOR, MINOR, WARNING)
         * @return This builder
         */
        public AlarmBuilder severity(String severity) {
            payload.put("severity", severity);
            return this;
        }

        /**
         * Set the alarm time.
         *
         * @param time The alarm time (ISO 8601 format or DateTime)
         * @return This builder
         */
        public AlarmBuilder time(Object time) {
            payload.put("time", time);
            return this;
        }

        /**
         * Set the alarm status.
         *
         * @param status The alarm status (ACTIVE, ACKNOWLEDGED, CLEARED)
         * @return This builder
         */
        public AlarmBuilder status(String status) {
            payload.put("status", status);
            return this;
        }

        /**
         * Add a custom property to the alarm.
         *
         * @param key The property key
         * @param value The property value
         * @return This builder
         */
        public AlarmBuilder property(String key, Object value) {
            payload.put(key, value);
            return this;
        }

        /**
         * Set the entire payload as a map.
         *
         * @param payload The complete payload
         * @return This builder
         */
        public AlarmBuilder payload(Map<String, Object> payload) {
            this.payload = new HashMap<>(payload);
            return this;
        }

        /**
         * Build the alarm CumulocityObject.
         *
         * @return A new CumulocityObject for an alarm
         */
        public CumulocityObject build() {
            return build(CumulocityType.ALARM);
        }
    }

    // ==================== Operation Builder ====================

    /**
     * Builder for creating operation objects.
     */
    public static class OperationBuilder extends BaseBuilder<OperationBuilder> {

        /**
         * Set the operation description.
         *
         * @param description The operation description
         * @return This builder
         */
        public OperationBuilder description(String description) {
            payload.put("description", description);
            return this;
        }

        /**
         * Set the operation status.
         *
         * @param status The operation status (PENDING, EXECUTING, SUCCESSFUL, FAILED)
         * @return This builder
         */
        public OperationBuilder status(String status) {
            payload.put("status", status);
            return this;
        }

        /**
         * Add a custom fragment to the operation.
         *
         * @param fragmentName The fragment name (e.g., "c8y_Restart")
         * @param fragmentValue The fragment value
         * @return This builder
         */
        public OperationBuilder fragment(String fragmentName, Object fragmentValue) {
            payload.put(fragmentName, fragmentValue);
            return this;
        }

        /**
         * Add a custom property to the operation.
         *
         * @param key The property key
         * @param value The property value
         * @return This builder
         */
        public OperationBuilder property(String key, Object value) {
            payload.put(key, value);
            return this;
        }

        /**
         * Set the entire payload as a map.
         *
         * @param payload The complete payload
         * @return This builder
         */
        public OperationBuilder payload(Map<String, Object> payload) {
            this.payload = new HashMap<>(payload);
            return this;
        }

        /**
         * Build the operation CumulocityObject.
         *
         * @return A new CumulocityObject for an operation
         */
        public CumulocityObject build() {
            return build(CumulocityType.OPERATION);
        }
    }

    // ==================== Managed Object Builder ====================

    /**
     * Builder for creating managed object (inventory) objects.
     */
    public static class ManagedObjectBuilder extends BaseBuilder<ManagedObjectBuilder> {

        /**
         * Set the managed object name.
         *
         * @param name The managed object name
         * @return This builder
         */
        public ManagedObjectBuilder name(String name) {
            payload.put("name", name);
            return this;
        }

        /**
         * Set the managed object type.
         *
         * @param type The managed object type
         * @return This builder
         */
        public ManagedObjectBuilder type(String type) {
            payload.put("type", type);
            return this;
        }

        /**
         * Add a custom fragment to the managed object.
         *
         * @param fragmentName The fragment name
         * @param fragmentValue The fragment value
         * @return This builder
         */
        public ManagedObjectBuilder fragment(String fragmentName, Object fragmentValue) {
            payload.put(fragmentName, fragmentValue);
            return this;
        }

        /**
         * Add a custom property to the managed object.
         *
         * @param key The property key
         * @param value The property value
         * @return This builder
         */
        public ManagedObjectBuilder property(String key, Object value) {
            payload.put(key, value);
            return this;
        }

        /**
         * Set the entire payload as a map.
         *
         * @param payload The complete payload
         * @return This builder
         */
        public ManagedObjectBuilder payload(Map<String, Object> payload) {
            this.payload = new HashMap<>(payload);
            return this;
        }

        /**
         * Build the managed object CumulocityObject.
         *
         * @return A new CumulocityObject for a managed object
         */
        public CumulocityObject build() {
            return build(CumulocityType.MANAGED_OBJECT);
        }
    }
}