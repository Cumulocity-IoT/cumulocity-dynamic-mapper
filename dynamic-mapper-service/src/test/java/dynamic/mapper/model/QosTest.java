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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class QosTest {

    private static final List<Qos> MQTT = Arrays.asList(Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE, Qos.EXACTLY_ONCE);
    private static final List<Qos> NO_EXACTLY_ONCE = Arrays.asList(Qos.AT_MOST_ONCE, Qos.AT_LEAST_ONCE);
    private static final List<Qos> ONLY_AT_LEAST_ONCE = Collections.singletonList(Qos.AT_LEAST_ONCE);

    @Test
    void levelsMatchMqttNumbering() {
        assertEquals(0, Qos.AT_MOST_ONCE.getLevel());
        assertEquals(1, Qos.AT_LEAST_ONCE.getLevel());
        assertEquals(2, Qos.EXACTLY_ONCE.getLevel());
        assertEquals(Qos.EXACTLY_ONCE, Qos.ofLevel(2));
        assertThrows(IllegalArgumentException.class, () -> Qos.ofLevel(3));
    }

    @Test
    void onlyAtMostOnceSkipsAcknowledgement() {
        assertFalse(Qos.AT_MOST_ONCE.requiresAcknowledgement());
        assertTrue(Qos.AT_LEAST_ONCE.requiresAcknowledgement());
        assertTrue(Qos.EXACTLY_ONCE.requiresAcknowledgement());
    }

    @Test
    void maxAndMinIgnoreNulls() {
        assertEquals(Qos.EXACTLY_ONCE, Qos.max(Qos.AT_LEAST_ONCE, Qos.EXACTLY_ONCE));
        assertEquals(Qos.AT_LEAST_ONCE, Qos.max(Qos.AT_LEAST_ONCE, null));
        assertEquals(Qos.AT_LEAST_ONCE, Qos.max(null, Qos.AT_LEAST_ONCE));
        assertEquals(Qos.AT_MOST_ONCE, Qos.min(Qos.AT_MOST_ONCE, Qos.EXACTLY_ONCE));
        assertEquals(Qos.EXACTLY_ONCE, Qos.min(null, Qos.EXACTLY_ONCE));
    }

    @Test
    void orDefaultFallsBackToAtLeastOnce() {
        assertEquals(Qos.AT_LEAST_ONCE, Qos.DEFAULT);
        assertEquals(Qos.AT_LEAST_ONCE, Qos.orDefault(null));
        assertEquals(Qos.AT_MOST_ONCE, Qos.orDefault(Qos.AT_MOST_ONCE));
    }

    @Test
    @DisplayName("clampTo keeps a supported level untouched")
    void clampKeepsSupportedLevel() {
        for (Qos qos : MQTT) {
            assertEquals(qos, Qos.clampTo(qos, MQTT));
        }
    }

    @Test
    @DisplayName("clampTo downgrades to the strongest supported level below the request")
    void clampDowngrades() {
        assertEquals(Qos.AT_LEAST_ONCE, Qos.clampTo(Qos.EXACTLY_ONCE, NO_EXACTLY_ONCE));
        assertEquals(Qos.AT_MOST_ONCE, Qos.clampTo(Qos.EXACTLY_ONCE,
                Collections.singletonList(Qos.AT_MOST_ONCE)));
    }

    @Test
    @DisplayName("clampTo upgrades when the connector supports nothing weaker")
    void clampUpgradesWhenNothingWeakerIsSupported() {
        // HTTP/WebHook always deliver at-least-once — a mapping asking for AT_MOST_ONCE gets
        // the stronger guarantee rather than a level the connector cannot implement.
        assertEquals(Qos.AT_LEAST_ONCE, Qos.clampTo(Qos.AT_MOST_ONCE, ONLY_AT_LEAST_ONCE));
    }

    @Test
    void clampTreatsMissingCapabilityAsNoRestriction() {
        assertEquals(Qos.EXACTLY_ONCE, Qos.clampTo(Qos.EXACTLY_ONCE, null));
        assertEquals(Qos.EXACTLY_ONCE, Qos.clampTo(Qos.EXACTLY_ONCE, Collections.emptyList()));
        assertEquals(Qos.DEFAULT, Qos.clampTo(null, MQTT));
    }

    @Test
    @DisplayName("wire format stays the enum name, so persisted mappings keep deserializing")
    void serializesByName() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("\"AT_LEAST_ONCE\"", mapper.writeValueAsString(Qos.AT_LEAST_ONCE));
        assertEquals(Qos.EXACTLY_ONCE, mapper.readValue("\"EXACTLY_ONCE\"", Qos.class));
    }

    @Test
    @DisplayName("a mapping without an explicit qos defaults instead of staying null")
    void mappingDefaultsQos() throws Exception {
        assertEquals(Qos.DEFAULT, Mapping.builder().build().getQos());

        ObjectMapper mapper = new ObjectMapper();
        Mapping fromJson = mapper.readValue("{\"name\":\"m\",\"qos\":null}", Mapping.class);
        assertEquals(Qos.DEFAULT, fromJson.getQos());
    }
}
