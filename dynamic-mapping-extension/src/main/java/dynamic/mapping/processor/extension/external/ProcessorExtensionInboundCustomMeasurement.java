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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class ProcessorExtensionInboundCustomMeasurement implements ProcessorExtensionSource<byte[]> {

    private ObjectMapper objectMapper;

    public ProcessorExtensionInboundCustomMeasurement() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(context.getPayload());
        } catch (IOException e) {
            throw new ProcessingException(e.getMessage());
        }
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context
                .getPostProcessingCache();

        postProcessingCache.put("time",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(new MappingSubstitution.SubstituteValue(
                                new TextNode(new DateTime(
                                        jsonNode.get("time").textValue())
                                        .toString()),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        ObjectNode fragmentTemperature = objectMapper.createObjectNode();
        ObjectNode fragmentTemperatureSeries = objectMapper.createObjectNode();
        fragmentTemperature.set("T", fragmentTemperatureSeries);
        fragmentTemperatureSeries.set("value", new FloatNode(jsonNode.get("temperature").floatValue()));
        fragmentTemperatureSeries.set("unit", new TextNode(jsonNode.get("unit").textValue()));

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
                                new TextNode(jsonNode.get("externalId").textValue()),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        float unexpected = Float.NaN;
        if (jsonNode.get("unexpected") != null) {
            // it is important to use RepairStrategy.CREATE_IF_MISSING as the node
            // "unexpected" does not yet exists in the target payload
            ObjectNode fragmentUnexpected = objectMapper.createObjectNode();
            ObjectNode fragmentUnexpectedSeries = objectMapper.createObjectNode();
            fragmentUnexpected.set("U", fragmentUnexpectedSeries);
            fragmentUnexpectedSeries.set("value", new FloatNode(jsonNode.get("unexpected").floatValue()));
            fragmentUnexpectedSeries.set("unit", new TextNode("unknown"));
            postProcessingCache.put("c8y_Unexpected",
                    new ArrayList<MappingSubstitution.SubstituteValue>(
                            Arrays.asList(new MappingSubstitution.SubstituteValue(
                                    fragmentUnexpected,
                                    MappingSubstitution.SubstituteValue.TYPE.OBJECT,
                                    RepairStrategy.CREATE_IF_MISSING))));
            unexpected = jsonNode.get("unexpected").floatValue();

        }

        log.info("Tenant {} - New measurement over json processor: {}, {}, {}, {}", context.getTenant(),
                jsonNode.get("time").textValue(),
                jsonNode.get("unit").textValue(), jsonNode.get("temperature").floatValue(),
                unexpected);
    }
}