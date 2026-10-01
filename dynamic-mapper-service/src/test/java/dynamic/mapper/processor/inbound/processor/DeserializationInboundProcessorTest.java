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

package dynamic.mapper.processor.inbound.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.mapping.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeserializationInboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer sparkPlugBDeserializer;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private ConnectorMessage connectorMessage;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private MappingStatus unspecifiedMappingStatus;

    @BeforeEach
    void setUp() throws Exception {
        // Create real Mapping object with proper initialization
        mapping = Mapping.builder().id("test-mapping-id")
                .identifier("test-mapping").name("Test Mapping")
                .build();

        // Create real MappingStatus objects
        mappingStatus = new MappingStatus(
                "test-id", "Test Mapping", "test-mapping", Direction.INBOUND,
                "test/topic", "output/topic", 0L, 0L, 0L, null);

        unspecifiedMappingStatus = new MappingStatus(
                "unspec-id", "Unspecified Mapping", "UNSPECIFIED", Direction.INBOUND,
                "#", "#", 0L, 0L, 0L, null);

        when(connectorMessage.getConnectorIdentifier()).thenReturn("test-connector");

        // Setup mapping status mocks
        when(mappingService.getMappingStatus(any(String.class), any(Mapping.class))).thenReturn(mappingStatus);
        when(mappingService.getMappingStatus(TEST_TENANT, Mapping.UNSPECIFIED_MAPPING))
                .thenReturn(unspecifiedMappingStatus);
    }

    private void setupValidPayload(MappingType mappingType) {
        switch (mappingType) {
            case JSON:
                // Provide valid JSON data
                when(connectorMessage.getPayload()).thenReturn("{\"temperature\":25.5,\"deviceId\":\"sensor001\",\"status\":\"active\"}".getBytes());
                break;
            case FLAT_FILE:
                // Provide valid flat file data
                when(connectorMessage.getPayload()).thenReturn("25.5,sensor001,active".getBytes());
                break;
            case HEX:
                // Provide valid hex data
                when(connectorMessage.getPayload()).thenReturn("48656c6c6f".getBytes());
                break;
            case PROTOBUF_INTERNAL:
            case ANY_PAYLOAD:
                // Provide valid byte array for byte-based processing
                when(connectorMessage.getPayload()).thenReturn("test payload".getBytes());
                break;
            default:
                // Default to valid JSON
                when(connectorMessage.getPayload()).thenReturn("{\"data\": \"test\"}".getBytes());
        }
    }

    @Test
    void testProcessJsonMappingTypeSuccess() throws Exception {
        // Given
        mapping.setMappingType(MappingType.JSON);
        setupValidPayload(MappingType.JSON);

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then
        assertNotNull(context);
        assertFalse(context.hasError());
    }

    @Test
    void testProcessProtobufInternalMappingTypeSuccess() throws Exception {
        // Given
        mapping.setMappingType(MappingType.PROTOBUF_INTERNAL);
        setupValidPayload(MappingType.PROTOBUF_INTERNAL);

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then
        assertNotNull(context);
        assertFalse(context.hasError());
    }

    @Test
    void testProcessExtensionJavaMappingTypeSuccess() throws Exception {
        // Given
        MappingType extensionJavaType = MappingType.valueOf("EXTENSION_JAVA");
        mapping.setMappingType(extensionJavaType);
        setupValidPayload(extensionJavaType);

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then - no deserializer is registered for MappingType.EXTENSION_JAVA, so this
        // records an error (matches handleMissingProcessor's behavior, unrelated to Camel removal)
        assertNotNull(context);
        assertTrue(context.hasError());
    }

    @Test
    void testProcessFlatFileMappingTypeSuccess() throws Exception {
        // Given
        mapping.setMappingType(MappingType.FLAT_FILE);
        setupValidPayload(MappingType.FLAT_FILE);

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then
        assertNotNull(context);
        assertFalse(context.hasError());
    }

    @Test
    void testProcessHexMappingTypeSuccess() throws Exception {
        // Given
        mapping.setMappingType(MappingType.HEX);
        setupValidPayload(MappingType.HEX);

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then
        assertNotNull(context);
        assertFalse(context.hasError());
    }

    @Test
    void testProcessWithNullMappingType() throws Exception {
        // Given
        mapping.setMappingType(null);
        when(connectorMessage.getPayload()).thenReturn("{\"data\": \"test\"}".getBytes());

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, Mapping.UNSPECIFIED_MAPPING);
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        assertNotNull(context);
        assertTrue(context.hasError());

        assertEquals(1, mappingStatus.errors);
        assertEquals(1, unspecifiedMappingStatus.errors);
    }

    @Test
    void testProcessWithInvalidPayload() throws Exception {
        // Given
        mapping.setMappingType(MappingType.JSON);
        // Provide invalid JSON to test error handling
        when(connectorMessage.getPayload()).thenReturn("invalid json".getBytes());

        DeserializationInboundProcessor processor = newProcessor();

        // When
        ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

        // Then - Error handling should be called, and the returned context carries the error
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        assertNotNull(context);
        assertTrue(context.hasError());

        assertEquals(1, mappingStatus.errors);
    }

    @Test
    void testProcessWithNullConnectorMessage() throws Exception {
        // Given
        mapping.setMappingType(MappingType.JSON);

        DeserializationInboundProcessor processor = newProcessor();

        // When & Then
        assertThrows(Exception.class,
                () -> processor.process(TEST_TENANT, mapping, null, serviceConfiguration, false));
    }

    @Test
    void testProcessWithNullMapping() throws Exception {
        DeserializationInboundProcessor processor = newProcessor();

        // When & Then
        assertThrows(NullPointerException.class,
                () -> processor.process(TEST_TENANT, null, connectorMessage, serviceConfiguration, false));
    }

    @Test
    void testConstructorInitializesDeserializers() {
        // Given & When
        DeserializationInboundProcessor createdProcessor = newProcessor();

        // Then
        assertNotNull(createdProcessor);
    }

    @Test
    void testAllMappingTypesWithValidPayloads() throws Exception {
        MappingType[] mappingTypes = {
                MappingType.JSON,
                MappingType.FLAT_FILE,
                MappingType.HEX,
                MappingType.PROTOBUF_INTERNAL,
            MappingType.valueOf("EXTENSION_JAVA"),
        };

        for (MappingType type : mappingTypes) {
            // Create fresh processor for each test
            DeserializationInboundProcessor processor = newProcessor();

            // Given
            mapping.setMappingType(type);
            setupValidPayload(type);

            // When
            ProcessingContext<?> context = processor.process(TEST_TENANT, mapping, connectorMessage, serviceConfiguration, false);

            // Then - EXTENSION_JAVA has no registered deserializer, so it records an error
            // (matches handleMissingProcessor's behavior, unrelated to Camel removal)
            assertNotNull(context);
            if (type == MappingType.valueOf("EXTENSION_JAVA")) {
                assertTrue(context.hasError());
            } else {
                assertFalse(context.hasError());
            }
        }
    }

    private DeserializationInboundProcessor newProcessor() {
        return new DeserializationInboundProcessor(mappingService, sparkPlugBDeserializer);
    }
}
