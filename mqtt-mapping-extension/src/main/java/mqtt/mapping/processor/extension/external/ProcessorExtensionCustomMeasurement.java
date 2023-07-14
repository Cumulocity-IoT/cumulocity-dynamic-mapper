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

package mqtt.mapping.processor.extension.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.extension.ProcessorExtension;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
public class ProcessorExtensionCustomMeasurement implements ProcessorExtension<byte[]> {

    private ObjectMapper objectMapper;
//     @Autowired
//     public void setObjectMapper (ObjectMapper objectMapper){
//         this.objectMapper = objectMapper;
//     }
    
    public ProcessorExtensionCustomMeasurement (){
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
        Map<String, List<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        postProcessingCache.put("time",
                new ArrayList<SubstituteValue>(
                        Arrays.asList(new SubstituteValue(
                                new TextNode(new DateTime(
                                        jsonNode.get("time").textValue())
                                        .toString()),
                                TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        ObjectNode fragment = objectMapper.createObjectNode();
        fragment.set("value",  new FloatNode(jsonNode.get("temperature").floatValue()));
        fragment.set("unit",  new TextNode(jsonNode.get("unit").textValue()));

        postProcessingCache.put("c8y_Fragment_to_remove",
                new ArrayList<SubstituteValue>(Arrays.asList(
                        new SubstituteValue(null,
                                TYPE.TEXTUAL,
                                RepairStrategy.REMOVE_IF_NULL))));

        postProcessingCache.put("c8y_Temperature",
                new ArrayList<SubstituteValue>(Arrays.asList(
                        new SubstituteValue(fragment,
                                TYPE.OBJECT,
                                RepairStrategy.DEFAULT))));

        postProcessingCache.put(context.getMapping().targetAPI.identifier,
                new ArrayList<SubstituteValue>(Arrays.asList(
                        new SubstituteValue(new TextNode(jsonNode.get("externalId").textValue()),
                                TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));

        log.info("New measurement over json processor: {}, {}, {}", jsonNode.get("time").textValue(), jsonNode.get("unit").textValue(), jsonNode.get("temperature").floatValue());
    }

}