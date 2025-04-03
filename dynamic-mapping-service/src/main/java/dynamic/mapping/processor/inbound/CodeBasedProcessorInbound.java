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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import org.joda.time.DateTime;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.API;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.processor.model.SubstituteValue.TYPE;
import dynamic.mapping.processor.model.SubstituteValue;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import dynamic.mapping.processor.model.SubstitutionContext;
import dynamic.mapping.processor.model.SubstitutionEvaluation;
import dynamic.mapping.processor.model.SubstitutionResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeBasedProcessorInbound extends BaseProcessorInbound<Object> {

    public CodeBasedProcessorInbound(ConfigurationRegistry configurationRegistry) {
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
            log.debug("Tenant {} - Patched payload: {} {} {} {}", tenant, payload, serviceConfiguration.logPayload,
                    mapping.debug, serviceConfiguration.logPayload || mapping.debug);
        }

        boolean substitutionTimeExists = false;

        if (mapping.code != null) {
            Context graalsContext = context.getGraalsContext();

            String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
            Value extractFromSourceFunc = graalsContext.getBindings("js").getMember(identifier);

            if (extractFromSourceFunc == null) {
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst(
                        Mapping.EXTRACT_FROM_SOURCE,
                        identifier);
                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();
                graalsContext.eval(source);
                extractFromSourceFunc = graalsContext.getBindings("js")
                        .getMember(identifier);
            }

            if (context.getSharedCode() != null) {
                byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
                String decodedSharedCode = new String(decodedSharedCodeBytes);
                Source sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                        .buildLiteral();
                graalsContext.eval(sharedSource);
            }

            Map jsonObject = (Map) context.getPayload();

            // add topic levels as metadata
            List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
            ((Map) jsonObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

            final Value result = extractFromSourceFunc
                    .execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),
                            jsonObject));

            // Convert the JavaScript result to Java objects before closing the context
            final SubstitutionResult typedResult = result.as(SubstitutionResult.class);

            if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.size() == 0) {
                context.setIgnoreFurtherProcessing(true);
                log.info("Tenant {} - Ignoring payload over CodeBasedProcessorInbound: {}", context.getTenant(),
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
                        SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, values.getFirst().value,
                                values.getFirst(), mapping);
                    }
                    processingCache.put(key, processingCacheEntry);

                    if (key.equals(Mapping.TIME)) {
                        substitutionTimeExists = true;
                    }
                }
                if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
                    log.info("Tenant {} - New payload over CodeBasedProcessorInbound: {}", context.getTenant(),
                            jsonObject);
                }
            }

        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY && mapping.targetAPI != API.OPERATION) {
            List<SubstituteValue> processingCacheEntry = processingCache.getOrDefault(
                    Mapping.TIME,
                    new ArrayList<>());
            processingCacheEntry.add(
                    new SubstituteValue(new DateTime().toString(),
                            TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
            processingCache.put(Mapping.TIME, processingCacheEntry);
        }
    }

}