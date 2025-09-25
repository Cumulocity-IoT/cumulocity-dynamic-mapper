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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.internal.JsonFormatter;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.processor.model.RepairStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

@Slf4j
@Getter
@Builder
@ToString()
@JsonDeserialize(builder = Substitution.SubstitutionBuilder.class)
@Schema(description = "Field substitution configuration for transforming data between source and target formats during mapping execution")
public class Substitution implements Serializable {

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = """
            JSONPath expression to extract data from the source payload. 
            Supports standard JSONPath syntax including:
            - Root reference: $
            - Property access: $.temperature, $.device.id
            - Array access: $.readings[0], $.sensors[*].value
            - Wildcards: $.devices.*.name
            - Filters: $.readings[?(@.type == 'temperature')]
            """,
        example = "$.device.temperature"
    )
    @NotNull
    public String pathSource;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = """
            JSONPath expression defining where to place the extracted data in the target payload.
            Can reference:
            - Static paths: $.temperature.value
            - Device identity: _IDENTITY_.c8ySourceId, _IDENTITY_.externalId
            - Topic levels: _TOPIC_LEVEL_[0], _TOPIC_LEVEL_[1]
            - Context data: _CONTEXT_DATA_.timestamp
            """,
        example = "$.c8y_TemperatureMeasurement.T.value"
    )
    @NotNull
    public String pathTarget;

    @Builder.Default
    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Strategy to handle data extraction and transformation edge cases",
        implementation = RepairStrategy.class,
        example = "DEFAULT"
    )
    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public RepairStrategy repairStrategy = RepairStrategy.DEFAULT;

        @Builder.Default
    @Schema(
        description = "Whether to expand arrays by creating multiple target objects (one for each array element) instead of copying the entire array",
        example = "false"
    )
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean expandArray = false;

        // Add the builder configuration
    @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubstitutionBuilder {
        // Lombok will generate the builder methods
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
}