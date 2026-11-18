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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.validation.ValidationError;
import dynamic.mapper.model.validation.ValidationIssue;

/**
 * validateFilterExpressions rejects only filters that could never match: it makes the same
 * {@code jsonata(...)} call the evaluators make, so a rejected filter already drops every message today.
 */
class MappingValidatorFilterExpressionTest {

    private final MappingValidator validator = new MappingValidator(null, null, null);

    private static Mapping mapping(String filterMapping, String filterInventory) {
        Mapping m = new Mapping();
        m.setFilterMapping(filterMapping);
        m.setFilterInventory(filterInventory);
        return m;
    }

    @ParameterizedTest
    @ValueSource(strings = { "true", "type = 'c8y_Event'", "$exists(c8y_Temperature)", "value > 50",
            "$exists(value) and $not($isDuplicate(_TOPIC_LEVEL_[1] & \"|\" & time, 60))", "has(c8y_IsDevice)" })
    void validFiltersAreAccepted(String expression) {
        assertTrue(validator.validateFilterExpressions(mapping(expression, expression)).isEmpty());
    }

    @Test
    void anEmptyOrMissingFilterIsAccepted() {
        assertTrue(validator.validateFilterExpressions(mapping(null, null)).isEmpty());
        assertTrue(validator.validateFilterExpressions(mapping("", "  ")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "type = ", "$exists(", "value >", "a and and b", "(a" })
    void aFilterThatDoesNotParseIsRejectedWithTheField(String expression) {
        List<ValidationIssue> executionFilter = validator.validateFilterExpressions(mapping(expression, null));
        assertEquals(1, executionFilter.size());
        assertEquals(ValidationError.Filter_Expression_Must_Be_Valid_JSONata, executionFilter.get(0).code());
        assertEquals("filterMapping", executionFilter.get(0).field());
        assertEquals(expression, executionFilter.get(0).value());

        List<ValidationIssue> inventoryFilter = validator.validateFilterExpressions(mapping("true", expression));
        assertEquals(1, inventoryFilter.size());
        assertEquals("filterInventory", inventoryFilter.get(0).field());
    }

    @Test
    void bothFiltersAreReportedSeparately() {
        List<ValidationIssue> issues = validator.validateFilterExpressions(mapping("type =", "has("));
        assertEquals(List.of("filterMapping", "filterInventory"), issues.stream().map(ValidationIssue::field).toList());
    }

    /**
     * The no-false-positive guarantee on real data: every filter that ships with the product or in the
     * samples must pass. If this fails, a working mapping just became unsavable.
     */
    @Test
    void everyFilterInTheShippedAndSampleMappingsIsAccepted() throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<File> files = List.of(
                new File("src/main/resources/mappings/mappings-INBOUND.json"),
                new File("src/main/resources/mappings/mappings-OUTBOUND.json"),
                new File("../resources/samples/mappings-INBOUND.json"),
                new File("../resources/samples/mappings-OUTBOUND.json"));
        List<String> rejected = new ArrayList<>();
        int checked = 0;
        for (File file : files) {
            for (JsonNode node : json.readTree(file)) {
                checked++;
                Mapping m = mapping(text(node, "filterMapping"), text(node, "filterInventory"));
                for (ValidationIssue issue : validator.validateFilterExpressions(m)) {
                    rejected.add(file.getName() + " " + node.path("name").asText() + " " + issue.field() + ": "
                            + issue.value());
                }
            }
        }
        assertTrue(checked > 10, "the sample files were not read");
        assertEquals(List.of(), rejected);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
