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

package dynamic.mapper.service;

import dynamic.mapper.model.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQTT wildcard semantics for validateMappingTopicAndSampleConsistency:
 * a trailing "#" must match any number (incl. zero) of remaining topic
 * levels in the sample, not require an identical level count.
 */
class MappingValidatorTopicSampleTest {

    private final MappingValidator validator = new MappingValidator(null, null);

    @Test
    void exactMatchIsValid() {
        assertTrue(validator.validateMappingTopicAndSampleConsistency("a/b/c", "a/b/c").isEmpty());
    }

    @Test
    void singleLevelWildcardMatchesOneSegment() {
        assertTrue(validator.validateMappingTopicAndSampleConsistency("a/+/c", "a/device1/c").isEmpty());
    }

    @Test
    void multiLevelWildcardMatchesMoreLevelsThanTopic() {
        assertTrue(
                validator.validateMappingTopicAndSampleConsistency(
                        "fridgeNew/#", "fridgeNew/east/sensor-ny-99").isEmpty());
    }

    @Test
    void multiLevelWildcardMatchesBareParentTopic() {
        assertTrue(validator.validateMappingTopicAndSampleConsistency("fridgeNew/#", "fridgeNew").isEmpty());
    }

    @Test
    void multiLevelWildcardMatchesExactSameLevelCount() {
        assertTrue(validator.validateMappingTopicAndSampleConsistency("a/b/#", "a/b/anything").isEmpty());
    }

    @Test
    void multiLevelWildcardRejectsFewerLevelsThanFixedPrefix() {
        List<ValidationError> errors = validator.validateMappingTopicAndSampleConsistency("a/b/#", "a");
        assertFalse(errors.isEmpty());
        assertTrue(errors.contains(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name));
    }

    @Test
    void multiLevelWildcardRejectsMismatchingFixedPrefix() {
        List<ValidationError> errors = validator.validateMappingTopicAndSampleConsistency("a/b/#", "a/c/d");
        assertFalse(errors.isEmpty());
        assertTrue(errors.contains(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name));
    }

    @Test
    void differentLevelCountWithoutWildcardIsInvalid() {
        List<ValidationError> errors = validator.validateMappingTopicAndSampleConsistency("a/b", "a/b/c");
        assertFalse(errors.isEmpty());
        assertTrue(errors.contains(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Number_Of_Levels_In_Topic_Name));
    }

    @Test
    void differentStaticSegmentIsInvalid() {
        List<ValidationError> errors = validator.validateMappingTopicAndSampleConsistency("a/b/c", "a/b/d");
        assertFalse(errors.isEmpty());
        assertTrue(errors.contains(
                ValidationError.MappingTopic_And_MappingTopicSample_Do_Not_Have_Same_Structure_In_Topic_Name));
    }
}
