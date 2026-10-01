/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One validation failure, with enough context to point the user at what actually broke.
 *
 * <p>{@link ValidationError} stays a payload-free vocabulary of machine-readable codes — the
 * contextual detail lives here instead, so a rule can gain detail without changing the meaning
 * of its code. A check that has no context to give emits {@link #of(ValidationError)} and the
 * extra fields are simply absent from the response.
 */
@Schema(description = "A single validation failure, including which element failed and why")
public record ValidationIssue(

        @Schema(description = "Machine-readable error code", example = "Substitution_Source_Expression_Must_Be_Valid_JSONata")
        ValidationError code,

        @Schema(description = "Path of the offending field within the mapping", example = "substitutions[2].pathSource")
        String field,

        @Schema(description = "Index of the offending substitution, when the failure concerns one", example = "2")
        Integer index,

        @Schema(description = "The offending value", example = "temperature +")
        String value,

        @Schema(description = "Why it was rejected, e.g. the expression parser's message", example = "Expected end of expression")
        String reason) {

    /** A failure with no further context — the shape every rule produced before detail existed. */
    public static ValidationIssue of(ValidationError code) {
        return new ValidationIssue(code, null, null, null, null);
    }
}
