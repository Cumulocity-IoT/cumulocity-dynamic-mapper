/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.extension.external;

import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import jakarta.ws.rs.ProcessingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class ProcessorExtensionCustomMeasurement implements ProcessorExtensionSource<byte[]> {

    private ObjectMapper objectMapper;

    public ProcessorExtensionCustomMeasurement() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        Map jsonObject;
        try {
            jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));
        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context
                .getPostProcessingCache();

        postProcessingCache.put("time",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(new MappingSubstitution.SubstituteValue(
                                new DateTime(
                                        jsonObject.get("time"))
                                        .toString(),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        Map fragmentTemperatureSeries = Map.of("value", jsonObject.get("temperature"), "unit", jsonObject.get("unit"));
        Map fragmentTemperature = Map.of("T", fragmentTemperatureSeries);

        postProcessingCache.put("c8y_Fragment_to_remove",
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(null,
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.REMOVE_IF_NULL))));

        postProcessingCache.put("c8y_Temperature",
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(fragmentTemperature,
                                MappingSubstitution.SubstituteValue.TYPE.OBJECT,
                                RepairStrategy.DEFAULT))));

        postProcessingCache.put(context.getMapping().targetAPI.identifier,
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(
                                jsonObject.get("externalId"),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        Number unexpected = Float.NaN;
        if (jsonObject.get("unexpected") != null) {
            // it is important to use RepairStrategy.CREATE_IF_MISSING as the node
            // "unexpected" does not yet exists in the target payload
            Map fragmentUnexpectedSeries = Map.of("value", jsonObject.get("unexpected"),"unit", "unknown");
            Map fragmentUnexpected = Map.of("U", fragmentUnexpectedSeries);
            postProcessingCache.put("c8y_Unexpected",
                    new ArrayList<MappingSubstitution.SubstituteValue>(
                            Arrays.asList(new MappingSubstitution.SubstituteValue(
                                    fragmentUnexpected,
                                    MappingSubstitution.SubstituteValue.TYPE.OBJECT,
                                    RepairStrategy.CREATE_IF_MISSING))));
            unexpected = (Number)jsonObject.get("unexpected");

        }

        log.info("Tenant {} - New measurement over json processor: {}, {}, {}, {}", context.getTenant(),
                jsonObject.get("time").toString(),
                jsonObject.get("unit").toString(), jsonObject.get("temperature"),
                unexpected);
    }
}