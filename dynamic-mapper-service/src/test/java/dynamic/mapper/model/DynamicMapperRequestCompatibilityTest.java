/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapper.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.MappingAction;

/**
 * An extension JAR compiled against an earlier version calls the all-args constructors it knew. Adding fields
 * must not change their signature, or that JAR fails with a NoSuchMethodError.
 */
class DynamicMapperRequestCompatibilityTest {

    @Test
    void theDeviceMessageConstructorOfEarlierVersionsStillExists() {
        DeviceMessage message = assertDoesNotThrow(() -> DeviceMessage.class.getConstructor(Object.class,
                MappingAction.class, CumulocityType.class, Object.class, String.class, String.class, String.class,
                Boolean.class, Map.class, Instant.class, String.class)
                .newInstance("p", MappingAction.CREATE, CumulocityType.EVENT, null, "mqtt", "t", "c", true, null,
                        null, "42"));

        assertEquals("p", message.getPayload());
        assertEquals("t", message.getTopic());
        assertEquals("42", message.getSourceId());
        assertNull(message.getPayloadEncoding());
        assertNull(message.getContentType());
    }

    @Test
    void theDynamicMapperRequestConstructorOfEarlierVersionsStillExists() {
        DynamicMapperRequest request = assertDoesNotThrow(() -> DynamicMapperRequest.class.getConstructor(int.class,
                RequestMethod.class, API.class, String.class, Boolean.class, String.class, String.class,
                String.class, String.class, String.class, String.class, String.class, Exception.class,
                byte[].class)
                .newInstance(3, RequestMethod.POST, null, "topic", false, "1", "e", "c8y_Serial", "{}", null, null,
                        null, null, new byte[] { 1 }));

        assertEquals(3, request.getPredecessor());
        assertEquals("topic", request.getPublishTopic());
        assertEquals("{}", request.getRequest());
        assertEquals(1, request.getBinaryPayload().length);
        assertNull(request.getContentType());
    }
}
