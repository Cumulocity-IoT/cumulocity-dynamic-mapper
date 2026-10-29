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

package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MappingLogContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theIdentifierIsInTheContextInsideTheScopeAndGoneAfterIt() {
        try (MappingLogContext ignored = MappingLogContext.open("l19zjk")) {
            assertEquals("l19zjk", MDC.get(MappingLogContext.KEY));
        }
        assertNull(MDC.get(MappingLogContext.KEY));
    }

    @Test
    void anEnclosingScopeIsRestoredWhenTheInnerOneCloses() {
        try (MappingLogContext outer = MappingLogContext.open("outer")) {
            try (MappingLogContext inner = MappingLogContext.open("inner")) {
                assertEquals("inner", MDC.get(MappingLogContext.KEY));
            }
            assertEquals("outer", MDC.get(MappingLogContext.KEY));
        }
    }

    @Test
    void anIdentifierThatCannotBeATagIsTreatedAsNoMapping() {
        for (String bad : new String[] { null, "", "  ", "a b", "x]", "a.b", "a\nb", "x".repeat(65) }) {
            try (MappingLogContext ignored = MappingLogContext.open(bad)) {
                assertNull(MDC.get(MappingLogContext.KEY), "'" + bad + "'");
            }
        }
        assertFalse(MappingLogContext.isValid("a b"));
        assertTrue(MappingLogContext.isValid("l19zjk"));
    }

    @Test
    void anInvalidIdentifierInsideAScopeHidesTheEnclosingOne() {
        try (MappingLogContext outer = MappingLogContext.open("outer")) {
            try (MappingLogContext inner = MappingLogContext.open("bad id")) {
                assertNull(MDC.get(MappingLogContext.KEY), "never attribute a line to the wrong mapping");
            }
            assertEquals("outer", MDC.get(MappingLogContext.KEY));
        }
    }

    @Test
    void mappingOfReadsAnEventsMdcAndIgnoresInvalidValues() {
        assertEquals("l19zjk", MappingLogContext.mappingOf(Map.of(MappingLogContext.KEY, "l19zjk")));
        assertNull(MappingLogContext.mappingOf(Map.of(MappingLogContext.KEY, "not valid!")));
        assertNull(MappingLogContext.mappingOf(Map.of()));
        assertNull(MappingLogContext.mappingOf(null));
    }

    @Test
    void runTagsOneStatementAndLeavesTheScopeAgain() {
        MappingLogContext.run("ibd55642", () -> assertEquals("ibd55642", MappingLogContext.current()));

        assertNull(MappingLogContext.current());
    }

    @Test
    void runRestoresTheEnclosingScope() {
        try (MappingLogContext outer = MappingLogContext.open("outer1")) {
            MappingLogContext.run("inner1", () -> assertEquals("inner1", MappingLogContext.current()));

            assertEquals("outer1", MappingLogContext.current());
        }
    }
}
