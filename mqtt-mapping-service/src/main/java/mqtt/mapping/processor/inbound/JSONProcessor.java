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

package mqtt.mapping.processor.inbound;

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
import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
//@Service
public class JSONProcessor extends BasePayloadProcessor<JsonNode> {

    public JSONProcessor(ObjectMapper objectMapper, C8YAgent c8yAgent, String tenant) {
        super(objectMapper, c8yAgent, tenant);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context,
                                                          ConnectorMessage message) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(message.getPayload());
        context.setPayload(jsonNode);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<JsonNode> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        JsonNode payloadJsonNode = context.getPayload();
        Map<String, List<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        ArrayNode topicLevels = objectMapper.createArrayNode();
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
        splitTopicAsList.forEach(s -> topicLevels.add(s));
        if (payloadJsonNode instanceof ObjectNode) {
            ((ObjectNode) payloadJsonNode).set(Mapping.TOKEN_TOPIC_LEVEL, topicLevels);
        } else {
            log.warn("Tenant {} - Parsing this message as JSONArray, no elements from the topic level can be used!", tenant);
        }

        String payload = payloadJsonNode.toPrettyString();
        log.info("Tenant {} - Patched payload: {}", tenant, payload);

        boolean substitutionTimeExists = false;
        for (MappingSubstitution substitution : mapping.substitutions) {
            JsonNode extractedSourceContent = null;
            /*
             * step 1 extract content from inbound payload
             */
            try {
                Expressions expr = Expressions.parse(substitution.pathSource);
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
                log.error("Tenant {} - No substitution for: {}, {}", substitution.pathSource, tenant,
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
                            } else if (jn.isArray()) {
                                postProcessingCacheEntry
                                        .add(new SubstituteValue(jn, TYPE.ARRAY, substitution.repairStrategy));
                            } else {
                                // log.warn("Since result is not textual or number it is ignored: {}",
                                // jn.asText());
                                postProcessingCacheEntry
                                        .add(new SubstituteValue(jn, TYPE.OBJECT, substitution.repairStrategy));
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
                    log.info("Tenant {} - This substitution, involves an objects for: {}, {}", tenant,
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                }
                if (c8yAgent.getServiceConfiguration().logSubstitution) {
                    log.info("Tenant {} - Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})", tenant,
                            substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
                }
            }

            if (substitution.pathTarget.equals(Mapping.TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY && mapping.targetAPI != API.OPERATION) {
            List<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(Mapping.TIME,
                    new ArrayList<SubstituteValue>());
            postProcessingCacheEntry.add(
                    new SubstituteValue(new TextNode(new DateTime().toString()), TYPE.TEXTUAL, RepairStrategy.DEFAULT));
            postProcessingCache.put(Mapping.TIME, postProcessingCacheEntry);
        }
    }

}