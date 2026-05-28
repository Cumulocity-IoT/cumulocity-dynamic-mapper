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

package dynamic.mapper.processor.inbound.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for the {@code ANY_PAYLOAD} mapping type (INBOUND direction).
 *
 * <p>{@code ANY_PAYLOAD} supports {@code SMART_FUNCTION} and
 * {@code EXTENSION_JAVA} transformations.  The broker payload (raw bytes)
 * is deserialized by {@link dynamic.mapper.processor.inbound.deserializer.BytePayloadDeserializer}
 * and delivered to the processing context as a {@code byte[]} without any
 * JSON or binary parsing — giving Smart Functions full control over
 * interpretation (e.g. SparkPlug B, custom binary protocols).</p>
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnyPayloadInboundTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private ConnectorMessage connectorMessage;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;

    @BeforeEach
    void setUp() {
        mapping = Mapping.builder()
                .id("any-payload-test-id")
                .identifier("any-payload-test")
                .name("ANY_PAYLOAD Test Mapping")
                .mappingTopic("device/+/data")
                .mappingTopicSample("device/sensor01/data")
                .mappingType(MappingType.ANY_PAYLOAD)
                .transformationType(TransformationType.SMART_FUNCTION)
                .direction(Direction.INBOUND)
                .externalIdType("c8y_Serial")
                .active(false)
                .snoopStatus(SnoopStatus.NONE)
                .snoopedTemplates(new java.util.ArrayList<>())
                .substitutions(new Substitution[0])
                .filterMapping("")
                .build();

        mappingStatus = new MappingStatus(
                "any-payload-test-id", "ANY_PAYLOAD Test Mapping", "any-payload-test",
                Direction.INBOUND, "device/+/data", null,
                0L, 0L, 0L, 0L, 0L, null);

        when(exchange.getIn()).thenReturn(message);
        when(message.getBody(Mapping.class)).thenReturn(mapping);
        when(message.getHeader("tenant", String.class)).thenReturn(TEST_TENANT);
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class))
                .thenReturn(serviceConfiguration);
        when(message.getHeader("connectorMessage", ConnectorMessage.class))
                .thenReturn(connectorMessage);
        when(message.getHeader("testing", Boolean.class)).thenReturn(Boolean.FALSE);

        when(mappingService.getMappingStatus(any(), any())).thenReturn(mappingStatus);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
    }

    // ── Positive: raw bytes pass through unchanged ────────────────────────────

    @Test
    void binaryPayload_passedAsRawBytesToContext() throws Exception {
        // Given: arbitrary binary data (simulates a custom binary protocol)
        byte[] binaryPayload = new byte[]{
                0x00, 0x01, 0x02, (byte) 0xCA, (byte) 0xFE, (byte) 0xDE, (byte) 0xAD
        };
        when(connectorMessage.getPayload()).thenReturn(binaryPayload);

        DeserializationInboundProcessor processor = createProcessor();

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext<?>> ctxCaptor =
                ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), ctxCaptor.capture());

        ProcessingContext<?> ctx = ctxCaptor.getValue();
        assertNotNull(ctx, "ProcessingContext must be set on the exchange");
        assertNotNull(ctx.getPayload(), "Payload must be present in context");

        // BytePayloadDeserializer returns raw bytes for ANY_PAYLOAD
        assertInstanceOf(byte[].class, ctx.getPayload(),
                "ANY_PAYLOAD must deliver a byte[] to the processing context");
        assertArrayEquals(binaryPayload, (byte[]) ctx.getPayload(),
                "Raw bytes must be preserved byte-for-byte");
    }

    @Test
    void jsonBytesAsAnyPayload_passedAsRawBytesUnparsed() throws Exception {
        // Given: JSON content sent via ANY_PAYLOAD (not parsed — passed through as bytes)
        byte[] jsonBytes = "{\"temperature\":42.0}".getBytes(StandardCharsets.UTF_8);
        when(connectorMessage.getPayload()).thenReturn(jsonBytes);

        DeserializationInboundProcessor processor = createProcessor();

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext<?>> ctxCaptor =
                ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), ctxCaptor.capture());

        ProcessingContext<?> ctx = ctxCaptor.getValue();
        assertNotNull(ctx.getPayload());
        assertInstanceOf(byte[].class, ctx.getPayload(),
                "JSON bytes sent as ANY_PAYLOAD must not be auto-parsed into a Map");
        assertArrayEquals(jsonBytes, (byte[]) ctx.getPayload());
    }

    @Test
    void largeBinaryPayload_notTruncated() throws Exception {
        // Given: 64 KB payload
        byte[] largePayload = new byte[65536];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }
        when(connectorMessage.getPayload()).thenReturn(largePayload);

        DeserializationInboundProcessor processor = createProcessor();

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext<?>> ctxCaptor =
                ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), ctxCaptor.capture());

        ProcessingContext<?> ctx = ctxCaptor.getValue();
        assertNotNull(ctx.getPayload());
        assertInstanceOf(byte[].class, ctx.getPayload());
        assertEquals(65536, ((byte[]) ctx.getPayload()).length,
                "Full 64 KB payload must be delivered to the context untruncated");
    }

    // ── EXTENSION_JAVA transformation ─────────────────────────────────────────

    @Test
    void extensionJavaTransformation_anyPayload_deserializedAsBytes() throws Exception {
        // Given: EXTENSION_JAVA + ANY_PAYLOAD (e.g. custom Java plugin for binary protocol)
        mapping.setTransformationType(TransformationType.EXTENSION_JAVA);
        byte[] payload = "binary-extension-data".getBytes(StandardCharsets.UTF_8);
        when(connectorMessage.getPayload()).thenReturn(payload);

        DeserializationInboundProcessor processor = createProcessor();

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext<?>> ctxCaptor =
                ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), ctxCaptor.capture());

        ProcessingContext<?> ctx = ctxCaptor.getValue();
        assertNotNull(ctx);
        assertInstanceOf(byte[].class, ctx.getPayload(),
                "EXTENSION_JAVA + ANY_PAYLOAD must also receive raw bytes");
    }

    // ── Empty / null payload ──────────────────────────────────────────────────

    @Test
    void emptyPayload_contextSetWithoutException() throws Exception {
        // Given: empty payload (zero-length byte array)
        when(connectorMessage.getPayload()).thenReturn(new byte[0]);

        DeserializationInboundProcessor processor = createProcessor();

        // When / Then: must not throw; context is still written to the exchange
        processor.process(exchange);
        verify(message).setHeader(eq("processingContext"), any(ProcessingContext.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DeserializationInboundProcessor createProcessor() throws Exception {
        DeserializationInboundProcessor processor = new DeserializationInboundProcessor();
        injectField(processor, "mappingService", mappingService);
        return processor;
    }

    private static void injectField(Object target, String fieldName, Object value)
            throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) return f;
            }
        }
        throw new RuntimeException("Field '" + name + "' not found in " + clazz);
    }
}
