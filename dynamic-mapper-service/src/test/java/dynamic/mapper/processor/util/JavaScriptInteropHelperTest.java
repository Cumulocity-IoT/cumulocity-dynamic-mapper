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

package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.model.ExternalId;

/**
 * Regression tests for https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/issues/482:
 * a Smart Function returning a null/undefined externalId must not be silently
 * coerced into the strings "null"/"undefined" and used to look up or create a device.
 */
class JavaScriptInteropHelperTest {

    @Test
    void nullExternalIdIsRejected() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "c8y_Serial");
        map.put("externalId", null);

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertNull(result, "A null externalId must not produce a usable ExternalId");
    }

    @Test
    void literalStringNullExternalIdIsRejected() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "c8y_Serial");
        map.put("externalId", "null");

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertNull(result, "The literal string \"null\" must not be treated as a valid externalId");
    }

    @Test
    void literalStringUndefinedExternalIdIsRejected() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "c8y_Serial");
        map.put("externalId", "undefined");

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertNull(result, "The literal string \"undefined\" must not be treated as a valid externalId");
    }

    @Test
    void blankExternalIdIsRejected() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "c8y_Serial");
        map.put("externalId", "   ");

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertNull(result, "A blank externalId must not be treated as valid");
    }

    @Test
    void missingExternalIdKeyIsRejected() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "c8y_Serial");

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertNull(result, "A missing externalId key must not produce a usable ExternalId");
    }

    @Test
    void nullOrSentinelTypeIsRejected() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "undefined");
        map.put("externalId", "12345");

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertNull(result, "A sentinel/undefined type must not produce a usable ExternalId");
    }

    @Test
    void validExternalIdIsAccepted() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "c8y_Serial");
        map.put("externalId", "12345");

        ExternalId result = JavaScriptInteropHelper.convertMapToExternalId(map);

        assertEquals("c8y_Serial", result.getType());
        assertEquals("12345", result.getExternalId());
    }
}
