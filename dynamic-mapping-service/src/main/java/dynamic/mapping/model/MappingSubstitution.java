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

package dynamic.mapping.model;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.internal.JsonFormatter;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.RepairStrategy;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;

@Slf4j
@Getter
@ToString()
public class MappingSubstitution implements Serializable {

    public static class SubstituteValue implements Cloneable {
        public static enum TYPE {
            ARRAY,
            IGNORE,
            NUMBER,
            OBJECT,
            TEXTUAL,
        }

        public Object value;
        public TYPE type;
        public RepairStrategy repairStrategy;

        public SubstituteValue(Object value, TYPE type, RepairStrategy repair) {
            this.type = type;
            this.value = value;
            this.repairStrategy = repair;
        }

        @Override
        public SubstituteValue clone() {
            return new SubstituteValue(this.value, this.type, this.repairStrategy);
        }
    }

    public MappingSubstitution() {
        this.repairStrategy = RepairStrategy.DEFAULT;
        this.expandArray = false;
    }

    @NotNull
    public String pathSource;

    @NotNull
    public String pathTarget;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public RepairStrategy repairStrategy;

    @JsonSetter(nulls = Nulls.SKIP)
    public boolean expandArray;

    public static Boolean isArray(Object obj) {
        return obj != null && obj instanceof Collection;
    }

    public static Boolean isObject(Object obj) {
        return obj != null && obj instanceof Map;
    }

    public static Boolean isTextual(Object obj) {
        return obj != null && obj instanceof String;
    }

    public static Boolean isNumber(Object obj) {
        return obj != null && obj instanceof Number;
    }

    public static Boolean isBoolean(Object obj) {
        return obj != null && obj instanceof Boolean;
    }

    public static String toPrettyJsonString(Object obj) {
        if (obj == null) {
            return null;
        } else if (obj instanceof Map || obj instanceof Collection) {
            return JsonFormatter.prettyPrint(JsonPath.parse(obj).jsonString());
        } else {
            return obj.toString();
        }
    }

    public static String toJsonString(Object obj) {
        if (obj == null) {
            return null;
        } else if (obj instanceof Map || obj instanceof Collection) {
            return JsonPath.parse(obj).jsonString();
        } else {
            return obj.toString();
        }
    }

    public static void substituteValueInPayload(MappingType type, MappingSubstitution.SubstituteValue sub,
            DocumentContext jsonObject, String keys)
            throws JSONException {
        boolean subValueMissingOrNull = sub == null || sub.value == null;
        // TOFDO fix this, we have to differentiate between {"nullField": null } and
        // "nonExisting"
        try {
            if (sub == null) return;
            if ("$".equals(keys)) {
                Object replacement = sub;
                if (replacement instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rm = (Map<String, Object>) map;
                    for (Map.Entry<String, Object> entry : rm.entrySet()) {
                        jsonObject.put("$", entry.getKey(), entry.getValue());
                    }
                }
            } else {
                if ((sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING_OR_NULL) && subValueMissingOrNull)) {
                    jsonObject.delete(keys);
                } else if (sub.repairStrategy.equals(RepairStrategy.CREATE_IF_MISSING)) {
                    // jsonObject.put("$", keys, sub.value);
                    addNestedValue(jsonObject, keys, sub.value);
                } else {
                    jsonObject.set(keys, sub.value);
                }
            }
        } catch (PathNotFoundException e) {
            throw new PathNotFoundException(String.format("Path: %s not found!", keys));
        }
    }

    public static void addNestedValue(DocumentContext jsonObject, String path, Object value) {
        String[] parts = path.split("\\.");
        StringBuilder currentPath = new StringBuilder("$");
        
        // Create all parent objects except the last one
        for (int i = 0; i < parts.length - 1; i++) {
            if (i == 0) {
                jsonObject.put("$", parts[i], new HashMap<>());
            } else {
                jsonObject.put(currentPath.toString(), parts[i], new HashMap<>());
            }
            currentPath.append(".").append(parts[i]);
        }
        
        // Add the final value
        jsonObject.put(currentPath.toString(), parts[parts.length - 1], value);
    }
    
    public static void processSubstitute(String tenant,
            List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry,
            Object extractedSourceContent, MappingSubstitution substitution, Mapping mapping) {
        if (extractedSourceContent == null) {
            log.warn("Tenant {} - Substitution {} not in message payload. Check your mapping {}", tenant,
                    substitution.pathSource, mapping.getMappingTopic());
            postProcessingCacheEntry
                    .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                            MappingSubstitution.SubstituteValue.TYPE.IGNORE, substitution.repairStrategy));
        } else if (isTextual(extractedSourceContent)) {
            postProcessingCacheEntry.add(
                    new MappingSubstitution.SubstituteValue(extractedSourceContent,
                            MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, substitution.repairStrategy));
        } else if (isNumber(extractedSourceContent)) {
            postProcessingCacheEntry
                    .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                            MappingSubstitution.SubstituteValue.TYPE.NUMBER, substitution.repairStrategy));
        } else if (isArray(extractedSourceContent)) {
            postProcessingCacheEntry
                    .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                            MappingSubstitution.SubstituteValue.TYPE.ARRAY, substitution.repairStrategy));
        } else {
            postProcessingCacheEntry
                    .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                            MappingSubstitution.SubstituteValue.TYPE.OBJECT, substitution.repairStrategy));
        }
    }
}
