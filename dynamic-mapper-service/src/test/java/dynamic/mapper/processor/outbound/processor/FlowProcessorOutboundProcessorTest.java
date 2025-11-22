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

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.DataPrepContext;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlowProcessorOutboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private Context graalContext;

    @Mock
    private Value bindings;

    @Mock
    private Value onMessageFunction;

    @Mock
    private Value resultValue;

    @InjectMocks
    private FlowProcessorOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "6926746";
    private static final String TEST_EXTERNAL_ID_TYPE = "c8y_Serial";
    private static final String TEST_CLIENT_ID = "test-client-123";

    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;
    
    // Make this a class-level field so it can be properly managed
    private MockedStatic<JavaScriptInteropHelper> mockJavaScriptInteropHelper;

    @BeforeEach
    void setUp() throws Exception {
        mapping = createSmartFunctionOutboundMapping();
        mappingStatus = new MappingStatus(
                "47266329", "Mapping - 54", "6ecyap6t", Direction.OUTBOUND,
                "smart/#", "external/topic", 0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);

        // Setup GraalVM mocks
        setupGraalVMMocks();

        // Setup static mock - only if not already created
        if (mockJavaScriptInteropHelper == null) {
            mockJavaScriptInteropHelper = mockStatic(JavaScriptInteropHelper.class);
        }
        setupJavaScriptInteropHelperMocks();
    }

    @AfterEach
    void tearDown() {
        // Properly close the static mock after each test
        if (mockJavaScriptInteropHelper != null) {
            mockJavaScriptInteropHelper.close();
            mockJavaScriptInteropHelper = null;
        }
    }

    private void setupJavaScriptInteropHelperMocks() {
        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.isDeviceMessage(any()))
                .thenReturn(true);
        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.isCumulocityObject(any()))
                .thenReturn(false);

        // Default device message
        DeviceMessage defaultDeviceMessage = new DeviceMessage();
        defaultDeviceMessage.setTopic("measurements/" + TEST_DEVICE_ID);
        defaultDeviceMessage.setClientId(TEST_CLIENT_ID);

        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.convertToDeviceMessage(any()))
                .thenReturn(defaultDeviceMessage);
    }

    private void setupGraalVMMocks() {
        // Mock GraalVM Context and JavaScript execution
        when(graalContext.getBindings("js")).thenReturn(bindings);
        when(bindings.getMember(anyString())).thenReturn(onMessageFunction);
        when(graalContext.asValue(any())).thenReturn(mock(Value.class));

        // Mock the JavaScript function execution result
        when(onMessageFunction.execute(any(), any())).thenReturn(resultValue);
        when(resultValue.hasArrayElements()).thenReturn(true);
        when(resultValue.getArraySize()).thenReturn(1L);

        // Mock the result array element
        Value deviceMessageValue = mock(Value.class);
        when(resultValue.getArrayElement(0)).thenReturn(deviceMessageValue);
    }

    private ProcessingContext<Object> createProcessingContext() {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .serviceConfiguration(serviceConfiguration)
                .topic("smart/berlin_01")
                .payload(createInputPayload())
                .build();

        // Set up the GraalVM context
        context.setGraalContext(graalContext);
        context.setFlowContext(mock(DataPrepContext.class));

        return context;
    }

    private Map<String, Object> createInputPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", "temp-msg-123");
        payload.put("clientId", TEST_CLIENT_ID);
        payload.put("time", "2022-08-05T00:14:49.389+02:00");
        payload.put("type", "c8y_TemperatureMeasurement");

        // Add temperature measurement
        Map<String, Object> tempMeasurement = new HashMap<>();
        Map<String, Object> tempValue = new HashMap<>();
        tempValue.put("value", 110.0);
        tempValue.put("unit", "C");
        tempMeasurement.put("T", tempValue);
        payload.put("c8y_TemperatureMeasurement", tempMeasurement);

        return payload;
    }

    @Test
    void testProcessSmartFunctionExecution() throws Exception {
        // When
        processor.process(exchange);

        // Then - Verify JavaScript function was called and result was processed
        verify(graalContext).eval(any(Source.class));
        verify(onMessageFunction).execute(any(), any());

        // Verify that flow result was set
        List<Object> flowResult = (List<Object>) processingContext.getFlowResult();
        assertNotNull(flowResult, "Should have set flow result");
        assertEquals(1, flowResult.size(), "Should have one result");

        log.info("✅ Smart function execution test passed");
    }

    @Test
    void testProcessWithEmptyResult() throws Exception {
        // Given - JavaScript function returns empty array
        when(resultValue.getArraySize()).thenReturn(0L);

        // When
        processor.process(exchange);

        // Then - Should ignore further processing
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should ignore further processing for empty result");

        log.info("✅ Empty result test passed");
    }

    @Test
    void testProcessWithNonArrayResult() throws Exception {
        // Given - JavaScript function returns non-array
        when(resultValue.hasArrayElements()).thenReturn(false);

        // When
        processor.process(exchange);

        // Then - Should ignore further processing
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Should not ignore further processing for non-array result");

        log.info("✅ Non-array result test passed");
    }

    @Test
    void testProcessWithMultipleResults() throws Exception {
        // Given - JavaScript function returns multiple messages
        when(resultValue.getArraySize()).thenReturn(2L);

        Value firstMessage = mock(Value.class);
        Value secondMessage = mock(Value.class);
        when(resultValue.getArrayElement(0)).thenReturn(firstMessage);
        when(resultValue.getArrayElement(1)).thenReturn(secondMessage);

        // Setup different responses for different calls
        DeviceMessage firstDeviceMsg = createDeviceMessage("device1", "client1");
        DeviceMessage secondDeviceMsg = createDeviceMessage("device2", "client2");

        // Reset the static mock to handle multiple calls differently
        mockJavaScriptInteropHelper.reset();
        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.isDeviceMessage(any())).thenReturn(true);
        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.isCumulocityObject(any())).thenReturn(false);
        
        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.convertToDeviceMessage(firstMessage))
                .thenReturn(firstDeviceMsg);
        mockJavaScriptInteropHelper.when(() -> JavaScriptInteropHelper.convertToDeviceMessage(secondMessage))
                .thenReturn(secondDeviceMsg);

        // When
        processor.process(exchange);

        // Then - Should process all messages
        List<Object> flowResult = (List<Object>) processingContext.getFlowResult();
        assertNotNull(flowResult, "Flow result should not be null");
        assertEquals(2, flowResult.size(), "Should have processed two messages");

        DeviceMessage result1 = (DeviceMessage) flowResult.get(0);
        DeviceMessage result2 = (DeviceMessage) flowResult.get(1);
        assertEquals("measurements/device1", result1.getTopic());
        assertEquals("measurements/device2", result2.getTopic());

        log.info("✅ Multiple results test passed");
    }

    @Test
    void testProcessWithSharedCode() throws Exception {
        // Given - Context with shared code
        String sharedCodeBase64 = Base64.getEncoder().encodeToString("var sharedFunction = function() {};".getBytes());
        processingContext.setSharedCode(sharedCodeBase64);

        // When
        processor.process(exchange);

        // Then - Should load shared code
        verify(graalContext, times(2)).eval(any(Source.class)); // Main code + shared code

        log.info("✅ Shared code test passed");
    }

    @Test
    void testProcessWithSystemCode() throws Exception {
        // Given - Context with system code
        String systemCodeBase64 = Base64.getEncoder().encodeToString("var systemFunction = function() {};".getBytes());
        processingContext.setSystemCode(systemCodeBase64);

        // When
        processor.process(exchange);

        // Then - Should load system code
        verify(graalContext, times(2)).eval(any(Source.class)); // Main code + system code

        log.info("✅ System code test passed");
    }

    @Test
    void testProcessHandlesJavaScriptError() throws Exception {
        // Given - JavaScript function throws error
        when(onMessageFunction.execute(any(), any())).thenThrow(new RuntimeException("JavaScript error"));

        // When
        processor.process(exchange);

        // Then - Should handle error gracefully
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), any());
        assertFalse(processingContext.getErrors().isEmpty(), "Should have recorded error");

        log.info("✅ JavaScript error handling test passed");
    }

    @Test
    void testProcessWithDebugLogging() throws Exception {
        // Given - Mapping with debug enabled
        mapping.setDebug(true);

        // When
        processor.process(exchange);

        // Then - Should process normally (debug logging is internal)
        verify(onMessageFunction).execute(any(), any());

        log.info("✅ Debug logging test passed");
    }

    private DeviceMessage createDeviceMessage(String deviceId, String clientId) {
        DeviceMessage msg = new DeviceMessage();
        msg.setTopic("measurements/" + deviceId);
        msg.setClientId(clientId);
        msg.setPayload(new HashMap<>());
        return msg;
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

        // Encode to Base64 as expected by the processor
        String encodedCode = Base64.getEncoder().encodeToString(smartFunctionCode.getBytes());

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
                .active(true)
                .tested(false)
                .code(encodedCode)
                .build();
    }
}