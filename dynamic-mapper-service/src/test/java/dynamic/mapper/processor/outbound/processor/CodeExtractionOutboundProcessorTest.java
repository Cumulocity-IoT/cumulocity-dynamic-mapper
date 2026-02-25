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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for CodeExtractionOutboundProcessor.
 * Tests JavaScript code execution for outbound payloads from Cumulocity operations.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodeExtractionOutboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private CodeExtractionOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        processor = new CodeExtractionOutboundProcessor(mappingService);

        mapping = createCodeExtractionMapping();
        mappingStatus = new MappingStatus(
                "outbound-code-extract-id",
                "Outbound Code Extraction Mapping",
                "outbound-code-extract",
                Direction.OUTBOUND,
                null,
                "device/+/command",
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
    }

    private Mapping createCodeExtractionMapping() {
        return Mapping.builder()
                .id("outbound-code-extract-id")
                .identifier("outbound-code-extract")
                .name("Outbound Code Extraction Mapping")
                .publishTopic("device/+/command")
                .publishTopicSample("device/device001/command")
                .targetAPI(API.OPERATION)
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.JSON)
                .active(true)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        // Typical Cumulocity operation payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "12345");
        payload.put("deviceId", "device001");
        payload.put("status", "PENDING");
        payload.put("creationTime", "2024-01-19T12:00:00.000Z");

        Map<String, Object> operation = new HashMap<>();
        operation.put("command", "restart");
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
    void testProcessSubstitutionResultWithValidResult() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        List<SubstituteValue> commandValues = new ArrayList<>();
        commandValues.add(new SubstituteValue("restart", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("command", commandValues);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "12345");

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey("deviceId"), "Cache should contain deviceId");
        assertTrue(cache.containsKey("command"), "Cache should contain command");

        assertEquals(1, cache.get("deviceId").size(), "DeviceId should have one value");
        assertEquals("device001", cache.get("deviceId").get(0).value, "DeviceId value should match");

        log.info("✅ Successfully processed substitution result");
    }

    @Test
    void testProcessSubstitutionResultWithEmptyResult() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();
        // Empty substitutions

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        assertTrue(state.shouldIgnoreFurtherProcessing(),
                "Should ignore further processing when result is empty");

        log.info("✅ Successfully handled empty substitution result");
    }

    @Test
    void testProcessSubstitutionResultWithNullResult() throws ProcessingException {
        // Given
        SubstitutionResult result = null;

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        assertTrue(state.shouldIgnoreFurtherProcessing(),
                "Should ignore further processing with null result");

        log.info("✅ Successfully handled null substitution result");
    }

    @Test
    void testProcessSubstitutionResultWithNullSubstitutions() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();
        result.substitutions = null;

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        assertTrue(state.shouldIgnoreFurtherProcessing(),
                "Should ignore further processing with null substitutions");

        log.info("✅ Successfully handled null substitutions");
    }

    @Test
    void testProcessSubstitutionResultWithArrayExpansion() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> arrayValues = new ArrayList<>();
        arrayValues.add(new SubstituteValue(
                List.of("cmd1", "cmd2", "cmd3"),
                TYPE.TEXTUAL,
                RepairStrategy.DEFAULT,
                true)); // expandArray = true
        result.substitutions.put("commands", arrayValues);

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey("commands"), "Cache should contain commands");

        // Array expansion should result in multiple cache entries
        assertTrue(cache.get("commands").size() > 0,
                "Should have processed array values");

        log.info("✅ Successfully handled array expansion");
    }

    @Test
    void testProcessSubstitutionResultWithAlarms() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        // Add alarms
        result.alarms.add("CRITICAL: Operation failed");
        result.alarms.add("WARNING: Device not responding");

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Set<String> alarms = processingContext.getAlarms();
        assertEquals(2, alarms.size(), "Should have two alarms");
        assertTrue(alarms.contains("CRITICAL: Operation failed"), "Should contain critical alarm");
        assertTrue(alarms.contains("WARNING: Device not responding"), "Should contain warning alarm");

        log.info("✅ Successfully processed alarms from extraction");
    }

    @Test
    void testProcessSubstitutionResultWithMultipleSubstitutions() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        List<SubstituteValue> commandValues = new ArrayList<>();
        commandValues.add(new SubstituteValue("restart", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("command", commandValues);

        List<SubstituteValue> statusValues = new ArrayList<>();
        statusValues.add(new SubstituteValue("EXECUTING", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("status", statusValues);

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertEquals(3, cache.size(), "Should have three substitutions in cache");
        assertTrue(cache.containsKey("deviceId"));
        assertTrue(cache.containsKey("command"));
        assertTrue(cache.containsKey("status"));

        log.info("✅ Successfully processed multiple substitutions");
    }

    @Test
    void testProcessSubstitutionResultWithComplexPayload() throws ProcessingException {
        // Given - Complex operation with nested structures
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("gateway-001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        List<SubstituteValue> configValues = new ArrayList<>();
        Map<String, Object> configData = new HashMap<>();
        configData.put("interval", 60);
        configData.put("enabled", true);
        configValues.add(new SubstituteValue(configData, TYPE.OBJECT, RepairStrategy.DEFAULT, false));
        result.substitutions.put("configuration", configValues);

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "op-123");

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey("deviceId"));
        assertTrue(cache.containsKey("configuration"));

        log.info("✅ Successfully processed complex payload");
    }

    @Test
    void testProcessSubstitutionResultWithDebugLogging() throws ProcessingException {
        // Given
        mapping.setDebug(true);

        SubstitutionResult result = new SubstitutionResult();
        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "data");

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then - No exception should be thrown
        assertFalse(state.shouldIgnoreFurtherProcessing(),
                "Should continue processing with valid result");

        log.info("✅ Successfully processed with debug logging enabled");
    }

    @Test
    void testHandleProcessingErrorInTestingMode() {
        // Given
        processingContext.setTesting(true);
        Exception testException = new RuntimeException("Test error");

        // When
        processor.handleProcessingError(testException, processingContext, TEST_TENANT, mapping);

        // Then
        assertEquals(1, processingContext.getErrors().size(), "Should have one error");
        assertTrue(processingContext.getErrors().get(0).getMessage().contains("Test error"),
                "Error message should contain original exception message");

        // In testing mode, should not ignore further processing
        // Note: handleProcessingError modifies context directly, not through state
        assertFalse(processingContext.getIgnoreFurtherProcessing(),
                "Should not ignore further processing in testing mode");

        verify(mappingService, never()).getMappingStatus(any(), any());
        verify(mappingService, never()).increaseAndHandleFailureCount(any(), any(), any());

        log.info("✅ Successfully handled error in testing mode");
    }

    @Test
    void testHandleProcessingErrorInProductionMode() {
        // Given
        processingContext.setTesting(false);
        Exception testException = new RuntimeException("Production error");

        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);

        // When
        processor.handleProcessingError(testException, processingContext, TEST_TENANT, mapping);

        // Then
        assertEquals(1, processingContext.getErrors().size(), "Should have one error");

        // In production mode, should ignore further processing
        // Note: handleProcessingError modifies context directly, not through state
        assertTrue(processingContext.getIgnoreFurtherProcessing(),
                "Should ignore further processing in production mode");

        verify(mappingService).getMappingStatus(TEST_TENANT, mapping);
        verify(mappingService).increaseAndHandleFailureCount(TEST_TENANT, mapping, mappingStatus);
        assertEquals(1, mappingStatus.errors, "Should increment error count");

        log.info("✅ Successfully handled error in production mode");
    }

    @Test
    void testHandleProcessingErrorWithProcessingException() {
        // Given
        processingContext.setTesting(false);
        ProcessingException testException = new ProcessingException("Processing failed");

        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);

        // When
        processor.handleProcessingError(testException, processingContext, TEST_TENANT, mapping);

        // Then
        assertEquals(1, processingContext.getErrors().size(), "Should have one error");
        assertSame(testException, processingContext.getErrors().get(0),
                "Should add the ProcessingException directly");

        log.info("✅ Successfully handled ProcessingException");
    }

    @Test
    void testHandleProcessingErrorExtractsLineNumber() {
        // Given
        processingContext.setTesting(false);

        // Create exception with stack trace
        Exception testException = new RuntimeException("Error at line 42");
        StackTraceElement[] stackTrace = new StackTraceElement[] {
            new StackTraceElement("TestClass", "testMethod", "TestClass.java", 42)
        };
        testException.setStackTrace(stackTrace);

        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);

        // When
        processor.handleProcessingError(testException, processingContext, TEST_TENANT, mapping);

        // Then
        assertEquals(1, processingContext.getErrors().size(), "Should have one error");
        String errorMessage = processingContext.getErrors().get(0).getMessage();
        assertTrue(errorMessage.contains("line 42"),
                "Error message should contain line number");

        log.info("✅ Successfully extracted line number from error");
    }

    @Test
    void testProcessSubstitutionResultDoesNotAddDefaultTime() throws ProcessingException {
        // Given - Outbound processor should NOT add default time (unlike inbound)
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        Map<String, Object> payload = new HashMap<>();

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertEquals(1, cache.size(), "Should only have the one substitution");
        assertFalse(cache.containsKey(Mapping.KEY_TIME),
                "Outbound processor should not add default time");

        log.info("✅ Successfully verified outbound does not add default time");
    }

    @Test
    void testProcessSubstitutionResultWithPayloadLogging() throws ProcessingException {
        // Given
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        SubstitutionResult result = new SubstitutionResult();
        List<SubstituteValue> deviceValues = new ArrayList<>();
        deviceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("deviceId", deviceValues);

        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "data");

        var state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(),
                processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then - Should process successfully with payload logging enabled
        assertFalse(state.shouldIgnoreFurtherProcessing(),
                "Should continue processing with valid result");

        log.info("✅ Successfully processed with payload logging enabled");
    }
}
