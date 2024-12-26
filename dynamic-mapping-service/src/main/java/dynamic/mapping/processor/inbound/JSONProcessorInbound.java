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

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapping.model.MappingSubstitution.isArray;
import static dynamic.mapping.model.MappingSubstitution.toPrettyJsonString;

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
import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
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
        Map<String, List<MappingSubstitution.SubstituteValue>> processingCache = context.getProcessingCache();

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
            List<MappingSubstitution.SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<>());

            if (isArray(extractedSourceContent) && substitution.expandArray) {
                // extracted result from sourcePayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                for (Object jn : (Collection) extractedSourceContent) {
                    MappingSubstitution.processSubstitute(tenant, processingCacheEntry, jn,
                            substitution, mapping);
                }
            } else {
                MappingSubstitution.processSubstitute(tenant, processingCacheEntry, extractedSourceContent,
                        substitution, mapping);
            }
            processingCache.put(substitution.pathTarget, processingCacheEntry);
            if (serviceConfiguration.logSubstitution || mapping.debug) {
                log.debug("Tenant {} - Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                        tenant,
                        substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
            }

            if (substitution.pathTarget.equals(Mapping.TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY && mapping.targetAPI != API.OPERATION) {
            List<MappingSubstitution.SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    Mapping.TIME,
                    new ArrayList<>());
            processingCacheEntry.add(
                    new MappingSubstitution.SubstituteValue(new DateTime().toString(),
                            TYPE.TEXTUAL, RepairStrategy.DEFAULT));
            processingCache.put(Mapping.TIME, processingCacheEntry);
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