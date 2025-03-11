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

package dynamic.mapping.processor.outbound;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import dynamic.mapping.processor.C8YMessage;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import dynamic.mapping.processor.model.Substitution;
import dynamic.mapping.processor.model.SubstitutionContext;
import dynamic.mapping.processor.model.SubstitutionResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeBasedProcessorOutbound extends BaseProcessorOutbound<Object> {

    public CodeBasedProcessorOutbound(ConfigurationRegistry configurationRegistry, AConnectorClient connectorClient) {
        super(configurationRegistry, connectorClient);
    }

    @Override
    public Object deserializePayload(Mapping mapping,
            C8YMessage c8yMessage) throws IOException {
        Object jsonNode = Json.parseJson(c8yMessage.getPayload());
        return jsonNode;
    }

    @Override
    public void extractFromSource(ProcessingContext<Object> context)
            throws ProcessingException {
        try {
            Mapping mapping = context.getMapping();
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

                Map jsonObject = (Map)context.getPayload();

                final Value result = extractFromSourceFunc
                        .execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),
                                jsonObject));

                // Convert the JavaScript result to Java objects before closing the context
                final SubstitutionResult typedResult = result.as(SubstitutionResult.class);

                if (typedResult == null || typedResult.substitutions == null || typedResult.substitutions.size() == 0) {
                    context.setIgnoreFurtherProcessing(true);
                    log.info("Tenant {} - Ignoring payload over CodeBasedProcessorOutbound: {}, {}", context.getTenant(),
                    jsonObject);
                } else { // Now use the copied objects
                    for (Substitution item : typedResult.substitutions) {
                        Object convertedValue = (item.getValue() instanceof Value)
                                ? convertPolyglotValue((Value) item.getValue())
                                : item.getValue();

                                context.addToProcessingCache(item.getKey(), convertedValue, TYPE.valueOf(item.getType()),
                                RepairStrategy.valueOf(item.getRepairStrategy()));
                    }

                    log.info("Tenant {} - New payload over CodeBasedProcessorOutbound: {}, {}", context.getTenant(),
                            jsonObject);
                }

            }

        } catch (Exception e) {
            throw new ProcessingException(e.getMessage());
        }
    }

        // Convert PolyglotMap to Java Map
    private Object convertPolyglotValue(Value value) {
        if (value == null) {
            return null;
        }
        if (value.isHostObject()) {
            return value.asHostObject();
        }
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(convertPolyglotValue(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertPolyglotValue(value.getMember(key)));
            }
            return map;
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.toString();
    }

}