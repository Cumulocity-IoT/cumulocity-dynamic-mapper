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

package dynamic.mapper.processor.outbound;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingException;
import dynamic.mapper.processor.model.SubstituteValue;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JSONProcessorOutbound extends BaseProcessorOutbound<Object> {

    public JSONProcessorOutbound(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient) {
        super(configurationRegistry, connectorClient);
    }

    @Override
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();
        String payloadAsString = toPrettyJsonString(payloadObject);

        if (serviceConfiguration.logPayload || mapping.debug) {
            log.info("{} - Incoming payload (patched) in extractFromSource(): {} {} {} {}", tenant,
                    payloadAsString,
                    serviceConfiguration.logPayload, mapping.debug, serviceConfiguration.logPayload || mapping.debug);
        }

        for (Substitution substitution : mapping.substitutions) {
            Object extractedSourceContent = null;

            /*
             * step 1 extract content from inbound payload
             */
            extractedSourceContent = extractContent(context, mapping, payloadObject, payloadAsString,
                    substitution.pathSource);
            /*
             * step 2 analyse extracted content: textual, array
             */
            List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<>());

            if (dynamic.mapper.processor.model.SubstitutionEvaluation.isArray(extractedSourceContent) && substitution.expandArray) {
                var extractedSourceContentCollection = (Collection) extractedSourceContent;
                // extracted result from sourcePayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                for (Object jn : extractedSourceContentCollection) {
                    dynamic.mapper.processor.model.SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, jn,
                            substitution, mapping);
                }
            } else {
                dynamic.mapper.processor.model.SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, extractedSourceContent,
                        substitution, mapping);
            }
            processingCache.put(substitution.pathTarget, processingCacheEntry);

            if (context.getServiceConfiguration().logSubstitution || mapping.debug) {
                String contentAsString = extractedSourceContent != null ? extractedSourceContent.toString() : "null";
                log.debug("{} - Evaluated substitution (pathSource:substitute)/({}: {}), (pathTarget)/({})",
                        context.getTenant(),
                        substitution.pathSource, contentAsString, substitution.pathTarget);
            }
        }
    }

}