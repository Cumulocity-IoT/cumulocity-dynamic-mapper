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

package dynamic.mapper.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://hooks.example.com/api",
            "http://10.1.2.3:8080/x",
            "http://cumulocity:8111",
            "http://unresolvable-host.invalid/x"
    })
    void acceptsOrdinaryTargets(String url) {
        assertEquals(url, OutboundUrlValidator.requireSafe(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data",
            "http://127.0.0.1:8080/actuator",
            "http://localhost/x",
            "http://[::1]/x",
            "http://0.0.0.0/x",
            "http://224.0.0.1/x",
            "file:///etc/passwd",
            "ftp://example.com/x",
            "https://user:secret@example.com/x",
            "not a url",
            ""
    })
    void rejectsForbiddenTargets(String url) {
        assertThrows(IllegalArgumentException.class, () -> OutboundUrlValidator.requireSafe(url));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> OutboundUrlValidator.requireSafe(null));
    }
}
