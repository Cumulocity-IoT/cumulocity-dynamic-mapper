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

package dynamic.mapping.processor.inbound;

import static dynamic.mapping.model.MappingSubstitution.isArray;
import static dynamic.mapping.model.MappingSubstitution.isTextual;
import static dynamic.mapping.model.MappingSubstitution.isNumber;
import static dynamic.mapping.model.MappingSubstitution.toPrettyJsonString;
import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.API;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JSONProcessorInbound extends BasePayloadProcessorInbound<Object> {

    public JSONProcessorInbound(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public ProcessingContext<Object> deserializePayload(
            Mapping mapping, ConnectorMessage message) throws IOException {
        Object jsonObject = Json.parseJson(new String(message.getPayload(), "UTF-8"));
        ProcessingContext<Object> context = new ProcessingContext<Object>();
        context.setPayload(jsonObject);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObjectNode = context.getPayload();
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
        if (payloadObjectNode instanceof Map) {
            ((Map) payloadObjectNode).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);
            if (context.isSupportsMessageContext() && context.getKey() != null) {
                String keyString = new String(context.getKey(), StandardCharsets.UTF_8);
                Map contextData = Map.of(Mapping.CONTEXT_DATA_KEY_NAME, keyString);
                ((Map) payloadObjectNode).put(Mapping.TOKEN_CONTEXT_DATA, contextData);
            }
        } else {
            log.warn("Tenant {} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                    tenant);
        }

        String payload = toPrettyJsonString(payloadObjectNode);
        if (serviceConfiguration.logPayload || mapping.debug) {
            log.debug("Tenant {} - Patched payload: {} {} {} {}", tenant, payload, serviceConfiguration.logPayload,
                    mapping.debug, serviceConfiguration.logPayload || mapping.debug);
        }

        boolean substitutionTimeExists = false;
        for (MappingSubstitution substitution : mapping.substitutions) {
            Object extractedSourceContent = null;
            /*
             * step 1 extract content from inbound payload
             */
            try {
                var expr = jsonata(substitution.pathSource);
                extractedSourceContent = expr.evaluate(payloadObjectNode);
            } catch (Exception e) {
                log.error("Tenant {} - Exception for: {}, {}: ", tenant, substitution.pathSource,
                        payload, e);
            }
            /*
             * step 2 analyse extracted content: textual, array
             */
            List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<MappingSubstitution.SubstituteValue>());
            if (extractedSourceContent == null) {
                log.warn("Tenant {} - Substitution {} not in message payload. Check your mapping {}", tenant,
                        substitution.pathSource, mapping.getMappingTopic());
                postProcessingCacheEntry
                        .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                MappingSubstitution.SubstituteValue.TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
            } else {
                if (isArray(extractedSourceContent)) {
                    if (substitution.expandArray) {
                        Collection extractedSourceContentCollection = (Collection) extractedSourceContent;
                        // extracted result from sourcePayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (Object jn : extractedSourceContentCollection) {
                            if (isTextual(jn)) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                                substitution.repairStrategy));
                            } else if (isNumber(jn)) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.NUMBER,
                                                substitution.repairStrategy));
                            } else if (isArray(jn)) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.ARRAY,
                                                substitution.repairStrategy));
                            } else {
                                // log.warn("Tenant {} - Since result is not textual or number it is ignored:
                                // {}", tenant
                                // jn.asText());
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.OBJECT,
                                                substitution.repairStrategy));
                            }
                        }
                        context.addCardinality(substitution.pathTarget, extractedSourceContentCollection.size());
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    } else {
                        // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE
                        // substitution
                        context.addCardinality(substitution.pathTarget, 1);
                        postProcessingCacheEntry
                                .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                        MappingSubstitution.SubstituteValue.TYPE.ARRAY,
                                        substitution.repairStrategy));
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    }
                } else if (isTextual(extractedSourceContent)) {
                    context.addCardinality(substitution.pathTarget, 1);
                    postProcessingCacheEntry.add(
                            new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else if (isNumber(extractedSourceContent)) {
                    context.addCardinality(substitution.pathTarget, 1);
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else {
                    if (serviceConfiguration.logSubstitution || mapping.debug) {
                        log.debug("Tenant {} - This substitution, involves an objects for: {}, {}", tenant,
                                substitution.pathSource, extractedSourceContent.toString());
                    }
                    context.addCardinality(substitution.pathTarget, 1);
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                }
                if (serviceConfiguration.logSubstitution || mapping.debug) {
                    log.debug("Tenant {} - Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                            tenant,
                            substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
                }
            }

            if (substitution.pathTarget.equals(Mapping.TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY && mapping.targetAPI != API.OPERATION) {
            List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(
                    Mapping.TIME,
                    new ArrayList<MappingSubstitution.SubstituteValue>());
            postProcessingCacheEntry.add(
                    new MappingSubstitution.SubstituteValue(new DateTime().toString(),
                            MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, RepairStrategy.DEFAULT));
            postProcessingCache.put(Mapping.TIME, postProcessingCacheEntry);
        }
    }

    @Override
    public void applyFilter(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        String mappingFilter = context.getMapping().getFilterMapping();
        if (mappingFilter != null && !("").equals(mappingFilter)) {
            Object payloadObjectNode = context.getPayload();
            String payload = toPrettyJsonString(payloadObjectNode);
            try {
                var expr = jsonata(mappingFilter);
                Object extractedSourceContent = expr.evaluate(payloadObjectNode);
                context.setIgnoreFurtherProcessing(!isNodeTrue(extractedSourceContent));
            } catch (Exception e) {
                log.error("Tenant {} - Exception for: {}, {}: ", tenant, mappingFilter,
                        payload, e);
            }
        }
    }

    private boolean isNodeTrue(Object node) {
        // Case 1: Direct boolean value check
        if (node instanceof Boolean) {
            return (Boolean) node;
        }

        // Case 2: String value that can be converted to boolean
        if (node instanceof String) {
            String text = ((String) node).trim().toLowerCase();
            return "true".equals(text) || "1".equals(text) || "yes".equals(text);
            // Add more string variations if needed
        }

        return false;
    }

}