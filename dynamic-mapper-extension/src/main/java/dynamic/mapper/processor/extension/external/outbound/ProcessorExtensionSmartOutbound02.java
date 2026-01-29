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

package dynamic.mapper.processor.extension.external.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.model.DataPreparationContext;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java Extension equivalent of template-SMART-OUTBOUND-02.js
 *
 * <p>This extension demonstrates outbound processing with array payload:</p>
 * <ul>
 *   <li>Transforming Cumulocity measurement to device message</li>
 *   <li>Wrapping measurement data in an array format</li>
 *   <li>Useful for devices expecting batch/array payloads</li>
 * </ul>
 *
 * <p>Input (Cumulocity measurement):</p>
 * <pre>
 * {
 *   "source": {"id": "12345"},
 *   "type": "c8y_TemperatureMeasurement",
 *   "c8y_TemperatureMeasurement": {
 *     "T": {
 *       "value": 25.5,
 *       "unit": "C"
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>Output (device message with array payload):</p>
 * <pre>
 * Topic: measurements/12345
 * Payload: [{
 *   "time": "2025-01-29T...",
 *   "c8y_Steam": {
 *     "Temperature": {
 *       "unit": "C",
 *       "value": 25.5
 *     }
 *   }
 * }]
 * </pre>
 */
@Slf4j
public class ProcessorExtensionSmartOutbound02 implements ProcessorExtensionOutbound<Object> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DeviceMessage[] onMessage(Message<Object> message, DataPreparationContext context) throws ProcessingException {
        try {
            // Get the Cumulocity measurement payload
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();

            // Log context and payload
            log.info("{} - Context state: {}", context.getTenant(), context.getStateAll());
            log.info("{} - Payload raw: {}", context.getTenant(), payload);
            log.info("{} - Payload messageId: {}", context.getTenant(), payload.get("messageId"));

            // Extract source device id
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) payload.getOrDefault("source", new HashMap<>());
            String sourceId = (String) source.get("id");

            // Extract temperature measurement
            @SuppressWarnings("unchecked")
            Map<String, Object> c8yTempMeasurement = (Map<String, Object>) payload.get("c8y_TemperatureMeasurement");
            @SuppressWarnings("unchecked")
            Map<String, Object> tempSeries = (Map<String, Object>) c8yTempMeasurement.get("T");
            Number value = (Number) tempSeries.get("value");

            // Build custom device payload (single measurement object)
            Map<String, Object> measurementObject = new HashMap<>();
            measurementObject.put("time", new DateTime().toString());

            Map<String, Object> temperatureData = new HashMap<>();
            temperatureData.put("unit", "C");
            temperatureData.put("value", value);

            Map<String, Object> c8ySteam = new HashMap<>();
            c8ySteam.put("Temperature", temperatureData);

            measurementObject.put("c8y_Steam", c8ySteam);

            // Wrap in array (as per JavaScript template)
            List<Map<String, Object>> arrayPayload = new ArrayList<>();
            arrayPayload.add(measurementObject);

            // Convert to JSON string
            String jsonPayload = objectMapper.writeValueAsString(arrayPayload);

            // Build and return device message using builder pattern
            return new DeviceMessage[] {
                DeviceMessage.forTopic("measurements/" + sourceId)
                    .payload(jsonPayload)
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
