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
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java Extension demonstrating bulk measurement collection (since 6.3).
 *
 * <p>Sends all measurements from a single incoming message in one Cumulocity REST
 * call using {@code CumulocityType.MEASUREMENT_COLLECTION}. This avoids N separate
 * API calls when a message carries readings for multiple time slots.</p>
 *
 * <p><b>Key difference from Smart Functions:</b> the mapper does <em>not</em>
 * auto-inject {@code source.id} for Java Extensions. This extension looks up the
 * internal Cumulocity device ID via the inventory cache and sets
 * {@code source.id} in each measurement entry explicitly using the convenience
 * builder overload
 * {@link CumulocityObject.MeasurementCollectionBuilder#measurement(String, String, String, Map)}.</p>
 *
 * <p>Input JSON format (MQTT topic: {@code testBulkMeasurement/<clientId>}):</p>
 * <pre>
 * {
 *   "clientId": "sensor-berlin-01",
 *   "readings": [
 *     { "time": "2026-01-01T00:00:00Z", "temperature": 22.5, "humidity": 55.0 },
 *     { "time": "2026-01-01T00:01:00Z", "temperature": 23.1, "humidity": 54.2 },
 *     { "time": "2026-01-01T00:02:00Z", "temperature": 23.8, "humidity": 53.7 }
 *   ]
 * }
 * </pre>
 *
 * <p>Output: one {@code POST /measurement/measurements} with
 * {@code Content-Type: application/vnd.com.nsn.cumulocity.measurementcollection+json}
 * carrying all three measurements.</p>
 *
 * @since 6.3
 */
@Slf4j
public class ProcessorExtensionSmartInbound07 implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }
            String tenant = context.getTenant();

            log.info("{} - Processing bulk measurement collection for clientId: {}", tenant, clientId);

            // Resolve the internal Cumulocity device ID from the inventory cache.
            // Java Extensions must set source.id explicitly — unlike Smart Functions
            // the mapper does NOT auto-inject it for the MEASUREMENT_COLLECTION path.
            ExternalId externalId = new ExternalId(clientId, "c8y_Serial");
            Map<String, Object> device = context.getManagedObjectAsMap(externalId);
            if (device == null) {
                String warn = String.format(
                        "Device not found in inventory cache for externalId '%s' (type c8y_Serial) — cannot create measurement collection",
                        clientId);
                log.warn("{} - {}", tenant, warn);
                context.addWarning(warn);
                return new CumulocityObject[0];
            }
            String deviceId = (String) device.get("id");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> readings = (List<Map<String, Object>>) payload.get("readings");
            if (readings == null || readings.isEmpty()) {
                log.warn("{} - No readings in payload for clientId: {}", tenant, clientId);
                return new CumulocityObject[0];
            }

            // Build the collection — one entry per reading.
            // The convenience overload injects source.id automatically.
            CumulocityObject.MeasurementCollectionBuilder builder = CumulocityObject.measurementCollection()
                    .externalId(clientId, "c8y_Serial")
                    .deviceName(clientId)
                    .deviceType("c8y_TemperatureSensor");

            for (Map<String, Object> reading : readings) {
                String time = (String) reading.get("time");
                Number temperature = (Number) reading.get("temperature");
                Number humidity = (Number) reading.get("humidity");

                // Build fragment map: fragment → {series → {value, unit}}
                Map<String, Map<String, Object>> fragments = new HashMap<>();

                if (temperature != null) {
                    Map<String, Object> tSeries = new HashMap<>();
                    tSeries.put("value", temperature.doubleValue());
                    tSeries.put("unit", "C");
                    Map<String, Object> tFragment = new HashMap<>();
                    tFragment.put("T", tSeries);
                    fragments.put("c8y_Temperature", tFragment);
                }

                if (humidity != null) {
                    Map<String, Object> hSeries = new HashMap<>();
                    hSeries.put("value", humidity.doubleValue());
                    hSeries.put("unit", "%");
                    Map<String, Object> hFragment = new HashMap<>();
                    hFragment.put("H", hSeries);
                    fragments.put("c8y_Humidity", hFragment);
                }

                // Convenience overload injects "source": {"id": deviceId} automatically
                builder.measurement("c8y_EnvironmentMeasurement", time, deviceId, fragments);
            }

            log.debug("{} - Built measurement collection with {} entries for device {}",
                    tenant, readings.size(), deviceId);

            return new CumulocityObject[] { builder.build() };

        } catch (Exception e) {
            String errorMsg = "Failed to process bulk measurement collection: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
