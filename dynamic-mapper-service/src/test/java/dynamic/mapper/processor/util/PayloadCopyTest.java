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

package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.outbound.processor.DeserializationOutboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;

class PayloadCopyTest {

    private static Map<String, Object> payload() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "42");
        List<Object> tags = new ArrayList<>(List.of("a", "b"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", source);
        root.put("tags", tags);
        root.put("value", 12.5);
        return root;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCopyIsIndependentAtEveryLevelAndKeepsOrderAndValues() {
        Map<String, Object> original = payload();

        Map<String, Object> copy = PayloadCopy.deepCopy(original);
        ((Map<String, Object>) copy.get("source")).put("extra", true);
        ((List<Object>) copy.get("tags")).add("c");
        copy.put("_IDENTITY_", "x");

        assertEquals(List.of("source", "tags", "value"), List.copyOf(original.keySet()));
        assertEquals(Map.of("id", "42"), original.get("source"));
        assertEquals(List.of("a", "b"), original.get("tags"));
        assertEquals(List.of("source", "tags", "value", "_IDENTITY_"), List.copyOf(copy.keySet()));
        assertNotSame(original.get("source"), copy.get("source"));
        assertEquals(12.5, copy.get("value"));
    }

    @Test
    void valuesThatAreNotContainersAreReturnedAsTheyAre() {
        String text = "abc";

        assertSame(text, PayloadCopy.deepCopy(text));
        assertNull(PayloadCopy.deepCopy(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyMappingOfOneNotificationGetsItsOwnPayload() throws Exception {
        C8YMessage message = new C8YMessage();
        message.setApi(API.MEASUREMENT);
        message.setSendPayload(true);
        message.setParsedPayload(payload());
        DeserializationOutboundProcessor processor = new DeserializationOutboundProcessor();

        ProcessingContext<Object> first = processor.process("t1", new Mapping(), message, new ServiceConfiguration(), false);
        ProcessingContext<Object> second = processor.process("t1", new Mapping(), message, new ServiceConfiguration(), false);
        ((Map<String, Object>) first.getPayload()).put("_IDENTITY_", Map.of("c8ySourceId", "42"));

        assertTrue(!((Map<String, Object>) second.getPayload()).containsKey("_IDENTITY_"),
                "one mapping's enrichment is not visible to another");
        assertTrue(!message.getParsedPayload().containsKey("_IDENTITY_"), "nor to the notification itself");
    }
}
