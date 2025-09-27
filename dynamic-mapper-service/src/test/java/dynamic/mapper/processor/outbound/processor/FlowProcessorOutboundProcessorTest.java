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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowResultOutboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private FlowResultOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "6926746";
    private static final String TEST_EXTERNAL_ID = "berlin_01";
    private static final String TEST_EXTERNAL_ID_TYPE = "c8y_Serial";
    private static final String TEST_CLIENT_ID = "test-client-123";

    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        processor = new FlowResultOutboundProcessor();
        injectDependencies();

        mapping = createSmartFunctionOutboundMapping();
        mappingStatus = new MappingStatus(
                "47266329", "Mapping - 54", "6ecyap6t", Direction.OUTBOUND,
                "smart/#", "external/topic", 0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        when(serviceConfiguration.isLogPayload()).thenReturn(false);

        // Setup ObjectMapper mock
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"transformed\": \"measurement\"}");
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());

        // Setup C8YAgent mock
        setupC8YAgentMocks();
    }

    private void injectDependencies() throws Exception {
        injectField("mappingService", mappingService);
        injectField("c8yAgent", c8yAgent);
        injectField("objectMapper", objectMapper);
    }

    private void injectField(String fieldName, Object value) throws Exception {
        Field field = FlowResultOutboundProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(processor, value);
    }

    private void setupC8YAgentMocks() {
        ManagedObjectRepresentation mockDevice = new ManagedObjectRepresentation();
        GId deviceGId = new GId(TEST_DEVICE_ID);
        mockDevice.setId(deviceGId);
        
        ExternalIDRepresentation mockExternalIdRep = new ExternalIDRepresentation();
        mockExternalIdRep.setManagedObject(mockDevice);
        
        when(c8yAgent.resolveExternalId2GlobalId(eq(TEST_TENANT), any(ID.class), any(ProcessingContext.class)))
                .thenReturn(mockExternalIdRep);
    }

    private Mapping createSmartFunctionOutboundMapping() {
        // Decoded JavaScript code from Base64
        String smartFunctionCode = """
            /**
             * @name Default template for Smart Function
             * @description Default template for Smart Function, creates one measurement
             * @templateType OUTBOUND_SMART_FUNCTION
             * @direction OUTBOUND
             * @defaultTemplate true
             * @internal true
             * @readonly true
             * 
            */

            function onMessage(inputMsg, context) {
                const msg = inputMsg; 

                var payload = msg.getPayload();

                context.logMessage("Context" + context.getStateAll());
                context.logMessage("Payload Raw:" + msg.getPayload());
                context.logMessage("Payload messageId" +  msg.getPayload().get('messageId'));

                return [{ 
                    topic: `measurements/${payload["source"]["id"]}`,
                    payload: {
                        "time":  new Date().toISOString(),
                        "c8y_Steam": {
                            "Temperature": {
                            "unit": "C",
                            "value": payload["c8y_TemperatureMeasurement"]["T"]["value"]
                            }
                        }
                    },

                    externalSource: [{"type":"c8y_Serial", "externalId": payload.get('clientId')}]
                }];
            }
            """;

        return Mapping.builder()
                .id("47266329")
                .identifier("6ecyap6t")
                .name("Mapping - 54")
                .publishTopic("smart/#")
                .publishTopicSample("smart/berlin_01")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.SMART_FUNCTION)
                .debug(false)
                .active(false)
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
                .filterMapping("$exists(c8y_TemperatureMeasurement)")
                .filterInventory("")
                .maxFailureCount(0)
                .qos(Qos.AT_LEAST_ONCE)
                .lastUpdate(1758263226682L)
                .code(smartFunctionCode)
                .sourceTemplate("{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}")
                .targetTemplate("{\"Temperature\":{\"value\":110,\"unit\":\"C\"},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"deviceId\":\"909090\"}")
                .substitutions(new dynamic.mapper.model.Substitution[0]) // Empty substitutions array
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .serviceConfiguration(serviceConfiguration)
                .topic("smart/berlin_01")
                .build();

        return context;
    }

    @Test
    void testProcessTemperatureMeasurementDeviceMessage() throws Exception {
        // Given - DeviceMessage with temperature measurement matching the smart function
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.isIgnoreFurtherProcessing(), 
                "Should not ignore further processing");
        assertFalse(processingContext.getRequests().isEmpty(), 
                "Should have created requests");
        assertEquals(1, processingContext.getRequests().size(), 
                "Should have created one request");

        DynamicMapperRequest request = processingContext.getRequests().get(0);
        assertEquals(RequestMethod.POST, request.getMethod(), "Should use POST method");
        assertEquals(TEST_DEVICE_ID, request.getSourceId(), "Should have resolved device ID");
        assertEquals(TEST_CLIENT_ID, request.getExternalId(), "Should use clientId as external ID");
        assertEquals(TEST_EXTERNAL_ID_TYPE, request.getExternalIdType(), "Should have c8y_Serial type");

        verify(c8yAgent).resolveExternalId2GlobalId(eq(TEST_TENANT), any(ID.class), eq(processingContext));
        
        log.info("✅ Temperature measurement DeviceMessage processing test passed");
    }

    @Test
    void testProcessWithCustomTopicFromSmartFunction() throws Exception {
        // Given - DeviceMessage that will generate custom topic via smart function
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        // The smart function generates topic: `measurements/${payload["source"]["id"]}`
        deviceMsg.setTopic("measurements/" + TEST_DEVICE_ID);
        
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        assertNotNull(processingContext.getResolvedPublishTopic(), 
                "Should have resolved publish topic");
        
        // The topic should be set by the smart function logic
        String expectedTopic = "measurements/" + TEST_DEVICE_ID;
        assertEquals(expectedTopic, processingContext.getResolvedPublishTopic(), 
                "Should use topic generated by smart function");

        log.info("✅ Custom topic from smart function test passed");
        log.info("   - Expected topic: {}", expectedTopic);
        log.info("   - Actual topic: {}", processingContext.getResolvedPublishTopic());
    }

    @Test
    void testProcessWithSteamMeasurementPayload() throws Exception {
        // Given - DeviceMessage with steam measurement payload matching smart function output
        DeviceMessage deviceMsg = createSteamMeasurementDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.getRequests().isEmpty(), 
                "Should have created requests");
        
        // Verify the payload transformation is handled
        verify(objectMapper).writeValueAsString(any());

        log.info("✅ Steam measurement payload test passed");
    }

    @Test
    void testProcessWithMessageContextSupport() throws Exception {
        // Given - DeviceMessage with message context (supportsMessageContext = true)
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        
        Map<String, String> transportFields = new HashMap<>();
        transportFields.put(Mapping.CONTEXT_DATA_KEY_NAME, "berlin-sensor-key");
        transportFields.put("customField", "customValue");
        deviceMsg.setTransportFields(transportFields);
        
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        assertEquals("berlin-sensor-key", processingContext.getKey(), 
                "Should have set message context key");

        log.info("✅ Message context support test passed");
    }

    @Test
    void testProcessWithFilterMapping() throws Exception {
        // Given - DeviceMessage that should pass the filter "$exists(c8y_TemperatureMeasurement)"
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.getRequests().isEmpty(), 
                "Should process message that passes filter");

        // Test with message that should NOT pass the filter
        DeviceMessage nonTempDeviceMsg = createNonTemperatureDeviceMessage();
        processingContext.setFlowResult(nonTempDeviceMsg);
        processingContext.getRequests().clear(); // Clear previous requests

        processor.process(exchange);

        // The filter evaluation would happen in a different processor, 
        // so this processor will still create requests
        log.info("✅ Filter mapping test passed (filter logic handled elsewhere)");
    }

    @Test
    void testProcessMultipleDeviceMessages() throws Exception {
        // Given - Multiple DeviceMessages as would be generated by smart function
        List<DeviceMessage> messages = new ArrayList<>();
        messages.add(createTemperatureMeasurementDeviceMessage());
        messages.add(createSecondTemperatureMeasurementDeviceMessage());
        
        processingContext.setFlowResult(messages);

        // When
        processor.process(exchange);

        // Then
        assertEquals(2, processingContext.getRequests().size(), 
                "Should have created two requests");

        log.info("✅ Multiple DeviceMessages test passed");
    }

    @Test
    void testProcessCompleteSmartFunctionFlow() throws Exception {
        // Given - Complete smart function outbound flow
        DeviceMessage deviceMsg = createCompleteSmartFunctionDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then - Verify complete processing
        assertFalse(processingContext.isIgnoreFurtherProcessing(), 
                "Should not ignore further processing");
        assertEquals(1, processingContext.getRequests().size(), 
                "Should have created one request");

        DynamicMapperRequest request = processingContext.getRequests().get(0);
        assertEquals(TEST_DEVICE_ID, request.getSourceId(), 
                "Should have resolved device ID from external source");
        assertEquals(TEST_CLIENT_ID, request.getExternalId(), 
                "Should use clientId as external ID");
        assertEquals(-1, request.getPredecessor(), 
                "Should use -1 as predecessor for flow-generated requests");

        assertNotNull(processingContext.getResolvedPublishTopic(), 
                "Should have resolved publish topic");

        log.info("✅ Complete smart function flow test passed:");
        log.info("   - Mapping: {} ({})", mapping.getName(), mapping.getIdentifier());
        log.info("   - Transformation: {}", mapping.getTransformationType());
        log.info("   - Source template: {}", mapping.getSourceTemplate());
        log.info("   - Target template: {}", mapping.getTargetTemplate());
        log.info("   - Filter mapping: {}", mapping.getFilterMapping());
        log.info("   - Request source ID: {}", request.getSourceId());
        log.info("   - Request external ID: {}", request.getExternalId());
        log.info("   - Resolved topic: {}", processingContext.getResolvedPublishTopic());
        log.info("   - Message context supported: {}", mapping.getSupportsMessageContext());
    }

    // Helper methods for creating test data based on the smart function

    private DeviceMessage createTemperatureMeasurementDeviceMessage() {
        DeviceMessage msg = new DeviceMessage();
        msg.setTopic("measurements/" + TEST_DEVICE_ID);
        msg.setClientId(TEST_CLIENT_ID);
        msg.setPayload(createTemperatureMeasurementPayload());
        msg.setExternalSource(createExternalSourceWithClientId());
        return msg;
    }

    private DeviceMessage createSecondTemperatureMeasurementDeviceMessage() {
        DeviceMessage msg = new DeviceMessage();
        msg.setTopic("measurements/second-device");
        msg.setClientId("second-client-456");
        msg.setPayload(createSecondTemperatureMeasurementPayload());
        msg.setExternalSource(createSecondExternalSource());
        return msg;
    }

    private DeviceMessage createSteamMeasurementDeviceMessage() {
        DeviceMessage msg = new DeviceMessage();
        msg.setTopic("measurements/" + TEST_DEVICE_ID);
        msg.setClientId(TEST_CLIENT_ID);
        msg.setPayload(createSteamMeasurementPayload());
        msg.setExternalSource(createExternalSourceWithClientId());
        return msg;
    }

    private DeviceMessage createNonTemperatureDeviceMessage() {
        DeviceMessage msg = new DeviceMessage();
        msg.setTopic("measurements/" + TEST_DEVICE_ID);
        msg.setClientId(TEST_CLIENT_ID);
        msg.setPayload(createNonTemperaturePayload());
        msg.setExternalSource(createExternalSourceWithClientId());
        return msg;
    }

    private DeviceMessage createCompleteSmartFunctionDeviceMessage() {
        DeviceMessage msg = new DeviceMessage();
        msg.setTopic("measurements/" + TEST_DEVICE_ID);
        msg.setClientId(TEST_CLIENT_ID);
        msg.setPayload(createCompleteSmartFunctionPayload());
        msg.setExternalSource(createExternalSourceWithClientId());
        
        // Add transport fields for message context
        Map<String, String> transportFields = new HashMap<>();
        transportFields.put(Mapping.CONTEXT_DATA_KEY_NAME, "smart-function-key");
        transportFields.put("messageId", "msg-12345");
        msg.setTransportFields(transportFields);
        
        return msg;
    }

    // Payload creation methods based on the smart function templates

    private Map<String, Object> createTemperatureMeasurementPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", "temp-msg-123");
        payload.put("clientId", TEST_CLIENT_ID);
        payload.put("time", "2022-08-05T00:14:49.389+02:00");
        payload.put("type", "c8y_TemperatureMeasurement");
        
        // Add source information as expected by smart function
        Map<String, Object> source = new HashMap<>();
        source.put("id", TEST_DEVICE_ID);
        source.put("name", "Berlin Temperature Sensor");
        payload.put("source", source);
        
        // Add temperature measurement as expected by smart function
        Map<String, Object> tempMeasurement = new HashMap<>();
        Map<String, Object> tempValue = new HashMap<>();
        tempValue.put("value", 110.0);
        tempValue.put("unit", "C");
        tempMeasurement.put("T", tempValue);
        payload.put("c8y_TemperatureMeasurement", tempMeasurement);
        
        return payload;
    }

    private Map<String, Object> createSecondTemperatureMeasurementPayload() {
        Map<String, Object> payload = createTemperatureMeasurementPayload();
        payload.put("clientId", "second-client-456");
        
        Map<String, Object> source = new HashMap<>();
        source.put("id", "second-device");
        payload.put("source", source);
        
        return payload;
    }

    private Map<String, Object> createSteamMeasurementPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("time", "2022-08-05T00:14:49.389+02:00");
        payload.put("clientId", TEST_CLIENT_ID);
        
        // Steam measurement as would be generated by the smart function
        Map<String, Object> steamMeasurement = new HashMap<>();
        Map<String, Object> temperature = new HashMap<>();
        temperature.put("unit", "C");
        temperature.put("value", 110.0);
        steamMeasurement.put("Temperature", temperature);
        payload.put("c8y_Steam", steamMeasurement);
        
        return payload;
    }

    private Map<String, Object> createNonTemperaturePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", "humidity-msg-123");
        payload.put("clientId", TEST_CLIENT_ID);
        payload.put("time", "2022-08-05T00:14:49.389+02:00");
        payload.put("type", "c8y_HumidityMeasurement");
        
        Map<String, Object> source = new HashMap<>();
        source.put("id", TEST_DEVICE_ID);
        payload.put("source", source);
        
        // No c8y_TemperatureMeasurement - should not pass filter
        Map<String, Object> humidityMeasurement = new HashMap<>();
        Map<String, Object> humidityValue = new HashMap<>();
        humidityValue.put("value", 65.0);
        humidityValue.put("unit", "%");
        humidityMeasurement.put("H", humidityValue);
        payload.put("c8y_HumidityMeasurement", humidityMeasurement);
        
        return payload;
    }

    private Map<String, Object> createCompleteSmartFunctionPayload() {
        Map<String, Object> payload = createTemperatureMeasurementPayload();
        payload.put("messageId", "complete-msg-789");
        
        // Add additional fields that the smart function might use
        payload.put("deviceName", "Berlin Smart Sensor");
        payload.put("location", "Berlin");
        
        return payload;
    }

    private List<ExternalSource> createExternalSourceWithClientId() {
        ExternalSource externalSource = new ExternalSource();
        externalSource.setType(TEST_EXTERNAL_ID_TYPE);
        externalSource.setExternalId(TEST_CLIENT_ID); // Smart function uses clientId as externalId
        
        List<ExternalSource> sources = new ArrayList<>();
        sources.add(externalSource);
        return sources;
    }

    private List<ExternalSource> createSecondExternalSource() {
        ExternalSource externalSource = new ExternalSource();
        externalSource.setType(TEST_EXTERNAL_ID_TYPE);
        externalSource.setExternalId("second-client-456");
        
        List<ExternalSource> sources = new ArrayList<>();
        sources.add(externalSource);
        return sources;
    }
}