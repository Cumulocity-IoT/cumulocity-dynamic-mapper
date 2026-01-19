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

package dynamic.mapper.processor.outbound.processor;

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
 * Tests for SnoopingOutboundProcessor.
 * Tests the outbound-specific snooping functionality for capturing Cumulocity operation payloads.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnoopingOutboundProcessorTest {

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

    private SnoopingOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        processor = new SnoopingOutboundProcessor();

        // Inject dependencies using reflection
        injectField(processor, "mappingService", mappingService);
        injectField(processor, "objectMapper", objectMapper);

        mapping = createOutboundSnoopingMapping();
        mappingStatus = new MappingStatus(
                "outbound-snoop-id",
                "Outbound Snooping Mapping",
                "outbound-snoop",
                Direction.OUTBOUND,
                null,
                "device/+/command",
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createOutboundProcessingContext();

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

    private Mapping createOutboundSnoopingMapping() {
        return Mapping.builder()
                .id("outbound-snoop-id")
                .identifier("outbound-snoop")
                .name("Outbound Snooping Mapping")
                .publishTopic("device/+/command")
                .publishTopicSample("device/device001/command")
                .targetAPI(API.OPERATION)
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.JSON)
                .active(true)
                .debug(false)
                .snoopStatus(SnoopStatus.ENABLED)
                .snoopedTemplates(new ArrayList<>())
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    private ProcessingContext<Object> createOutboundProcessingContext() {
        // Typical Cumulocity operation payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "12345");
        payload.put("deviceId", "device001");
        payload.put("status", "PENDING");
        payload.put("creationTime", "2024-01-19T12:00:00.000Z");

        Map<String, Object> operation = new HashMap<>();
        operation.put("command", "c8y_Restart");
        payload.put("c8y_Restart", operation);

        return ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("device/device001/command")
                .sourceId("device001")
                .build();
    }

    @Test
    void testSnoopOutboundOperationPayload() throws Exception {
        // Given
        String serializedPayload = "{\"id\":\"12345\",\"deviceId\":\"device001\",\"status\":\"PENDING\",\"c8y_Restart\":{\"command\":\"c8y_Restart\"}}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(objectMapper).writeValueAsString(processingContext.getPayload());
        verify(mappingService).addDirtyMapping(TEST_TENANT, mapping);

        // Verify snooped template
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have captured one operation template");
        assertEquals(serializedPayload, mapping.getSnoopedTemplates().get(0),
                "Should have correct operation payload");

        // Verify status counters
        assertEquals(1, mappingStatus.snoopedTemplatesTotal);
        assertEquals(1, mappingStatus.snoopedTemplatesActive);

        // Verify further processing is blocked
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should block further processing during snooping");

        log.info("✅ Successfully snooped outbound operation payload");
    }

    @Test
    void testSnoopMultipleOperationTypes() throws Exception {
        // Given - Different operation types
        String restartOp = "{\"c8y_Restart\":{}}";
        String configOp = "{\"c8y_Configuration\":{\"config\":\"value\"}}";
        String softwareOp = "{\"c8y_SoftwareUpdate\":{\"software\":[{\"name\":\"app\",\"version\":\"1.0\"}]}}";

        // First operation
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(restartOp);
        processor.process(exchange);

        // Second operation
        Map<String, Object> configPayload = new HashMap<>();
        configPayload.put("c8y_Configuration", Map.of("config", "value"));
        processingContext.setPayload(configPayload);
        when(objectMapper.writeValueAsString(configPayload)).thenReturn(configOp);
        processor.process(exchange);

        // Third operation
        Map<String, Object> softwarePayload = new HashMap<>();
        softwarePayload.put("c8y_SoftwareUpdate", Map.of("software", new ArrayList<>()));
        processingContext.setPayload(softwarePayload);
        when(objectMapper.writeValueAsString(softwarePayload)).thenReturn(softwareOp);
        processor.process(exchange);

        // Then
        assertEquals(3, mapping.getSnoopedTemplates().size(), "Should have captured three operation templates");
        assertEquals(restartOp, mapping.getSnoopedTemplates().get(0));
        assertEquals(configOp, mapping.getSnoopedTemplates().get(1));
        assertEquals(softwareOp, mapping.getSnoopedTemplates().get(2));

        log.info("✅ Successfully snooped multiple operation types");
    }

    @Test
    void testSnoopWithComplexOperationPayload() throws Exception {
        // Given - Complex c8y_Command operation
        Map<String, Object> complexPayload = new HashMap<>();
        complexPayload.put("id", "67890");
        complexPayload.put("deviceId", "gateway-001");
        complexPayload.put("status", "EXECUTING");
        complexPayload.put("creationTime", "2024-01-19T12:00:00.000Z");
        complexPayload.put("description", "Execute remote command");

        Map<String, Object> command = new HashMap<>();
        command.put("text", "sudo systemctl restart application");
        command.put("timeout", 300);
        command.put("async", true);
        complexPayload.put("c8y_Command", command);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("priority", "HIGH");
        metadata.put("requestor", "admin");
        complexPayload.put("metadata", metadata);

        processingContext.setPayload(complexPayload);

        String serialized = "{\"id\":\"67890\",\"deviceId\":\"gateway-001\",\"status\":\"EXECUTING\",\"c8y_Command\":{...},\"metadata\":{...}}";
        when(objectMapper.writeValueAsString(complexPayload)).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size(), "Should have captured complex operation template");
        verify(objectMapper).writeValueAsString(complexPayload);

        log.info("✅ Successfully snooped complex operation payload");
    }

    @Test
    void testSnoopWithDeviceControlOperation() throws Exception {
        // Given - Device control operation (relay, measurement request, etc.)
        Map<String, Object> controlPayload = new HashMap<>();
        controlPayload.put("id", "ctrl-001");
        controlPayload.put("deviceId", "relay-device");

        Map<String, Object> relayControl = new HashMap<>();
        relayControl.put("relayState", "OPEN");
        relayControl.put("duration", 5000);
        controlPayload.put("c8y_RelayControl", relayControl);

        processingContext.setPayload(controlPayload);

        String serialized = "{\"id\":\"ctrl-001\",\"deviceId\":\"relay-device\",\"c8y_RelayControl\":{\"relayState\":\"OPEN\",\"duration\":5000}}";
        when(objectMapper.writeValueAsString(controlPayload)).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());
        assertEquals(serialized, mapping.getSnoopedTemplates().get(0));

        log.info("✅ Successfully snooped device control operation");
    }

    @Test
    void testSnoopBlocksFurtherProcessing() throws Exception {
        // Given
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Initially should not block processing");

        String serializedPayload = "{\"test\":\"operation\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serializedPayload);

        // When
        processor.process(exchange);

        // Then
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should block further processing to prevent actual operation execution during snooping");

        log.info("✅ Successfully verified processing is blocked during snooping");
    }

    @Test
    void testSnoopWithOperationStatus() throws Exception {
        // Given - Operation with various status values
        Map<String, Object> statusPayload = new HashMap<>();
        statusPayload.put("id", "op-123");
        statusPayload.put("status", "SUCCESSFUL");
        statusPayload.put("deliveryTime", "2024-01-19T12:05:00.000Z");

        processingContext.setPayload(statusPayload);

        String serialized = "{\"id\":\"op-123\",\"status\":\"SUCCESSFUL\",\"deliveryTime\":\"2024-01-19T12:05:00.000Z\"}";
        when(objectMapper.writeValueAsString(statusPayload)).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());
        assertEquals(serialized, mapping.getSnoopedTemplates().get(0));

        log.info("✅ Successfully snooped operation with status");
    }

    @Test
    void testSnoopPreservesOperationIntegrity() throws Exception {
        // Given - Original operation payload
        Map<String, Object> originalPayload = new HashMap<>();
        originalPayload.put("id", "preserve-test");
        originalPayload.put("status", "PENDING");
        Map<String, Object> operation = new HashMap<>();
        operation.put("param1", "value1");
        originalPayload.put("c8y_Test", operation);

        processingContext.setPayload(originalPayload);

        String serialized = "{\"id\":\"preserve-test\",\"status\":\"PENDING\",\"c8y_Test\":{\"param1\":\"value1\"}}";
        when(objectMapper.writeValueAsString(originalPayload)).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then - Original payload should be unchanged
        assertEquals("preserve-test", originalPayload.get("id"));
        assertEquals("PENDING", originalPayload.get("status"));
        assertTrue(originalPayload.containsKey("c8y_Test"));

        log.info("✅ Successfully verified operation payload integrity is preserved");
    }

    @Test
    void testSnoopIncrementsMappingStatusCorrectly() throws Exception {
        // Given - Existing snooped templates
        mapping.getSnoopedTemplates().add("{\"old\":\"operation\"}");
        mappingStatus.snoopedTemplatesTotal = 1;
        mappingStatus.snoopedTemplatesActive = 0;

        String serializedPayload = "{\"new\":\"operation\"}";
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
                .thenThrow(new RuntimeException("Cannot serialize operation"));

        // When
        processor.process(exchange);

        // Then - Should handle gracefully
        assertEquals(0, mapping.getSnoopedTemplates().size(), "Should not add template on error");
        verify(mappingService, never()).addDirtyMapping(any(), any());

        // Should still block further processing
        assertTrue(processingContext.getIgnoreFurtherProcessing());

        log.info("✅ Successfully handled serialization error");
    }

    @Test
    void testSnoopWithPublishTopicPattern() throws Exception {
        // Given - Publish topic with wildcards
        mapping.setPublishTopic("devices/+/commands/#");
        processingContext.setTopic("devices/sensor-001/commands/restart");

        String serialized = "{\"command\":\"restart\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());

        log.info("✅ Successfully snooped with wildcard publish topic");
    }

    @Test
    void testSnoopCapturesSourceIdContext() throws Exception {
        // Given - Context with source ID
        processingContext.setSourceId("device-abc-123");

        String serialized = "{\"operation\":\"test\"}";
        when(objectMapper.writeValueAsString(processingContext.getPayload())).thenReturn(serialized);

        // When
        processor.process(exchange);

        // Then
        assertEquals(1, mapping.getSnoopedTemplates().size());
        // Source ID context is preserved in processing context
        assertEquals("device-abc-123", processingContext.getSourceId());

        log.info("✅ Successfully snooped with source ID context");
    }
}
