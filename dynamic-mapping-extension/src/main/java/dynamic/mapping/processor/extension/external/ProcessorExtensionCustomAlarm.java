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
import com.fasterxml.jackson.databind.node.TextNode;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.extension.ProcessorExtensionTarget;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import dynamic.mapping.core.C8YAgent;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class ProcessorExtensionCustomAlarm
        implements ProcessorExtensionSource<byte[]>, ProcessorExtensionTarget<byte[]> {

    private ObjectMapper objectMapper;

    public ProcessorExtensionCustomAlarm() {
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

        postProcessingCache.put("type",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        new TextNode(jsonNode.get("alarmType")
                                                .textValue()),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));

        postProcessingCache.put("severity",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        new TextNode(jsonNode.get("criticality")
                                                .textValue()),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));

        postProcessingCache.put("text",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        new TextNode(jsonNode.get("message")
                                                .textValue()),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));
        postProcessingCache.put(context.getMapping().targetAPI.identifier,
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(
                                new TextNode(jsonNode.get("externalId").textValue()),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        log.info("Tenant {} - New alarm over json processor: {}, {}", context.getTenant(),
                jsonNode.get("time").textValue(), jsonNode.get("message").textValue());
    }

    @Override
    public void substituteInTargetAndSend(ProcessingContext<byte[]> context, C8YAgent c8yAgent) {

    }
}