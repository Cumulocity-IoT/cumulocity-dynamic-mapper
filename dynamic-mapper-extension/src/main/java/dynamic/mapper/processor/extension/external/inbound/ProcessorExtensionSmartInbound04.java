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
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.Map;

/**
 * Java Extension equivalent of template-SMART-INBOUND-04.js
 *
 * <p>This extension demonstrates conditional creation of different Cumulocity object types:</p>
 * <ul>
 *   <li>Creates a measurement for "telemetry" payloadType</li>
 *   <li>Creates an event for "error" payloadType</li>
 *   <li>Single extension handling multiple output types based on input data</li>
 * </ul>
 *
 * <p>Input JSON format for telemetry:</p>
 * <pre>
 * {
 *   "messageId": "msg-001",
 *   "clientId": "sensor-001",
 *   "payloadType": "telemetry",
 *   "sensorData": {
 *     "temp_val": 25.5
 *   }
 * }
 * </pre>
 *
 * <p>Input JSON format for error:</p>
 * <pre>
 * {
 *   "messageId": "msg-002",
 *   "clientId": "sensor-001",
 *   "payloadType": "error",
 *   "logMessage": "Sensor malfunction detected"
 * }
 * </pre>
 *
 * <p>Output: Either c8y_TemperatureMeasurement or c8y_ErrorEvent depending on payloadType</p>
 */
@Slf4j
public class ProcessorExtensionSmartInbound04 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Context state: {}", context.getTenant(), context.getStateAll());
            log.debug("{} - Payload raw: {}", context.getTenant(), jsonString);
            log.info("{} - Processing message, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Get clientId from context first, fall back to payload
            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }

            // Extract common data
            String payloadType = (String) payload.get("payloadType");

            // Decide which type of Cumulocity object to create based on payloadType
            if ("telemetry".equals(payloadType)) {
                // Create measurement
                @SuppressWarnings("unchecked")
                Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
                Number tempVal = (Number) sensorData.get("temp_val");

                log.info("{} - Creating temperature measurement: {} C for device: {}",
                        context.getTenant(), tempVal, clientId);

                return new CumulocityObject[] {
                    CumulocityObject.measurement()
                        .type("c8y_TemperatureMeasurement")
                        .time(new DateTime().toString())
                        .fragment("c8y_Steam", "Temperature", tempVal.doubleValue(), "C")
                        .externalId(clientId, "c8y_Serial")
                        .build()
                };

            } else {
                // Create event for error or other payload types
                String logMessage = (String) payload.get("logMessage");

                log.warn("{} - Creating error event for device: {}, message: {}",
                        context.getTenant(), clientId, logMessage);

                return new CumulocityObject[] {
                    CumulocityObject.event()
                        .type("c8y_ErrorEvent")
                        .text(logMessage)
                        .time(new DateTime().toString())
                        .externalId(clientId, "c8y_Serial")
                        .build()
                };
            }

        } catch (Exception e) {
            String errorMsg = "Failed to process inbound message: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
