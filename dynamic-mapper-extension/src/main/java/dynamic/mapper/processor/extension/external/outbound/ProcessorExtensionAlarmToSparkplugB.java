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

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.extension.external.inbound.SparkplugBProto;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Outbound extension that converts Cumulocity Alarms to Sparkplug B ISA-95 alarm metrics.
 *
 * <p>In Sparkplug B, alarms are represented as named boolean metrics indicating active state,
 * with associated string metrics for message, severity, and status per the ISA-95 alarm model.</p>
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
 * <p>Output (Sparkplug B DCMD protobuf payload on topic spBv1.0/{group}/DCMD/{node}/{device}
 * with metrics):</p>
 * <ul>
 *   <li>{@code Alarms/c8y_TemperatureAlarm} — Boolean, true=ACTIVE / false=CLEARED</li>
 *   <li>{@code Alarms/c8y_TemperatureAlarm/Message} — String, alarm text</li>
 *   <li>{@code Alarms/c8y_TemperatureAlarm/Severity} — String, CRITICAL/MAJOR/MINOR/WARNING</li>
 *   <li>{@code Alarms/c8y_TemperatureAlarm/Status} — String, ACTIVE/ACKNOWLEDGED/CLEARED</li>
 * </ul>
 *
 * <p>The publish topic must follow the Sparkplug B convention:
 * {@code spBv1.0/{groupId}/DCMD/{edgeNodeId}/{deviceId}}.
 * It is taken from the mapping's configured publish topic.</p>
 */
@Slf4j
public class ProcessorExtensionAlarmToSparkplugB implements ProcessorExtensionOutbound<Object> {

    /** Sparkplug B DataType constant for Boolean (11) */
    private static final int DATATYPE_BOOLEAN = 11;
    /** Sparkplug B DataType constant for String (12) */
    private static final int DATATYPE_STRING = 12;

    @Override
    public DeviceMessage[] onMessage(Message<Object> message, JavaExtensionContext context) throws ProcessingException {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> alarmPayload = (Map<String, Object>) message.getPayload();

            // Extract alarm fields
            String alarmType = (String) alarmPayload.getOrDefault("type", "UNKNOWN");
            String severity  = (String) alarmPayload.getOrDefault("severity", "WARNING");
            String text      = (String) alarmPayload.getOrDefault("text", "");
            String timeStr   = (String) alarmPayload.getOrDefault("time", "");
            String status    = (String) alarmPayload.getOrDefault("status", "ACTIVE");

            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) alarmPayload.getOrDefault("source", new HashMap<>());
            String sourceId = (String) source.getOrDefault("id", "");

            log.info("{} - Converting alarm to Sparkplug B: type={}, severity={}, status={}, sourceId={}",
                    context.getTenant(), alarmType, severity, status, sourceId);

            // Determine timestamp (ms since epoch)
            long timestampMs = parseTimestamp(timeStr);

            // Alarm active = true when ACTIVE, false when CLEARED or ACKNOWLEDGED
            boolean isActive = "ACTIVE".equalsIgnoreCase(status);

            // Metric name prefix following ISA-95 convention
            String alarmMetricBase = "Alarms/" + alarmType;

            // Build Sparkplug B payload with four metrics
            SparkplugBProto.Payload sparkplugPayload = SparkplugBProto.Payload.newBuilder()
                    .setTimestamp(timestampMs)
                    // 1. Boolean metric: alarm active state
                    .addMetrics(SparkplugBProto.Payload.Metric.newBuilder()
                            .setName(alarmMetricBase)
                            .setTimestamp(timestampMs)
                            .setDatatype(DATATYPE_BOOLEAN)
                            .setBooleanValue(isActive)
                            .build())
                    // 2. String metric: alarm message/text
                    .addMetrics(SparkplugBProto.Payload.Metric.newBuilder()
                            .setName(alarmMetricBase + "/Message")
                            .setTimestamp(timestampMs)
                            .setDatatype(DATATYPE_STRING)
                            .setStringValue(text)
                            .build())
                    // 3. String metric: severity (ISA-95: Critical/High/Medium/Low)
                    .addMetrics(SparkplugBProto.Payload.Metric.newBuilder()
                            .setName(alarmMetricBase + "/Severity")
                            .setTimestamp(timestampMs)
                            .setDatatype(DATATYPE_STRING)
                            .setStringValue(mapSeverityToIsa95(severity))
                            .build())
                    // 4. String metric: status (ISA-95: Active/Acknowledged/Cleared)
                    .addMetrics(SparkplugBProto.Payload.Metric.newBuilder()
                            .setName(alarmMetricBase + "/Status")
                            .setTimestamp(timestampMs)
                            .setDatatype(DATATYPE_STRING)
                            .setStringValue(mapStatusToIsa95(status))
                            .build())
                    .build();

            byte[] protoBytes = sparkplugPayload.toByteArray();

            log.info("{} - Sparkplug B alarm payload: {} bytes, topic={}",
                    context.getTenant(), protoBytes.length, context.getMapping().getPublishTopic());

            return new DeviceMessage[] {
                DeviceMessage.forTopic(context.getMapping().getPublishTopic())
                    .payload(protoBytes)
                    .retain(false)
                    .transportField("qos", "1")
                    .sourceId(sourceId)
                    .build()
            };

        } catch (Exception e) {
            String errorMsg = "Failed to convert alarm to Sparkplug B: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new DeviceMessage[0];
        }
    }

    /**
     * Parse ISO-8601 time string to epoch milliseconds. Falls back to current time on failure.
     */
    private long parseTimestamp(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return Instant.parse(timeStr).toEpochMilli();
        } catch (Exception e) {
            log.warn("Could not parse alarm time '{}', using current time", timeStr);
            return System.currentTimeMillis();
        }
    }

    /**
     * Map Cumulocity severity to ISA-95 / Sparkplug B severity levels.
     * ISA-95 defines: Critical, High, Medium, Low
     */
    private String mapSeverityToIsa95(String severity) {
        switch (severity.toUpperCase()) {
            case "CRITICAL": return "Critical";
            case "MAJOR":    return "High";
            case "MINOR":    return "Medium";
            case "WARNING":  return "Low";
            default:         return severity;
        }
    }

    /**
     * Map Cumulocity alarm status to ISA-95 alarm status labels.
     * ISA-95 defines: Active, Acknowledged, Cleared
     */
    private String mapStatusToIsa95(String status) {
        switch (status.toUpperCase()) {
            case "ACTIVE":       return "Active";
            case "ACKNOWLEDGED": return "Acknowledged";
            case "CLEARED":      return "Cleared";
            default:             return status;
        }
    }
}
