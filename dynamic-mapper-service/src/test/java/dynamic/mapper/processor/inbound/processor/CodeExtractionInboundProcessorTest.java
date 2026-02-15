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
import java.util.Base64;
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
 * Tests for CodeExtractionInboundProcessor.
 * Tests JavaScript code execution, topic level handling, time substitution, and device identification.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodeExtractionInboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private CodeExtractionInboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;

    @BeforeEach
    void setUp() throws Exception {
        processor = new CodeExtractionInboundProcessor(mappingService);

        mapping = createCodeExtractionMapping();
        mappingStatus = new MappingStatus(
                "code-extract-id",
                "Code Extraction Mapping",
                "code-extract",
                Direction.INBOUND,
                "device/+/data",
                null,
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
                .id("code-extract-id")
                .identifier("code-extract")
                .name("Code Extraction Mapping")
                .mappingTopic("device/+/data")
                .mappingTopicSample("device/sensor001/data")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .active(true)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "sensor001");
        payload.put("temperature", 23.5);
        payload.put("humidity", 65);
        payload.put("timestamp", "2024-01-19T12:00:00.000Z");

        return ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("device/sensor001/data")
                .clientId("mqtt-client-001")
                .api(API.MEASUREMENT)
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    @Test
    void testPreparePayloadAddsTopicLevels() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "sensor001");
        payload.put("value", 42);

        processingContext.setPayload(payload);
        processingContext.setTopic("devices/building1/floor2/room3");

        // When
        processor.preparePayload(processingContext.getRoutingContext(), processingContext.getPayloadContext(), payload);

        // Then
        assertTrue(payload.containsKey(Mapping.TOKEN_TOPIC_LEVEL),
                "Should add topic levels to payload");

        @SuppressWarnings("unchecked")
        List<String> topicLevels = (List<String>) payload.get(Mapping.TOKEN_TOPIC_LEVEL);
        assertNotNull(topicLevels, "Topic levels should not be null");
        assertEquals("devices", topicLevels.get(0), "First topic level should be 'devices'");
        assertEquals("building1", topicLevels.get(1), "Second topic level should be 'building1'");

        log.info("✅ Successfully verified topic levels are added to payload");
    }

    @Test
    void testPreparePayloadAddsContextData() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "data");

        processingContext.setPayload(payload);

        // When
        processor.preparePayload(processingContext.getRoutingContext(), processingContext.getPayloadContext(), payload);

        // Then
        assertTrue(payload.containsKey(Mapping.TOKEN_CONTEXT_DATA),
                "Should add context data to payload");

        @SuppressWarnings("unchecked")
        Map<String, String> contextData = (Map<String, String>) payload.get(Mapping.TOKEN_CONTEXT_DATA);
        assertNotNull(contextData, "Context data should not be null");
        assertEquals(API.MEASUREMENT.toString(), contextData.get("api"),
                "Context should contain target API");

        log.info("✅ Successfully verified context data is added to payload");
    }

    @Test
    void testProcessSubstitutionResultWithValidResult() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("sensor001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        List<SubstituteValue> tempValues = new ArrayList<>();
        tempValues.add(new SubstituteValue(23.5, TYPE.NUMBER, RepairStrategy.DEFAULT, false));
        result.substitutions.put("temp.value", tempValues);

        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "sensor001");

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey("source"), "Cache should contain source");
        assertTrue(cache.containsKey("temp.value"), "Cache should contain temperature");

        assertEquals(1, cache.get("source").size(), "Source should have one value");
        assertEquals("sensor001", cache.get("source").get(0).value, "Source value should match");

        log.info("✅ Successfully processed substitution result");
    }

    @Test
    void testProcessSubstitutionResultWithEmptyResult() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();
        // Empty substitutions

        Map<String, Object> payload = new HashMap<>();

        // Get the state to check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        assertTrue(state.shouldIgnoreFurtherProcessing(),
                "Should ignore further processing when result is empty");

        log.info("✅ Successfully handled empty substitution result");
    }

    @Test
    void testProcessSubstitutionResultAddsDefaultTimeForMeasurements() throws ProcessingException {
        // Given
        mapping.setTargetAPI(API.MEASUREMENT);

        SubstitutionResult result = new SubstitutionResult();
        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        // Note: No time substitution provided

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey(Mapping.KEY_TIME),
                "Should add default time for measurements");

        assertEquals(1, cache.get(Mapping.KEY_TIME).size(),
                "Should have one time value");

        SubstituteValue timeValue = cache.get(Mapping.KEY_TIME).get(0);
        assertNotNull(timeValue.value, "Time value should not be null");
        assertEquals(TYPE.TEXTUAL, timeValue.type, "Time should be textual");

        log.info("✅ Successfully added default time for measurement");
    }

    @Test
    void testProcessSubstitutionResultDoesNotAddTimeForInventory() throws ProcessingException {
        // Given
        mapping.setTargetAPI(API.INVENTORY);

        SubstitutionResult result = new SubstitutionResult();
        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertFalse(cache.containsKey(Mapping.KEY_TIME),
                "Should not add default time for inventory API");

        log.info("✅ Successfully skipped default time for inventory");
    }

    @Test
    void testProcessSubstitutionResultDoesNotAddTimeForOperations() throws ProcessingException {
        // Given
        mapping.setTargetAPI(API.OPERATION);

        SubstitutionResult result = new SubstitutionResult();
        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertFalse(cache.containsKey(Mapping.KEY_TIME),
                "Should not add default time for operation API");

        log.info("✅ Successfully skipped default time for operations");
    }

    @Test
    void testProcessSubstitutionResultWithExplicitTime() throws ProcessingException {
        // Given
        mapping.setTargetAPI(API.MEASUREMENT);

        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> timeValues = new ArrayList<>();
        timeValues.add(new SubstituteValue("2024-01-19T12:00:00.000Z", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put(Mapping.KEY_TIME, timeValues);

        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey(Mapping.KEY_TIME), "Should have time");

        // Should use the provided time, not add a new default one
        assertEquals(1, cache.get(Mapping.KEY_TIME).size(),
                "Should only have the provided time value");
        assertEquals("2024-01-19T12:00:00.000Z", cache.get(Mapping.KEY_TIME).get(0).value,
                "Should use provided time value");

        log.info("✅ Successfully used explicit time without adding default");
    }

    @Test
    void testProcessSubstitutionResultWithArrayExpansion() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> arrayValues = new ArrayList<>();
        arrayValues.add(new SubstituteValue(
                List.of(1.0, 2.0, 3.0),
                TYPE.NUMBER,
                RepairStrategy.DEFAULT,
                true)); // expandArray = true
        result.substitutions.put("measurements", arrayValues);

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertTrue(cache.containsKey("measurements"), "Cache should contain measurements");

        // Array expansion should result in multiple cache entries
        assertTrue(cache.get("measurements").size() > 0,
                "Should have processed array values");

        log.info("✅ Successfully handled array expansion");
    }

    @Test
    void testProcessSubstitutionResultWithAlarms() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        // Add alarms
        result.alarms.add("CRITICAL: Temperature too high");
        result.alarms.add("WARNING: Battery low");

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Set<String> alarms = processingContext.getAlarms();
        assertEquals(2, alarms.size(), "Should have two alarms");
        assertTrue(alarms.contains("CRITICAL: Temperature too high"), "Should contain critical alarm");
        assertTrue(alarms.contains("WARNING: Battery low"), "Should contain warning alarm");

        log.info("✅ Successfully processed alarms from extraction");
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
    void testProcessSubstitutionResultWithDebugLogging() throws ProcessingException {
        // Given
        mapping.setDebug(true);

        SubstitutionResult result = new SubstitutionResult();
        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "data");

        // Get the state to check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then - No exception should be thrown
        assertFalse(state.shouldIgnoreFurtherProcessing(),
                "Should continue processing with valid result");

        log.info("✅ Successfully processed with debug logging enabled");
    }

    @Test
    void testProcessSubstitutionResultWithNullSubstitutions() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();
        result.substitutions = null;

        Map<String, Object> payload = new HashMap<>();

        // Get the state to check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        assertTrue(state.shouldIgnoreFurtherProcessing(),
                "Should ignore further processing with null substitutions");

        log.info("✅ Successfully handled null substitutions");
    }

    @Test
    void testProcessSubstitutionResultWithMultipleSubstitutions() throws ProcessingException {
        // Given
        SubstitutionResult result = new SubstitutionResult();

        List<SubstituteValue> sourceValues = new ArrayList<>();
        sourceValues.add(new SubstituteValue("device001", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        result.substitutions.put("source", sourceValues);

        List<SubstituteValue> tempValues = new ArrayList<>();
        tempValues.add(new SubstituteValue(23.5, TYPE.NUMBER, RepairStrategy.DEFAULT, false));
        result.substitutions.put("temp.value", tempValues);

        List<SubstituteValue> humidityValues = new ArrayList<>();
        humidityValues.add(new SubstituteValue(65, TYPE.NUMBER, RepairStrategy.DEFAULT, false));
        result.substitutions.put("humidity.value", humidityValues);

        Map<String, Object> payload = new HashMap<>();

        // Get the state to use and check after processing
        dynamic.mapper.processor.model.ProcessingState state = processingContext.getProcessingState();

        // When
        processor.processSubstitutionResult(result, processingContext.getRoutingContext(), processingContext.getPayloadContext(), state, payload, mapping, TEST_TENANT, processingContext);

        // Then
        Map<String, List<SubstituteValue>> cache = state.getProcessingCache();
        assertEquals(4, cache.size(), "Should have four substitutions in cache (including default time)");
        assertTrue(cache.containsKey("source"));
        assertTrue(cache.containsKey("temp.value"));
        assertTrue(cache.containsKey("humidity.value"));
        assertTrue(cache.containsKey(Mapping.KEY_TIME), "Should have default time for measurement");

        log.info("✅ Successfully processed multiple substitutions");
    }
}
