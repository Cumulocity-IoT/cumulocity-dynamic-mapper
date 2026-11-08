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

package dynamic.mapper.connector.core.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.runtime.ProcessingContext;

class TransportMetadataTest {

    @Test
    void emptyCollectorBuildsNull() {
        assertNull(new TransportMetadata().build());
    }

    @Test
    void prefixesNamesAndCapsSizeAndCount() {
        TransportMetadata m = new TransportMetadata()
                .put(TransportMetadata.HEADER, "tenant", "t1".getBytes(StandardCharsets.UTF_8))
                .put("", "routingKey", "a.b")
                .put(TransportMetadata.PROPERTY, "skipped", (String) null)
                .put(TransportMetadata.PROPERTY, "long", "x".repeat(5000));
        for (int i = 0; i < 100; i++) {
            m.put(TransportMetadata.PROPERTY, "p" + i, "v");
        }
        Map<String, String> built = m.build();
        assertEquals("t1", built.get("header.tenant"));
        assertEquals("a.b", built.get("routingKey"));
        assertEquals(1024, built.get("property.long").length());
        assertEquals(50, built.size());
    }

    @Test
    void transportFieldsCombineKeyAndMetadataKeyWinning() {
        ProcessingContext<byte[]> ctx = ProcessingContext.<byte[]>builder()
                .key("device-1")
                .transportMetadata(Map.of("key", "other", "header.a", "1"))
                .build();
        assertEquals(Map.of("key", "device-1", "header.a", "1"), ctx.buildTransportFields());
        assertEquals(Map.of(), ProcessingContext.<byte[]>builder().build().buildTransportFields());
    }
}
