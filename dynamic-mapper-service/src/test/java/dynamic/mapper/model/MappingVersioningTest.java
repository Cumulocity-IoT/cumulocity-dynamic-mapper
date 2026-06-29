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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward-compatibility and round-trip tests for the P1 versioning model
 * additions (see docs/feature/REQUIREMENTS-VERSION-MAPPING.md, NFR-1 / NFR-1a).
 */
class MappingVersioningTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void legacyMappingWithoutVersionFieldsGetsDefaults() throws Exception {
        // A mapping persisted before versioning existed: no versionNumber / draftDirty / versionNote.
        String legacyJson = """
                {
                  "id": "34573838974",
                  "identifier": "l19zjk",
                  "name": "Legacy Mapping",
                  "direction": "INBOUND",
                  "active": true,
                  "debug": false
                }
                """;

        Mapping mapping = objectMapper.readValue(legacyJson, Mapping.class);

        assertEquals("1.0.0", mapping.getVersion(), "missing version must default to 1.0.0");
        assertFalse(mapping.isDraftDirty(), "missing draftDirty must default to false");
        assertNull(mapping.getVersionNote(), "missing versionNote must be null");
        assertEquals("l19zjk", mapping.getIdentifier());
    }

    @Test
    void explicitVersionFieldsAreRespected() throws Exception {
        String json = """
                {
                  "id": "1",
                  "identifier": "abc",
                  "name": "M",
                  "direction": "INBOUND",
                  "active": false,
                  "debug": false,
                  "version": "5.0.0",
                  "draftDirty": true,
                  "versionNote": "note"
                }
                """;

        Mapping mapping = objectMapper.readValue(json, Mapping.class);

        assertEquals("5.0.0", mapping.getVersion());
        assertTrue(mapping.isDraftDirty());
        assertEquals("note", mapping.getVersionNote());
    }

    @Test
    void nullVersionFieldsSkipAndKeepDefaults() throws Exception {
        // Explicit JSON null must not override the builder defaults (@JsonSetter Nulls.SKIP).
        String json = """
                {
                  "identifier": "abc",
                  "name": "M",
                  "direction": "INBOUND",
                  "active": false,
                  "debug": false,
                  "version": null,
                  "draftDirty": null
                }
                """;

        Mapping mapping = objectMapper.readValue(json, Mapping.class);

        assertEquals("1.0.0", mapping.getVersion(), "explicit JSON null must not override the default via @JsonSetter Nulls.SKIP");
        assertFalse(mapping.isDraftDirty());
    }

    @Test
    void unknownLegacyPropertiesAreIgnored() throws Exception {
        // Removed/renamed fields from older releases must not break loading.
        String json = """
                {
                  "identifier": "abc",
                  "name": "M",
                  "direction": "INBOUND",
                  "active": false,
                  "debug": false,
                  "someRemovedLegacyField": "whatever"
                }
                """;

        Mapping mapping = assertDoesNotThrow(() -> objectMapper.readValue(json, Mapping.class));
        assertEquals("abc", mapping.getIdentifier());
    }

    @Test
    void mappingVersionRoundTrip() throws Exception {
        Mapping snapshot = Mapping.builder()
                .id("1")
                .identifier("abc")
                .name("M")
                .direction(Direction.INBOUND)
                .targetAPI(API.MEASUREMENT)
                .active(true)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .version("3.0.0")
                .build();

        MappingVersion version = MappingVersion.builder()
                .identifier("abc")
                .version("3.0.0")
                .snapshot(snapshot)
                .isDraft(false)
                .createdAt(123L)
                .createdBy("admin")
                .note("v3")
                .build();

        String json = objectMapper.writeValueAsString(version);
        MappingVersion restored = objectMapper.readValue(json, MappingVersion.class);

        assertEquals("abc", restored.getIdentifier());
        assertEquals("3.0.0", restored.getVersion());
        assertEquals(123L, restored.getCreatedAt());
        assertEquals("admin", restored.getCreatedBy());
        assertEquals("v3", restored.getNote());
        assertFalse(restored.isDraft());
        assertNotNull(restored.getSnapshot());
        assertEquals("M", restored.getSnapshot().getName());
        assertEquals("3.0.0", restored.getSnapshot().getVersion());
    }

    @Test
    void draftVersionRoundTrip() throws Exception {
        MappingVersion draft = MappingVersion.builder()
                .identifier("abc")
                .version(null)
                .snapshot(Mapping.builder().identifier("abc").name("draft").build())
                .isDraft(true)
                .build();

        String json = objectMapper.writeValueAsString(draft);
        MappingVersion restored = objectMapper.readValue(json, MappingVersion.class);

        assertTrue(restored.isDraft(), "draft flag must survive round-trip");
        assertEquals("abc", restored.getIdentifier());
    }
}
