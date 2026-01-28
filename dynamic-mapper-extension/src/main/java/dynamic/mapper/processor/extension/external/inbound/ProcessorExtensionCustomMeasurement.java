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
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DataPreparationContext;
import dynamic.mapper.processor.flow.Message;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.Map;

/**
 * Extension for processing custom JSON measurements using the Smart Java Function pattern.
 *
 * <p>This extension demonstrates:</p>
 * <ul>
 *   <li>JSON parsing and field extraction</li>
 *   <li>Building complex measurement fragments</li>
 *   <li>Handling optional fields gracefully</li>
 *   <li>Return-value based processing with builder pattern</li>
 * </ul>
 *
 * <p>Input JSON format:</p>
 * <pre>
 * {
 *   "externalId": "sensor001",
 *   "time": "2024-01-01T12:00:00Z",
 *   "temperature": 25.5,
 *   "unit": "C",
 *   "unexpected": 42 (optional)
 * }
 * </pre>
 *
 * <p>Output: Cumulocity Measurement with c8y_Temperature fragment,
 * and optionally c8y_Unexpected fragment if present</p>
 */
@Slf4j
public class ProcessorExtensionCustomMeasurement implements ProcessorExtensionInbound<byte[]> {

    public ProcessorExtensionCustomMeasurement() {
    }

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        try {
            // 1. Parse JSON payload
            String jsonString = new String(message.getPayload(), "UTF-8");
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonObject = (Map<String, Object>) Json.parseJson(jsonString);

            // 2. Extract required fields
            String externalId = jsonObject.get("externalId").toString();
            DateTime time = new DateTime(jsonObject.get("time"));
            Number temperature = (Number) jsonObject.get("temperature");
            String unit = jsonObject.get("unit").toString();

            // 3. Build measurement with temperature fragment
            CumulocityObject.MeasurementBuilder builder = CumulocityObject.measurement()
                .type("c8y_TemperatureMeasurement")
                .time(time.toString())
                .fragment("c8y_Temperature", "T", temperature.doubleValue(), unit)
                .externalId(externalId, context.getMapping().getExternalIdType());

            // 4. Add optional unexpected fragment if present
            Number unexpected = null;
            if (jsonObject.get("unexpected") != null) {
                unexpected = (Number) jsonObject.get("unexpected");
                builder.fragment("c8y_Unexpected", "U", unexpected.doubleValue(), "unknown_unit");
            }

            log.info("{} - Processing custom measurement: time={}, temperature={} {}, unexpected={}",
                    context.getTenant(), time, temperature, unit, unexpected);

            return new CumulocityObject[] { builder.build() };

        } catch (Exception e) {
            String errorMsg = "Failed to process custom measurement: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
