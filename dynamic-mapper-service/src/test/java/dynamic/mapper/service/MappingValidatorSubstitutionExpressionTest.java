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

package dynamic.mapper.service;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.model.ValidationError;
import dynamic.mapper.model.ValidationIssue;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.TransformationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * validateSubstitutionExpressions rejects only substitutions that could never run.
 *
 * <p>The no-false-positive property is the point of these tests: the validator makes the same
 * {@code jsonata(...)} call the runtime processor makes, so anything it rejects would already
 * fail for every message. A test that fails here means working mappings just became unsavable.
 */
class MappingValidatorSubstitutionExpressionTest {

    private final MappingValidator validator = new MappingValidator(null, null, null);

    private Mapping mappingWith(TransformationType type, Substitution... substitutions) {
        Mapping mapping = new Mapping();
        mapping.setTransformationType(type);
        mapping.setSubstitutions(substitutions);
        return mapping;
    }

    private Substitution substitution(String pathSource, String pathTarget) {
        return Substitution.builder()
                .pathSource(pathSource)
                .pathTarget(pathTarget)
                .repairStrategy(RepairStrategy.DEFAULT)
                .expandArray(false)
                .build();
    }

    // ---- expressions that must stay valid -----------------------------------

    @Test
    void plainPathIsValid() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("temperature", "c8y_Temperature.T.value")))
                .isEmpty());
    }

    @Test
    void nestedPathIsValid() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("device.sensor.value", "time")))
                .isEmpty());
    }

    @Test
    void functionCallIsValid() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("$number(payload.value)", "time")))
                .isEmpty());
    }

    @Test
    void concatenationAndLiteralsAreValid() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA,
                        substitution("$join([_TOPIC_LEVEL_[1], 'suffix'], '-')", "_IDENTITY_.externalId")))
                .isEmpty());
    }

    @Test
    void arrayAndPredicateExpressionsAreValid() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA,
                        substitution("readings[type='temp'].value", "c8y_Temperature.T.value")))
                .isEmpty());
    }

    /** A path that simply does not exist in the payload is legal — JSONata yields undefined. */
    @Test
    void pathThatResolvesToNothingIsStillValid() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("does.not.exist", "time")))
                .isEmpty());
    }

    // ---- expressions that can never run -------------------------------------

    @Test
    void unparseableExpressionIsRejected() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("temperature +", "time")));
        assertEquals(1, issues.size());
        assertEquals(ValidationError.Substitution_Source_Expression_Must_Be_Valid_JSONata, issues.get(0).code());
    }

    @Test
    void unclosedFunctionCallIsRejected() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("$number(temperature", "time")));
        assertEquals(1, issues.size());
        assertEquals(ValidationError.Substitution_Source_Expression_Must_Be_Valid_JSONata, issues.get(0).code());
    }

    @Test
    void emptyPathSourceIsRejected() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("", "time")));
        assertEquals(1, issues.size());
        assertEquals(ValidationError.Substitution_Paths_Must_Not_Be_Empty, issues.get(0).code());
        assertEquals("substitutions[0].pathSource", issues.get(0).field());
    }

    @Test
    void emptyPathTargetIsRejected() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA, substitution("temperature", "  ")));
        assertEquals(1, issues.size());
        assertEquals(ValidationError.Substitution_Paths_Must_Not_Be_Empty, issues.get(0).code());
        assertEquals("substitutions[0].pathTarget", issues.get(0).field());
    }

    // ---- the detail that lets the editor jump to the problem ----------------

    /**
     * The whole point of the issue detail: the index, the offending expression and the parser's
     * reason must all reach the caller, since the UI uses them to take the user to the
     * substitution and explain what is wrong with it.
     */
    @Test
    void issueCarriesIndexFieldValueAndReason() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA,
                        substitution("temperature", "a"),
                        substitution("humidity", "b"),
                        substitution("pressure +", "c")));

        assertEquals(1, issues.size());
        ValidationIssue issue = issues.get(0);
        assertEquals(2, issue.index());
        assertEquals("substitutions[2].pathSource", issue.field());
        assertEquals("pressure +", issue.value());
        assertNotNull(issue.reason());
        assertFalse(issue.reason().isBlank());
    }

    /** Every offending substitution is reported, so none is hidden behind the first. */
    @Test
    void eachFailingSubstitutionIsReportedSeparately() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA,
                        substitution("temperature +", "a"),
                        substitution("humidity +", "b"),
                        substitution("pressure +", "c")));

        assertEquals(3, issues.size());
        assertEquals(List.of(0, 1, 2), issues.stream().map(ValidationIssue::index).toList());
        assertTrue(issues.stream().allMatch(
                i -> i.code() == ValidationError.Substitution_Source_Expression_Must_Be_Valid_JSONata));
    }

    @Test
    void bothProblemKindsAreReportedTogether() {
        List<ValidationIssue> issues = validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA,
                        substitution("", "a"),
                        substitution("humidity +", "b")));
        assertEquals(2, issues.size());
        assertEquals(ValidationError.Substitution_Paths_Must_Not_Be_Empty, issues.get(0).code());
        assertEquals(0, issues.get(0).index());
        assertEquals(ValidationError.Substitution_Source_Expression_Must_Be_Valid_JSONata, issues.get(1).code());
        assertEquals(1, issues.get(1).index());
    }

    // ---- transformation types that do not use substitutions -----------------

    @Test
    void smartFunctionSubstitutionsAreNotChecked() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.SMART_FUNCTION, substitution("temperature +", "time")))
                .isEmpty());
    }

    @Test
    void javaExtensionSubstitutionsAreNotChecked() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.EXTENSION_JAVA, substitution("temperature +", "time")))
                .isEmpty());
    }

    // ---- degenerate input ---------------------------------------------------

    @Test
    void nullSubstitutionsAreTolerated() {
        Mapping mapping = new Mapping();
        mapping.setTransformationType(TransformationType.JSONATA);
        mapping.setSubstitutions(null);
        assertTrue(validator.validateSubstitutionExpressions(mapping).isEmpty());
    }

    @Test
    void emptySubstitutionArrayIsTolerated() {
        assertTrue(validator.validateSubstitutionExpressions(
                mappingWith(TransformationType.JSONATA)).isEmpty());
    }
}
