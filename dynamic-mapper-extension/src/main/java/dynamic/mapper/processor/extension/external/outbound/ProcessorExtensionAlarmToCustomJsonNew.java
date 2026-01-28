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

package dynamic.mapper.processor.extension.external.outbound;

import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.flow.DataPreparationContext;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Reference implementation demonstrating the new return-value based outbound extension pattern.
 *
 * <p>This extension converts Cumulocity alarms to custom JSON format for devices using
 * the new SMART function pattern. It demonstrates:</p>
 * <ul>
 *   <li>Using the {@link Message} wrapper to access Cumulocity payload</li>
 *   <li>Using {@link DataPreparationContext} for context information</li>
 *   <li>Using {@link DeviceMessage} builder for clean object construction</li>
 *   <li>Returning arrays of device messages instead of side effects</li>
 * </ul>
 *
 * <p>Input (Cumulocity alarm):</p>
 * <pre>
 * {
 *   "type": "c8y_TemperatureAlarm",
 *   "severity": "CRITICAL",
 *   "text": "Temperature exceeds threshold",
 *   "time": "2024-01-01T12:00:00Z",
 *   "source": {"id": "12345"},
 *   "status": "ACTIVE"
 * }
 * </pre>
 *
 * <p>Output (custom device format):</p>
 * <pre>
 * {
 *   "alertCode": "TEMP_001",
 *   "notificationLevel": 4,
 *   "message": "Temperature exceeds threshold",
 *   "timestamp": "2024-01-01T12:00:00Z",
 *   "deviceIdentifier": "12345",
 *   "acknowledged": false,
 *   "metadata": {
 *     "originType": "cumulocity",
 *     "originalSeverity": "CRITICAL",
 *     "originalType": "c8y_TemperatureAlarm"
 *   }
 * }
 * </pre>
 *
 * <p>This replaces the legacy pattern where extensions directly called
 * {@code context.addRequest()} with side effects.</p>
 *
 * @see ProcessorExtensionAlarmToCustomJson for the legacy implementation
 */
@Slf4j
public class ProcessorExtensionAlarmToCustomJsonNew implements ProcessorExtensionOutbound<Object> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DeviceMessage[] onMessage(Message<Object> message, DataPreparationContext context) throws ProcessingException {
        try {
            // 1. Get the Cumulocity alarm representation from the payload
            // For outbound, payload is already a parsed Map from Cumulocity API
            @SuppressWarnings("unchecked")
            Map<String, Object> alarmPayload = (Map<String, Object>) message.getPayload();

            log.info("{} - Processing outbound alarm with new pattern: {}",
                    context.getTenant(), alarmPayload.get("type"));

            // 2. Extract alarm fields
            String alarmType = (String) alarmPayload.getOrDefault("type", "UNKNOWN");
            String severity = (String) alarmPayload.getOrDefault("severity", "WARNING");
            String text = (String) alarmPayload.getOrDefault("text", "");
            String time = (String) alarmPayload.getOrDefault("time", "");
            String status = (String) alarmPayload.getOrDefault("status", "ACTIVE");

            // 3. Extract source device information
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) alarmPayload.getOrDefault("source", new HashMap<>());
            String sourceId = (String) source.getOrDefault("id", "");

            // 4. Build custom JSON structure for the device
            Map<String, Object> customAlarmFormat = buildCustomAlarmFormat(
                    alarmType, severity, text, time, status, sourceId);

            // 5. Convert to JSON string
            String customJsonString = objectMapper.writeValueAsString(customAlarmFormat);

            log.info("{} - Generated custom alarm format: {}", context.getTenant(), customJsonString);

            // 6. Build and return device message using builder pattern
            // This is much cleaner than the old pattern!
            return new DeviceMessage[] {
                DeviceMessage.forTopic(context.getMapping().getPublishTopic())
                    .payload(customJsonString)
                    .retain(false)
                    .transportField("qos", "1")
                    .build()
            };

        } catch (Exception e) {
            String errorMsg = "Failed to convert alarm to custom format: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            // Return empty array to indicate processing failure
            return new DeviceMessage[0];
        }
    }

    /**
     * Build a custom JSON structure that matches the device's expected format.
     * This is where you would implement your device-specific protocol.
     */
    private Map<String, Object> buildCustomAlarmFormat(
            String alarmType,
            String severity,
            String text,
            String time,
            String status,
            String sourceId) {

        Map<String, Object> customFormat = new HashMap<>();

        // Device-specific fields
        customFormat.put("alertCode", mapTypeToAlertCode(alarmType));
        customFormat.put("notificationLevel", mapSeverityToLevel(severity));
        customFormat.put("message", text);
        customFormat.put("timestamp", time);
        customFormat.put("deviceIdentifier", sourceId);
        customFormat.put("acknowledged", "ACKNOWLEDGED".equalsIgnoreCase(status));

        // Add custom metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("originType", "cumulocity");
        metadata.put("originalSeverity", severity);
        metadata.put("originalType", alarmType);
        customFormat.put("metadata", metadata);

        return customFormat;
    }

    /**
     * Map Cumulocity alarm type to device-specific alert codes.
     */
    private String mapTypeToAlertCode(String alarmType) {
        switch (alarmType) {
            case "c8y_TemperatureAlarm":
                return "TEMP_001";
            case "c8y_PressureAlarm":
                return "PRES_001";
            case "c8y_BatteryAlarm":
                return "BATT_001";
            case "c8y_ConnectionAlarm":
                return "CONN_001";
            default:
                return "GEN_999"; // Generic alarm code
        }
    }

    /**
     * Map Cumulocity severity to device-specific notification level.
     */
    private int mapSeverityToLevel(String severity) {
        switch (severity) {
            case "CRITICAL":
                return 4;
            case "MAJOR":
                return 3;
            case "MINOR":
                return 2;
            case "WARNING":
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Example of creating multiple device messages from a single Cumulocity object.
     *
     * <p>This demonstrates how you can return multiple messages (e.g., send to
     * multiple topics or devices) from a single Cumulocity alarm.</p>
     */
    @SuppressWarnings("unused")
    private DeviceMessage[] exampleMultipleMessages(Map<String, Object> alarmPayload,
                                                    DataPreparationContext context) throws Exception {
        String customJson = objectMapper.writeValueAsString(
                buildCustomAlarmFormat("c8y_TemperatureAlarm", "CRITICAL",
                        "Temperature critical", "", "ACTIVE", "12345"));

        // Send to both a device-specific topic and a general alarm topic
        DeviceMessage deviceSpecific = DeviceMessage.forTopic("device/12345/alarms")
            .payload(customJson)
            .retain(false)
            .build();

        DeviceMessage broadcast = DeviceMessage.forTopic("alarms/critical")
            .payload(customJson)
            .retain(true) // Retain critical alarms
            .transportField("qos", "2") // Higher QoS for critical
            .build();

        return new DeviceMessage[] { deviceSpecific, broadcast };
    }

    /**
     * Example of using transport fields for MQTT-specific configuration.
     *
     * <p>This demonstrates how to use transportFields to set MQTT-specific
     * properties like QoS, retain flag, etc.</p>
     */
    @SuppressWarnings("unused")
    private DeviceMessage exampleWithTransportFields(String payload, String topic) {
        return DeviceMessage.forTopic(topic)
            .payload(payload)
            .retain(true)
            .transportField("qos", "2")
            .transportField("messageExpiryInterval", "3600")
            .transportField("contentType", "application/json")
            .build();
    }
}
