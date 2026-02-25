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

package dynamic.mapper.processor.model;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Qos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RoutingContext focusing on immutability and builder pattern.
 */
class RoutingContextTest {

    @Test
    void shouldBuildRoutingContextWithAllFields() {
        RoutingContext context = RoutingContext.builder()
            .topic("device/+/measurements")
            .clientId("client123")
            .api(API.MEASUREMENT)
            .qos(Qos.AT_LEAST_ONCE)
            .resolvedPublishTopic("device/sensor1/measurements")
            .tenant("t12345")
            .build();

        assertEquals("device/+/measurements", context.getTopic());
        assertEquals("client123", context.getClientId());
        assertEquals(API.MEASUREMENT, context.getApi());
        assertEquals(Qos.AT_LEAST_ONCE, context.getQos());
        assertEquals("device/sensor1/measurements", context.getResolvedPublishTopic());
        assertEquals("t12345", context.getTenant());
    }

    @Test
    void shouldBuildRoutingContextWithMinimalFields() {
        RoutingContext context = RoutingContext.builder()
            .topic("test/topic")
            .build();

        assertEquals("test/topic", context.getTopic());
        assertNull(context.getClientId());
        assertNull(context.getApi());
        assertNull(context.getQos());
        assertNull(context.getResolvedPublishTopic());
        assertNull(context.getTenant());
    }

    @Test
    void shouldCreateNewContextWithDifferentResolvedPublishTopic() {
        RoutingContext original = RoutingContext.builder()
            .topic("device/+/measurements")
            .clientId("client123")
            .tenant("t12345")
            .build();

        RoutingContext modified = original.withResolvedPublishTopic("device/sensor1/measurements");

        // Original unchanged (immutable)
        assertNull(original.getResolvedPublishTopic());

        // Modified has new topic but same other fields
        assertEquals("device/sensor1/measurements", modified.getResolvedPublishTopic());
        assertEquals("device/+/measurements", modified.getTopic());
        assertEquals("client123", modified.getClientId());
        assertEquals("t12345", modified.getTenant());
    }

    @Test
    void shouldCreateNewContextWithDifferentTenant() {
        RoutingContext original = RoutingContext.builder()
            .topic("test/topic")
            .tenant("t12345")
            .build();

        RoutingContext modified = original.withTenant("t67890");

        // Original unchanged
        assertEquals("t12345", original.getTenant());

        // Modified has new tenant
        assertEquals("t67890", modified.getTenant());
        assertEquals("test/topic", modified.getTopic());
    }

    @Test
    void shouldBeImmutable() {
        RoutingContext context = RoutingContext.builder()
            .topic("test/topic")
            .clientId("client123")
            .tenant("t12345")
            .build();

        // Create modified version
        RoutingContext modified = context.withTenant("t67890");

        // Original should be unchanged
        assertEquals("t12345", context.getTenant());
        assertEquals("t67890", modified.getTenant());

        // They should be different objects
        assertNotSame(context, modified);
    }

    @Test
    void shouldImplementEqualsCorrectly() {
        RoutingContext context1 = RoutingContext.builder()
            .topic("test/topic")
            .clientId("client123")
            .tenant("t12345")
            .build();

        RoutingContext context2 = RoutingContext.builder()
            .topic("test/topic")
            .clientId("client123")
            .tenant("t12345")
            .build();

        RoutingContext context3 = RoutingContext.builder()
            .topic("test/topic")
            .clientId("different")
            .tenant("t12345")
            .build();

        assertEquals(context1, context2);
        assertNotEquals(context1, context3);
    }

    @Test
    void shouldImplementHashCodeCorrectly() {
        RoutingContext context1 = RoutingContext.builder()
            .topic("test/topic")
            .clientId("client123")
            .build();

        RoutingContext context2 = RoutingContext.builder()
            .topic("test/topic")
            .clientId("client123")
            .build();

        assertEquals(context1.hashCode(), context2.hashCode());
    }

    @Test
    void shouldAllowNullValues() {
        RoutingContext context = RoutingContext.builder()
            .topic(null)
            .clientId(null)
            .api(null)
            .qos(null)
            .resolvedPublishTopic(null)
            .tenant(null)
            .build();

        assertNull(context.getTopic());
        assertNull(context.getClientId());
        assertNull(context.getApi());
        assertNull(context.getQos());
        assertNull(context.getResolvedPublishTopic());
        assertNull(context.getTenant());
    }
}
