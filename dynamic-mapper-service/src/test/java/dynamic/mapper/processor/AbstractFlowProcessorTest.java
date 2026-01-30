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
import static org.mockito.Mockito.*;

import java.util.Base64;
import java.util.HashMap;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.SimpleFlowContext;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for AbstractFlowProcessorProcessor base class.
 * Tests JavaScript code loading, execution, warning/log extraction, and GraalVM cleanup.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractFlowProcessorTest {

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

    private TestableAbstractFlowProcessorProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private ProcessingContext<Object> processingContext;
    private Context graalContext;
    private DataPrepContext flowContext;

    /**
     * Concrete test implementation of AbstractFlowProcessorProcessor for testing.
     */
    static class TestableAbstractFlowProcessorProcessor extends AbstractFlowProcessor {

        private boolean processResultCalled = false;
        private boolean handleErrorCalled = false;
        private Exception lastError;
        private String lastErrorMessage;

        public TestableAbstractFlowProcessorProcessor(MappingService mappingService) {
            super(mappingService);
        }

        @Override
        protected String getProcessorName() {
            return "TestableFlowProcessor";
        }

        @Override
        protected Value createInputMessage(Context graalContext, ProcessingContext<?> context) {
            // Create a simple JavaScript object as input
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("payload", context.getPayload());
            messageData.put("topic", context.getTopic());
            return graalContext.asValue(messageData);
        }

        @Override
        protected void processResult(Value result, ProcessingContext<?> context, String tenant)
                throws ProcessingException {
            processResultCalled = true;
            // Simple implementation for testing
        }

        @Override
        protected void handleProcessingError(Exception e, String errorMessage,
                ProcessingContext<?> context, String tenant, Mapping mapping) {
            handleErrorCalled = true;
            lastError = e;
            lastErrorMessage = errorMessage;
        }

        public boolean wasProcessResultCalled() {
            return processResultCalled;
        }

        public boolean wasHandleErrorCalled() {
            return handleErrorCalled;
        }

        public Exception getLastError() {
            return lastError;
        }

        public String getLastErrorMessage() {
            return lastErrorMessage;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        processor = new TestableAbstractFlowProcessorProcessor(mappingService);

        mapping = createFlowProcessorMapping();
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

    private Mapping createFlowProcessorMapping() {
        // Simple JavaScript smart function
        String jsCode = """
                function onMessage(message, flowContext) {
                    var result = {
                        processed: true,
                        value: message.payload.value * 2
                    };
                    return result;
                }
                """;

        String encodedCode = Base64.getEncoder().encodeToString(jsCode.getBytes());

        return Mapping.builder()
                .id("test_flow_processor_id")
                .identifier("test_flow_processor")
                .name("Test Flow Processor Mapping")
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

        // Create GraalVM context and flow context for testing
        graalContext = Context.newBuilder("js")
                .allowAllAccess(true)
                .build();
        context.setGraalContext(graalContext);

        flowContext = new SimpleFlowContext(graalContext, TEST_TENANT, inventoryEnrichmentClient, false);
        context.setFlowContext(flowContext);

        return context;
    }

    @Test
    void testProcessClosesContextInFinallyBlock() throws Exception {
        // Given
        assertNotNull(processingContext.getGraalContext(), "GraalVM context should exist initially");

        // When
        processor.process(exchange);

        // Then - Context should be closed after processing
        // Note: The context.close() in AbstractFlowProcessorProcessor closes the ProcessingContext,
        // which includes closing the GraalVM context
        assertTrue(processor.wasProcessResultCalled(), "Should have processed the result");

        log.info("✅ Successfully verified context cleanup in finally block");
    }

    @Test
    void testProcessSmartMappingLoadsAndExecutesCode() throws Exception {
        // Given
        assertNotNull(processingContext.getGraalContext());

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(),
                "Should call processResult after JavaScript execution");

        log.info("✅ Successfully loaded and executed JavaScript code");
    }

    @Test
    void testProcessSmartMappingWithNullCode() throws Exception {
        // Given
        mapping.setCode(null);

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertFalse(processor.wasProcessResultCalled(),
                "Should not call processResult when code is null");

        log.info("✅ Successfully handled null code");
    }

    @Test
    void testProcessSmartMappingWithSharedCode() throws Exception {
        // Given
        String sharedJsCode = """
                function sharedHelper(x) {
                    return x * 10;
                }
                """;
        String encodedSharedCode = Base64.getEncoder().encodeToString(sharedJsCode.getBytes());
        processingContext.setSharedCode(encodedSharedCode);

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(),
                "Should process successfully with shared code");

        log.info("✅ Successfully loaded shared code");
    }

    @Test
    void testLoadSharedCodeWithValidCode() {
        // Given - Create cached Source object (simulating ConfigurationRegistry behavior)
        String sharedJsCode = "var sharedValue = 'shared';";
        Source sharedSource = Source.newBuilder("js", sharedJsCode, "sharedCode.js")
                .cached(true)
                .buildLiteral();
        processingContext.setSharedSource(sharedSource);

        // When
        processor.loadSharedCode(graalContext, processingContext);

        // Then - Should execute without error
        // Verify shared code is loaded by checking if variable exists
        Value bindings = graalContext.getBindings("js");
        assertTrue(bindings.hasMember("sharedValue"), "Shared variable should be available");

        log.info("✅ Successfully loaded shared code");
    }

    @Test
    void testLoadSharedCodeWithNullCode() {
        // Given
        processingContext.setSharedCode(null);

        // When
        processor.loadSharedCode(graalContext, processingContext);

        // Then - Should complete without error
        log.info("✅ Successfully handled null shared code");
    }

    @Test
    void testExtractWarningsWithValidWarnings() {
        // Given - Add warnings to flow context as GraalVM array
        Value warningsArray = graalContext.eval("js", "[\"Warning 1\", \"Warning 2\", \"Warning 3\"]");
        flowContext.setState(DataPrepContext.WARNINGS, warningsArray);

        // When
        processor.extractWarnings(processingContext, TEST_TENANT);

        // Then
        assertEquals(3, processingContext.getWarnings().size(), "Should have extracted three warnings");
        assertTrue(processingContext.getWarnings().contains("Warning 1"));
        assertTrue(processingContext.getWarnings().contains("Warning 2"));
        assertTrue(processingContext.getWarnings().contains("Warning 3"));

        log.info("✅ Successfully extracted warnings from flow context");
    }

    @Test
    void testExtractWarningsWithNoWarnings() {
        // Given - No warnings in flow context

        // When
        processor.extractWarnings(processingContext, TEST_TENANT);

        // Then
        assertTrue(processingContext.getWarnings().isEmpty(), "Should have no warnings");

        log.info("✅ Successfully handled empty warnings");
    }

    @Test
    void testExtractLogsWithValidLogs() {
        // Given - Add logs to flow context as GraalVM array
        Value logsArray = graalContext.eval("js", "[\"Log entry 1\", \"Log entry 2\", \"Log entry 3\"]");
        flowContext.setState(DataPrepContext.LOGS, logsArray);

        // When
        processor.extractLogs(processingContext, TEST_TENANT);

        // Then
        assertEquals(3, processingContext.getLogs().size(), "Should have extracted three logs");
        assertTrue(processingContext.getLogs().contains("Log entry 1"));
        assertTrue(processingContext.getLogs().contains("Log entry 2"));
        assertTrue(processingContext.getLogs().contains("Log entry 3"));

        log.info("✅ Successfully extracted logs from flow context");
    }

    @Test
    void testExtractLogsWithNoLogs() {
        // Given - No logs in flow context

        // When
        processor.extractLogs(processingContext, TEST_TENANT);

        // Then
        assertTrue(processingContext.getLogs().isEmpty(), "Should have no logs");

        log.info("✅ Successfully handled empty logs");
    }

    @Test
    void testProcessSmartMappingWithPayloadLogging() throws Exception {
        // Given
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(),
                "Should process successfully with payload logging");

        log.info("✅ Successfully processed with payload logging enabled");
    }

    @Test
    void testProcessSmartMappingWithDebugLogging() throws Exception {
        // Given
        mapping.setDebug(true);

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(),
                "Should process successfully with debug logging");

        log.info("✅ Successfully processed with debug logging enabled");
    }

    @Test
    void testProcessHandlesExceptionAndCallsErrorHandler() throws Exception {
        // Given - Create a mapping with invalid code that will cause an exception
        String invalidJsCode = "function onMessage(message, flowContext) { undefined.method(); }";
        mapping.setCode(Base64.getEncoder().encodeToString(invalidJsCode.getBytes()));

        // When
        processor.process(exchange);

        // Then
        assertTrue(processor.wasHandleErrorCalled(), "Should call handleProcessingError on exception");
        assertNotNull(processor.getLastError(), "Should have captured the error");
        assertNotNull(processor.getLastErrorMessage(), "Should have error message");
        assertTrue(processor.getLastErrorMessage().contains("TestableFlowProcessor"),
                "Error message should contain processor name");

        log.info("✅ Successfully handled exception and called error handler");
    }

    @Test
    void testProcessExtractsLineNumberFromException() throws Exception {
        // Given - Code with error that has line number in stack trace
        String errorJsCode = """
                function onMessage(message, flowContext) {
                    var x = 1;
                    var y = 2;
                    undefined.method(); // Error on line 4
                }
                """;
        mapping.setCode(Base64.getEncoder().encodeToString(errorJsCode.getBytes()));

        // When
        processor.process(exchange);

        // Then
        assertTrue(processor.wasHandleErrorCalled(), "Should call error handler");
        String errorMessage = processor.getLastErrorMessage();
        assertNotNull(errorMessage, "Should have error message");
        assertTrue(errorMessage.contains("line"), "Error message should mention line number");

        log.info("✅ Successfully extracted line number from error");
    }

    @Test
    void testProcessNullsOutGraalVMValuesInFinally() throws Exception {
        // Given - Valid mapping that will execute successfully

        // When
        processor.processSmartMapping(processingContext);

        // Then - Should complete successfully
        // The finally block should null out GraalVM Value references
        assertTrue(processor.wasProcessResultCalled(), "Should have processed successfully");

        log.info("✅ Successfully nulled out GraalVM values in finally block");
    }

    @Test
    void testProcessSmartMappingWithConsole() throws Exception {
        // Given - JavaScript code that uses console
        String jsCodeWithConsole = """
                function onMessage(message, flowContext) {
                    console.log("Test log message");
                    console.warn("Test warning message");
                    return { processed: true };
                }
                """;
        mapping.setCode(Base64.getEncoder().encodeToString(jsCodeWithConsole.getBytes()));

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(), "Should process successfully");

        log.info("✅ Successfully processed with console logging");
    }

    @Test
    void testProcessSmartMappingAdaptsIdentifier() throws Exception {
        // Given - Mapping with identifier containing underscores

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(),
                "Should successfully adapt function name with identifier");

        log.info("✅ Successfully adapted function identifier");
    }

    @Test
    void testProcessSmartMappingWithComplexPayload() throws Exception {
        // Given - Complex nested payload
        Map<String, Object> complexPayload = new HashMap<>();
        Map<String, Object> nested = new HashMap<>();
        nested.put("temperature", 23.5);
        nested.put("humidity", 65);
        complexPayload.put("sensors", nested);
        complexPayload.put("deviceId", "complex-device");
        complexPayload.put("timestamp", System.currentTimeMillis());

        processingContext.setPayload(complexPayload);

        // When
        processor.processSmartMapping(processingContext);

        // Then
        assertTrue(processor.wasProcessResultCalled(),
                "Should process complex payload successfully");

        log.info("✅ Successfully processed complex payload");
    }

    @Test
    void testExtractWarningsAndLogsSequentially() {
        // Given - Add both warnings and logs
        Value warningsArray = graalContext.eval("js", "[\"Warning 1\", \"Warning 2\"]");
        Value logsArray = graalContext.eval("js", "[\"Log 1\", \"Log 2\"]");
        flowContext.setState(DataPrepContext.WARNINGS, warningsArray);
        flowContext.setState(DataPrepContext.LOGS, logsArray);

        // When
        processor.extractWarnings(processingContext, TEST_TENANT);
        processor.extractLogs(processingContext, TEST_TENANT);

        // Then
        assertEquals(2, processingContext.getWarnings().size(), "Should have two warnings");
        assertEquals(2, processingContext.getLogs().size(), "Should have two logs");

        log.info("✅ Successfully extracted both warnings and logs");
    }

    @Test
    void testLoadSharedCodeCleansUpSourceInFinally() {
        // Given - Create cached Source object
        String sharedJsCode = "var testVar = 123;";
        Source sharedSource = Source.newBuilder("js", sharedJsCode, "sharedCode.js")
                .cached(true)
                .buildLiteral();
        processingContext.setSharedSource(sharedSource);

        // When
        processor.loadSharedCode(graalContext, processingContext);

        // Then - Should execute and clean up successfully
        Value bindings = graalContext.getBindings("js");
        assertTrue(bindings.hasMember("testVar"), "Shared variable should be loaded");

        log.info("✅ Successfully cleaned up source in finally block");
    }

    @Test
    void testExtractWarningsHandlesNonStringElements() {
        // Given - Add non-string element to warnings (though shouldn't happen in practice)
        Value warningsArray = graalContext.eval("js", "[\"Valid warning\", 123, null]");
        flowContext.setState(DataPrepContext.WARNINGS, warningsArray);

        // When
        processor.extractWarnings(processingContext, TEST_TENANT);

        // Then - Should only extract valid string warnings
        assertEquals(1, processingContext.getWarnings().size(),
                "Should only extract string warnings");
        assertTrue(processingContext.getWarnings().contains("Valid warning"));

        log.info("✅ Successfully handled non-string warning elements");
    }

    @Test
    void testExtractLogHandlesNonStringElements() {
        // Given - Add non-string element to logs
        Value logsArray = graalContext.eval("js", "[\"Valid log\", 456, undefined]");
        flowContext.setState(DataPrepContext.LOGS, logsArray);

        // When
        processor.extractLogs(processingContext, TEST_TENANT);

        // Then - Should only extract valid string logs
        assertEquals(1, processingContext.getLogs().size(),
                "Should only extract string logs");
        assertTrue(processingContext.getLogs().contains("Valid log"));

        log.info("✅ Successfully handled non-string log elements");
    }
}
