/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Java Extension demonstrating N:1 collection with {@code context.collectNative} (since 6.3).
 *
 * <p>A device reports temperature and humidity in separate messages that carry the same timestamp. This extension
 * holds the first message back and, when the second one arrives, creates one measurement with both fragments.</p>
 *
 * <ul>
 *   <li>The group key is {@code clientId|time}, so each device and timestamp gets its own group.</li>
 *   <li>The group is complete when both item keys, {@code temperature} and {@code humidity}, are present.</li>
 *   <li>{@code maxAgeSeconds} drops a group whose partner message never arrived, so it does not stay open.</li>
 *   <li>{@code collectNative} is atomic: if both messages are processed at the same time, exactly one of them
 *       receives the complete group.</li>
 * </ul>
 *
 * <p>Input JSON format, two messages:</p>
 * <pre>
 * { "clientId": "sensor-001", "time": "2026-01-01T00:00:00Z", "sensor": "temperature", "value": 22.5 }
 * { "clientId": "sensor-001", "time": "2026-01-01T00:00:00Z", "sensor": "humidity",    "value": 55.0 }
 * </pre>
 *
 * <p>Output: nothing for the first message. One {@code c8y_TemperatureMeasurement} with
 * {@code c8y_Temperature.T} and {@code c8y_Humidity.H} for the second.</p>
 *
 * <p>Groups live in memory only and are lost on a restart. An item is stored at once, so a message that is
 * redelivered after a failure adds its item again; this is harmless here because the same item key replaces the
 * earlier value. In a test run nothing is collected and a warning is added.</p>
 *
 * @since 6.3
 */
@Slf4j
public class ProcessorExtensionCollectMeasurement implements ProcessorExtensionInbound<byte[]> {

    static final String TEMPERATURE = "temperature";
    static final String HUMIDITY = "humidity";
    static final int MAX_AGE_SECONDS = 60;

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, JavaExtensionContext context) {
        try {
            String jsonString = new String(message.getPayload(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) Json.parseJson(jsonString);

            String clientId = context.getClientId();
            if (clientId == null) {
                clientId = (String) payload.get("clientId");
            }
            Object time = payload.get("time");
            Object sensor = payload.get("sensor");
            Object value = payload.get("value");
            if (clientId == null || time == null || value == null
                    || !(TEMPERATURE.equals(sensor) || HUMIDITY.equals(sensor))) {
                context.addWarning("Expected clientId, time, value and a sensor of temperature or humidity - "
                        + "the message was ignored.");
                return new CumulocityObject[0];
            }

            FlowStateStore.CollectOptions options = new FlowStateStore.CollectOptions(
                    null, Set.of(TEMPERATURE, HUMIDITY), false, MAX_AGE_SECONDS);
            Map<String, Object> group = context.collectNative(clientId + "|" + time, (String) sensor, value, options);
            if (group == null) {
                log.debug("{} - Waiting for the partner of {} at {}", context.getTenant(), sensor, time);
                return new CumulocityObject[0];
            }

            return new CumulocityObject[] {
                CumulocityObject.measurement()
                    .type("c8y_TemperatureMeasurement")
                    .time(time)
                    .fragment("c8y_Temperature", "T", ((Number) group.get(TEMPERATURE)).doubleValue(), "C")
                    .fragment("c8y_Humidity", "H", ((Number) group.get(HUMIDITY)).doubleValue(), "%")
                    .externalId(clientId, "c8y_Serial")
                    .deviceName(clientId)
                    .deviceType("c8y_TemperatureSensor")
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
