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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Legacy outbound extension using deprecated side-effect pattern.
 *
 * @deprecated Since 2.0. Use {@link ProcessorExtensionAlarmToCustomJsonNew} instead.
 *             This implementation uses the deprecated extractAndPrepare() pattern
 *             with manual request building. The new pattern uses onMessage() with
 *             return values and builder pattern for cleaner, more testable code.
 * @see ProcessorExtensionAlarmToCustomJsonNew
 *
 * Sample outbound extension that converts a Cumulocity alarm to a custom JSON format.
 *
 * This extension demonstrates complete outbound processing:
 * - Extracting alarm data from Cumulocity payload
 * - Converting to a custom device-specific format
 * - Generating the complete broker message
 *
 * Use Case: Send alarm notifications to devices in their proprietary format
 */
@Slf4j
@Deprecated(since = "2.0", forRemoval = false)
public class ProcessorExtensionAlarmToCustomJson implements ProcessorExtensionOutbound<byte[]> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context) throws ProcessingException {
        try {
            String tenant = context.getTenant();

            // Parse the Cumulocity alarm representation from the payload
            Map<String, Object> alarmPayload = (Map<String, Object>) Json.parseJson(
                    new String(context.getPayload(), "UTF-8"));

            log.info("{} - Processing outbound alarm: {}", tenant, alarmPayload);

            // Extract alarm fields
            String alarmType = (String) alarmPayload.getOrDefault("type", "UNKNOWN");
            String severity = (String) alarmPayload.getOrDefault("severity", "WARNING");
            String text = (String) alarmPayload.getOrDefault("text", "");
            String time = (String) alarmPayload.getOrDefault("time", "");
            String status = (String) alarmPayload.getOrDefault("status", "ACTIVE");

            // Extract source device information
            Map<String, Object> source = (Map<String, Object>) alarmPayload.getOrDefault("source", new HashMap<>());
            String sourceId = (String) source.getOrDefault("id", "");

            // Build custom JSON structure for the device
            Map<String, Object> customAlarmFormat = buildCustomAlarmFormat(
                    alarmType, severity, text, time, status, sourceId);

            // Convert to JSON string
            String customJsonString = objectMapper.writeValueAsString(customAlarmFormat);

            log.info("{} - Converted alarm to custom format: {}", tenant, customJsonString);

            // Add substitutions to the processing context
            // These can be used in the outbound template
            context.addSubstitution("alarmType", alarmType, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("alarmSeverity", mapSeverityToDeviceFormat(severity),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("alarmText", text, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("alarmTimestamp", time, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("alarmStatus", status, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("deviceId", sourceId, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

            // Add the complete custom JSON as a substitution
            context.addSubstitution("customAlarmJson", customJsonString,
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

            // Add individual custom fields
            context.addSubstitution("notificationLevel",
                    String.valueOf(customAlarmFormat.get("notificationLevel")),
                    TYPE.NUMBER, RepairStrategy.DEFAULT, false);
            context.addSubstitution("alertCode",
                    String.valueOf(customAlarmFormat.get("alertCode")),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

            log.info("{} - Successfully extracted {} substitutions from alarm",
                    tenant, context.getProcessingCache().size());

        } catch (JsonProcessingException e) {
            String errorMsg = "Failed to process alarm JSON: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            throw new ProcessingException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Error extracting alarm data: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            throw new ProcessingException(errorMsg, e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void extractAndPrepare(ProcessingContext<byte[]> context) throws ProcessingException {
        try {
            String tenant = context.getTenant();

            // Parse the Cumulocity alarm representation from the payload
            Map<String, Object> alarmPayload = (Map<String, Object>) Json.parseJson(
                    new String(context.getPayload(), "UTF-8"));

            log.info("{} - Processing complete outbound alarm: {}", tenant, alarmPayload);

            // Extract alarm fields
            String alarmType = (String) alarmPayload.getOrDefault("type", "UNKNOWN");
            String severity = (String) alarmPayload.getOrDefault("severity", "WARNING");
            String text = (String) alarmPayload.getOrDefault("text", "");
            String time = (String) alarmPayload.getOrDefault("time", "");
            String status = (String) alarmPayload.getOrDefault("status", "ACTIVE");

            // Extract source device information
            Map<String, Object> source = (Map<String, Object>) alarmPayload.getOrDefault("source", new HashMap<>());
            String sourceId = (String) source.getOrDefault("id", "");

            // Build custom JSON structure for the device
            Map<String, Object> customAlarmFormat = buildCustomAlarmFormat(
                    alarmType, severity, text, time, status, sourceId);

            // Convert to JSON string
            String customJsonString = objectMapper.writeValueAsString(customAlarmFormat);

            log.info("{} - Generated custom alarm format: {}", tenant, customJsonString);

            // Create and add outbound request using the helper
            // The helper will create the request and add it to context.getRequests()
            ProcessingResultHelper.createAndAddDynamicMapperRequest(
                    context,
                    customJsonString,
                    null,  // action (not needed for outbound)
                    context.getMapping()
            );

            log.info("{} - Successfully prepared outbound alarm request for topic: {}",
                    tenant, context.getResolvedPublishTopic());

        } catch (JsonProcessingException e) {
            String errorMsg = "Failed to generate custom alarm JSON: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            throw new ProcessingException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Error preparing outbound alarm: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            throw new ProcessingException(errorMsg, e);
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
     * Map Cumulocity alarm type to device-specific alert codes
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
     * Map Cumulocity severity to device-specific notification level
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
     * Map severity to device-specific severity format (if different from numeric level)
     */
    private String mapSeverityToDeviceFormat(String severity) {
        switch (severity) {
            case "CRITICAL":
                return "HIGH";
            case "MAJOR":
                return "HIGH";
            case "MINOR":
                return "MEDIUM";
            case "WARNING":
                return "LOW";
            default:
                return "INFO";
        }
    }
}
