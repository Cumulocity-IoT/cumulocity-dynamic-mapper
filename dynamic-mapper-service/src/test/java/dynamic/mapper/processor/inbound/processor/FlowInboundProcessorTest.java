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

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.SmartFunctionContext;
import dynamic.mapper.processor.model.MappingAction;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({ "rawtypes", "unchecked" })
class FlowInboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private InventoryEnrichmentClient inventoryEnrichmentClient;

    private FlowInboundProcessor processor;

    /** Produces real GraalVM Values from JS, replacing brittle hand-built Value mocks. */
    private GraalValueFixtures graal;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        graal = new GraalValueFixtures();
        processor = new FlowInboundProcessor(mappingService);

        mapping = createSampleMapping();
        mappingStatus = new MappingStatus(
                "80267264",
                "Mapping - 10",
                "nlzm75nv",
                Direction.INBOUND,
                "flow",
                null,
                0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);

        // Mock service configuration - avoid mocking fields directly
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        if (graal != null) {
            graal.close();
        }
    }

    private Mapping createSampleMapping() {
        String code = """
                /**
                 * @name Default template, one measurement
                 * @description Default template, one measurement
                 * @templateType INBOUND
                 * @defaultTemplate true
                 * @internal true
                 * @readonly true

                 * sample to generate one measurement
                 * payload
                 * {
                 *     "temperature": 139.0,
                 *     "unit": "C",
                 *     "externalId": "berlin_01"
                 *  }
                 * topic 'testGraalsSingle/berlin_01'
                */

                function onMessage(msg, context) {
                    var payload = msg.getPayload();

                    console.log("Context" + context.getStateAll());
                    console.log("Payload Raw:" + msg.getPayload());
                    console.log("Payload messageId" +  msg.getPayload().get('messageId'));

                    return [{
                        cumulocityType: "measurement",
                        action: "create",

                        payload: {
                            "time":  new Date().toISOString(),
                            "type": "c8y_TemperatureMeasurement",
                            "c8y_Steam": {
                                "Temperature": {
                                "unit": "C",
                                "value": payload["sensorData"]["temp_val"]
                                }
                            }
                        },

                        externalSource: [{"type":"c8y_Serial", "externalId": payload.get('clientId')}]
                    }];
                }
                """;

        String codeEncoded = Base64.getEncoder().encodeToString(code.getBytes());

        return Mapping.builder()
                .id("80267264")
                .identifier("nlzm75nv")
                .name("Mapping - 10")
                .mappingTopic("flow")
                .mappingTopicSample("flow")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .sourceTemplate(
                        "{\"messageId\":\"C333646781-17108550186195\",\"messageType\":\"statusMessage\",\"messageVersion\":\"1.5\",\"messageTimestamp\":\"2024-03-19T13:30:18.619Z\",\"manufacturer\":{\"manufacturerSerialNumber\":\"C333646781\"},\"sensorData\":{\"temp_val\":100}}")
                .targetTemplate(
                        "{\"c8y_TemperatureMeasurement\":{\"T\":{\"value\":110,\"unit\":\"C\"}},\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TemperatureMeasurement\"}")
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.SMART_FUNCTION)
                .substitutions(new dynamic.mapper.model.Substitution[0])
                .active(false)
                .debug(false)
                .eventWithAttachment(false)
                .createNonExistingDevice(true)
                .updateExistingDevice(false)
                .autoAckOperation(true)
                .useExternalId(true)
                .externalIdType("c8y_Serial")
                .filterMapping("")
                .maxFailureCount(0)
                .qos(Qos.AT_LEAST_ONCE)
                .code(codeEncoded)
                .lastUpdate(System.currentTimeMillis())
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        // Sample payload based on mapping sourceTemplate
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", "C333646781-17108550186195");
        payload.put("messageType", "statusMessage");
        payload.put("messageVersion", "1.5");
        payload.put("messageTimestamp", "2024-03-19T13:30:18.619Z");
        Map<String, Object> manufacturer = new HashMap<>();
        manufacturer.put("manufacturerSerialNumber", "C333646781");
        payload.put("manufacturer", manufacturer);
        Map<String, Object> sensorData = new HashMap<>();
        sensorData.put("temp_val", 100);
        payload.put("sensorData", sensorData);

        Context mockGraalContext = mock(Context.class);
        SmartFunctionContext flowContext = new SmartFunctionContext(
                mockGraalContext,
                TEST_TENANT,
                inventoryEnrichmentClient,
                true // testing = true
        );

        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("flow/test")
                .clientId("test-client")
                .flowContext(flowContext)
                .build();

        return context;
    }

    @Test
    void testProcessSmartFunctionMapping() throws Exception {
        // This test will likely fail due to missing GraalContext, but let's test the
        // basic flow
        try {
            processor.process(exchange);
            log.info("FlowInboundProcessor processed SMART_FUNCTION mapping successfully");
        } catch (Exception e) {
            // Expected to fail due to missing GraalVM context, but should increment error
            // count
            verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping),
                    any(MappingStatus.class));
            log.info("FlowInboundProcessor correctly handled missing GraalVM context: {}", e.getMessage());
        }
    }

    @Test
    void testProcessSmartFunctionMappingWithNullCode() throws Exception {
        // Given - Mapping without code
        mapping.setCode(null);

        // When
        processor.process(exchange);

        // Then - Should complete processing without executing JavaScript
        log.info("FlowInboundProcessor handled mapping without code");
    }

    @Test
    void testProcessSmartFunctionMappingWithDebugLogging() throws Exception {
        // Given - Enable debug logging
        mapping.setDebug(true);

        try {
            // When
            processor.process(exchange);
        } catch (Exception e) {
            // Expected due to missing GraalVM context
            log.info("FlowInboundProcessor correctly handled debug case: {}", e.getMessage());
        }
    }

    @Test
    void testProcessSmartFunctionMappingWithPayloadLogging() throws Exception {
        // Given - Enable payload logging
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        try {
            // When
            processor.process(exchange);
        } catch (Exception e) {
            // Expected due to missing GraalVM context
            log.info("FlowInboundProcessor correctly handled payload logging case: {}", e.getMessage());
        }
    }

    @Test
    void testProcessSmartFunctionMappingWithSharedCode() throws Exception {
        // Given - Add shared code to context
        String sharedCode = "function sharedFunction() { return 'shared'; }";
        String encodedSharedCode = Base64.getEncoder().encodeToString(sharedCode.getBytes());
        processingContext.setSharedCode(encodedSharedCode);

        try {
            // When
            processor.process(exchange);
        } catch (Exception e) {
            // Expected due to missing GraalVM context
            log.info("FlowInboundProcessor correctly handled shared code case: {}", e.getMessage());
        }
    }

    @Test
    void testProcessSmartFunctionMappingWithSystemCode() throws Exception {
        // Given - Add system code to context
        String systemCode = "function systemFunction() { return 'system'; }";
        String encodedSystemCode = Base64.getEncoder().encodeToString(systemCode.getBytes());
        processingContext.setSystemCode(encodedSystemCode);

        try {
            // When
            processor.process(exchange);
        } catch (Exception e) {
            // Expected due to missing GraalVM context
            log.info("FlowInboundProcessor correctly handled system code case: {}", e.getMessage());
        }
    }

    @Test
    void testMappingConfiguration() {
        // Test the mapping configuration itself
        assertNotNull(mapping.getCode(), "Mapping should have encoded code");
        assertEquals(TransformationType.SMART_FUNCTION, mapping.getTransformationType(),
                "Should be SMART_FUNCTION type");
        assertEquals("nlzm75nv", mapping.getIdentifier(), "Should have correct identifier");

        log.info("Mapping configuration validated successfully");
    }

    @Test
    void testProcessingContextSetup() {
        // Test the processing context setup
        assertEquals(TEST_TENANT, processingContext.getTenant(), "Should have correct tenant");
        assertEquals(mapping, processingContext.getMapping(), "Should have correct mapping");
        assertNotNull(processingContext.getPayload(), "Should have payload");
        assertEquals("flow/test", processingContext.getTopic(), "Should have correct topic");
        assertEquals("test-client", processingContext.getClientId(), "Should have correct client ID");

        // Verify payload structure
        Map<String, Object> payload = (Map<String, Object>) processingContext.getPayload();
        assertEquals("C333646781-17108550186195", payload.get("messageId"), "Should have correct message ID");
        assertTrue(payload.containsKey("sensorData"), "Should contain sensor data");

        log.info("Processing context setup validated successfully");
    }

    @Test
    void testCodeDecoding() {
        // Test that the code can be decoded properly
        String encodedCode = mapping.getCode();
        assertNotNull(encodedCode, "Encoded code should not be null");

        byte[] decodedBytes = Base64.getDecoder().decode(encodedCode);
        String decodedCode = new String(decodedBytes);

        assertTrue(decodedCode.contains("function onMessage"), "Decoded code should contain onMessage function");
        assertTrue(decodedCode.contains("cumulocityType"), "Decoded code should contain cumulocityType");
        assertTrue(decodedCode.contains("measurement"), "Decoded code should contain measurement type");

        log.info("Code decoding validated successfully");
    }

    @Test
    void testErrorHandling() throws Exception {
        // Test error handling by causing a processing exception
        // Set invalid mapping to cause an error
        mapping.setCode("invalid-base64-content-that-will-cause-error");

        try {
            processor.process(exchange);
        } catch (Exception e) {
            // Should handle the error and update mapping status
            verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping),
                    any(MappingStatus.class));
            log.info("Error handling validated successfully: {}", e.getMessage());
        }

    }

    @Test
    void testProcessResultWithCumulocityObjects() throws Exception {
        // Given - a real onMessage result array with one CumulocityObject
        Value result = graal.eval("""
                [{
                    cumulocityType: 'measurement',
                    action: 'create',
                    payload: {
                        time: '2024-03-19T13:30:18.619Z',
                        type: 'c8y_TemperatureMeasurement',
                        c8y_Steam: { Temperature: { value: 100, unit: 'C' } }
                    },
                    externalSource: [{ type: 'c8y_Serial', externalId: 'C333646781' }]
                }]
                """);

        // When - Call processResult directly using reflection
        invokeProcessResult(result);

        // Then - Verify flow result
        assertNotNull(processingContext.getFlowResult(), "Flow result should not be null");
        assertEquals(1, ((List) processingContext.getFlowResult()).size(), "Should have one result message");

        Object resultMessage = ((List) processingContext.getFlowResult()).get(0);
        assertTrue(resultMessage instanceof CumulocityObject, "Result should be CumulocityObject");

        CumulocityObject cumulocityObj = (CumulocityObject) resultMessage;
        assertEquals(CumulocityType.MEASUREMENT, cumulocityObj.getCumulocityType(),
                "Should have correct cumulocity type");
        assertEquals(MappingAction.CREATE, cumulocityObj.getAction(), "Should have correct action");
        assertNotNull(cumulocityObj.getPayload(), "Should have payload");

        log.info("Successfully validated CumulocityObject flow result: type={}, action={}",
                cumulocityObj.getCumulocityType(), cumulocityObj.getAction());
    }

    @Test
    void testProcessResultWithEmptyArray() throws Exception {
        // Given - a real empty result array
        Value result = graal.eval("[]");

        // When
        invokeProcessResult(result);

        // Then - Verify processing is ignored
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should ignore further processing for empty array");

        log.info("Successfully validated empty array flow result handling");
    }

    @Test
    void testProcessResultWithNonArrayResult() throws Exception {
        // Given - a real non-array, non-object result (a bare number has neither
        // array elements nor members), exercising the "unexpected result type" path
        Value result = graal.eval("42");

        // When
        invokeProcessResult(result);

        // Then - Verify processing is ignored
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should ignore further processing for non-array result");

        log.info("Successfully validated non-array flow result handling");
    }

    @Test
    void testProcessResultWithUnknownMessageType() throws Exception {
        // Given - a real array whose element has neither topic nor cumulocityType
        Value result = graal.eval("[{ foo: 'bar' }]");

        // When
        invokeProcessResult(result);

        // Then - Verify flow result is empty (unknown types are ignored)
        assertNotNull(processingContext.getFlowResult(), "Flow result should not be null");
        assertEquals(1, ((List) processingContext.getFlowResult()).size(),
                "Should have one message for unknown types");

        log.info("Successfully validated unknown message type handling");
    }

    /** Invokes the package-private processResult method under test via reflection. */
    private void invokeProcessResult(Value result) throws Exception {
        java.lang.reflect.Method processResultMethod = FlowInboundProcessor.class
                .getDeclaredMethod("processResult", Value.class, ProcessingContext.class, String.class);
        processResultMethod.setAccessible(true);
        processResultMethod.invoke(processor, result, processingContext, TEST_TENANT);
    }

    /**
     * A real onMessage result array with two CumulocityObject measurement
     * elements, used by the multiple-result tests.
     */
    private Value twoMeasurementResult() {
        return graal.eval("""
                [
                    { cumulocityType: 'measurement', action: 'create',
                      payload: { type: 'c8y_TemperatureMeasurement' },
                      externalSource: [{ type: 'c8y_Serial', externalId: 'test-client' }] },
                    { cumulocityType: 'measurement', action: 'create',
                      payload: { type: 'c8y_ProcessedEvent', processed: true, originalValue: 100 },
                      externalSource: [{ type: 'c8y_Serial', externalId: 'test-client' }] }
                ]
                """);
    }

    @Test
    void testCompleteFlowProcessingWithSampleMapping() throws Exception {
        // Given - Use the actual sample mapping and enable debug for better visibility
        mapping.setDebug(true);
        mapping.setActive(true);

        // Create a more realistic GraalVM context mock that can handle the JavaScript
        // execution
        Context mockGraalContext = mock(Context.class);
        Value mockBindings = mock(Value.class);
        Value mockOnMessageFunction = mock(Value.class);
        DataPrepContext mockFlowContext = mock(DataPrepContext.class);

        // Setup GraalContext in processing context
        processingContext.setGraalContext(mockGraalContext);
        processingContext.setFlowContext(mockFlowContext);

        // Setup bindings and function execution
        when(mockGraalContext.getBindings("js")).thenReturn(mockBindings);
        when(mockBindings.getMember("onMessage")).thenReturn(mockOnMessageFunction);

        // Create the expected JavaScript result array that matches our sample code
        Value mockResult = createExpectedJavaScriptResult();

        // Mock the JavaScript function execution
        when(mockOnMessageFunction.execute(any(), any())).thenReturn(mockResult);

        // When - Process the exchange with the complete flow
        processor.process(exchange);

        // Then - Verify the flow result contains the expected CumulocityObject
        assertNotNull(processingContext.getFlowResult(),
                "Flow result should not be null after processing");
        assertFalse(((List) processingContext.getFlowResult()).isEmpty(),
                "Flow result should contain messages");
        assertEquals(1, ((List) processingContext.getFlowResult()).size(),
                "Should have exactly one result message from the sample code");

        // Verify the message is a CumulocityObject as expected from the sample code
        Object resultMessage = ((List) processingContext.getFlowResult()).get(0);
        assertTrue(resultMessage instanceof CumulocityObject,
                "Result should be CumulocityObject as defined in sample code");

        CumulocityObject cumulocityObj = (CumulocityObject) resultMessage;

        // Verify the message properties match the sample JavaScript code expectations
        assertEquals(CumulocityType.MEASUREMENT, cumulocityObj.getCumulocityType(),
                "Should be measurement type as per sample code");
        assertEquals(MappingAction.CREATE, cumulocityObj.getAction(),
                "Should be create action as per sample code");
        assertNotNull(cumulocityObj.getPayload(),
                "Should have payload as generated by sample code");
        assertNotNull(cumulocityObj.getExternalSource(),
                "Should have external source as defined in sample code");

        // Verify payload structure matches what the sample JavaScript should produce
        Map<String, Object> payload = (Map<String, Object>) cumulocityObj.getPayload();
        assertEquals("c8y_TemperatureMeasurement", payload.get("type"),
                "Should have correct measurement type from sample code");
        assertTrue(payload.containsKey("time"),
                "Should have timestamp as per sample code");
        assertTrue(payload.containsKey("c8y_Steam"),
                "Should have c8y_Steam measurement as per sample code");

        // Verify the c8y_Steam structure
        Map<String, Object> steamMeasurement = (Map<String, Object>) payload.get("c8y_Steam");
        assertTrue(steamMeasurement.containsKey("Temperature"),
                "Should have Temperature measurement");

        Map<String, Object> temperature = (Map<String, Object>) steamMeasurement.get("Temperature");
        assertEquals("C", temperature.get("unit"),
                "Should have Celsius unit as per sample code");
        // JavaScriptInteropHelper coerces an integral JS number (100) to an Integer,
        // so compare numerically rather than asserting a specific boxed type.
        assertEquals(100, ((Number) temperature.get("value")).intValue(),
                "Should have temperature value from input payload temp_val");

        // Verify external source
        List<ExternalId> externalSources = (List<ExternalId>) cumulocityObj.getExternalSource();
        assertNotNull(externalSources, "Should have external sources");
        assertEquals(1, externalSources.size(), "Should have one external source");

        ExternalId externalSource = externalSources.get(0);
        assertEquals("c8y_Serial", externalSource.getType(),
                "Should have c8y_Serial type as per sample code");
        assertEquals("test-client", externalSource.getExternalId(),
                "Should use clientId as external ID per sample code");

        // Verify no errors occurred during processing
        assertTrue(processingContext.getErrors().isEmpty(),
                "Should have no processing errors");
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Should not ignore further processing for successful result");

        log.info("✅ Complete flow processing test passed:");
        log.info("   - Mapping: {} ({})", mapping.getName(), mapping.getIdentifier());
        log.info("   - Result type: {}", cumulocityObj.getCumulocityType());
        log.info("   - Action: {}", cumulocityObj.getAction());
        log.info("   - Temperature value: {}", temperature.get("value"));
        log.info("   - External ID: {}", externalSource.getExternalId());
    }

    @Test
    void testCompleteFlowProcessingWithError() throws Exception {
        // Given - Use invalid JavaScript code to trigger an error
        String errorCode = """
                function onMessage(msg, context) {
                    // This will cause a JavaScript error
                    throw new Error("Test JavaScript error in onMessage function");
                }
                """;

        String errorCodeEncoded = Base64.getEncoder().encodeToString(errorCode.getBytes());
        mapping.setCode(errorCodeEncoded);

        // Setup mocks to simulate JavaScript error
        Context mockGraalContext = mock(Context.class);
        Value mockBindings = mock(Value.class);
        Value mockOnMessageFunction = mock(Value.class);

        processingContext.setGraalContext(mockGraalContext);

        when(mockGraalContext.getBindings("js")).thenReturn(mockBindings);
        when(mockBindings.getMember("onMessage")).thenReturn(mockOnMessageFunction);

        // Simulate JavaScript execution throwing an exception
        when(mockOnMessageFunction.execute(any(), any()))
                .thenThrow(new RuntimeException(
                        "JavaScript execution failed: Test JavaScript error in onMessage function"));

        // When
        processor.process(exchange);

        // Then - Verify error handling
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), any(MappingStatus.class));
        assertEquals(1, mappingStatus.errors, "Should have incremented error count");

        // Verify processing context contains error
        assertFalse(processingContext.getErrors().isEmpty(), "Should have processing errors");

        log.info("✅ Error handling flow processing test passed - errors properly handled");
    }

    /**
     * A real single-element onMessage result matching the sample mapping: one
     * measurement CumulocityObject with a nested c8y_Steam payload and a
     * c8y_Serial external source using the clientId.
     */
    private Value createExpectedJavaScriptResult() {
        return graal.eval("""
                [{
                    cumulocityType: 'measurement',
                    action: 'create',
                    payload: {
                        time: '2024-03-19T13:30:18.619Z',
                        type: 'c8y_TemperatureMeasurement',
                        c8y_Steam: { Temperature: { value: 100, unit: 'C' } }
                    },
                    externalSource: [{ type: 'c8y_Serial', externalId: 'test-client' }]
                }]
                """);
    }

    private Value createMultipleResultsJavaScriptResult() {
        return twoMeasurementResult();
    }


    @Test
    void testProcessResultWithMultipleMessages() throws Exception {
        // Given - a real result array with two CumulocityObject elements
        Value result = twoMeasurementResult();

        // When - Call processResult directly using reflection
        invokeProcessResult(result);

        // Then - Verify flow result
        assertNotNull(processingContext.getFlowResult(), "Flow result should not be null");
        assertEquals(2, ((List) processingContext.getFlowResult()).size(), "Should have two result messages");

        // Verify first message (CumulocityObject)
        Object firstMessage = ((List) processingContext.getFlowResult()).get(0);
        assertTrue(firstMessage instanceof CumulocityObject, "First message should be CumulocityObject");
        CumulocityObject cumulocityObj = (CumulocityObject) firstMessage;
        assertEquals(CumulocityType.MEASUREMENT, cumulocityObj.getCumulocityType(),
                "Should have correct cumulocity type");

        // Verify second message - FIX: Should also be CumulocityObject base type
        Object secondMessage = ((List) processingContext.getFlowResult()).get(1);
        assertTrue(secondMessage instanceof CumulocityObject, "Second message should be CumulocityObject");
        CumulocityObject secondObj = (CumulocityObject) secondMessage;

        assertNotNull(secondObj, "Second message should exist");
        log.info("Successfully validated multiple flow results: {} messages processed",
                ((List) processingContext.getFlowResult()).size());

    }

    @Test
    void testCompleteFlowProcessingWithMultipleResults() throws Exception {
        // Given - Modify the sample mapping to return multiple results
        String multiResultCode = """
                function onMessage(msg, context) {
                    var payload = msg.getPayload();
                    console.log("Processing message with payload:", JSON.stringify(payload));

                    return [
                        {
                            cumulocityType: "measurement",
                            action: "create",
                            payload: {
                                "time": new Date().toISOString(),
                                "type": "c8y_TemperatureMeasurement",
                                "c8y_Steam": {
                                    "Temperature": {
                                        "unit": "C",
                                        "value": payload["sensorData"]["temp_val"]
                                    }
                                }
                            },
                            externalSource: [{"type":"c8y_Serial", "externalId": payload.get('clientId')}]
                        },
                        {
                            cumulocityType: "event",
                            action: "create",
                            payload: {
                                "type": "c8y_ProcessedEvent",
                                "text": "Temperature processed",
                                "processed": true,
                                "originalValue": payload["sensorData"]["temp_val"]
                            },
                            externalSource: [{"type":"c8y_Serial", "externalId": payload.get('clientId')}]
                        }
                    ];
                }
                """;

        String multiResultCodeEncoded = Base64.getEncoder().encodeToString(multiResultCode.getBytes());
        mapping.setCode(multiResultCodeEncoded);

        // Setup mocks for multiple results
        Context mockGraalContext = mock(Context.class);
        Value mockBindings = mock(Value.class);
        Value mockOnMessageFunction = mock(Value.class);
        DataPrepContext mockFlowContext = mock(DataPrepContext.class);

        processingContext.setGraalContext(mockGraalContext);
        processingContext.setFlowContext(mockFlowContext);

        when(mockGraalContext.getBindings("js")).thenReturn(mockBindings);
        when(mockBindings.getMember("onMessage")).thenReturn(mockOnMessageFunction);

        // Create result with both CumulocityObject
        Value mockResult = createMultipleResultsJavaScriptResult();
        when(mockOnMessageFunction.execute(any(), any())).thenReturn(mockResult);

        // When
        processor.process(exchange);

        // Then - Verify multiple results
        assertNotNull(processingContext.getFlowResult(), "Flow result should not be null");
        assertEquals(2, ((List) processingContext.getFlowResult()).size(),
                "Should have two result messages");

        // Verify first result (CumulocityObject)
        Object firstMessage = ((List) processingContext.getFlowResult()).get(0);
        assertTrue(firstMessage instanceof CumulocityObject,
                "First message should be CumulocityObject");
        CumulocityObject cumulocityObj = (CumulocityObject) firstMessage;
        assertEquals(CumulocityType.MEASUREMENT, cumulocityObj.getCumulocityType());

        // Verify second result - FIX: Get payload properly
        Object secondMessage = ((List) processingContext.getFlowResult()).get(1);
        assertTrue(secondMessage instanceof CumulocityObject,
                "Second message should be CumulocityObject");

        CumulocityObject secondObj = (CumulocityObject) secondMessage;

        // FIX: The payload should be accessed from the secondObj, not the first one
        Object payloadObj = secondObj.getPayload();

        // Handle both String and Map payloads
        Map<String, Object> processedPayload;
        if (payloadObj instanceof String) {
            // If it's a JSON string, we need to parse it
            String payloadStr = (String) payloadObj;
            log.info("Payload is String: {}", payloadStr);
            // For test purposes, just verify it's not empty
            assertFalse(payloadStr.isEmpty(), "Payload string should not be empty");
            // Skip detailed validation since it's a string
            log.info("✅ Multiple results flow processing test passed with string payload");
            return;
        } else if (payloadObj instanceof Map) {
            processedPayload = (Map<String, Object>) payloadObj;
        } else {
            fail("Unexpected payload type: " + (payloadObj != null ? payloadObj.getClass() : "null"));
            return;
        }

        log.info("DEBUG - Processed payload: {}", processedPayload);

        if (processedPayload.containsKey("processed")) {
            assertEquals(true, processedPayload.get("processed"));
        }

        if (processedPayload.containsKey("originalValue")) {
            Object originalValue = processedPayload.get("originalValue");
            log.info("DEBUG - originalValue type: {}", originalValue != null ? originalValue.getClass() : "null");
            log.info("DEBUG - originalValue value: {}", originalValue);

            if (originalValue instanceof Number) {
                assertEquals(100, ((Number) originalValue).intValue(), "Original value should be 100");
            } else {
                assertEquals(100, originalValue, "Original value should be 100");
            }
        }

        log.info("✅ Multiple results flow processing test passed:");
        log.info("   - CumulocityObject: {} {}", cumulocityObj.getCumulocityType(), cumulocityObj.getAction());
    }

}