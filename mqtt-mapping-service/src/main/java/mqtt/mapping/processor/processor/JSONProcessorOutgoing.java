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

package mqtt.mapping.processor.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.BasePayloadProcessorOutgoing;
import mqtt.mapping.processor.C8YMessage;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class JSONProcessorOutgoing extends BasePayloadProcessorOutgoing<JsonNode> {

    public JSONProcessorOutgoing(ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent) {
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context,
            C8YMessage c8yMessage) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(c8yMessage.getPayload());
        context.setPayload(jsonNode);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<JsonNode> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        JsonNode payloadJsonNode = context.getPayload();
        Map<String, List<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        String payload = payloadJsonNode.toPrettyString();
        log.info("Patched payload: {}", payload);

        boolean substitutionTimeExists = false;
        for (MappingSubstitution substitution : mapping.substitutions) {
            JsonNode extractedSourceContent = null;
            /*
             * step 1 extract content from incoming payload
             */
            try {
                // have to escape _DEVICE_IDENT_ , _TOPIC_LEVEL_ with BACKQUOTE "`" since
                // JSONata4Java does work for tokens with starting "_"
                var p = substitution.pathSource.replace(TOKEN_DEVICE_TOPIC, TOKEN_DEVICE_TOPIC_BACKQUOTE);
                p = p.replace(TOKEN_TOPIC_LEVEL, TOKEN_TOPIC_LEVEL_BACKQUOTE);
                log.debug("Patched sub.pathSource: {}, {}", substitution.pathSource, p);
                Expressions expr = Expressions.parse(p);
                extractedSourceContent = expr.evaluate(payloadJsonNode);
            } catch (ParseException | IOException | EvaluateException e) {
                log.error("Exception for: {}, {}", substitution.pathSource,
                        payload, e);
            } catch (EvaluateRuntimeException e) {
                log.error("EvaluateRuntimeException for: {}, {}", substitution.pathSource,
                        payload, e);
            }
            /*
             * step 2 analyse exctracted content: textual, array
             */
            List<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(substitution.pathTarget,
                    new ArrayList<SubstituteValue>());
            if (extractedSourceContent == null) {
                log.error("No substitution for: {}, {}", substitution.pathSource,
                        payload);
                postProcessingCacheEntry
                        .add(new SubstituteValue(extractedSourceContent, TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
            } else {
                if (extractedSourceContent.isArray()) {
                    if (substitution.expandArray) {
                        // extracted result from sourcPayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (JsonNode jn : extractedSourceContent) {
                            if (jn.isTextual()) {
                                postProcessingCacheEntry
                                        .add(new SubstituteValue(jn, TYPE.TEXTUAL, substitution.repairStrategy));
                            } else if (jn.isNumber()) {
                                postProcessingCacheEntry
                                        .add(new SubstituteValue(jn, TYPE.NUMBER, substitution.repairStrategy));
                            } else {
                                log.warn("Since result is not textual or number it is ignored: {}",
                                        jn.asText());
                            }
                        }
                        context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    } else {
                        // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE
                        // substitution
                        context.addCardinality(substitution.pathTarget, 1);
                        postProcessingCacheEntry
                                .add(new SubstituteValue(extractedSourceContent, TYPE.ARRAY,
                                        substitution.repairStrategy));
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    }
                } else if (extractedSourceContent.isTextual()) {
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry.add(
                            new SubstituteValue(extractedSourceContent, TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else if (extractedSourceContent.isNumber()) {
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else {
                    log.info("This substitution, involves an objects for: {}, {}",
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                }
                if (mqttClient.getServiceConfiguration().logSubstitution) {
                    log.info("Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                            substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
                }
            }

            if (substitution.pathTarget.equals(TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY) {
            List<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(TIME,
                    new ArrayList<SubstituteValue>());
            postProcessingCacheEntry.add(
                    new SubstituteValue(new TextNode(new DateTime().toString()), TYPE.TEXTUAL, RepairStrategy.DEFAULT));
            postProcessingCache.put(TIME, postProcessingCacheEntry);
        }
    }

}