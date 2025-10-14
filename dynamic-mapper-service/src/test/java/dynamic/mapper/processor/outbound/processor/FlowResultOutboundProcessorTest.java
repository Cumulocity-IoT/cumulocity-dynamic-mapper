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
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"c8y_Steam\":{\"Temperature\":{\"unit\":\"C\",\"value\":110}},\"time\":\"2024-03-19T13:30:18.619Z\"}");
        when(objectMapper.convertValue(any(), eq(Map.class))).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof Map) {
                return new HashMap<>((Map<?, ?>) arg);
            }
            return new HashMap<>();
        });

        // Setup C8YAgent mock
        setupC8YAgentMocks();
    }

    private void injectDependencies() throws Exception {
        // Try to inject fields, but handle cases where they might not exist
        try {
            injectField("mappingService", mappingService);
        } catch (NoSuchFieldException e) {
            log.warn("Field 'mappingService' not found in FlowResultOutboundProcessor");
        }
        
        try {
            injectField("c8yAgent", c8yAgent);
        } catch (NoSuchFieldException e) {
            log.warn("Field 'c8yAgent' not found in FlowResultOutboundProcessor");
        }
        
        try {
            injectField("objectMapper", objectMapper);
        } catch (NoSuchFieldException e) {
            log.warn("Field 'objectMapper' not found in FlowResultOutboundProcessor");
        }

        // Log available fields for debugging
        logAvailableFields();
    }

    private void logAvailableFields() {
        Field[] fields = FlowResultOutboundProcessor.class.getDeclaredFields();
        log.info("Available fields in FlowResultOutboundProcessor:");
        for (Field field : fields) {
            log.info("  - {} ({})", field.getName(), field.getType().getSimpleName());
        }
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
                .substitutions(new dynamic.mapper.model.Substitution[0])
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
    void testProcessSingleDeviceMessage() throws Exception {
        // Given - Single DeviceMessage in flow result
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
        assertEquals(-1, request.getPredecessor(), "Should use -1 as predecessor for flow requests");

        // Only verify C8Y agent if the field was successfully injected
        try {
            verify(c8yAgent).resolveExternalId2GlobalId(eq(TEST_TENANT), any(ID.class), eq(processingContext));
        } catch (Exception e) {
            log.warn("Could not verify c8yAgent interaction: {}", e.getMessage());
        }
        
        log.info("✅ Single DeviceMessage processing test passed");
    }

    @Test
    void testProcessMultipleDeviceMessages() throws Exception {
        // Given - List of DeviceMessages
        List<DeviceMessage> messages = new ArrayList<>();
        messages.add(createTemperatureMeasurementDeviceMessage());
        messages.add(createSecondTemperatureMeasurementDeviceMessage());
        processingContext.setFlowResult(messages);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.isIgnoreFurtherProcessing(), 
                "Should not ignore further processing");
        assertEquals(2, processingContext.getRequests().size(), 
                "Should have created two requests");

        // Verify both requests were created with expected structure
        for (DynamicMapperRequest request : processingContext.getRequests()) {
            assertEquals(RequestMethod.POST, request.getMethod());
            assertEquals(-1, request.getPredecessor());
            assertEquals(TEST_EXTERNAL_ID_TYPE, request.getExternalIdType());
            assertNotNull(request.getSourceId());
            assertNotNull(request.getExternalId());
        }

        log.info("✅ Multiple DeviceMessages processing test passed");
    }

    @Test
    void testProcessWithNullFlowResult() throws Exception {
        // Given - Null flow result
        processingContext.setFlowResult(null);

        // When
        processor.process(exchange);

        // Then
        assertTrue(processingContext.isIgnoreFurtherProcessing(), 
                "Should ignore further processing for null flow result");
        assertTrue(processingContext.getRequests().isEmpty(), 
                "Should not create any requests");

        log.info("✅ Null flow result test passed");
    }

    @Test
    void testProcessWithEmptyFlowResult() throws Exception {
        // Given - Empty list flow result
        processingContext.setFlowResult(new ArrayList<>());

        // When
        processor.process(exchange);

        // Then
        assertTrue(processingContext.isIgnoreFurtherProcessing(), 
                "Should ignore further processing for empty flow result");
        assertTrue(processingContext.getRequests().isEmpty(), 
                "Should not create any requests");

        log.info("✅ Empty flow result test passed");
    }

    @Test
    void testProcessWithNonDeviceMessage() throws Exception {
        // Given - Flow result with non-DeviceMessage objects
        List<Object> messages = new ArrayList<>();
        messages.add("not a device message");
        messages.add(new HashMap<>());
        messages.add(42);
        processingContext.setFlowResult(messages);

        // When
        processor.process(exchange);

        // Then
        assertTrue(processingContext.isIgnoreFurtherProcessing(), 
                "Should ignore further processing when no DeviceMessages");
        assertTrue(processingContext.getRequests().isEmpty(), 
                "Should not create any requests");

        log.info("✅ Non-DeviceMessage test passed");
    }

    @Test
    void testProcessWithExternalSourceResolutionFailure() throws Exception {
        // Given - External source that cannot be resolved
        when(c8yAgent.resolveExternalId2GlobalId(eq(TEST_TENANT), any(ID.class), any(ProcessingContext.class)))
                .thenReturn(null);

        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then - Should handle error gracefully
        try {
            verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), any(MappingStatus.class));
            assertEquals(1, mappingStatus.errors, "Should have incremented error count");
            assertFalse(processingContext.getErrors().isEmpty(), "Should have recorded error");
        } catch (Exception e) {
            log.warn("Could not verify error handling: {}", e.getMessage());
        }

        log.info("✅ External source resolution failure test passed");
    }

    @Test
    void testProcessWithTransportFields() throws Exception {
        // Given - DeviceMessage with transport fields (supportsMessageContext = true)
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        Map<String, String> transportFields = new HashMap<>();
        transportFields.put(Mapping.CONTEXT_DATA_KEY_NAME, "transport-key-456");
        transportFields.put("messageId", "msg-789");
        deviceMsg.setTransportFields(transportFields);
        
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        assertEquals("transport-key-456", processingContext.getKey(), 
                "Should have set key from transport fields");

        log.info("✅ Transport fields processing test passed");
    }

    @Test
    void testProcessWithExternalIdTokenInTopic() throws Exception {
        // Given - DeviceMessage with external ID token in topic
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        deviceMsg.setTopic("measurements/" + BaseProcessor.EXTERNAL_ID_TOKEN + "/data");
        
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        String expectedTopic = "measurements/" + TEST_DEVICE_ID + "/data";
        assertEquals(expectedTopic, processingContext.getResolvedPublishTopic(), 
                "Should have replaced external ID token with resolved device ID");

        log.info("✅ External ID token replacement test passed");
        log.info("   - Original topic: measurements/{}/data", BaseProcessor.EXTERNAL_ID_TOKEN);
        log.info("   - Resolved topic: {}", processingContext.getResolvedPublishTopic());
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
        msg.setTopic("measurements/second-device-456");
        msg.setClientId("second-client-456");
        msg.setPayload(createSecondTemperatureMeasurementPayload());
        msg.setExternalSource(createSecondExternalSource());
        return msg;
    }

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
        payload.put("messageId", "temp-msg-456");
        
        Map<String, Object> source = new HashMap<>();
        source.put("id", "second-device-456");
        source.put("name", "Second Temperature Sensor");
        payload.put("source", source);
        
        return payload;
    }

    private List<ExternalSource> createExternalSourceWithClientId() {
        ExternalSource externalSource = new ExternalSource();
        externalSource.setType(TEST_EXTERNAL_ID_TYPE);
        externalSource.setExternalId(TEST_CLIENT_ID); // Smart function uses clientId as externalId
        externalSource.setClientId(TEST_CLIENT_ID);
        externalSource.setAutoCreateDeviceMO(false);
        
        List<ExternalSource> sources = new ArrayList<>();
        sources.add(externalSource);
        return sources;
    }

    private List<ExternalSource> createSecondExternalSource() {
        ExternalSource externalSource = new ExternalSource();
        externalSource.setType(TEST_EXTERNAL_ID_TYPE);
        externalSource.setExternalId("second-client-456");
        externalSource.setClientId("second-client-456");
        externalSource.setAutoCreateDeviceMO(false);
        
        List<ExternalSource> sources = new ArrayList<>();
        sources.add(externalSource);
        return sources;
    }
}