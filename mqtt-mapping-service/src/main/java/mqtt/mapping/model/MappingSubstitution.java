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

package mqtt.mapping.model;

import lombok.Getter;
import lombok.ToString;
import mqtt.mapping.processor.model.RepairStrategy;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

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
            Object result;
            DocumentContext dc;
            switch (type) {
                case ARRAY:
                dc = JsonPath.parse(value.toString());
                result = dc.read("$");
                return result;
                case IGNORE:
                    return null;
                case NUMBER:
                    return value.numberValue();
                case OBJECT:
                    dc = JsonPath.parse(value.toString());
                    result = dc.read("$");
                    return result;
                case TEXTUAL:
                    return value.textValue();
                default:
                    return value.toString();
            }

            // if (type.equals(TYPE.TEXTUAL)) {

            // } else if (type.equals(TYPE.OBJECT)) {
            // return value;
            // } else if {
            // check if int
            // try {
            // return Integer.parseInt(value.asText());
            // } catch (NumberFormatException e1) {
            // // not int
            // try {
            // return Float.parseFloat(value.asText());
            // } catch (NumberFormatException e2) {
            // // not int
            // try {
            // return Double.parseDouble(value.asText());
            // } catch (NumberFormatException e3) {
            // return value;
            // }
            // }
            // }
            // }
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
    public boolean definesDeviceIdentifier(API api, Direction direction) {
        if (Direction.OUTBOUND.equals(direction)) {
            return api.identifier.equals(pathSource);
        } else {
            return api.identifier.equals(pathTarget);
        }
    }

    @JsonSetter(nulls = Nulls.SKIP)
    public boolean expandArray;
}
