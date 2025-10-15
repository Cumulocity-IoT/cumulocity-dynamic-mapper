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
package dynamic.mapper.processor.outbound.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.TransformationType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeserializationOutboundProcessorTest {

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private DeserializationOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "test-device-id-123";
    private static final String TEST_DEVICE_NAME = "Temperature Sensor";
    private static final String TEST_MESSAGE_ID = "msg-12345";
    private static final String TEST_EXTERNAL_ID_TYPE = "c8y_Serial";

    private C8YMessage c8yMessage;
    private Mapping mapping;

    @BeforeEach
    void setUp() throws Exception {
        processor = new DeserializationOutboundProcessor();

        c8yMessage = createC8YMessage();
        mapping = createOutboundMapping();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);

        // Setup mock returns for headers and body
        when(message.getHeader("c8yMessage", C8YMessage.class)).thenReturn(c8yMessage);
        when(message.getBody(Mapping.class)).thenReturn(mapping);
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(serviceConfiguration);
        when(message.getHeader("testing", Boolean.class)).thenReturn(Boolean.FALSE);

        // Setup service configuration defaults
        when(serviceConfiguration.isLogPayload()).thenReturn(false);
        when(serviceConfiguration.isLogSubstitution()).thenReturn(false);
    }

    private C8YMessage createC8YMessage() {
        C8YMessage msg = new C8YMessage();
        msg.setSourceId(TEST_DEVICE_ID);
        msg.setDeviceName(TEST_DEVICE_NAME);
        msg.setMessageId(TEST_MESSAGE_ID);
        msg.setTenant(TEST_TENANT);
        msg.setApi(API.MEASUREMENT);
        msg.setSendPayload(true);
        msg.setOperation("CREATE");

        // Create JSON payload string
        String jsonPayload = createMeasurementPayloadJson();
        msg.setPayload(jsonPayload);

        // Create parsed payload map
        Map<String, Object> parsedPayload = createMeasurementPayloadMap();
        msg.setParsedPayload(parsedPayload);

        return msg;
    }

    private String createMeasurementPayloadJson() {
        return "{\n" +
                "  \"id\": \"266315\",\n" +
                "  \"time\": \"2025-09-17T12:40:36.383+02:00\",\n" +
                "  \"type\": \"c8y_TemperatureMeasurement\",\n" +
                "  \"source\": {\n" +
                "    \"id\": \"" + TEST_DEVICE_ID + "\",\n" +
                "    \"name\": \"" + TEST_DEVICE_NAME + "\"\n" +
                "  },\n" +
                "  \"c8y_TemperatureMeasurement\": {\n" +
                "    \"T\": {\n" +
                "      \"value\": 25.5,\n" +
                "      \"unit\": \"°C\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    private Map<String, Object> createMeasurementPayloadMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "266315");
        payload.put("time", "2025-09-17T12:40:36.383+02:00");
        payload.put("type", "c8y_TemperatureMeasurement");

        // Add source information
        Map<String, Object> source = new HashMap<>();
        source.put("id", TEST_DEVICE_ID);
        source.put("name", TEST_DEVICE_NAME);
        payload.put("source", source);

        // Add measurement data
        Map<String, Object> measurement = new HashMap<>();
        Map<String, Object> temperature = new HashMap<>();
        temperature.put("value", 25.5);
        temperature.put("unit", "°C");
        measurement.put("T", temperature);
        payload.put("c8y_TemperatureMeasurement", measurement);

        return payload;
    }

    private Mapping createOutboundMapping() {
        return Mapping.builder()
                .id("test-outbound-mapping-id")
                .identifier("test-outbound-mapping")
                .name("Test Outbound Mapping")
                .publishTopic("measurements/outbound/+")
                .publishTopicSample("measurements/outbound/device123")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.DEFAULT)
                .debug(false)
                .active(true)
                .tested(false)
                .supportsMessageContext(true)
                .eventWithAttachment(false)
                .createNonExistingDevice(false)
                .updateExistingDevice(false)
                .autoAckOperation(true)
                .useExternalId(true)
                .externalIdType(TEST_EXTERNAL_ID_TYPE)
                .snoopStatus(SnoopStatus.NONE)
                .snoopedTemplates(new ArrayList<>())
                .filterMapping("")
                .filterInventory("")
                .maxFailureCount(0)
                .qos(Qos.AT_MOST_ONCE)
                .lastUpdate(System.currentTimeMillis())
                .sourceTemplate(
                        "{\"id\":\"string\",\"time\":\"string\",\"type\":\"string\",\"source\":{\"id\":\"string\"}}")
                .targetTemplate("{\"deviceId\":\"string\",\"temperature\":0,\"timestamp\":\"string\"}")
                .substitutions(createOutboundSubstitutions())
                .build();
    }

    private Substitution[] createOutboundSubstitutions() {
        return new Substitution[] {
                Substitution.builder()
                        .pathSource("source.id")
                        .pathTarget("deviceId")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("c8y_TemperatureMeasurement.T.value")
                        .pathTarget("temperature")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("time")
                        .pathTarget("timestamp")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
    }

    @Test
    void testProcessBasicOutboundMessage() throws Exception {
        // When
        processor.process(exchange);

        // Then - Verify processingContext was set in header
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should not be null");
        assertEquals(TEST_TENANT, capturedContext.getTenant(), "Should have correct tenant");
        assertEquals(mapping, capturedContext.getMapping(), "Should have correct mapping");
        assertEquals(serviceConfiguration, capturedContext.getServiceConfiguration(),
                "Should have correct service configuration");
        assertNotNull(capturedContext.getPayload(), "Should have payload from C8Y message");

        log.info("✅ Basic outbound message processing test passed");
        log.info("   - Tenant: {}", capturedContext.getTenant());
        log.info("   - Mapping: {}", capturedContext.getMapping().getName());
        log.info("   - Source ID: {}", c8yMessage.getSourceId());
        log.info("   - Device Name: {}", c8yMessage.getDeviceName());
        log.info("   - API: {}", c8yMessage.getApi());
    }

    @Test
    void testProcessEventMessage() throws Exception {
        // Given - Event message
        c8yMessage.setApi(API.EVENT);
        c8yMessage.setOperation("CREATE");
        String eventPayloadJson = createEventPayloadJson();
        Map<String, Object> eventPayloadMap = createEventPayloadMap();
        c8yMessage.setPayload(eventPayloadJson);
        c8yMessage.setParsedPayload(eventPayloadMap);

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should not be null");
        assertNotNull(capturedContext.getPayload(), "Should have event payload");

        log.info("✅ Event message processing test passed");
        log.info("   - API: {}", c8yMessage.getApi());
        log.info("   - Operation: {}", c8yMessage.getOperation());
    }

    @Test
    void testProcessAlarmMessage() throws Exception {
        // Given - Alarm message
        c8yMessage.setApi(API.ALARM);
        c8yMessage.setOperation("UPDATE");
        String alarmPayloadJson = createAlarmPayloadJson();
        Map<String, Object> alarmPayloadMap = createAlarmPayloadMap();
        c8yMessage.setPayload(alarmPayloadJson);
        c8yMessage.setParsedPayload(alarmPayloadMap);

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should not be null");
        assertEquals(alarmPayloadMap, capturedContext.getPayload(), "Should have alarm payload from parsed payload");

        log.info("✅ Alarm message processing test passed");
        log.info("   - API: {}", c8yMessage.getApi());
        log.info("   - Operation: {}", c8yMessage.getOperation());
    }

    @Test
    void testProcessInventoryMessage() throws Exception {
        // Given - Inventory message
        c8yMessage.setApi(API.INVENTORY);
        c8yMessage.setOperation("UPDATE");
        String inventoryPayloadJson = createInventoryPayloadJson();
        Map<String, Object> inventoryPayloadMap = createInventoryPayloadMap();
        c8yMessage.setPayload(inventoryPayloadJson);
        c8yMessage.setParsedPayload(inventoryPayloadMap);

        // Update mapping for inventory
        mapping.setTargetAPI(API.INVENTORY);

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should not be null");
        assertEquals(inventoryPayloadMap, capturedContext.getPayload(), "Should have inventory payload");

        log.info("✅ Inventory message processing test passed");
    }

    @Test
    void testProcessWithSendPayloadFalse() throws Exception {
        // Given - Message with sendPayload = false
        c8yMessage.setSendPayload(false);

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should not be null");
        // The payload should still be available since it's passed through the C8Y
        // message
        assertNotNull(capturedContext.getPayload(), "Should still have payload");

        log.info("✅ SendPayload false test passed");
    }

    @Test
    void testProcessWithNullC8YMessage() throws Exception {
        // Given - Null C8Y message
        when(message.getHeader("c8yMessage", C8YMessage.class)).thenReturn(null);

        // When & Then - Should throw exception due to null tenant
        assertThrows(Exception.class, () -> processor.process(exchange),
                "Should throw exception with null C8Y message");

        log.info("✅ Null C8Y message handling test passed");
    }

    @Test
    void testProcessWithNullMapping() throws Exception {
        // Given - Null mapping
        when(message.getBody(Mapping.class)).thenReturn(null);

        // When & Then - Should throw exception
        assertThrows(Exception.class, () -> processor.process(exchange),
                "Should throw exception with null mapping");

        log.info("✅ Null mapping handling test passed");
    }

    @Test
    void testProcessWithNullServiceConfiguration() throws Exception {
        // Given - Null service configuration
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(null);

        // When - Should not throw exception
        assertDoesNotThrow(() -> processor.process(exchange),
                "Processor should handle null service configuration gracefully");

        // Then - Verify processing context is still created
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should be created");
        assertEquals(TEST_TENANT, capturedContext.getTenant(), "Should have correct tenant from C8Y message");
        assertEquals(mapping, capturedContext.getMapping(), "Should have correct mapping");

        log.info("✅ Null service configuration handling test passed - graceful handling");
    }

    @Test
    void testProcessWithDifferentOperations() throws Exception {
        // Test CREATE operation
        c8yMessage.setOperation("CREATE");
        processor.process(exchange);

        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message, times(1)).setHeader(eq("processingContext"), contextCaptor.capture());

        // Test UPDATE operation
        reset(message);
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("c8yMessage", C8YMessage.class)).thenReturn(c8yMessage);
        when(message.getBody(Mapping.class)).thenReturn(mapping);
        when(message.getHeader("serviceConfiguration", ServiceConfiguration.class)).thenReturn(serviceConfiguration);
        when(message.getHeader("testing", Boolean.class)).thenReturn(Boolean.FALSE);

        c8yMessage.setOperation("UPDATE");
        processor.process(exchange);

        verify(message, times(1)).setHeader(eq("processingContext"), any(ProcessingContext.class));

        log.info("✅ Different operations test passed");
        log.info("   - Tested CREATE and UPDATE operations");
    }

    @Test
    void testProcessWithDifferentTenants() throws Exception {
        // Given - Different tenant
        String differentTenant = "differentTenant";
        c8yMessage.setTenant(differentTenant);

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertEquals(differentTenant, capturedContext.getTenant(),
                "Should use tenant from C8Y message");

        log.info("✅ Different tenant processing test passed");
    }

    @Test
    void testProcessWithDebugMapping() throws Exception {
        // Given - Debug enabled mapping
        mapping.setDebug(true);

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertTrue(capturedContext.getMapping().getDebug(), "Debug should be enabled");

        log.info("✅ Debug mapping processing test passed");
    }

    @Test
    void testProcessWithComplexParsedPayload() throws Exception {
        // Given - Complex parsed payload with nested objects and arrays
        Map<String, Object> complexPayload = createComplexPayloadMap();
        c8yMessage.setParsedPayload(complexPayload);
        c8yMessage.setPayload(createComplexPayloadJson());

        // When
        processor.process(exchange);

        // Then
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext, "Processing context should not be null");
        assertEquals(complexPayload, capturedContext.getPayload(), "Should use parsed payload");

        // Verify nested structure is maintained
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) capturedContext.getPayload();
        assertTrue(payloadMap.containsKey("measurements"), "Should contain measurements array");
        assertTrue(payloadMap.containsKey("metadata"), "Should contain metadata object");

        log.info("✅ Complex parsed payload processing test passed");
    }

    @Test
    void testProcessingContextCreation() throws Exception {
        // When
        processor.process(exchange);

        // Then - Verify all context components
        ArgumentCaptor<ProcessingContext> contextCaptor = ArgumentCaptor.forClass(ProcessingContext.class);
        verify(message).setHeader(eq("processingContext"), contextCaptor.capture());

        ProcessingContext<Object> capturedContext = contextCaptor.getValue();

        // Verify basic properties
        assertEquals(TEST_TENANT, capturedContext.getTenant(), "Tenant should match C8Y message");
        assertEquals(mapping, capturedContext.getMapping(), "Mapping should be set");
        assertEquals(serviceConfiguration, capturedContext.getServiceConfiguration(),
                "Service configuration should be set");
        assertNotNull(capturedContext.getPayload(), "Payload should be set");

        // Verify context is initialized properly
        assertNotNull(capturedContext.getProcessingCache(), "Processing cache should be initialized");
        assertNotNull(capturedContext.getRequests(), "Requests list should be initialized");
        assertNotNull(capturedContext.getErrors(), "Errors list should be initialized");

        assertTrue(capturedContext.getProcessingCache().isEmpty(),
                "Processing cache should be empty initially");
        assertTrue(capturedContext.getRequests().isEmpty(),
                "Requests should be empty initially");
        assertTrue(capturedContext.getErrors().isEmpty(),
                "Errors should be empty initially");

        log.info("✅ Processing context creation test passed");
        log.info("   - Context initialized with all required components");
        log.info("   - Source ID: {}", c8yMessage.getSourceId());
        log.info("   - Device Name: {}", c8yMessage.getDeviceName());
        log.info("   - Message ID: {}", c8yMessage.getMessageId());
        log.info("   - API: {}", c8yMessage.getApi());
        log.info("   - Operation: {}", c8yMessage.getOperation());
        log.info("   - Send Payload: {}", c8yMessage.isSendPayload());
    }

    // Helper methods for creating test payloads

    private String createEventPayloadJson() {
        return "{\n" +
                "  \"id\": \"event123\",\n" +
                "  \"time\": \"2025-09-17T12:40:36.383+02:00\",\n" +
                "  \"type\": \"c8y_LocationUpdate\",\n" +
                "  \"text\": \"Device location updated\",\n" +
                "  \"source\": {\n" +
                "    \"id\": \"" + TEST_DEVICE_ID + "\"\n" +
                "  }\n" +
                "}";
    }

    private Map<String, Object> createEventPayloadMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "event123");
        payload.put("time", "2025-09-17T12:40:36.383+02:00");
        payload.put("type", "c8y_LocationUpdate");
        payload.put("text", "Device location updated");

        Map<String, Object> source = new HashMap<>();
        source.put("id", TEST_DEVICE_ID);
        payload.put("source", source);

        return payload;
    }

    private String createAlarmPayloadJson() {
        return "{\n" +
                "  \"id\": \"alarm123\",\n" +
                "  \"time\": \"2025-09-17T12:40:36.383+02:00\",\n" +
                "  \"type\": \"c8y_TemperatureAlarm\",\n" +
                "  \"text\": \"Temperature too high\",\n" +
                "  \"severity\": \"MAJOR\",\n" +
                "  \"status\": \"ACTIVE\",\n" +
                "  \"source\": {\n" +
                "    \"id\": \"" + TEST_DEVICE_ID + "\"\n" +
                "  }\n" +
                "}";
    }

    private Map<String, Object> createAlarmPayloadMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "alarm123");
        payload.put("time", "2025-09-17T12:40:36.383+02:00");
        payload.put("type", "c8y_TemperatureAlarm");
        payload.put("text", "Temperature too high");
        payload.put("severity", "MAJOR");
        payload.put("status", "ACTIVE");

        Map<String, Object> source = new HashMap<>();
        source.put("id", TEST_DEVICE_ID);
        payload.put("source", source);

        return payload;
    }

    private String createInventoryPayloadJson() {
        return "{\n" +
                "  \"id\": \"" + TEST_DEVICE_ID + "\",\n" +
                "  \"name\": \"" + TEST_DEVICE_NAME + "\",\n" +
                "  \"type\": \"c8y_TemperatureSensor\",\n" +
                "  \"c8y_IsDevice\": {},\n" +
                "  \"c8y_Position\": {\n" +
                "    \"lat\": 52.5200,\n" +
                "    \"lng\": 13.4050\n" +
                "  }\n" +
                "}";
    }

    private Map<String, Object> createInventoryPayloadMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", TEST_DEVICE_ID);
        payload.put("name", TEST_DEVICE_NAME);
        payload.put("type", "c8y_TemperatureSensor");

        Map<String, Object> c8yIsDevice = new HashMap<>();
        payload.put("c8y_IsDevice", c8yIsDevice);

        Map<String, Object> position = new HashMap<>();
        position.put("lat", 52.5200);
        position.put("lng", 13.4050);
        payload.put("c8y_Position", position);

        return payload;
    }

    private String createComplexPayloadJson() {
        return "{\n" +
                "  \"id\": \"complex123\",\n" +
                "  \"time\": \"2025-09-17T12:40:36.383+02:00\",\n" +
                "  \"measurements\": [\n" +
                "    {\n" +
                "      \"type\": \"temperature\",\n" +
                "      \"value\": 25.5,\n" +
                "      \"unit\": \"°C\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"type\": \"humidity\",\n" +
                "      \"value\": 60.0,\n" +
                "      \"unit\": \"%\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"metadata\": {\n" +
                "    \"version\": \"1.0\",\n" +
                "    \"protocol\": \"MQTT\",\n" +
                "    \"device\": {\n" +
                "      \"manufacturer\": \"Acme Corp\",\n" +
                "      \"model\": \"TempSensor-v2\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    private Map<String, Object> createComplexPayloadMap() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "complex123");
        payload.put("time", "2025-09-17T12:40:36.383+02:00");

        // Add array of measurements
        List<Map<String, Object>> measurements = new ArrayList<>();
        Map<String, Object> tempMeasurement = new HashMap<>();
        tempMeasurement.put("type", "temperature");
        tempMeasurement.put("value", 25.5);
        tempMeasurement.put("unit", "°C");
        measurements.add(tempMeasurement);

        Map<String, Object> humidityMeasurement = new HashMap<>();
        humidityMeasurement.put("type", "humidity");
        humidityMeasurement.put("value", 60.0);
        humidityMeasurement.put("unit", "%");
        measurements.add(humidityMeasurement);

        payload.put("measurements", measurements);

        // Add nested metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1.0");
        metadata.put("protocol", "MQTT");

        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("manufacturer", "Acme Corp");
        deviceInfo.put("model", "TempSensor-v2");
        metadata.put("device", deviceInfo);

        payload.put("metadata", metadata);

        return payload;
    }
}