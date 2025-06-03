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

package dynamic.mapping.processor.inbound;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static dynamic.mapping.model.Substitution.toPrettyJsonString;

import java.io.IOException;
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
import dynamic.mapping.model.Substitution;
import dynamic.mapping.processor.model.SubstituteValue.TYPE;
import dynamic.mapping.processor.model.SubstitutionEvaluation;
import dynamic.mapping.processor.model.SubstituteValue;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JSONProcessorInbound extends BaseProcessorInbound<Object> {

    public JSONProcessorInbound(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public Object deserializePayload(
            Mapping mapping, ConnectorMessage message) throws IOException {
        Object jsonObject = Json.parseJson(new String(message.getPayload(), "UTF-8"));
        return jsonObject;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

        String payload = toPrettyJsonString(payloadObject);
        if (serviceConfiguration.logPayload || mapping.debug) {
            log.info("{} - Patched payload: {}", tenant, payload);
        }

        boolean substitutionTimeExists = false;
        for (Substitution substitution : mapping.substitutions) {
            Object extractedSourceContent = null;
            /*
             * step 1 extract content from inbound payload
             */
            try {
                var expr = jsonata(substitution.pathSource);
                extractedSourceContent = expr.evaluate(payloadObject);
            } catch (Exception e) {
                log.error("{} - Exception for: {}, {}: ", tenant, substitution.pathSource,
                        payload, e);
            }
            /*
             * step 2 analyse extracted content: textual, array
             */
            List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<>());

            if (extractedSourceContent != null && SubstitutionEvaluation.isArray(extractedSourceContent) && substitution.expandArray) {
                // extracted result from sourcePayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                for (Object jn : (Collection) extractedSourceContent) {
                    SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, jn,
                            substitution, mapping);
                }
            } else {
                SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, extractedSourceContent,
                        substitution, mapping);
            }
            processingCache.put(substitution.pathTarget, processingCacheEntry);
            if (serviceConfiguration.logSubstitution || mapping.debug) {
                log.debug("{} - Evaluated substitution (pathSource:substitute)/({}: {}), (pathTarget)/({})",
                        tenant,
                        substitution.pathSource,
                        extractedSourceContent == null ? null : extractedSourceContent.toString(),
                        substitution.pathTarget);
            }

            if (substitution.pathTarget.equals(Mapping.KEY_TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY && mapping.targetAPI != API.OPERATION) {
            List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    Mapping.KEY_TIME,
                    new ArrayList<>());
            processingCacheEntry.add(
                    new SubstituteValue(new DateTime().toString(),
                            TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false));
            processingCache.put(Mapping.KEY_TIME, processingCacheEntry);
        }
    }

}