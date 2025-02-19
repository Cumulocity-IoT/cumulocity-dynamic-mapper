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

package dynamic.mapping.processor.extension.internal;

import org.joda.time.DateTime;

import com.dashjoin.jsonata.json.Json;

import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;

import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import jakarta.ws.rs.ProcessingException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraalsMeasurement implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        try {
            Map jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));

            context.addToProcessingCache("time", new DateTime(
                    jsonObject.get("time"))
                    .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT);

            Map fragmentTemperatureSeries = Map.of("value", jsonObject.get("temperature"), "unit",
                    jsonObject.get("unit"));
            Map fragmentTemperature = Map.of("T", fragmentTemperatureSeries);

            context.addToProcessingCache("c8y_Fragment_to_remove", null, TYPE.TEXTUAL,
                    RepairStrategy.REMOVE_IF_MISSING_OR_NULL);
            context.addToProcessingCache("c8y_Temperature",
                    fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT);
            context.addToProcessingCache("c8y_Temperature",
                    fragmentTemperature, TYPE.OBJECT, RepairStrategy.DEFAULT);
            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addToProcessingCache(context.getMapping().getGenericDeviceIdentifier(),
                    jsonObject.get("externalId")
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT);

            Number unexpected = Float.NaN;
            if (jsonObject.get("unexpected") != null) {
                // it is important to use RepairStrategy.CREATE_IF_MISSING as the node
                // "unexpected" does not yet exists in the target payload
                Map fragmentUnexpectedSeries = Map.of("value", jsonObject.get("unexpected"), "unit", "unknown_unit");
                Map fragmentUnexpected = Map.of("U", fragmentUnexpectedSeries);
                context.addToProcessingCache("c8y_Unexpected",
                        fragmentUnexpected, TYPE.OBJECT, RepairStrategy.CREATE_IF_MISSING);
                unexpected = (Number) jsonObject.get("unexpected");
            }

            log.info("Tenant {} - New measurement over json processor: {}, {}, {}, {}", context.getTenant(),
                    jsonObject.get("time").toString(),
                    jsonObject.get("unit").toString(), jsonObject.get("temperature"),
                    unexpected);
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }
}