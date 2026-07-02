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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards against {@link LoggingEventType} drifting out of sync with its hand-maintained
 * TypeScript mirror in {@code dynamic-mapper-ui}. This mirror has already caused one real bug
 * (a missing constant broke the Service Events type filter and deep-linking for that type) —
 * this test is the cheap safety net agreed in the logging-system audit (see
 * {@code attic/feature/logging-system-audit/AUDIT.md}, Phase 3 item 9, option (c)) so the next
 * omission fails CI instead of shipping silently.
 *
 * <p>If this test fails after adding/renaming a {@link LoggingEventType} constant, update
 * {@code dynamic-mapper-ui/src/shared/connector-details/connector-log.model.ts}'s
 * {@code LoggingEventType} enum (and its {@code LoggingEventTypeMap} entry) to match.
 */
class LoggingEventTypeFrontendSyncTest {

    private static final Pattern ENUM_MEMBER_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*'\\w+'");

    @Test
    void frontendLoggingEventTypeEnumMatchesBackend() throws IOException {
        Path frontendModel = locateFrontendModel();

        String source = Files.readString(frontendModel);
        String enumBody = extractEnumBody(source, "LoggingEventType");

        Set<String> frontendNames = new LinkedHashSet<>();
        Matcher matcher = ENUM_MEMBER_PATTERN.matcher(enumBody);
        while (matcher.find()) {
            frontendNames.add(matcher.group(1));
        }
        assertFalse(frontendNames.isEmpty(),
                "Failed to parse any members out of the frontend LoggingEventType enum in " + frontendModel
                        + " — the extraction regex may need updating if the enum's formatting changed.");

        Set<String> backendNames = new LinkedHashSet<>();
        Arrays.stream(LoggingEventType.values()).map(Enum::name).forEach(backendNames::add);

        assertEquals(backendNames, frontendNames,
                "dynamic.mapper.model.LoggingEventType and the frontend's LoggingEventType enum in "
                        + frontendModel + " have drifted apart. Every constant must exist in both, "
                        + "including a matching LoggingEventTypeMap entry on the frontend side, otherwise "
                        + "the Service Events type filter and ?type= deep-links silently break for the "
                        + "missing type.");
    }

    private static String extractEnumBody(String source, String enumName) {
        String marker = "enum " + enumName + " {";
        int start = source.indexOf(marker);
        if (start < 0) {
            fail("Could not find 'enum " + enumName + " {' in the frontend model file");
        }
        int bodyStart = start + marker.length();
        int end = source.indexOf('}', bodyStart);
        if (end < 0) {
            fail("Unterminated enum body for '" + enumName + "' in the frontend model file");
        }
        return source.substring(bodyStart, end);
    }

    private static Path locateFrontendModel() {
        Path relative = Path.of("..", "dynamic-mapper-ui", "src", "shared", "connector-details",
                "connector-log.model.ts");
        Path resolved = relative.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            fail("Could not find frontend model at expected path: " + resolved
                    + " (test assumes dynamic-mapper-service and dynamic-mapper-ui are sibling modules "
                    + "and the working directory is the dynamic-mapper-service module root)");
        }
        return resolved;
    }
}
