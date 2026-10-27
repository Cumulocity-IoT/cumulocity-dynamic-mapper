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
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MappingTopicSlashHandlingTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "a/b|a/b",
            "//a/b//|/a/b/",
            "/a/b/|/a/b/",
            "a/b/|a/b/",
            "////|/",
            "/|/"
    })
    void normalizeTopicReducesLeadingAndTrailingSlashRuns(String topic, String expected) {
        assertEquals(expected, Mapping.normalizeTopic(topic));
    }

    @Test
    void splitCutsOffAllLeadingAndTrailingSlashes() {
        assertArrayEquals(new String[] { "a", "b" }, Mapping.splitTopicExcludingSeparatorAsArray("//a/b//", true));
    }

    @Test
    void splitKeepsOneLeadingSlashWhenNotCuttingOff() {
        assertArrayEquals(new String[] { "/", "a", "b" },
                Mapping.splitTopicExcludingSeparatorAsArray("//a/b//", false));
        assertArrayEquals(new String[] { "a", "b" }, Mapping.splitTopicExcludingSeparatorAsArray("a/b/", false));
    }

    @Test
    void longSlashRunsDoNotCauseSlowMatching() {
        String hostile = "/".repeat(200_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Mapping.splitTopicExcludingSeparatorAsArray(hostile, true);
            Mapping.splitTopicExcludingSeparatorAsArray(hostile, false);
            Mapping.normalizeTopic(hostile);
        });
    }
}
