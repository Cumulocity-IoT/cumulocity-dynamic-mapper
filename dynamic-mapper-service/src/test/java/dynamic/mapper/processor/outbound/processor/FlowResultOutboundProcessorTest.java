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
import dynamic.mapper.processor.inbound.processor.ProcessorTestHelper;
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

    private TestableFlowResultOutboundProcessor processor;

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
    // Create testable processor with simplified processing enabled
    processor = new TestableFlowResultOutboundProcessor()
            .withDefaultDeviceId(TEST_DEVICE_ID)
            .withSimplifiedProcessing(true);  // <-- ADD THIS!
    
    injectDependencies();

    mapping = createSmartFunctionOutboundMapping();
    mappingStatus = new MappingStatus(
            "47266329", "Mapping - 54", "6ecyap6t", Direction.OUTBOUND,
            "smart/#", "external/topic", 0L, 0L, 0L, 0L, 0L, null);

    // Create fresh processing context for each test
    processingContext = createProcessingContext();

    // Setup basic mocks
    when(exchange.getIn()).thenReturn(message);
    when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
    when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
    when(serviceConfiguration.getLogPayload()).thenReturn(false);

    // Setup ObjectMapper mock - IMPORTANT: We need this for payload conversion
    when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
        Object arg = invocation.getArgument(0);
        try {
            return new ObjectMapper().writeValueAsString(arg);
        } catch (Exception e) {
            return "{\"test\": \"payload\"}";
        }
    });
    
    when(objectMapper.convertValue(any(), eq(Map.class))).thenAnswer(invocation -> {
        Object arg = invocation.getArgument(0);
        if (arg instanceof Map) {
            return new HashMap<>((Map<?, ?>) arg);
        }
        return new HashMap<>();
    });

    // Setup C8YAgent mock
    setupC8YAgentMocks();
    
    // Reset Mockito invocations
    clearInvocations(mappingService, c8yAgent, objectMapper);
}

    private void injectDependencies() throws Exception {
        ProcessorTestHelper.injectField(processor, "mappingService", mappingService);
        ProcessorTestHelper.injectField(processor, "c8yAgent", c8yAgent);
        ProcessorTestHelper.injectField(processor, "objectMapper", objectMapper);
    }

    private void setupC8YAgentMocks() {
        ManagedObjectRepresentation mockDevice = new ManagedObjectRepresentation();
        GId deviceGId = new GId(TEST_DEVICE_ID);
        mockDevice.setId(deviceGId);

        ExternalIDRepresentation mockExternalIdRep = new ExternalIDRepresentation();
        mockExternalIdRep.setManagedObject(mockDevice);

        when(c8yAgent.resolveExternalId2GlobalId(eq(TEST_TENANT), any(ID.class), any(Boolean.class)))
                .thenReturn(mockExternalIdRep);
    }

    private Mapping createSmartFunctionOutboundMapping() {
        String smartFunctionCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();

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
                .sourceTemplate(
                        "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}")
                .targetTemplate(
                        "{\"Temperature\":{\"value\":110,\"unit\":\"C\"},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"deviceId\":\"909090\"}")
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
        // Clear any existing requests
        processingContext.getRequests().clear();

        log.info("=== Starting testProcessSingleDeviceMessage ===");
        log.info("Initial requests count: {}", processingContext.getRequests().size());

        // Given - Single DeviceMessage in flow result
        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        processor.process(exchange);

        // Then
        log.info("Final requests count: {}", processingContext.getRequests().size());
        processingContext.getRequests().forEach(req -> log.info("Request: sourceId={}, externalId={}, method={}",
                req.getSourceId(), req.getExternalId(), req.getMethod()));

        assertFalse(processingContext.getIgnoreFurtherProcessing(),
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
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
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
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
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
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
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
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should ignore further processing when no DeviceMessages");
        assertTrue(processingContext.getRequests().isEmpty(),
                "Should not create any requests");

        log.info("✅ Non-DeviceMessage test passed");
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

    @Test
    void testProcessWithCustomDeviceResolver() throws Exception {
        // Given - Create a NEW processor with custom resolver
        String customDeviceId = "custom-device-999";

        TestableFlowResultOutboundProcessor customProcessor = new TestableFlowResultOutboundProcessor()
                .withDefaultDeviceId(customDeviceId)
                .withDeviceResolver((externalSource, context, tenant) -> {
                    log.info("Custom device resolver called for externalId: {}, returning: {}",
                            externalSource.getExternalId(), customDeviceId);
                    return customDeviceId;
                });

        // Inject dependencies into the custom processor
        ProcessorTestHelper.injectField(customProcessor, "mappingService", mappingService);
        ProcessorTestHelper.injectField(customProcessor, "c8yAgent", c8yAgent);
        ProcessorTestHelper.injectField(customProcessor, "objectMapper", objectMapper);

        DeviceMessage deviceMsg = createTemperatureMeasurementDeviceMessage();
        processingContext.setFlowResult(deviceMsg);

        // When
        customProcessor.process(exchange);

        // Then
        log.info("Requests created: {}", processingContext.getRequests().size());
        processingContext.getRequests().forEach(
                req -> log.info("Request sourceId: {}, externalId: {}", req.getSourceId(), req.getExternalId()));

        assertFalse(processingContext.getRequests().isEmpty(),
                "Should have created requests");
        assertEquals(1, processingContext.getRequests().size(),
                "Should have created exactly one request");

        DynamicMapperRequest request = processingContext.getRequests().get(0);
        assertEquals(customDeviceId, request.getSourceId(),
                "Should use custom resolved device ID");

        log.info("✅ Custom device resolver test passed");
    }

    @Test
    void testDiagnostic_UnderstandDoubleRequests() throws Exception {
        // Clear any existing requests
        processingContext.getRequests().clear();

        log.info("=== DIAGNOSTIC TEST ===");
        log.info("Initial requests: {}", processingContext.getRequests().size());

        // Create a simple device message

        DeviceMessage deviceMsg = new DeviceMessage();
        deviceMsg.setTopic("test/topic");
        deviceMsg.setClientId("diagnostic-client");

        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "value");
        deviceMsg.setPayload(payload);

        // Single external source
        List<ExternalSource> externalSources = new ArrayList<>();
        ExternalSource es = new ExternalSource();
        es.setType("c8y_Serial");

        es.setExternalId("diagnostic-device");
        es.setClientId("diagnostic-client");
        externalSources.add(es);
        deviceMsg.setExternalSource(externalSources);

        processingContext.setFlowResult(deviceMsg);

        log.info("Before processing - requests: {}", processingContext.getRequests().size());

        // Process
        processor.process(exchange);

        log.info("After processing - requests: {}", processingContext.getRequests().size());

        // Print all requests
        for (int i = 0; i < processingContext.getRequests().size(); i++) {
            DynamicMapperRequest req = processingContext.getRequests().get(i);
            log.info("Request #{}: sourceId={}, externalId={}, method={}, api={}, predecessor={}",
                    i, req.getSourceId(), req.getExternalId(), req.getMethod(),
                    req.getApi(), req.getPredecessor());
        }

        // Don't fail, just observe
        log.info("=== END DIAGNOSTIC ===");
    }

    // Helper methods for creating test data

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

        Map<String, Object> source = new HashMap<>();
        source.put("id", TEST_DEVICE_ID);
        source.put("name", "Berlin Temperature Sensor");
        payload.put("source", source);

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
        externalSource.setExternalId(TEST_CLIENT_ID);
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