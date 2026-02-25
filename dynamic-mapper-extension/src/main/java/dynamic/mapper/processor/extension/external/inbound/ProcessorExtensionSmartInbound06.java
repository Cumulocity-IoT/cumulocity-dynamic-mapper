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

import java.util.List;
import java.util.Map;

/**
 * Java Extension demonstrating sourceId override to route measurements to parent devices.
 *
 * <p>This extension demonstrates the Smart Function pattern with sourceId override (since 6.1.6):</p>
 * <ul>
 *   <li>Looking up originating device using externalId from inventory cache</li>
 *   <li>Extracting parent device information from assetParents</li>
 *   <li>Using builder pattern with .sourceId() to route measurement to parent device</li>
 *   <li>Fallback to normal device resolution if no parents found</li>
 * </ul>
 *
 * <p><b>Prerequisites:</b></p>
 * <ul>
 *   <li>Enable inventory cache in connector configuration</li>
 *   <li>Add "assetParents" to inventoryFragmentsToCache list</li>
 *   <li>Ensure devices have parent-child relationships configured in Cumulocity</li>
 * </ul>
 *
 * <p><b>Use Case:</b></p>
 * <p>A child device sends data, but you want the measurement to appear on the parent device.
 * For example: Multiple sensors on a production line sending data to the line's device group.</p>
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
 * <p>Output: Cumulocity c8y_TemperatureMeasurement routed to parent device (if parents exist)</p>
 *
 * @since 6.1.6
 */
@Slf4j
public class ProcessorExtensionSmartInbound06 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            // Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            log.info("{} - Processing smart inbound message with sourceId override, messageId: {}",
                    context.getTenant(), payload.get("messageId"));

            // Get clientId from context first, fall back to payload
            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }

            String tenant = context.getTenant();

            // Lookup device from inventory cache (same pattern as JavaScript Smart Functions)
            ExternalId externalId = new ExternalId(clientId, "c8y_Serial");
            Map<String, Object> originatingDevice = context.getManagedObjectAsMap(externalId);

            // Variable to hold the target device ID (parent if available, null otherwise)
            String targetDeviceId = null;

            if (originatingDevice != null) {
                log.debug("{} - Originating device found: {}", tenant, originatingDevice);

                // Extract parent device ID from assetParents (if available)
                Object assetParentsObj = originatingDevice.get("assetParents");

                if (assetParentsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> assetParents = (List<Map<String, Object>>) assetParentsObj;

                    if (!assetParents.isEmpty()) {
                        // Get the first parent device (you could implement logic to select a specific parent)
                        Map<String, Object> firstParent = assetParents.get(0);
                        targetDeviceId = (String) firstParent.get("id");

                        log.info("{} - Routing measurement to parent device - ID: {}, Name: {}, Type: {}",
                                tenant,
                                targetDeviceId,
                                firstParent.get("name"),
                                firstParent.get("type"));
                    } else {
                        log.debug("{} - No parent devices found in assetParents, measurement will go to originating device",
                                tenant);
                    }
                }
            } else {
                log.warn("{} - Could not find originating device with externalId: {}, type: c8y_Serial",
                        tenant, clientId);
            }

            // Extract sensor data
            @SuppressWarnings("unchecked")
            Map<String, Object> sensorData = (Map<String, Object>) payload.get("sensorData");
            Number tempVal = (Number) sensorData.get("temp_val");

            log.debug("{} - Creating temperature measurement: {} C for device: {}",
                    tenant, tempVal, clientId);

            // Build measurement using builder pattern with sourceId override
            CumulocityObject.MeasurementBuilder builder = CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(new DateTime().toString())
                    .fragment("c8y_Steam", "Temperature", tempVal.doubleValue(), "C")
                    .externalId(clientId, "c8y_Serial")
                    .deviceName(clientId)
                    .deviceType("c8y_TemperatureSensor");

            // NEW: Set sourceId to route to parent device (if parent was found)
            if (targetDeviceId != null) {
                builder.sourceId(targetDeviceId);
            }

            return new CumulocityObject[] { builder.build() };

        } catch (Exception e) {
            String errorMsg = "Failed to process inbound message with sourceId override: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);  // context.getTenant() is safe here as it's in catch block
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
