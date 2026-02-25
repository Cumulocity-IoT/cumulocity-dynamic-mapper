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
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.Map;

/**
 * Java Extension equivalent of template-SMART-INBOUND-02.js
 *
 * <p>This extension demonstrates using device data for enrichment:</p>
 * <ul>
 *   <li>Dynamic measurement type selection based on device configuration</li>
 *   <li>Creates either c8y_CurrentMeasurement or c8y_VoltageMeasurement</li>
 *   <li>Device inventory lookup for configuration data</li>
 *   <li>Conditional logic based on device sensor type</li>
 * </ul>
 *
 * <p>Input JSON format:</p>
 * <pre>
 * {
 *   "deviceId": "device-001",
 *   "clientId": "sensor-001",
 *   "sensorType": "voltage",  // or "current"
 *   "sensorData": {
 *     "val": 12.5
 *   }
 * }
 * </pre>
 *
 * <p>Output: c8y_VoltageMeasurement or c8y_CurrentMeasurement based on device config</p>
 */
@Slf4j
public class ProcessorExtensionSmartInbound02 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Processing smart inbound message with enrichment, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Get clientId from context first, fall back to payload
            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }

            String tenant = context.getTenant();

            // Lookup device from inventory cache for enrichment (same pattern as JavaScript Smart Functions)
            ExternalId externalId = new ExternalId(clientId, "c8y_Serial");
            Map<String, Object> deviceByExternalId = context.getManagedObjectAsMap(externalId);

            // Determine measurement type based on device configuration (enrichment)
            String sensorType = "voltage"; // default

            if (deviceByExternalId != null) {
                log.debug("{} - Device found for enrichment: {}", tenant, deviceByExternalId);

                // Try to extract sensor type from device configuration (c8y_Sensor.type.voltage or .current)
                Object c8ySensorObj = deviceByExternalId.get("c8y_Sensor");
                if (c8ySensorObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> c8ySensor = (Map<String, Object>) c8ySensorObj;
                    Object typeObj = c8ySensor.get("type");
                    if (typeObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typeMap = (Map<String, Object>) typeObj;
                        if (Boolean.TRUE.equals(typeMap.get("voltage"))) {
                            sensorType = "voltage";
                        } else if (Boolean.TRUE.equals(typeMap.get("current"))) {
                            sensorType = "current";
                        }
                    }
                }
            } else {
                log.warn("{} - No device found for enrichment, using default sensor type", tenant);
            }

            // Extract sensor value
            @SuppressWarnings("unchecked")
            Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
            Number value = (Number) sensorData.get("val");

            // Build appropriate measurement based on sensor type
            if ("voltage".equals(sensorType)) {
                log.info("{} - Creating c8y_VoltageMeasurement for device: {}",
                        context.getTenant(), clientId);
                return new CumulocityObject[] {
                    CumulocityObject.measurement()
                        .type("c8y_VoltageMeasurement")
                        .time(new DateTime().toString())
                        .fragment("c8y_Voltage", "voltage", value.doubleValue(), "V")
                        .externalId(clientId, "c8y_Serial")
                        .deviceName(clientId)
                        .deviceType("c8y_VoltageSensor")
                        .build()
                };
            } else if ("current".equals(sensorType)) {
                log.info("{} - Creating c8y_CurrentMeasurement for device: {}",
                        context.getTenant(), clientId);
                return new CumulocityObject[] {
                    CumulocityObject.measurement()
                        .type("c8y_CurrentMeasurement")
                        .time(new DateTime().toString())
                        .fragment("c8y_Current", "current", value.doubleValue(), "A")
                        .externalId(clientId, "c8y_Serial")
                        .deviceName(clientId)
                        .deviceType("c8y_CurrentSensor")
                        .build()
                };
            } else {
                String warningMsg = "No valid sensor type specified. Use 'voltage' or 'current'";
                log.warn("{} - {}", context.getTenant(), warningMsg);
                context.addWarning(warningMsg);
                return new CumulocityObject[0];
            }

        } catch (Exception e) {
            String errorMsg = "Failed to process inbound message: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
