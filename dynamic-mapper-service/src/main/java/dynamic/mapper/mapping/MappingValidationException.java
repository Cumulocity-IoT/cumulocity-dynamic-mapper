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

package dynamic.mapper.mapping;

import dynamic.mapper.model.validation.ValidationError;
import dynamic.mapper.model.validation.ValidationIssue;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Exception thrown when mapping validation fails.
 *
 * <p>Carries {@link ValidationIssue}s — the codes plus whatever context the failing rule could
 * supply. {@link #getErrors()} is derived from them rather than stored separately, so the code
 * list and the detailed list can never disagree.
 */
public class MappingValidationException extends RuntimeException {

    @Getter
    private final List<ValidationIssue> issues;

    /**
     * Retained for callers and tests that only have codes; each becomes a context-free issue.
     * Type erasure rules out an overload taking {@code List<ValidationIssue>}, hence
     * {@link #ofIssues(List)}.
     */
    public MappingValidationException(List<ValidationError> errors) {
        this(toIssues(errors));
    }

    /** Package-private: {@code List<ValidationError>} and {@code List<ValidationIssue>} share an
     *  erasure, so the issue-based entry point is the static factory below. */
    private MappingValidationException(ValidationIssues issues) {
        super(formatIssues(issues.values()));
        this.issues = issues.values();
    }

    public static MappingValidationException ofIssues(List<ValidationIssue> issues) {
        return new MappingValidationException(new ValidationIssues(issues));
    }

    private static ValidationIssues toIssues(List<ValidationError> errors) {
        return new ValidationIssues(errors.stream().map(ValidationIssue::of).collect(Collectors.toList()));
    }

    /** Wrapper that gives the issue-based constructor a distinct signature. */
    private record ValidationIssues(List<ValidationIssue> values) {
    }

    public List<ValidationError> getErrors() {
        return issues.stream().map(ValidationIssue::code).collect(Collectors.toList());
    }

    private static String formatIssues(List<ValidationIssue> issues) {
        return "Mapping validation failed: " +
            issues.stream()
                .map(MappingValidationException::describe)
                .collect(Collectors.joining(", "));
    }

    /** Appends the offending field and reason when the rule supplied them. */
    private static String describe(ValidationIssue issue) {
        StringBuilder text = new StringBuilder(String.valueOf(issue.code()));
        if (issue.field() != null) {
            text.append(" [").append(issue.field()).append(']');
        }
        if (issue.reason() != null) {
            text.append(": ").append(issue.reason());
        }
        return text.toString();
    }
}
