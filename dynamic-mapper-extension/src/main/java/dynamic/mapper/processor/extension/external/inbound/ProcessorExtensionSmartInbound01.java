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
 *
 * <p>Supported parameter keys (under the top-level {@code parameter} map):
 * <ul>
 *   <li>{@code fragment} – measurement fragment name (default: "c8y_Steam")</li>
 *   <li>{@code series} – series name within the fragment (default: "Temperature")</li>
 *   <li>{@code unit} – unit of the measurement value (default: "C")</li>
 * </ul>
 */
@Slf4j
public class ProcessorExtensionSmartInbound01 implements ProcessorExtensionInbound<byte[]> {

    private static final String DEFAULT_FRAGMENT = "c8y_Steam";
    private static final String DEFAULT_SERIES = "Temperature";
    private static final String DEFAULT_UNIT = "C";

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Processing smart inbound message, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Get clientId from context first, fall back to payload
            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }

            // Read optional parameters
            String fragment = DEFAULT_FRAGMENT;
            String series = DEFAULT_SERIES;
            String unit = DEFAULT_UNIT;
            @SuppressWarnings("unchecked")
            Map<String, Object> parameter = (Map<String, Object>) context.getConfigAsMap().get("parameter");
            if (parameter != null) {
                log.info("{} - Extension parameter defined: {}", context.getTenant(), parameter);
                if (parameter.get("fragment") instanceof String f) fragment = f;
                if (parameter.get("series") instanceof String s) series = s;
                if (parameter.get("unit") instanceof String u) unit = u;
            }

            // Extract data
            @SuppressWarnings("unchecked")
            Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
            Number tempVal = (Number) sensorData.get("temp_val");

            log.debug("{} - Creating temperature measurement: {} {} for device: {}",
                    context.getTenant(), tempVal, unit, clientId);

            // Build measurement using builder pattern
            // Note: deviceName and deviceType are needed for implicit device creation
            return new CumulocityObject[] {
                CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(new DateTime().toString())
                    .fragment(fragment, series, tempVal.doubleValue(), unit)
                    .externalId(clientId, "c8y_Serial")
                    .deviceName(clientId)           // Use clientId as device name
                    .deviceType("c8y_TemperatureSensor")  // Device type for implicit creation
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
