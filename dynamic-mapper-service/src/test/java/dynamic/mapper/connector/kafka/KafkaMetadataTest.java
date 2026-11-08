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

package dynamic.mapper.connector.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaMetadataTest {

    @Test
    void headersBecomeHeaderFields() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        record.headers().add("source", "gw-1".getBytes(StandardCharsets.UTF_8));
        assertEquals(Map.of("header.source", "gw-1"), KafkaClientV2.kafkaMetadata(record));
    }

    @Test
    void noHeadersGivesNull() {
        assertNull(KafkaClientV2.kafkaMetadata(new ConsumerRecord<>("t", 0, 0L, "k", "v")));
    }
}
