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
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import lombok.Getter;
import lombok.ToString;
import dynamic.mapping.processor.model.RepairStrategy;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

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

        public JsonNode value;
        public TYPE type;
        public RepairStrategy repairStrategy;

        public SubstituteValue(JsonNode value, TYPE type, RepairStrategy repair) {
            this.type = type;
            this.value = value;
            this.repairStrategy = repair;
        }

        public Object typedValue() {
            DocumentContext dc;
            switch (type) {
                case OBJECT:
                    Map<String, Object> ro = null;
                    if (value != null && !value.isNull()) {
                        dc = JsonPath.parse(value.toString());
                        ro = dc.read("$");
                    } else {
                        ro = null;
                    }
                    return ro;
                case ARRAY:
                    List<Map<String, Object>> ra = null;
                    if (value != null && !value.isNull()) {
                        dc = JsonPath.parse(value.toString());
                        ra = dc.read("$");
                    } else {
                        ra = null;
                    }
                    return ra;
                case IGNORE:
                    return null;
                case NUMBER:
                    return value.numberValue();
                case TEXTUAL:
                    return value.textValue();
                default:
                    return value.toString();
            }
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
    public boolean definesDeviceIdentifier(API api, String externalIdType, Direction direction,
            MappingSubstitution sub) {
        if (Direction.INBOUND.equals(direction)) {
            if (externalIdType != null && !("").equals(externalIdType)) {
                return (Mapping.IDENTITY + ".externalId").equals(sub.pathTarget);
            } else {
                return (Mapping.IDENTITY + ".c8yId").equals(sub.pathTarget);
            }
        } else {
            if (externalIdType != null && !("").equals(externalIdType)) {
                return (Mapping.IDENTITY + ".externalId").equals(sub.pathSource);
            } else {
                return (Mapping.IDENTITY + ".c8yId").equals(sub.pathSource);
            }
        }
    }

    @JsonSetter(nulls = Nulls.SKIP)
    public boolean expandArray;

}
