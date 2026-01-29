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

package dynamic.mapper.processor.extension.external.inbound;

import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DataPreparationContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.Map;

/**
 * Reference implementation demonstrating the new return-value based extension pattern.
 *
 * <p>This extension converts custom alarm JSON messages to Cumulocity alarms using
 * the new SMART function pattern. It demonstrates:</p>
 * <ul>
 *   <li>Using the {@link Message} wrapper to access incoming data</li>
 *   <li>Using {@link DataPreparationContext} for context information</li>
 *   <li>Using {@link CumulocityObject} builders for clean object construction</li>
 *   <li>Returning arrays of domain objects instead of side effects</li>
 * </ul>
 *
 * <p>Input JSON format:</p>
 * <pre>
 * {
 *   "externalId": "device-001",
 *   "type": "c8y_TemperatureAlarm",
 *   "alarmType": "CRITICAL",
 *   "message": "Temperature exceeds threshold",
 *   "time": "2024-01-01T12:00:00Z"
 * }
 * </pre>
 *
 * <p>This replaces the legacy pattern where extensions directly called
 * {@code c8yAgent.createMEAO()} with side effects.</p>
 *
 * @see ProcessorExtensionCustomAlarm for the legacy implementation
 */
@Slf4j
public class ProcessorExtensionCustomAlarm implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        try {
            // 1. Parse the incoming message payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            Map<?, ?> jsonObject = (Map<?, ?>) Json.parseJson(jsonString);

            log.info("{} - Processing custom alarm from new pattern: {}",
                    context.getTenant(), jsonObject.get("message"));

            // 2. Extract fields from the JSON
            String externalId = jsonObject.get("externalId").toString();
            String alarmType = jsonObject.get("type").toString();
            String severity = jsonObject.get("alarmType").toString(); // Maps to Cumulocity severity
            String text = jsonObject.get("message").toString();
            DateTime time = new DateTime(jsonObject.get("time"));

            // 3. Get external ID type from mapping configuration
            String externalIdType = context.getMapping().getExternalIdType();

            // 4. Build and return Cumulocity alarm using builder pattern
            // This is much cleaner than the old pattern!
            return new CumulocityObject[] {
                CumulocityObject.alarm()
                    .type(alarmType)
                    .severity(severity)
                    .text(text)
                    .time(time.toString())
                    .status("ACTIVE")
                    .externalId(externalId, externalIdType)
                    .build()
            };

        } catch (Exception e) {
            String errorMsg = "Failed to process custom alarm: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            // Return empty array to indicate processing failure
            return new CumulocityObject[0];
        }
    }

    /**
     * Example of creating multiple Cumulocity objects from a single message.
     *
     * <p>This demonstrates how you can return multiple objects (e.g., an alarm
     * and a measurement) from a single incoming message.</p>
     */
    @SuppressWarnings("unused")
    private CumulocityObject[] exampleMultipleObjects(Map<?, ?> jsonObject, DataPreparationContext context) {
        String externalId = jsonObject.get("externalId").toString();
        String externalIdType = context.getMapping().getExternalIdType();

        // Create both an alarm and a measurement from the same message
        CumulocityObject alarm = CumulocityObject.alarm()
            .type("c8y_TemperatureAlarm")
            .severity("CRITICAL")
            .text("Temperature critical")
            .externalId(externalId, externalIdType)
            .build();

        CumulocityObject measurement = CumulocityObject.measurement()
            .type("c8y_TemperatureMeasurement")
            .time(new DateTime().toString())
            .fragment("c8y_Temperature", "T", 95.5, "C")
            .externalId(externalId, externalIdType)
            .build();

        return new CumulocityObject[] { alarm, measurement };
    }

    /**
     * Example of using context data for device metadata.
     *
     * <p>This demonstrates how to use contextData to pass additional information
     * like device name and type for implicit device creation.</p>
     */
    @SuppressWarnings("unused")
    private CumulocityObject exampleWithDeviceMetadata(Map<?, ?> jsonObject, DataPreparationContext context) {
        return CumulocityObject.alarm()
            .type("c8y_TemperatureAlarm")
            .severity("CRITICAL")
            .text("Temperature critical")
            .externalId(jsonObject.get("externalId").toString(),
                       context.getMapping().getExternalIdType())
            .deviceName("Temperature Sensor 1")  // Used for device creation
            .deviceType("c8y_TemperatureSensor") // Used for device creation
            .build();
    }

    /**
     * Example of using warnings and logs.
     *
     * <p>This demonstrates how to use the context to add warnings and logs
     * for debugging and monitoring.</p>
     */
    @SuppressWarnings("unused")
    private CumulocityObject exampleWithWarnings(Map<?, ?> jsonObject, DataPreparationContext context) {
        String externalId = jsonObject.get("externalId").toString();

        // Check if device exists (if inventory lookup is available)
        if (context.getManagedObjectByDeviceId(externalId) == null) {
            context.addWarning("Device " + externalId + " not found, will be created implicitly");
        }

        context.addLog("Processing alarm for device: " + externalId);

        return CumulocityObject.alarm()
            .type("c8y_TemperatureAlarm")
            .severity("CRITICAL")
            .text("Temperature critical")
            .externalId(externalId, context.getMapping().getExternalIdType())
            .build();
    }
}
