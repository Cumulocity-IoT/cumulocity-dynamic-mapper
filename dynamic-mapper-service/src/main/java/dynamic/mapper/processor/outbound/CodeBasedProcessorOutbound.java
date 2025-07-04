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

import java.util.ArrayList;
import java.util.Base64;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.dashjoin.jsonata.Functions;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;

import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.processor.model.SubstitutionContext;
import dynamic.mapper.processor.model.SubstitutionResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeBasedProcessorOutbound extends BaseProcessorOutbound<Object> {

    public CodeBasedProcessorOutbound(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient) {
        super(configurationRegistry, connectorClient);
    }

    @Override
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        try {
            Mapping mapping = context.getMapping();
            String tenant = context.getTenant();
            if (mapping.code != null) {
                Context graalContext = context.getGraalContext();

                String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
                Value bindings = graalContext.getBindings("js");

                byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst(
                        Mapping.EXTRACT_FROM_SOURCE,
                        identifier);
                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier +
                        ".js")
                        .buildLiteral();
                graalContext.eval(source);
                Value sourceValue = bindings
                        .getMember(identifier);

                if (context.getSharedCode() != null) {
                    byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
                    String decodedSharedCode = new String(decodedSharedCodeBytes);
                    Source sharedSource = Source.newBuilder("js", decodedSharedCode,
                            "sharedCode.js")
                            .buildLiteral();
                    graalContext.eval(sharedSource);
                }

                if (context.getSystemCode() != null) {
                    byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
                    String decodedSystemCode = new String(decodedSystemCodeBytes);
                    Source systemSource = Source.newBuilder("js", decodedSystemCode,
                            "systemCode.js")
                            .buildLiteral();
                    graalContext.eval(systemSource);
                }

                Map jsonObject = (Map) context.getPayload();
                String payloadAsString = Functions.string(context.getPayload(), false);

                Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

                final Value result = sourceValue
                        .execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),
                                payloadAsString));

                // Convert the JavaScript result to Java objects before closing the context
                final SubstitutionResult typedResult = result.as(SubstitutionResult.class);

                if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.size() == 0) {
                    context.setIgnoreFurtherProcessing(true);
                    log.info(
                            "{} - Extraction of source in CodeBasedProcessorOutbound.extractFromSource returned no result, payload: {}",
                            context.getTenant(),
                            jsonObject);
                } else { // Now use the copied objects
                    Set<String> keySet = typedResult.getSubstitutions().keySet();
                    for (String key : keySet) {
                        List<SubstituteValue> processingCacheEntry = new ArrayList<>();
                        List<SubstituteValue> values = typedResult.getSubstitutions().get(key);
                        if (values != null && values.size() > 0
                                && values.get(0).expandArray) {
                            // extracted result from sourcePayload is an array, so we potentially have to
                            // iterate over the result, e.g. creating multiple devices
                            for (SubstituteValue substitutionValue : values) {
                                SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry,
                                        substitutionValue.value,
                                        substitutionValue, mapping);
                            }
                        } else if (values != null) {
                            SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry,
                                    values.getFirst().value,
                                    values.getFirst(), mapping);
                        }
                        processingCache.put(key, processingCacheEntry);
                    }
                    if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
                        log.info(
                                "{} - Extraction of source in CodeBasedProcessorOutbound.extractFromSource returned {} results, payload: {} ",
                                context.getTenant(),
                                keySet == null ? 0 : keySet.size(), jsonObject);
                    }
                }

            }

        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }
}