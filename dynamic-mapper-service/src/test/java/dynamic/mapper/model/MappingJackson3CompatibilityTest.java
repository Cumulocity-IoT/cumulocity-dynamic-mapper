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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tools.jackson.databind.json.JsonMapper;

/**
 * Regression test for the Spring Boot 4 / Jackson 3 default-MVC-converter
 * incompatibility discovered via production test-V runs: POST /mapping with
 * a substitutions array 500'd with
 * {@code InvalidDefinitionException: no Creators, like default constructor,
 * exist} because Boot 4's default MVC JSON converter is Jackson 3
 * (tools.jackson.databind), whose introspector did not see the Jackson 2
 * {@code @JsonDeserialize(builder = ...)} annotation on Mapping/Substitution.
 *
 * Fix: both classes and their builders now carry the Jackson 3 equivalent
 * annotation alongside the Jackson 2 one, so both mappers can construct them
 * — no custom MVC message converter required.
 */
class MappingJackson3CompatibilityTest {

    private static final String MAPPING_JSON = """
            {
              "name": "test",
              "substitutions": [
                { "pathSource": "$.value", "pathTarget": "$.c8y_Measurement.value" }
              ]
            }
            """;

    @Test
    void jackson3DeserializesMappingWithSubstitutions() throws Exception {
        JsonMapper jackson3Mapper = JsonMapper.builder().build();

        Mapping mapping = jackson3Mapper.readValue(MAPPING_JSON, Mapping.class);

        assertEquals("test", mapping.getName());
        assertEquals(1, mapping.getSubstitutions().length);
        assertEquals("$.value", mapping.getSubstitutions()[0].getPathSource());
        assertEquals("$.c8y_Measurement.value", mapping.getSubstitutions()[0].getPathTarget());
    }

    @Test
    void jackson2StillDeserializesMappingWithSubstitutions() throws Exception {
        // The app's own internal ObjectMapper bean is still Jackson 2 — must keep working.
        ObjectMapper jackson2Mapper = new ObjectMapper();

        Mapping mapping = jackson2Mapper.readValue(MAPPING_JSON, Mapping.class);

        assertEquals("test", mapping.getName());
        assertEquals(1, mapping.getSubstitutions().length);
        assertEquals("$.value", mapping.getSubstitutions()[0].getPathSource());
        assertEquals("$.c8y_Measurement.value", mapping.getSubstitutions()[0].getPathTarget());
    }
}
