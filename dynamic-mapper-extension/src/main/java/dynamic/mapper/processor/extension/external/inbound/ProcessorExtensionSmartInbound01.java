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
 * Java Extension equivalent of template-SMART-INBOUND-01.js
 *
 * <p>This extension demonstrates the default Smart Function pattern:</p>
 * <ul>
 *   <li>Creating a temperature measurement from sensor data</li>
 *   <li>Device lookup by deviceId and externalId for enrichment</li>
 *   <li>Using builder pattern for clean object construction</li>
 * </ul>
 *
 * <p>Input JSON format:</p>
 * <pre>
 * {
 *   "messageId": "msg-001",
 *   "deviceId": "device-001",
 *   "clientId": "sensor-001",
 *   "sensorData": {
 *     "temp_val": 25.5
 *   }
 * }
 * </pre>
 *
 * <p>Output: Cumulocity c8y_TemperatureMeasurement</p>
 */
@Slf4j
public class ProcessorExtensionSmartInbound01 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Processing smart inbound message, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Extract data
            String clientId = (String) payload.get("clientId");
            @SuppressWarnings("unchecked")
            Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
            Number tempVal = (Number) sensorData.get("temp_val");

            log.debug("{} - Creating temperature measurement: {} C for device: {}",
                    context.getTenant(), tempVal, clientId);

            // Build measurement using builder pattern
            return new CumulocityObject[] {
                CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(new DateTime().toString())
                    .fragment("c8y_Steam", "Temperature", tempVal.doubleValue(), "C")
                    .externalId(clientId, "c8y_Serial")
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
