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

package dynamic.mapper.processor.inbound;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.io.IOException;
import java.util.*;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import org.joda.time.DateTime;

import com.dashjoin.jsonata.Functions;
import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstitutionContext;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.processor.model.SubstitutionResult;
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
            log.info("{} - Patched payload: {}", tenant, payload);
        }

        boolean substitutionTimeExists = false;

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

            // add topic levels as metadata
            List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
            ((Map) jsonObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);
            Map contextData = new HashMap<String, String>() {
                {
                    put("api", mapping.targetAPI.toString());
                }
            };
            ((Map) jsonObject).put(Mapping.TOKEN_CONTEXT_DATA, contextData);

            final Value result = sourceValue
                    .execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),
                            payloadAsString));

            // Convert the JavaScript result to Java objects before closing the context
            final SubstitutionResult typedResult = result.as(SubstitutionResult.class);

            if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.size() == 0) {
                context.setIgnoreFurtherProcessing(true);
                log.info(
                        "{} - Extraction of source in CodeBasedProcessorInbound.extractFromSource returned no result, payload: {}",
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
                        SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, values.getFirst().value,
                                values.getFirst(), mapping);
                    }
                    processingCache.put(key, processingCacheEntry);

                    if (key.equals(Mapping.KEY_TIME)) {
                        substitutionTimeExists = true;
                    }
                }
                if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
                    log.info(
                            "{} - Extraction of source in CodeBasedProcessorInbound.extractFromSource returned {} results, payload: {} ",
                            context.getTenant(),
                            keySet == null ? 0 : keySet.size(), jsonObject);
                }
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