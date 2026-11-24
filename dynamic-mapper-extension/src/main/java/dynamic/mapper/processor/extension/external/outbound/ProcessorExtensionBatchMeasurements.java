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

package dynamic.mapper.processor.extension.external.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java Extension demonstrating outbound batching with {@code context.collectNative} (since 6.3).
 *
 * <p>Sends the measurements of one device to the broker in batches: each Cumulocity measurement is held back until
 * {@value #BATCH_SIZE} of them have arrived for the same device, then one device message with a JSON array of all
 * of them is published. This reduces the number of messages for a device that is billed or limited per message.</p>
 *
 * <ul>
 *   <li>The group key is {@code batch|<source id>}: each device collects its own batch.</li>
 *   <li>The item key is the measurement time, so a redelivered measurement replaces itself and is not counted
 *       twice.</li>
 *   <li>{@code maxAgeSeconds} drops a batch that did not fill within {@value #MAX_AGE_SECONDS} seconds. Its
 *       measurements are lost, so use {@code takeNative} and {@code openGroupsNative} to flush partial batches
 *       instead if that is not acceptable.</li>
 * </ul>
 *
 * <p>Input (Cumulocity measurement, three times for one device):</p>
 * <pre>
 * { "source": {"id": "12345"}, "time": "2026-01-01T00:00:00Z",
 *   "c8y_TemperatureMeasurement": { "T": { "value": 25.5, "unit": "C" } } }
 * </pre>
 *
 * <p>Output after the third measurement:</p>
 * <pre>
 * Topic: measurements/batch/12345
 * Payload: [ {"time": "2026-01-01T00:00:00Z", "value": 25.5, "unit": "C"}, ... ]   (ordered by time)
 * </pre>
 *
 * <p>Groups live in memory only and are lost on a restart. In a test run nothing is collected and a warning is
 * added.</p>
 *
 * @since 6.3
 */
@Slf4j
public class ProcessorExtensionBatchMeasurements implements ProcessorExtensionOutbound<Object> {

    static final int BATCH_SIZE = 3;
    static final int MAX_AGE_SECONDS = 300;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @SuppressWarnings("unchecked")
    public DeviceMessage[] onMessage(Message<Object> message, JavaExtensionContext context)
            throws ProcessingException {
        try {
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();
            Map<String, Object> source = (Map<String, Object>) payload.get("source");
            Object sourceId = source == null ? null : source.get("id");
            Object time = payload.get("time");
            Map<String, Object> fragment = (Map<String, Object>) payload.get("c8y_TemperatureMeasurement");
            Map<String, Object> series = fragment == null ? null : (Map<String, Object>) fragment.get("T");
            if (sourceId == null || time == null || series == null) {
                context.addWarning("Expected source.id, time and c8y_TemperatureMeasurement.T - "
                        + "the measurement was ignored.");
                return new DeviceMessage[0];
            }

            Map<String, Object> reading = new TreeMap<>();
            reading.put("time", time);
            reading.put("value", series.get("value"));
            reading.put("unit", series.get("unit"));

            Map<String, Object> batch = context.collectNative("batch|" + sourceId, time.toString(), reading,
                    new FlowStateStore.CollectOptions(BATCH_SIZE, null, false, MAX_AGE_SECONDS));
            if (batch == null) {
                return new DeviceMessage[0]; // the batch is not full yet
            }

            // The group has no order: sort by time (ISO-8601 text sorts chronologically)
            List<Object> readings = new ArrayList<>(new TreeMap<>(batch).values());
            return new DeviceMessage[] {
                DeviceMessage.forTopic("measurements/batch/" + sourceId)
                    .payload(objectMapper.writeValueAsString(readings))
                    .build()
            };
        } catch (Exception e) {
            String errorMsg = "Failed to process outbound message: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new DeviceMessage[0];
        }
    }
}
