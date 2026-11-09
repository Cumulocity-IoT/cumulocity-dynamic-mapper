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

package dynamic.mapper.processor.outbound.serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.tahu.protobuf.SparkplugBProto;
import org.junit.jupiter.api.Test;

/** Tests for {@link SparkPlugBSerializer#serializeJson}, the entry point used by the mapping test tab. */
class SparkPlugBSerializerTest {

    private final SparkPlugBSerializer serializer = new SparkPlugBSerializer();

    @Test
    void serializeJson_encodesMetricsFromTypeOrDataType() throws Exception {
        byte[] bytes = serializer.serializeJson("""
                {"timestamp": 1735732800000, "seq": 7, "metrics": [
                  {"name": "Loop Current", "dataType": "Float", "value": 12.4},
                  {"name": "Count", "type": "Int32", "value": 3},
                  {"name": "Active", "type": "Boolean", "value": true},
                  {"name": "Label", "type": "String", "value": "pump"}
                ]}""");

        SparkplugBProto.Payload decoded = SparkplugBProto.Payload.parseFrom(bytes);

        assertEquals(1735732800000L, decoded.getTimestamp());
        assertEquals(7L, decoded.getSeq());
        assertEquals(4, decoded.getMetricsCount());
        assertEquals("Loop Current", decoded.getMetrics(0).getName());
        assertEquals(12.4f, decoded.getMetrics(0).getFloatValue(), 0.0001f);
        assertEquals(3, decoded.getMetrics(1).getIntValue());
        assertTrue(decoded.getMetrics(2).getBooleanValue());
        assertEquals("pump", decoded.getMetrics(3).getStringValue());
    }

    @Test
    void serializeJson_acceptsAPayloadWithoutMetrics() throws Exception {
        SparkplugBProto.Payload decoded = SparkplugBProto.Payload.parseFrom(serializer.serializeJson("{}"));

        assertEquals(0, decoded.getMetricsCount());
    }

    @Test
    void serializeJson_rejectsTextThatIsNotAJsonObject() {
        assertThrows(IllegalArgumentException.class, () -> serializer.serializeJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> serializer.serializeJson("[1,2]"));
    }
}
