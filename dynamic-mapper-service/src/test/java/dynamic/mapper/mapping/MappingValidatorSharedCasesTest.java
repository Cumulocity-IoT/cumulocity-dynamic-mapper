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

package dynamic.mapper.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.validation.ValidationError;

/**
 * Runs the validation cases that the UI runs too (resources/validation, repository root). The UI
 * counterpart is topic-validation-cases.spec.ts; a case added to the file must pass in both, so the
 * two validators cannot drift apart unnoticed.
 */
class MappingValidatorSharedCasesTest {

    private static final File VALIDATION_DIR = new File("../resources/validation");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MappingValidator validator = new MappingValidator(null, null, null);

    record Case(Direction direction, String topic, String sample, boolean valid, String error) {
        @Override
        public String toString() {
            return direction + " " + topic + " | " + sample + (valid ? " -> valid" : " -> " + error);
        }
    }

    static Stream<Case> cases() throws IOException {
        JsonNode root = JSON.readTree(new File(VALIDATION_DIR, "topic-cases.json"));
        return Stream.of(Direction.INBOUND, Direction.OUTBOUND).flatMap(direction -> {
            JsonNode list = root.get(direction.name().toLowerCase());
            return Stream.iterate(0, i -> i + 1).limit(list.size()).map(i -> {
                JsonNode c = list.get(i);
                return new Case(direction, c.get("topic").asText(), c.get("sample").asText(),
                        c.get("valid").asBoolean(), c.has("error") ? c.get("error").asText() : null);
            });
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void backendAgreesWithTheSharedCase(Case c) {
        Mapping.MappingBuilder builder = Mapping.builder().direction(c.direction());
        Mapping mapping = c.direction() == Direction.INBOUND
                ? builder.mappingTopic(c.topic()).mappingTopicSample(c.sample()).build()
                : builder.publishTopic(c.topic()).publishTopicSample(c.sample()).build();

        List<ValidationError> errors = validator.validateTopics(mapping);

        if (c.valid()) {
            assertTrue(errors.isEmpty(), "expected valid but got " + errors);
        } else {
            assertTrue(errors.stream().anyMatch(e -> e.name().equals(c.error())),
                    "expected " + c.error() + " but got " + errors);
        }
    }

    @Test
    void errorCodeEnumMatchesTheSharedList() throws IOException {
        List<String> shared = Arrays.stream(JSON.treeToValue(
                JSON.readTree(new File(VALIDATION_DIR, "error-codes.json")).get("codes"), String[].class)).sorted().toList();
        List<String> enumNames = Arrays.stream(ValidationError.values()).map(Enum::name).sorted().toList();
        assertEquals(shared, enumNames,
                "ValidationError and resources/validation/error-codes.json differ; update both (and ValidationFormlyError in the UI)");
    }
}
