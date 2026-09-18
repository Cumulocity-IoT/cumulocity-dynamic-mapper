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

package dynamic.mapper.model.validation;

import dynamic.mapper.model.Mapping;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Structured error body returned for a 422 mapping-validation failure. Carries the individual
 * {@link ValidationError} codes (not just a flattened string) so the frontend can translate each
 * one to a human-readable message and render a proper per-error list instead of a single toast
 * built from the raw enum names.
 *
 * <p>{@code details} adds, for rules able to supply it, which element failed and why — enough for
 * the UI to take the user to the offending substitution. It is purely additive: {@code errors}
 * keeps its exact previous contents and ordering, and {@code details} is omitted entirely when
 * empty, so a response for a rule with no detail is unchanged from before.
 */
@Getter
@Builder
@Schema(description = "Structured response body for a mapping validation failure")
public class ValidationErrorResponse {

    @Schema(description = "Human-readable summary of the failure", example = "Mapping validation failed")
    private String message;

    @Schema(description = "The individual validation error codes that caused the failure")
    private List<ValidationError> errors;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(description = "Per-failure detail: which element failed and why, when the rule could determine it")
    private List<ValidationIssue> details;
}
