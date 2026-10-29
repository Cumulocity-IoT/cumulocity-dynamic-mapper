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

package dynamic.mapper.logs.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.event.EventRepresentation;

import dynamic.mapper.logs.model.ServiceLogChunk;

class EventServiceLogSinkTest {

    private static ServiceLogChunk chunk(Set<String> mappings) {
        return new ServiceLogChunk(Instant.parse("2026-10-15T12:00:00Z"), Instant.parse("2026-10-15T12:00:05Z"), 3,
                Map.of("WARN", 3), 0, "the log text", List.of(), mappings);
    }

    @Test
    void aChunkWithMappingsGetsOneEmptyMarkerFragmentPerMapping() {
        EventRepresentation event = EventServiceLogSink.toEvent(GId.asGId("42"), "pod-1", chunk(Set.of("aaa", "bbb")));

        assertEquals("d11r_serviceLog", event.getType());
        assertNotNull(event.getProperty("d11r_serviceLog_map_aaa"));
        assertNotNull(event.getProperty("d11r_serviceLog_map_bbb"));
        assertEquals(Map.of(), event.getProperty("d11r_serviceLog_map_aaa"));
        assertNull(event.getProperty("d11r_serviceLog_map_ccc"));
    }

    @Test
    void aChunkWithoutMappingsGetsNoMarkerAndKeepsTheLogFragment() {
        EventRepresentation event = EventServiceLogSink.toEvent(GId.asGId("42"), "pod-1", chunk(Set.of()));

        assertTrue(event.getProperty(EventServiceLogSink.FRAGMENT) instanceof Map<?, ?> m && "the log text".equals(m.get("logs")));
        assertFalse(event.getAttrs().keySet().stream().anyMatch(k -> k.startsWith(EventServiceLogSink.MAPPING_FRAGMENT_PREFIX)));
    }

    @Test
    void anIdentifierThatCouldBreakAFragmentNameIsNeverWrittenAsOne() {
        EventRepresentation event = EventServiceLogSink.toEvent(GId.asGId("42"), "pod-1", chunk(Set.of("a.b", "x y", "ok")));

        assertNotNull(event.getProperty("d11r_serviceLog_map_ok"));
        assertEquals(1, event.getAttrs().keySet().stream().filter(k -> k.startsWith(EventServiceLogSink.MAPPING_FRAGMENT_PREFIX)).count());
    }

    @Test
    void theMarkerNameIsWhatTheReaderQueriesFor() {
        // Writer and reader must agree on the name: the reader's query is built from the same function.
        assertEquals("d11r_serviceLog_map_l19zjk", EventServiceLogSink.mappingFragment("l19zjk"));
        assertEquals("d11r_serviceLog_map_l19zjk",
                dynamic.mapper.logs.read.EventServiceLogEvents.filterFragmentOf("l19zjk"));
    }
}
