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
 * Tests for SnoopingInboundProcessor.
 * Tests the inbound-specific snooping functionality for capturing device payloads.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnoopingInboundProcessorTest {

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

    private SnoopingInboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        processor = new SnoopingInboundProcessor();

        // Inject dependencies using reflection
        injectField(processor, "mappingService", mappingService);
        injectField(processor, "objectMapper", objectMapper);

        mapping = createInboundSnoopingMapping();
        mappingStatus = new MappingStatus(
                "inbound-snoop-id",
                "Inbound Snooping Mapping",
                "inbound-snoop",
                Direction.INBOUND,
                "device/+/data",
                null,
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createInboundProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Mapping createInboundSnoopingMapping() {
        return Mapping.builder()
                .id("inbound-snoop-id")
                .identifier("inbound-snoop")
                .name("Inbound Snooping Mapping")
                .mappingTopic("device/+/data")
                .mappingTopicSample("device/sensor001/data")
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

    private ProcessingContext<Object> createInboundProcessingContext() {
        // Typical inbound device payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "sensor001");
        payload.put("temperature", 23.5);
        payload.put("humidity", 65);
        payload.put("pressure", 1013.25);
        payload.put("timestamp", System.currentTimeMillis());

        return ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("device/sensor001/data")
                .clientId("mqtt-client-001")
                .build();
    }

    @Test
    void testSnoopInboundDevicePayload() throws Exception {
        // Given
        String serializedPayload = "{\"deviceId\":\"sensor001\",\"temperature\":23.5,\"humidity\":65,\"pressure\":1013.25}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(objectMapper).writeValueAsString(processingContext.getPayload());
        verify(mappingService).addDirtyMapping(TEST_TENANT, mapping);

        // Verify snooped template
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have captured one template");
        assertEquals(serializedPayload, mapping.getSnoopedTemplates().get(0),
                "Should have correct device payload");

        // Verify status counters
        assertEquals(1, mappingStatus.snoopedTemplatesTotal);
        assertEquals(1, mappingStatus.snoopedTemplatesActive);

        // Verify further processing is blocked
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should block further processing during snooping");

        log.info("✅ Successfully snooped inbound device payload");
    }

    @Test
    void testSnoopMultipleDevicePayloads() throws Exception {
        // Given - Simulate multiple device messages being snooped
        String firstPayload = "{\"deviceId\":\"sensor001\",\"temperature\":23.5}";
        String secondPayload = "{\"deviceId\":\"sensor002\",\"temperature\":24.0}";
        String thirdPayload = "{\"deviceId\":\"sensor003\",\"temperature\":25.5}";

        // First message
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(firstPayload);
        processor.process(exchange);

        // Second message
        Map<String, Object> payload2 = new HashMap<>();
        payload2.put("deviceId", "sensor002");
        payload2.put("temperature", 24.0);
        processingContext.setPayload(payload2);
        when(objectMapper.writeValueAsString(payload2)).thenReturn(secondPayload);
        processor.process(exchange);

        // Third message
        Map<String, Object> payload3 = new HashMap<>();
        payload3.put("deviceId", "sensor003");
        payload3.put("temperature", 25.5);
        processingContext.setPayload(payload3);
        when(objectMapper.writeValueAsString(payload3)).thenReturn(thirdPayload);
        processor.process(exchange);

        // Then
        assertEquals(3, mapping.getSnoopedTemplates().size(), "Should have captured three templates");
        assertEquals(firstPayload, mapping.getSnoopedTemplates().get(0));
        assertEquals(secondPayload, mapping.getSnoopedTemplates().get(1));
        assertEquals(thirdPayload, mapping.getSnoopedTemplates().get(2));

        log.info("✅ Successfully snooped multiple device payloads");
    }

    @Test
    void testSnoopWithComplexDevicePayload() throws Exception {
        // Given - Complex IoT device payload with nested structures
        Map<String, Object> complexPayload = new HashMap<>();
        complexPayload.put("deviceId", "weather-station-01");
        complexPayload.put("timestamp", "2024-01-19T12:00:00Z");

        Map<String, Object> sensors = new HashMap<>();
        sensors.put("temperature", 22.5);
        sensors.put("humidity", 58);
        sensors.put("pressure", 1015.3);
        sensors.put("windSpeed", 12.5);
        sensors.put("windDirection", 180);
        complexPayload.put("sensors", sensors);

        Map<String, Object> location = new HashMap<>();
        location.put("latitude", 52.5200);
        location.put("longitude", 13.4050);
        location.put("altitude", 34);
        complexPayload.put("location", location);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("firmwareVersion", "2.1.5");
        metadata.put("batteryLevel", 87);
        metadata.put("signalStrength", -65);
        complexPayload.put("metadata", metadata);

        processingContext.setPayload(complexPayload);

        String serialized = "{\"deviceId\":\"weather-station-01\",\"timestamp\":\"2024-01-19T12:00:00Z\",\"sensors\":{...},\"location\":{...},\"metadata\":{...}}";
        when(objectMapper.writeValueAsString(complexPayload)).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have captured complex template");
        verify(objectMapper).writeValueAsString(complexPayload);

        log.info("✅ Successfully snooped complex device payload");
    }

    @Test
    void testSnoopWithMQTTClientIdInTopic() throws Exception {
        // Given - Topic includes client/device identifier
        processingContext.setTopic("device/sensor-abc-123/temperature");
        processingContext.setClientId("mqtt-client-sensor-abc-123");

        String serializedPayload = "{\"temperature\":25.0}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());
        verify(mappingService).addDirtyMapping(TEST_TENANT, mapping);

        log.info("✅ Successfully snooped with MQTT client ID context");
    }

    @Test
    void testSnoopBlocksFurtherProcessing() throws Exception {
        // Given
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Initially should not block processing");

        String serializedPayload = "{\"test\":\"data\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should block further processing to prevent actual data ingestion during snooping");

        log.info("✅ Successfully verified processing is blocked during snooping");
    }

    @Test
    void testSnoopWithBinaryPayload() throws Exception {
        // Given - Binary/byte array payload (common in IoT)
        byte[] binaryPayload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        processingContext.setPayload(binaryPayload);

        String serializedPayload = "[1,2,3,4,5]";
        when(objectMapper.writeValueAsString(binaryPayload)).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());
        assertEquals(serializedPayload, mapping.getSnoopedTemplates().get(0));

        log.info("✅ Successfully snooped binary payload");
    }

    @Test
    void testSnoopWithWildcardTopicMatch() throws Exception {
        // Given - Mapping has wildcard topic pattern
        mapping.setMappingTopic("sensors/+/data");
        processingContext.setTopic("sensors/temperature-001/data");

        String serializedPayload = "{\"value\":23.5}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());

        log.info("✅ Successfully snooped with wildcard topic");
    }

    @Test
    void testSnoopPreservesPayloadIntegrity() throws Exception {
        // Given - Original payload
        Map<String, Object> originalPayload = new HashMap<>();
        originalPayload.put("key1", "value1");
        originalPayload.put("key2", 123);
        originalPayload.put("key3", true);

        processingContext.setPayload(originalPayload);

        String serializedPayload = "{\"key1\":\"value1\",\"key2\":123,\"key3\":true}";
        when(objectMapper.writeValueAsString(originalPayload)).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then - Original payload should be unchanged
        assertEquals("value1", originalPayload.get("key1"));
        assertEquals(123, originalPayload.get("key2"));
        assertEquals(true, originalPayload.get("key3"));

        log.info("✅ Successfully verified payload integrity is preserved");
    }

    @Test
    void testSnoopIncrementsMappingStatusCorrectly() throws Exception {
        // Given - Existing snooped templates
        mapping.getSnoopedTemplates().add("{\"old\":\"template\"}");
        mappingStatus.snoopedTemplatesTotal = 1;
        mappingStatus.snoopedTemplatesActive = 0;

        String serializedPayload = "{\"new\":\"template\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertEquals(2, mappingStatus.snoopedTemplatesTotal, "Total should be incremented");
        assertEquals(1, mappingStatus.snoopedTemplatesActive, "Active should reflect current execution");

        log.info("✅ Successfully verified mapping status counters");
    }

    @Test
    void testSnoopHandlesSerializationError() throws Exception {
        // Given - Serialization throws exception
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("Cannot serialize circular reference"));

        // When
        processor.process(exchange);

        // Then - Should handle gracefully
        assertEquals(0, mapping.getSnoopedTemplates().size(), "Should not add template on error");
        verify(mappingService, never()).addDirtyMapping(any(), any());

        // Should still block further processing
        assertTrue(processingContext.getIgnoreFurtherProcessing());

        log.info("✅ Successfully handled serialization error");
    }
}
