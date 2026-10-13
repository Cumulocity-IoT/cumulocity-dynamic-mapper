/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack
 */

package dynamic.mapper.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression test for GET /monitoring/tree failing with
 * {@code Document nesting depth (501) exceeds the maximum allowed (500)}.
 * Spring Boot 4 writes MVC responses with Jackson 3, which ignored the Jackson 2 serializer
 * and followed {@code parentNode} back up the tree. {@link MappingTreeNodeJackson3Serializer}
 * must write the parent as its absolute path instead of recursing into it.
 */
class MappingTreeNodeJackson3SerializationTest {

    private MappingTreeNode root;

    @BeforeEach
    void setUp() throws Exception {
        List<Mapping> mappings;
        try (InputStream in = getClass().getResourceAsStream("/mappings-test-INBOUND.json")) {
            mappings = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(in, new TypeReference<List<Mapping>>() {
                    });
        }
        root = MappingTreeNode.createRootNode("TEST_TENANT");
        for (Mapping mapping : mappings) {
            root.addMapping(mapping);
        }
    }

    @Test
    void jackson3SerializesTreeWithoutFollowingParentNode() {
        JsonMapper jackson3Mapper = JsonMapper.builder().build();

        JsonNode json = jackson3Mapper.readTree(jackson3Mapper.writeValueAsString(root));

        assertEquals("root", json.get("level").asString());
        assertFalse(json.get("isMappingNode").asBoolean());
        assertTrue(json.get("childNodes").size() > 0, "root should expose its children");
        assertTrue(json.get("childNodes").get("device").isArray());
    }

    @Test
    void parentNodeIsWrittenAsPathNotAsNestedObject() {
        JsonMapper jackson3Mapper = JsonMapper.builder().build();

        JsonNode json = jackson3Mapper.readTree(jackson3Mapper.writeValueAsString(root));
        JsonNode device = json.get("childNodes").get("device").get(0);

        assertTrue(device.get("parentNode").isString());
        assertEquals("", device.get("parentNode").asString());
        assertEquals("device", device.get("absolutePath").asString());
    }

    @Test
    void serializedDepthIsBoundedByTreeDepth() {
        JsonMapper jackson3Mapper = JsonMapper.builder().build();

        JsonNode json = jackson3Mapper.readTree(jackson3Mapper.writeValueAsString(root));

        assertTrue(depth(json) < 100, "tree JSON must not grow with parent back-references");
    }

    private static int depth(JsonNode node) {
        int max = 0;
        for (JsonNode child : node) {
            max = Math.max(max, depth(child));
        }
        return max + 1;
    }
}
