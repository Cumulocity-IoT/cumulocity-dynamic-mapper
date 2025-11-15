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
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.CumulocityType;
import dynamic.mapper.processor.flow.ExternalId;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.resolver.MappingResolverService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowResultInboundProcessorTest {

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

    @Mock
    private MappingResolverService mappingResolverService;

    private TestableFlowResultInboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "test-c8y-device-id";
    private static final String TEST_EXTERNAL_ID = "test-external-id";
    private static final String TEST_EXTERNAL_ID_TYPE = "c8y_Serial";

    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        // Create testable processor with default device ID
        processor = new TestableFlowResultInboundProcessor()
                .withDefaultDeviceId(TEST_DEVICE_ID);
        
        injectDependencies();

        mapping = createSampleMapping();
        mappingStatus = new MappingStatus(
                "test-id",
                "Test Mapping",
                "test-mapping",
                Direction.INBOUND,
                "test/topic",
                "output/topic",
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);

        // Setup ObjectMapper mock
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

    private Mapping createSampleMapping() {
        return Mapping.builder()
                .id("test-mapping-id")
                .identifier("test-mapping")
                .name("Test Mapping")
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.SMART_FUNCTION)
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .debug(true)
                .active(true)
                .tested(false)
                .supportsMessageContext(true)
                .snoopStatus(SnoopStatus.NONE)
                .snoopedTemplates(new ArrayList<>())
                .qos(Qos.AT_MOST_ONCE)
                .useExternalId(true)
                .externalIdType(TEST_EXTERNAL_ID_TYPE)
                .createNonExistingDevice(false)
                .updateExistingDevice(true)
                .lastUpdate(System.currentTimeMillis())
                .sourceTemplate("{\"ID\": \"string\", \"meas\": {}, \"ts\": \"string\"}")
                .targetTemplate("{\"source\": {\"id\": \"\"}, \"type\": \"c8y_TemperatureMeasurement\"}")
                .substitutions(new dynamic.mapper.model.Substitution[0])
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ID", TEST_EXTERNAL_ID);
        payload.put("temperature", 25.5);

        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .serviceConfiguration(serviceConfiguration)
                .payload(payload)
                .topic("test/topic")
                .clientId("test-client")
                .build();

        return context;
    }

    @Test
    void testProcessSingleCumulocityObject() throws Exception {
        // Given - Single CumulocityObject in flow result
        CumulocityObject cumulocityObj = createCumulocityObject();
        processingContext.setFlowResult(cumulocityObj);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Should not ignore further processing");
        assertFalse(processingContext.getRequests().isEmpty(),
                "Should have created C8Y requests");
        assertEquals(1, processingContext.getRequests().size(),
                "Should have created one request");

        DynamicMapperRequest request = processingContext.getRequests().get(0);
        assertEquals(API.MEASUREMENT, request.getApi(), "Should use MEASUREMENT API");
        assertEquals(RequestMethod.POST, request.getMethod(), "Should use POST method for create");
        assertEquals(TEST_DEVICE_ID, request.getSourceId(), "Should have correct source ID");

        log.info("✅ Single CumulocityObject processing test passed");
    }

    @Test
    void testProcessMultipleCumulocityObjects() throws Exception {
        // Given - List of CumulocityObjects
        List<CumulocityObject> messages = new ArrayList<>();
        messages.add(createCumulocityObject());
        messages.add(createEventCumulocityObject());
        processingContext.setFlowResult(messages);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Should not ignore further processing");
        assertEquals(2, processingContext.getRequests().size(),
                "Should have created two requests");

        DynamicMapperRequest measurementRequest = processingContext.getRequests().get(0);
        assertEquals(API.MEASUREMENT, measurementRequest.getApi(), "First request should be MEASUREMENT");

        DynamicMapperRequest eventRequest = processingContext.getRequests().get(1);
        assertEquals(API.EVENT, eventRequest.getApi(), "Second request should be EVENT");

        log.info("✅ Multiple CumulocityObjects processing test passed");
    }

    @Test
    void testProcessWithCustomDeviceResolver() throws Exception {
        // Given - Custom device resolver
        String customDeviceId = "custom-resolved-device-id";
        processor.withDeviceResolver((msg, context, tenant) -> customDeviceId);
        
        CumulocityObject cumulocityObj = createCumulocityObject();
        processingContext.setFlowResult(cumulocityObj);

        // When
        processor.process(exchange);

        // Then
        assertFalse(processingContext.getRequests().isEmpty(),
                "Should have created C8Y requests");

        DynamicMapperRequest request = processingContext.getRequests().get(0);
        assertEquals(customDeviceId, request.getSourceId(),
                "Should use custom resolved device ID");

        log.info("✅ Custom device resolver test passed");
    }

    @Test
    void testCompleteFlowProcessing() throws Exception {
        // Given - Complete flow with multiple message types
        List<Object> flowResult = new ArrayList<>();
        flowResult.add(createCumulocityObject()); // Measurement
        flowResult.add(createAlarmCumulocityObject()); // Alarm
        flowResult.add("ignored non-cumulocity message"); // Should be ignored

        processingContext.setFlowResult(flowResult);

        // When
        processor.process(exchange);

        // Then
        log.info("Ignore further processing: {}", processingContext.getIgnoreFurtherProcessing());
        log.info("Created requests: {}", processingContext.getRequests().size());
        
        processingContext.getRequests().forEach(req -> 
            log.info("Request: API={}, Method={}, SourceId={}", 
                req.getApi(), req.getMethod(), req.getSourceId())
        );
        
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Should not ignore further processing");
        assertEquals(2, processingContext.getRequests().size(),
                "Should have created two requests (ignoring non-CumulocityObject)");

        // Verify different API types
        boolean hasMeasurement = processingContext.getRequests().stream()
                .anyMatch(r -> r.getApi() == API.MEASUREMENT);
        boolean hasAlarm = processingContext.getRequests().stream()
                .anyMatch(r -> r.getApi() == API.ALARM);

        assertTrue(hasMeasurement, "Should have measurement request");
        assertTrue(hasAlarm, "Should have alarm request");

        log.info("✅ Complete flow processing test passed: {} requests generated",
                processingContext.getRequests().size());
    }

    // Helper methods for creating test data

    private CumulocityObject createCumulocityObject() {
        CumulocityObject msg = new CumulocityObject();
        msg.setCumulocityType(CumulocityType.MEASUREMENT);
        msg.setAction("create");
        msg.setPayload(createMeasurementPayload());
        msg.setExternalSource(createExternalSourceList());
        return msg;
    }

    private CumulocityObject createEventCumulocityObject() {
        CumulocityObject msg = new CumulocityObject();
        msg.setCumulocityType(CumulocityType.EVENT);
        msg.setAction("create");
        msg.setPayload(createEventPayload());
        msg.setExternalSource(createExternalSourceList());
        return msg;
    }

    private CumulocityObject createAlarmCumulocityObject() {
        CumulocityObject msg = new CumulocityObject();
        msg.setCumulocityType(CumulocityType.ALARM);
        msg.setAction("create");
        msg.setPayload(createAlarmPayload());
        msg.setExternalSource(createExternalSourceList());
        return msg;
    }

    private Map<String, Object> createMeasurementPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("time", "2024-03-19T13:30:18.619Z");
        payload.put("type", "c8y_TemperatureMeasurement");

        Map<String, Object> measurement = new HashMap<>();
        Map<String, Object> temperature = new HashMap<>();
        temperature.put("value", 25.5);
        temperature.put("unit", "°C");
        measurement.put("T", temperature);
        payload.put("c8y_TemperatureMeasurement", measurement);

        return payload;
    }

    private Map<String, Object> createEventPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("time", "2024-03-19T13:30:18.619Z");
        payload.put("type", "c8y_TestEvent");
        payload.put("text", "Test event message");
        return payload;
    }

    private Map<String, Object> createAlarmPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("time", "2024-03-19T13:30:18.619Z");
        payload.put("type", "c8y_TemperatureAlarm");
        payload.put("text", "Temperature too high");
        payload.put("severity", "MAJOR");
        payload.put("status", "ACTIVE");
        return payload;
    }

    private List<ExternalId> createExternalSourceList() {
        ExternalId externalSource = new ExternalId();
        externalSource.setType(TEST_EXTERNAL_ID_TYPE);
        externalSource.setExternalId(TEST_EXTERNAL_ID);

        List<ExternalId> sources = new ArrayList<>();
        sources.add(externalSource);
        return sources;
    }
}