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
 * Java Extension equivalent of template-SMART-INBOUND-03.js
 *
 * <p>This extension demonstrates implicit device creation:</p>
 * <ul>
 *   <li>Creating a measurement with contextData for device creation</li>
 *   <li>Specifying deviceName and deviceType for implicit device creation</li>
 *   <li>Using builder pattern with contextData</li>
 * </ul>
 *
 * <p>Input JSON format:</p>
 * <pre>
 * {
 *   "messageId": "msg-001",
 *   "clientId": "sensor-001",
 *   "sensorData": {
 *     "temp_val": 25.5
 *   }
 * }
 * </pre>
 *
 * <p>Output: c8y_TemperatureMeasurement with contextData specifying device name and type</p>
 * <p>If the device doesn't exist, it will be created automatically with name "Test-Sensor"
 * and type "sensor-type"</p>
 */
@Slf4j
public class ProcessorExtensionSmartInbound03 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Processing smart inbound message with implicit device creation, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Get clientId from context first, fall back to payload
            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }

            // Extract data
            @SuppressWarnings("unchecked")
            Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
            Number tempVal = (Number) sensorData.get("temp_val");

            log.debug("{} - Creating measurement for device: {} (will be created if not exists)",
                    context.getTenant(), clientId);

            // Build measurement with contextData for implicit device creation
            // The deviceName and deviceType will be used if the device doesn't exist yet
            return new CumulocityObject[] {
                CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(new DateTime().toString())
                    .fragment("c8y_Steam", "Temperature", tempVal.doubleValue(), "C")
                    .externalId(clientId, "c8y_Serial")
                    .deviceName("Test-Sensor")      // Used for implicit device creation
                    .deviceType("sensor-type")       // Used for implicit device creation
                    .build()
            };

        } catch (Exception e) {
            String errorMsg = "Failed to process inbound message: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
