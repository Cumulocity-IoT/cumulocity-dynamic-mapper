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
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A tenant option stored by an older version can still hold properties that no longer exist.
 * Reading it must not fail, even with a mapper that rejects unknown properties.
 */
class ServiceConfigurationRemovedPropertiesTest {

    @Test
    void storedExplorerSessionTtlIsIgnored() {
        ObjectMapper strict = new ObjectMapper();
        ServiceConfiguration config = assertDoesNotThrow(() -> strict.readValue(
                "{\"explorerSessionTTLMinutes\": 30, \"mappingVersionRetention\": 7}",
                ServiceConfiguration.class));
        assertEquals(7, config.getMappingVersionRetention());
    }
}
