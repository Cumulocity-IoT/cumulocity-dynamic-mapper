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

package dynamic.mapping.processor.extension.internal;

import com.dashjoin.jsonata.json.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;

import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import jakarta.ws.rs.ProcessingException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GraalsCodeExtension implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context) throws ProcessingException {
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

                Map jsonObject = (Map) Json.parseJson(new String(context.getPayload(), "UTF-8"));

                // add topic levels as metadata
                List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
                ((Map) jsonObject).put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicAsList);

                final Value result = extractFromSourceFunc
                        .execute(new SubstitutionContext(context.getMapping().getGenericDeviceIdentifier(),
                                jsonObject));

                // Convert the JavaScript result to Java objects before closing the context
                final SubstitutionResult typedResult = result.as(SubstitutionResult.class);

                // Now use the copied objects
                for (Substitution item : typedResult.substitutions) {
                    Object convertedValue = (item.value instanceof Value)
                            ? convertPolyglotValue((Value) item.value)
                            : item.value;

                    context.addToProcessingCache(item.key, convertedValue, TYPE.valueOf(item.type),
                            RepairStrategy.valueOf(item.repairStrategy));
                }

                log.info("Tenant {} - New payload over GraalsCodeExtension: {}, {}", context.getTenant(),
                        jsonObject);

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