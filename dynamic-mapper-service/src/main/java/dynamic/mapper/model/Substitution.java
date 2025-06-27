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

package dynamic.mapper.model;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.internal.JsonFormatter;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.processor.model.RepairStrategy;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

@Slf4j
@Getter
@ToString()
public class Substitution implements Serializable {

    public Substitution() {
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
}
