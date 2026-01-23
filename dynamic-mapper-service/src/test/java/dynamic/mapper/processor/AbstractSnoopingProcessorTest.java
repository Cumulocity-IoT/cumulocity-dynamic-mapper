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

package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for AbstractSnoopingProcessor base class.
 * Uses a concrete test implementation to test the abstract functionality.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractSnoopingProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private TestableAbstractSnoopingProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    /**
     * Concrete test implementation of AbstractSnoopingProcessor for testing.
     */
    static class TestableAbstractSnoopingProcessor extends AbstractSnoopingProcessor {
        // Minimal test implementation - dependencies injected via reflection
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = AbstractSnoopingProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        processor = new TestableAbstractSnoopingProcessor();

        // Inject dependencies using reflection
        injectField(processor, "mappingService", mappingService);
        injectField(processor, "objectMapper", objectMapper);

        mapping = createSampleMapping();
        mappingStatus = new MappingStatus(
                "test-id",
                "Test Snooping Mapping",
                "test-snoop",
                Direction.INBOUND,
                "snoop/topic",
                null,
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
    }

    private Mapping createSampleMapping() {
        return Mapping.builder()
                .id("test-id")
                .identifier("test-snoop")
                .name("Test Snooping Mapping")
                .mappingTopic("snoop/topic")
                .mappingTopicSample("snoop/topic/sample")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .active(true)
                .debug(false)
                .snoopStatus(SnoopStatus.ENABLED)
                .snoopedTemplates(new ArrayList<>())
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("temperature", 25.5);
        payload.put("humidity", 60);
        payload.put("deviceId", "sensor-001");

        return ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("snoop/topic/sensor-001")
                .clientId("test-client")
                .build();
    }

    @Test
    void testProcessSuccessfully() throws Exception {
        // Given
        String serializedPayload = "{\"temperature\":25.5,\"humidity\":60,\"deviceId\":\"sensor-001\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(objectMapper).writeValueAsString(processingContext.getPayload());
        verify(mappingService).addDirtyMapping(TEST_TENANT, mapping);

        // Verify snooped template was added
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have one snooped template");
        assertEquals(serializedPayload, mapping.getSnoopedTemplates().get(0), "Should have correct serialized payload");

        // Verify mapping status was updated
        assertEquals(1, mappingStatus.snoopedTemplatesTotal, "Should have updated total count");
        assertEquals(1, mappingStatus.snoopedTemplatesActive, "Should have updated active count");

        // Verify processing is marked to be ignored
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should ignore further processing after snooping");

        log.info("✅ Successfully tested snooping process flow");
    }

    @Test
    void testProcessWithMultipleSnoopedTemplates() throws Exception {
        // Given - Add existing snooped templates
        mapping.getSnoopedTemplates().add("{\"old\":\"template1\"}");
        mapping.getSnoopedTemplates().add("{\"old\":\"template2\"}");
        mappingStatus.snoopedTemplatesTotal = 2;

        String newSerializedPayload = "{\"temperature\":25.5,\"humidity\":60,\"deviceId\":\"sensor-001\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(newSerializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertEquals(3, mapping.getSnoopedTemplates().size(), "Should have three snooped templates");
        assertEquals(newSerializedPayload, mapping.getSnoopedTemplates().get(2), "Should have added new template");
        assertEquals(3, mappingStatus.snoopedTemplatesTotal, "Should have updated total count to 3");
        assertEquals(1, mappingStatus.snoopedTemplatesActive, "Active count should be 1 (only for this execution)");

        log.info("✅ Successfully tested multiple snooped templates");
    }

    @Test
    void testProcessWithSerializationReturningNull() throws Exception {
        // Given - ObjectMapper returns null
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(null);

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(objectMapper).writeValueAsString(processingContext.getPayload());

        // Should not add dirty mapping or update templates when serialization returns null
        verify(mappingService, never()).addDirtyMapping(any(), any());
        assertEquals(0, mapping.getSnoopedTemplates().size(), "Should not have added any template");
        assertEquals(0, mappingStatus.snoopedTemplatesTotal, "Should not have updated total");

        // Still should ignore further processing
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should still ignore further processing");

        log.info("✅ Successfully handled null serialization result");
    }

    @Test
    void testProcessWithSerializationException() throws Exception {
        // Given - ObjectMapper throws exception
        when(objectMapper.writeValueAsString(processingContext.getPayload()))
                .thenThrow(new RuntimeException("Serialization failed"));

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(objectMapper).writeValueAsString(processingContext.getPayload());

        // Should not add dirty mapping when exception occurs
        verify(mappingService, never()).addDirtyMapping(any(), any());
        assertEquals(0, mapping.getSnoopedTemplates().size(), "Should not have added any template");

        // Still should ignore further processing
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should still ignore further processing");

        log.info("✅ Successfully handled serialization exception");
    }

    @Test
    void testProcessWithComplexPayload() throws Exception {
        // Given - Complex nested payload
        Map<String, Object> complexPayload = new HashMap<>();
        Map<String, Object> nested = new HashMap<>();
        nested.put("sensor", "DHT22");
        nested.put("location", "Room 1");
        complexPayload.put("metadata", nested);
        complexPayload.put("readings", new ArrayList<Double>() {{ add(25.5); add(26.0); add(25.8); }});
        complexPayload.put("timestamp", System.currentTimeMillis());

        processingContext.setPayload(complexPayload);

        String complexSerialized = "{\"metadata\":{\"sensor\":\"DHT22\",\"location\":\"Room 1\"},\"readings\":[25.5,26.0,25.8],\"timestamp\":1234567890}";
        when(objectMapper.writeValueAsString(complexPayload)).thenReturn(complexSerialized);

        // When
        processor.process(exchange);

        // Then
        verify(objectMapper).writeValueAsString(complexPayload);
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have added complex template");
        assertEquals(complexSerialized, mapping.getSnoopedTemplates().get(0),
                "Should have correct complex serialized payload");

        log.info("✅ Successfully handled complex payload");
    }

    @Test
    void testProcessIgnoresFurtherProcessing() throws Exception {
        // Given
        String serializedPayload = "{\"test\":\"data\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // Initially should not ignore further processing
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Initially should not ignore further processing");

        // When
        processor.process(exchange);

        // Then
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should set ignore further processing flag");

        log.info("✅ Successfully verified ignoreFurtherProcessing flag");
    }

    @Test
    void testProcessWithDebugLogging() throws Exception {
        // Given - Enable debug logging
        mapping.setDebug(true);

        String serializedPayload = "{\"debug\":\"enabled\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then - Should still process normally
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have added template");
        verify(mappingService).addDirtyMapping(TEST_TENANT, mapping);

        log.info("✅ Successfully tested with debug logging enabled");
    }

    @Test
    void testProcessWithEmptyPayload() throws Exception {
        // Given - Empty payload
        Map<String, Object> emptyPayload = new HashMap<>();
        processingContext.setPayload(emptyPayload);

        String serializedPayload = "{}";
        when(objectMapper.writeValueAsString(emptyPayload)).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then - Should still process empty payload
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have added empty template");
        assertEquals("{}", mapping.getSnoopedTemplates().get(0), "Should have empty JSON object");

        log.info("✅ Successfully handled empty payload");
    }

    @Test
    void testMappingStatusCounters() throws Exception {
        // Given
        String serializedPayload = "{\"counter\":\"test\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // Initial state
        assertEquals(0, mappingStatus.snoopedTemplatesTotal, "Initial total should be 0");
        assertEquals(0, mappingStatus.snoopedTemplatesActive, "Initial active should be 0");

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mappingStatus.snoopedTemplatesTotal,
                "Total should match number of templates");
        assertEquals(1, mappingStatus.snoopedTemplatesActive,
                "Active should be incremented for this execution");

        log.info("✅ Successfully verified mapping status counters");
    }

    @Test
    void testProcessCallsAddDirtyMapping() throws Exception {
        // Given
        String serializedPayload = "{\"dirty\":\"test\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        verify(mappingService, times(1)).addDirtyMapping(TEST_TENANT, mapping);

        log.info("✅ Successfully verified addDirtyMapping is called");
    }

    @Test
    void testProcessWithNullMapping() throws Exception {
        // Given - Context with null mapping
        processingContext.setMapping(null);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);

        // When - Should handle gracefully without throwing exception
        processor.process(exchange);

        // Then - Should not add any templates and should still ignore further processing
        verify(mappingService, never()).addDirtyMapping(any(), any());
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should still ignore further processing even with null mapping");

        log.info("✅ Successfully handled null mapping gracefully");
    }

    @Test
    void testProcessPreservesExistingSnoopedTemplates() throws Exception {
        // Given - Existing snooped templates
        String existing1 = "{\"old\":\"template1\"}";
        String existing2 = "{\"old\":\"template2\"}";
        mapping.getSnoopedTemplates().add(existing1);
        mapping.getSnoopedTemplates().add(existing2);

        String newTemplate = "{\"new\":\"template\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(newTemplate);

        // When
        processor.process(exchange);

        // Then
        assertEquals(3, mapping.getSnoopedTemplates().size(), "Should have all templates");
        assertEquals(existing1, mapping.getSnoopedTemplates().get(0), "First template should be preserved");
        assertEquals(existing2, mapping.getSnoopedTemplates().get(1), "Second template should be preserved");
        assertEquals(newTemplate, mapping.getSnoopedTemplates().get(2), "New template should be added");

        log.info("✅ Successfully verified existing templates are preserved");
    }
}
