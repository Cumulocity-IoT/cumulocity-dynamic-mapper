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

package dynamic.mapper.processor.extension.external;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.extension.ProcessorExtensionSource;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import jakarta.ws.rs.ProcessingException;
import java.util.Map;

@Slf4j
public class ProcessorExtensionCustomMeasurement implements ProcessorExtensionSource<byte[]> {

    public ProcessorExtensionCustomMeasurement() {
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        try {
            Map jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));

            context.addSubstitution("time", new DateTime(
                    jsonObject.get("time"))
                    .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);

            Map fragmentTemperatureSeries = Map.of("value", jsonObject.get("temperature"), "unit",
                    jsonObject.get("unit"));
            Map fragmentTemperature = Map.of("T", fragmentTemperatureSeries);

            context.addSubstitution("c8y_Fragment_to_remove", null, TYPE.TEXTUAL,
                    RepairStrategy.REMOVE_IF_MISSING_OR_NULL, false);
            context.addSubstitution("c8y_Temperature",
                    fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT,false);
            context.addSubstitution("c8y_Temperature",
                    fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT,false);
            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addSubstitution(context.getMapping().getGenericDeviceIdentifier(),
                    jsonObject.get("externalId")
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);

            Number unexpected = Float.NaN;
            if (jsonObject.get("unexpected") != null) {
                // it is important to use RepairStrategy.CREATE_IF_MISSING as the node
                // "unexpected" does not yet exists in the target payload
                Map fragmentUnexpectedSeries = Map.of("value", jsonObject.get("unexpected"), "unit", "unknown_unit");
                Map fragmentUnexpected = Map.of("U", fragmentUnexpectedSeries);
                context.addSubstitution("c8y_Unexpected",
                        fragmentUnexpected, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING, false);
                unexpected = (Number) jsonObject.get("unexpected");
            }

            log.info("{} - New measurement over json processor: {}, {}, {}, {}", context.getTenant(),
                    jsonObject.get("time").toString(),
                    jsonObject.get("unit").toString(), jsonObject.get("temperature"),
                    unexpected);
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }
}