/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
 */

package dynamic.mapper.model.validation;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outcome of {@code POST /mapping/validate}: the same codes and details that a rejected save would
 * return as a 422, but as a regular 200 answer, since asking whether a mapping is valid is not an error.
 */
@Schema(description = "Result of validating a mapping without saving it")
public record MappingValidationResult(

        @Schema(description = "True when saving the mapping would not be rejected by validation")
        boolean valid,

        @Schema(description = "The individual validation error codes; empty when valid")
        List<ValidationError> errors,

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "Per-failure detail: which element failed and why, when the rule could determine it")
        List<ValidationIssue> details) {

    public static MappingValidationResult of(List<ValidationIssue> issues) {
        return new MappingValidationResult(
                issues.isEmpty(),
                issues.stream().map(ValidationIssue::code).toList(),
                issues);
    }
}
