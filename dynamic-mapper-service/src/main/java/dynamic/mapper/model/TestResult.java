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

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

import dynamic.mapper.processor.model.DynamicMapperRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Getter
@Setter
@Schema(description = "Result of testing mapping with payload in backend")
@ToString()
public class TestResult {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Generated requests resulting from the transformation")
    @NotNull
    private List<DynamicMapperRequest> requests = new ArrayList<>();

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "List of errors")
    @NotNull
    private List<String> errors= new ArrayList<>();

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "List of warnings")
    @NotNull
    private List<String> warnings= new ArrayList<>();

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Was the test successful")
    @NotNull
    private Boolean success = false;
}
