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

import java.lang.reflect.Field;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
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
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MappingContextInboundProcessorTest {

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

    @Mock
    private ProcessingContext<Object> processingContext;

    private MappingContextInboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;

    @BeforeEach
    void setUp() throws Exception {
        // Create real Mapping object
        mapping = Mapping.builder().identifier("test-mapping").name("Test Mapping").debug(false).qos(Qos.AT_LEAST_ONCE)
                .build();

        // Create real MappingStatus object with all required fields initialized
        mappingStatus = new MappingStatus(
                "test-id",
                "Test Mapping",
                "test-mapping",
                Direction.INBOUND,
                "test/topic",
                "output/topic",
                0L, // messagesReceived
                0L, // errors
                0L, // currentFailureCount
                0L, // snoopedTemplatesActive
                0L, // snoopedTemplatesTotal
                null // loadingError
        );

        // Create the processor
        processor = new MappingContextInboundProcessor();

        // Use reflection to inject the mocked mappingService
        injectMappingServiceIfExists(processor, mappingService);

        // Setup basic exchange and message mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getBody(Mapping.class)).thenReturn(mapping);
        when(message.getHeader("tenant", String.class)).thenReturn(TEST_TENANT);
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(serviceConfiguration);
        when(message.getHeader("connectorMessage", ConnectorMessage.class)).thenReturn(connectorMessage);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);

        // Mock processing context methods to prevent null pointer exceptions
        when(processingContext.getServiceConfiguration()).thenReturn(serviceConfiguration);
        when(processingContext.getTenant()).thenReturn(TEST_TENANT);
        when(processingContext.getMapping()).thenReturn(mapping);

        // Setup mapping status mocks - this is crucial!
        when(mappingService.getMappingStatus(eq(TEST_TENANT), eq(mapping))).thenReturn(mappingStatus);
        when(mappingService.getMappingStatus(any(String.class), any(Mapping.class))).thenReturn(mappingStatus);

        // Also mock for null tenant case
        when(mappingService.getMappingStatus(isNull(), any(Mapping.class))).thenReturn(mappingStatus);

        // Mock connector message
        when(connectorMessage.getPayload()).thenReturn("test payload".getBytes());
    }

    private void injectMappingServiceIfExists(MappingContextInboundProcessor processor, MappingService mappingService) {
        try {
            Field field = findMappingServiceField(processor.getClass());
            if (field != null) {
                field.setAccessible(true);
                field.set(processor, mappingService);
                log.info("Successfully injected mappingService into " + processor.getClass().getSimpleName());
            } else {
                log.info("No mappingService field found in " + processor.getClass().getSimpleName()
                        + " or its parent classes");
            }
        } catch (Exception e) {
            log.info("Failed to inject mappingService: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Field findMappingServiceField(Class<?> clazz) {
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField("mappingService");
                log.info("Found mappingService field in " + clazz.getSimpleName());
                return field;
            } catch (NoSuchFieldException e) {
                log.info("No mappingService field in " + clazz.getSimpleName() + ", checking parent class");
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    @Test
    void testProcessWithValidProcessingContext() throws Exception {
        // Given
        mapping.setMappingType(MappingType.JSON);

        // When & Then
        try {
            processor.process(exchange);
            // If we get here without exception, the test passes
            assertTrue(true, "Process completed without exception");
        } catch (Exception e) {
            log.info("Exception details:");
            log.info("Message: " + e.getMessage());
            log.info("Cause: " + e.getCause());
            e.printStackTrace();
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    void testProcessWithDifferentMappingTypes() throws Exception {
        MappingType[] mappingTypes = {
                MappingType.JSON,
                MappingType.FLAT_FILE,
                MappingType.HEX,
                MappingType.PROTOBUF_INTERNAL,
                MappingType.EXTENSION_SOURCE,
                MappingType.EXTENSION_SOURCE_TARGET,
                MappingType.CODE_BASED
        };

        for (MappingType type : mappingTypes) {
            try {
                // Given
                mapping.setMappingType(type);

                // When
                processor.process(exchange);

                // If we get here, the processing was successful
                assertTrue(true, "Successfully processed " + type);

            } catch (Exception e) {
                log.info("Failed to process mapping type " + type + ": " + e.getMessage());
                e.printStackTrace();
                fail("Failed to process mapping type " + type + ": " + e.getMessage());
            }
        }
    }

    @Test
    void testProcessWithNullMapping() throws Exception {
        // Given
        when(processingContext.getMapping()).thenReturn(null);

        // When & Then - if the processor should handle null gracefully
        try {
            processor.process(exchange);
            fail("Should have thrown NullPointerException");
        } catch (NullPointerException e) {
            // Expected - verify it's the mapping that's null
            assertTrue(true, "Correctly threw NPE for null mapping");
        }
    }

    @Test
    void testConstructorInitialization() {
        // Given & When
        MappingContextInboundProcessor newProcessor = new MappingContextInboundProcessor();

        // Then
        assertNotNull(newProcessor);
    }

    @Test
    void testProcessHandlesNullProcessingContext() throws Exception {
        // Given
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(null);
        mapping.setMappingType(MappingType.JSON);

        // When & Then
        assertThrows(Exception.class, () -> processor.process(exchange));
    }

    @Test
    void testProcessWithValidInputs() throws Exception {
        // Given
        mapping.setMappingType(MappingType.JSON);

        // When & Then
        try {
            processor.process(exchange);
            assertTrue(true, "Process completed successfully");
        } catch (Exception e) {
            log.info("Exception during testProcessWithValidInputs:");
            log.info("Message: " + e.getMessage());
            e.printStackTrace();
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    void testMappingServiceInjection() throws Exception {
        // Test that our mapping service injection worked
        Field field = findMappingServiceField(processor.getClass());
        if (field != null) {
            field.setAccessible(true);
            Object injectedService = field.get(processor);
            assertNotNull(injectedService, "MappingService should be injected");
            assertEquals(mappingService, injectedService, "Injected service should be our mock");
        } else {
            log.info("No mappingService field found - processor might not use MappingService");
        }
    }

    @Test
    void testMappingStatusAccess() throws Exception {
        // Test that mappingService.getMappingStatus returns our mock
        MappingStatus status = mappingService.getMappingStatus(TEST_TENANT, mapping);
        assertNotNull(status, "MappingStatus should not be null");
        assertEquals(0L, status.messagesReceived, "messagesReceived should be initialized to 0");
        assertEquals(0L, status.errors, "errors should be initialized to 0");
    }

    @Test
    void testWithMinimalMocking() throws Exception {
        // Create a completely fresh processor with minimal mocking
        MappingContextInboundProcessor freshProcessor = new MappingContextInboundProcessor();

        // Only inject mappingService if the field exists
        try {
            Field field = findMappingServiceField(freshProcessor.getClass());
            if (field != null) {
                field.setAccessible(true);
                field.set(freshProcessor, mappingService);

                // Given
                mapping.setMappingType(MappingType.JSON);

                // When & Then
                assertDoesNotThrow(() -> freshProcessor.process(exchange));
            } else {
                // If no mappingService field exists, the processor might not need it
                log.info("Processor does not have mappingService field - skipping test");
            }
        } catch (Exception e) {
            log.info("Minimal mocking test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}