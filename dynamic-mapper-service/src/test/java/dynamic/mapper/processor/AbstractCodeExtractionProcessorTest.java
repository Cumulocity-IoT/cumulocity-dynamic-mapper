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

package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
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
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for AbstractCodeExtractionProcessor base class.
 * Tests GraalVM context lifecycle management, code loading, and Value conversion.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractCodeExtractionProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private TestableAbstractCodeExtractionProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private ProcessingContext<Object> processingContext;
    private Context graalContext;

    /**
     * Concrete test implementation of AbstractCodeExtractionProcessor for testing.
     */
    static class TestableAbstractCodeExtractionProcessor extends AbstractCodeExtractionProcessor {

        private boolean processSubstitutionCalled = false;
        private boolean handleErrorCalled = false;
        private Exception lastError;

        public TestableAbstractCodeExtractionProcessor(MappingService mappingService) {
            super(mappingService);
        }

        @Override
        protected void processSubstitutionResult(
                SubstitutionResult result,
                dynamic.mapper.processor.model.RoutingContext routing,
                dynamic.mapper.processor.model.PayloadContext<?> payload,
                dynamic.mapper.processor.model.ProcessingState state,
                Object payloadObject,
                Mapping mapping,
                String tenant,
                ProcessingContext<?> context) throws ProcessingException {
            processSubstitutionCalled = true;
            // Simple implementation for testing
        }

        @Override
        protected void handleProcessingError(Exception e, ProcessingContext<?> context,
                                            String tenant, Mapping mapping) {
            handleErrorCalled = true;
            lastError = e;
        }

        public boolean wasProcessSubstitutionCalled() {
            return processSubstitutionCalled;
        }

        public boolean wasHandleErrorCalled() {
            return handleErrorCalled;
        }

        public Exception getLastError() {
            return lastError;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        processor = new TestableAbstractCodeExtractionProcessor(mappingService);

        mapping = createCodeExtractionMapping();
        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        // Clean up GraalVM context if it exists
        if (graalContext != null) {
            try {
                graalContext.close();
            } catch (Exception e) {
                log.warn("Error closing GraalVM context in tearDown: {}", e.getMessage());
            }
        }
        if (processingContext != null && processingContext.getGraalContext() != null) {
            try {
                processingContext.getGraalContext().close();
            } catch (Exception e) {
                log.warn("Error closing processing context GraalVM context: {}", e.getMessage());
            }
        }
    }

    private Mapping createCodeExtractionMapping() {
        // Simple JavaScript code that returns a SubstitutionResult
        String jsCode = """
                function extractFromSource(context) {
                    var SubstitutionResult = Java.type('dynamic.mapper.processor.model.SubstitutionResult');
                    var SubstituteValue = Java.type('dynamic.mapper.processor.model.SubstituteValue');
                    var ArrayList = Java.type('java.util.ArrayList');
                    var RepairStrategy = Java.type('dynamic.mapper.processor.model.RepairStrategy');
                    var TYPE = Java.type('dynamic.mapper.processor.model.SubstituteValue$TYPE');

                    var result = new SubstitutionResult();
                    var values = new ArrayList();
                    values.add(new SubstituteValue("test-value", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
                    result.substitutions.put("test-key", values);

                    return result;
                }
                """;

        String encodedCode = Base64.getEncoder().encodeToString(jsCode.getBytes());

        return Mapping.builder()
                .id("test_code_extract_id")
                .identifier("test_code_extract")
                .name("Test Code Extraction Mapping")
                .mappingTopic("test/topic")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .code(encodedCode)
                .active(true)
                .debug(false)
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "test-device");
        payload.put("value", 42);

        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("test/topic")
                .build();

        // Create GraalVM context for testing
        graalContext = Context.newBuilder("js")
                .allowAllAccess(true)
                .build();
        context.setGraalContext(graalContext);

        return context;
    }

    @Test
    void testProcessCreatesAndClosesGraalContext() throws Exception {
        // Given
        assertNotNull(processingContext.getGraalContext(), "GraalVM context should exist initially");

        // When
        processor.process(exchange);

        // Then
        // Context should be closed after processing
        assertNull(processingContext.getGraalContext(), "GraalVM context should be null after processing");

        log.info("✅ Successfully verified GraalVM context lifecycle");
    }

    @Test
    void testExtractFromSourceLoadsAndExecutesCode() throws Exception {
        // Given
        assertNotNull(processingContext.getGraalContext());

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertTrue(processor.wasProcessSubstitutionCalled(),
                "Should call processSubstitutionResult after extraction");

        log.info("✅ Successfully loaded and executed JavaScript code");
    }

    @Test
    void testExtractFromSourceWithNullCode() throws Exception {
        // Given
        mapping.setCode(null);

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertFalse(processor.wasProcessSubstitutionCalled(),
                "Should not call processSubstitutionResult when code is null");

        log.info("✅ Successfully handled null code");
    }

    @Test
    void testExtractFromSourceWithSharedCode() throws Exception {
        // Given
        String sharedJsCode = """
                function sharedFunction() {
                    return "shared-value";
                }
                """;
        String encodedSharedCode = Base64.getEncoder().encodeToString(sharedJsCode.getBytes());
        processingContext.setSharedCode(encodedSharedCode);

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertTrue(processor.wasProcessSubstitutionCalled(),
                "Should process successfully with shared code");

        log.info("✅ Successfully loaded shared code");
    }

    @Test
    void testExtractFromSourceWithSystemCode() throws Exception {
        // Given
        String systemJsCode = """
                function systemFunction() {
                    return "system-value";
                }
                """;
        String encodedSystemCode = Base64.getEncoder().encodeToString(systemJsCode.getBytes());
        processingContext.setSystemCode(encodedSystemCode);

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertTrue(processor.wasProcessSubstitutionCalled(),
                "Should process successfully with system code");

        log.info("✅ Successfully loaded system code");
    }

    @Test
    void testExtractFromSourceWithAllCodeTypes() throws Exception {
        // Given - Main code, shared code, and system code
        String sharedJsCode = "var sharedValue = 'shared';";
        String systemJsCode = "var systemValue = 'system';";

        processingContext.setSharedCode(Base64.getEncoder().encodeToString(sharedJsCode.getBytes()));
        processingContext.setSystemCode(Base64.getEncoder().encodeToString(systemJsCode.getBytes()));

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertTrue(processor.wasProcessSubstitutionCalled(),
                "Should process successfully with all code types");

        log.info("✅ Successfully loaded all code types (main, shared, system)");
    }

    @Test
    void testDeepConvertSubstitutionResultWithValidResult() {
        // Given
        SubstitutionResult originalResult = new SubstitutionResult();
        List<SubstituteValue> values = new ArrayList<>();
        values.add(new SubstituteValue("test-value", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        originalResult.substitutions.put("key1", values);
        originalResult.alarms.add("test-alarm");

        Value resultValue = graalContext.asValue(originalResult);

        // When
        SubstitutionResult converted = processor.deepConvertSubstitutionResultWithContext(
                resultValue,
                graalContext,
                TEST_TENANT);

        // Then
        assertNotNull(converted, "Converted result should not be null");
        assertEquals(1, converted.substitutions.size(), "Should have one substitution");
        assertTrue(converted.substitutions.containsKey("key1"), "Should contain key1");
        assertEquals(1, converted.alarms.size(), "Should have one alarm");
        assertTrue(converted.alarms.contains("test-alarm"), "Should contain alarm");

        log.info("✅ Successfully converted SubstitutionResult with context");
    }

    @Test
    void testDeepConvertSubstitutionResultWithNullResult() {
        // Given
        Value nullValue = null;

        // When
        SubstitutionResult converted = processor.deepConvertSubstitutionResultWithContext(
                nullValue,
                graalContext,
                TEST_TENANT);

        // Then
        assertNotNull(converted, "Should return empty result for null");
        assertTrue(converted.substitutions.isEmpty(), "Should have empty substitutions");

        log.info("✅ Successfully handled null result in conversion");
    }

    @Test
    void testDeepConvertSubstitutionResultWithMultipleSubstitutions() {
        // Given
        SubstitutionResult originalResult = new SubstitutionResult();

        List<SubstituteValue> values1 = new ArrayList<>();
        values1.add(new SubstituteValue("value1", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false));
        originalResult.substitutions.put("key1", values1);

        List<SubstituteValue> values2 = new ArrayList<>();
        values2.add(new SubstituteValue(42, TYPE.NUMBER, RepairStrategy.DEFAULT, false));
        originalResult.substitutions.put("key2", values2);

        List<SubstituteValue> values3 = new ArrayList<>();
        values3.add(new SubstituteValue(true, TYPE.BOOLEAN, RepairStrategy.DEFAULT, false));
        originalResult.substitutions.put("key3", values3);

        Value resultValue = graalContext.asValue(originalResult);

        // When
        SubstitutionResult converted = processor.deepConvertSubstitutionResultWithContext(
                resultValue,
                graalContext,
                TEST_TENANT);

        // Then
        assertEquals(3, converted.substitutions.size(), "Should have three substitutions");
        assertTrue(converted.substitutions.containsKey("key1"));
        assertTrue(converted.substitutions.containsKey("key2"));
        assertTrue(converted.substitutions.containsKey("key3"));

        log.info("✅ Successfully converted multiple substitutions");
    }

    @Test
    void testConvertSubstituteValueWithTextualValue() {
        // Given
        SubstituteValue original = new SubstituteValue("text-value", TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

        // When
        SubstituteValue converted = processor.convertSubstituteValueWithContext(
                original,
                graalContext,
                TEST_TENANT);

        // Then
        assertNotNull(converted, "Converted value should not be null");
        assertEquals("text-value", converted.value, "Value should match");
        assertEquals(TYPE.TEXTUAL, converted.type, "Type should match");

        log.info("✅ Successfully converted textual SubstituteValue");
    }

    @Test
    void testConvertSubstituteValueWithNumberValue() {
        // Given
        SubstituteValue original = new SubstituteValue(42.5, TYPE.NUMBER, RepairStrategy.DEFAULT, false);

        // When
        SubstituteValue converted = processor.convertSubstituteValueWithContext(
                original,
                graalContext,
                TEST_TENANT);

        // Then
        assertNotNull(converted);
        assertEquals(42.5, converted.value, "Number value should match");
        assertEquals(TYPE.NUMBER, converted.type, "Type should be NUMBER");

        log.info("✅ Successfully converted number SubstituteValue");
    }

    @Test
    void testConvertSubstituteValueWithBooleanValue() {
        // Given
        SubstituteValue original = new SubstituteValue(true, TYPE.BOOLEAN, RepairStrategy.DEFAULT, false);

        // When
        SubstituteValue converted = processor.convertSubstituteValueWithContext(
                original,
                graalContext,
                TEST_TENANT);

        // Then
        assertEquals(true, converted.value, "Boolean value should match");
        assertEquals(TYPE.BOOLEAN, converted.type, "Type should be BOOLEAN");

        log.info("✅ Successfully converted boolean SubstituteValue");
    }

    @Test
    void testConvertSubstituteValueWithNullValue() {
        // Given
        SubstituteValue original = new SubstituteValue(null, TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

        // When
        SubstituteValue converted = processor.convertSubstituteValueWithContext(
                original,
                graalContext,
                TEST_TENANT);

        // Then
        assertNull(converted.value, "Null value should remain null");

        log.info("✅ Successfully handled null value in conversion");
    }

    @Test
    void testConvertSubstituteValueWithExpandArray() {
        // Given
        SubstituteValue original = new SubstituteValue("array-value", TYPE.TEXTUAL, RepairStrategy.DEFAULT, true);

        // When
        SubstituteValue converted = processor.convertSubstituteValueWithContext(
                original,
                graalContext,
                TEST_TENANT);

        // Then
        assertTrue(converted.expandArray, "expandArray flag should be preserved");
        assertEquals(RepairStrategy.DEFAULT, converted.repairStrategy, "RepairStrategy should be preserved");

        log.info("✅ Successfully preserved expandArray flag");
    }

    @Test
    void testProcessHandlesExceptionAndCallsErrorHandler() throws Exception {
        // Given - Create a mapping with invalid code that will cause an exception
        String invalidJsCode = "function extractFromSource(context) { throw new Error('Test error'); }";
        mapping.setCode(Base64.getEncoder().encodeToString(invalidJsCode.getBytes()));

        // When
        processor.process(exchange);

        // Then
        assertTrue(processor.wasHandleErrorCalled(), "Should call handleProcessingError on exception");
        assertNotNull(processor.getLastError(), "Should have captured the error");

        // Context should still be closed despite error
        assertNull(processingContext.getGraalContext(), "GraalVM context should be closed even after error");

        log.info("✅ Successfully handled exception and closed context");
    }

    @Test
    void testExtractFromSourceWithPayloadLogging() throws Exception {
        // Given
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertTrue(processor.wasProcessSubstitutionCalled(),
                "Should process successfully with payload logging");

        log.info("✅ Successfully processed with payload logging enabled");
    }

    @Test
    void testExtractFromSourceWithDebugLogging() throws Exception {
        // Given
        mapping.setDebug(true);

        // When
        processor.extractFromSource(processingContext);

        // Then
        assertTrue(processor.wasProcessSubstitutionCalled(),
                "Should process successfully with debug logging");

        log.info("✅ Successfully processed with debug logging enabled");
    }

    @Test
    void testProcessClosesContextInFinallyBlock() throws Exception {
        // Given - Code that will throw exception during processing
        String errorJsCode = "function extractFromSource(context) { undefined.method(); }";
        mapping.setCode(Base64.getEncoder().encodeToString(errorJsCode.getBytes()));

        assertNotNull(processingContext.getGraalContext(), "Context should exist before processing");

        // When
        processor.process(exchange);

        // Then - Context should be closed even though error occurred
        assertNull(processingContext.getGraalContext(),
                "GraalVM context should be closed in finally block despite error");

        log.info("✅ Successfully verified context cleanup in finally block");
    }

    @Test
    void testDeepConvertPreservesAlarms() {
        // Given
        SubstitutionResult originalResult = new SubstitutionResult();
        originalResult.alarms.add("CRITICAL: Device offline");
        originalResult.alarms.add("WARNING: Low battery");
        originalResult.alarms.add("INFO: Maintenance due");

        Value resultValue = graalContext.asValue(originalResult);

        // When
        SubstitutionResult converted = processor.deepConvertSubstitutionResultWithContext(
                resultValue,
                graalContext,
                TEST_TENANT);

        // Then
        assertEquals(3, converted.alarms.size(), "Should preserve all alarms");
        assertTrue(converted.alarms.contains("CRITICAL: Device offline"));
        assertTrue(converted.alarms.contains("WARNING: Low battery"));
        assertTrue(converted.alarms.contains("INFO: Maintenance due"));

        log.info("✅ Successfully preserved alarms during conversion");
    }

    @Test
    void testConvertSubstituteValueWithComplexObject() {
        // Given - Object value
        Map<String, Object> objectValue = new HashMap<>();
        objectValue.put("nested", "value");
        objectValue.put("number", 123);

        SubstituteValue original = new SubstituteValue(objectValue, TYPE.OBJECT, RepairStrategy.DEFAULT, false);

        // When
        SubstituteValue converted = processor.convertSubstituteValueWithContext(
                original,
                graalContext,
                TEST_TENANT);

        // Then
        assertNotNull(converted.value, "Object value should be converted");
        assertEquals(TYPE.OBJECT, converted.type, "Type should be OBJECT");

        log.info("✅ Successfully converted complex object value");
    }
}
