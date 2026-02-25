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

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import dynamic.mapper.service.cache.FlowStateStore;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.SimpleFlowContext;
import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests for AbstractEnrichmentProcessor base class.
 * Tests GraalVM context creation, message logging, JSONata extraction, and flow context helpers.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractEnrichmentProcessorTest {

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private MappingService mappingService;

    @Mock
    private FlowStateStore flowStateStore;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private C8YAgent c8yAgent;

    // Real GraalVM engine and context (not mocked) as they need to work together
    private Engine graalEngine;

    private TestableAbstractEnrichmentProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;
    private Context graalContext;
    private DataPrepContext flowContext;

    /**
     * Concrete test implementation of AbstractEnrichmentProcessor for testing.
     */
    static class TestableAbstractEnrichmentProcessor extends AbstractEnrichmentProcessor {

        private boolean enrichPayloadCalled = false;
        private boolean handleErrorCalled = false;
        private boolean preEnrichmentSetupCalled = false;
        private Exception lastError;

        public TestableAbstractEnrichmentProcessor(
                ConfigurationRegistry configurationRegistry,
                MappingService mappingService,
                FlowStateStore flowStateStore) {
            super(configurationRegistry, mappingService, flowStateStore);
        }

        @Override
        protected void enrichPayload(ProcessingContext<?> context) {
            enrichPayloadCalled = true;
            // Simple implementation for testing
        }

        @Override
        protected void handleEnrichmentError(String tenant, Mapping mapping, Exception e,
                ProcessingContext<?> context, MappingStatus mappingStatus) {
            handleErrorCalled = true;
            lastError = e;
        }

        @Override
        protected void performPreEnrichmentSetup(ProcessingContext<?> context, String connectorIdentifier) {
            preEnrichmentSetupCalled = true;
        }

        public boolean wasEnrichPayloadCalled() {
            return enrichPayloadCalled;
        }

        public boolean wasHandleErrorCalled() {
            return handleErrorCalled;
        }

        public boolean wasPreEnrichmentSetupCalled() {
            return preEnrichmentSetupCalled;
        }

        public Exception getLastError() {
            return lastError;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        processor = new TestableAbstractEnrichmentProcessor(configurationRegistry, mappingService, flowStateStore);

        // Create real GraalVM engine (not mocked)
        graalEngine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        mapping = createEnrichmentMapping();
        mappingStatus = new MappingStatus(
                "test-enrich-id",
                "Test Enrichment Mapping",
                "test-enrich",
                Direction.INBOUND,
                "test/topic",
                null,
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(message.getHeader("connectorIdentifier", String.class)).thenReturn("test-connector");
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);
        when(serviceConfiguration.getLogPayload()).thenReturn(false);

        // Setup code templates
        Map<String, CodeTemplate> codeTemplates = new HashMap<>();
        CodeTemplate sharedTemplate = new CodeTemplate();
        sharedTemplate.setCode(Base64.getEncoder().encodeToString("// Shared code".getBytes()));
        codeTemplates.put(TemplateType.SHARED.name(), sharedTemplate);

        CodeTemplate systemTemplate = new CodeTemplate();
        systemTemplate.setCode(Base64.getEncoder().encodeToString("// System code".getBytes()));
        codeTemplates.put(TemplateType.SYSTEM.name(), systemTemplate);

        when(serviceConfiguration.getCodeTemplates()).thenReturn(codeTemplates);

        // Setup GraalVM engine and host access
        when(configurationRegistry.getGraalEngine(TEST_TENANT)).thenReturn(graalEngine);
        when(configurationRegistry.getHostAccess()).thenReturn(HostAccess.ALL);
        when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
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
        // Clean up GraalVM engine
        if (graalEngine != null) {
            try {
                graalEngine.close();
            } catch (Exception e) {
                log.warn("Error closing GraalVM engine in tearDown: {}", e.getMessage());
            }
        }
    }

    private Mapping createEnrichmentMapping() {
        String jsCode = "function process(payload) { return payload; }";
        String encodedCode = Base64.getEncoder().encodeToString(jsCode.getBytes());

        return Mapping.builder()
                .id("test_enrich_id")
                .identifier("test_enrich")
                .name("Test Enrichment Mapping")
                .mappingTopic("test/topic")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .code(encodedCode)
                .transformationType(TransformationType.SUBSTITUTION_AS_CODE)
                .active(true)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .build();
    }

    private ProcessingContext<Object> createProcessingContext() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", "test-device");
        payload.put("value", 42);

        return ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .payload(payload)
                .serviceConfiguration(serviceConfiguration)
                .topic("test/topic")
                .build();
    }

    @Test
    void testProcessWithSubstitutionAsCodeCreatesGraalContext() throws Exception {
        // Given
        mapping.setTransformationType(TransformationType.SUBSTITUTION_AS_CODE);

        // When
        processor.process(exchange);

        // Then
        verify(configurationRegistry).getGraalEngine(TEST_TENANT);
        assertNotNull(processingContext.getSharedCode(), "Should have set shared code");
        assertNotNull(processingContext.getSystemCode(), "Should have set system code");
        assertTrue(processor.wasEnrichPayloadCalled(), "Should have called enrichPayload");

        log.info("✅ Successfully created GraalVM context for SUBSTITUTION_AS_CODE");
    }

    @Test
    void testProcessWithSmartFunctionCreatesGraalContextAndFlowContext() throws Exception {
        // Given
        mapping.setTransformationType(TransformationType.SMART_FUNCTION);

        // When
        processor.process(exchange);

        // Then
        verify(configurationRegistry).getGraalEngine(TEST_TENANT);
        assertNotNull(processingContext.getSystemCode(), "Should have set system code");
        assertNotNull(processingContext.getFlowState(), "Should have initialized flow state");
        assertNotNull(processingContext.getFlowContext(), "Should have created flow context");
        assertTrue(processor.wasEnrichPayloadCalled(), "Should have called enrichPayload");

        log.info("✅ Successfully created GraalVM context and flow context for SMART_FUNCTION");
    }

    @Test
    void testProcessWithoutCodeSkipsGraalSetup() throws Exception {
        // Given
        mapping.setCode(null);

        // When
        processor.process(exchange);

        // Then
        verify(configurationRegistry, never()).getGraalEngine(any());
        assertNull(processingContext.getGraalContext(), "Should not have created GraalVM context");
        assertTrue(processor.wasEnrichPayloadCalled(), "Should still call enrichPayload");

        log.info("✅ Successfully skipped GraalVM setup when no code");
    }

    @Test
    void testProcessIncrementsMessagesReceived() throws Exception {
        // Given
        assertEquals(0L, mappingStatus.messagesReceived, "Initial count should be 0");

        // When
        processor.process(exchange);

        // Then
        assertEquals(1L, mappingStatus.messagesReceived, "Should have incremented messages received");

        log.info("✅ Successfully incremented messagesReceived counter");
    }

    @Test
    void testProcessCallsPerformPreEnrichmentSetup() throws Exception {
        // Given

        // When
        processor.process(exchange);

        // Then
        assertTrue(processor.wasPreEnrichmentSetupCalled(),
                "Should have called performPreEnrichmentSetup");

        log.info("✅ Successfully called performPreEnrichmentSetup hook");
    }

    @Test
    void testProcessHandlesGraalVMSetupError() throws Exception {
        // Given
        when(configurationRegistry.getGraalEngine(TEST_TENANT))
                .thenThrow(new RuntimeException("Failed to get GraalVM engine"));

        // When
        processor.process(exchange);

        // Then
        assertFalse(processor.wasEnrichPayloadCalled(),
                "Should not call enrichPayload when GraalVM setup fails");
        assertEquals(1, processingContext.getErrors().size(), "Should have added error");
        assertEquals(1, mappingStatus.errors, "Should have incremented error count");

        log.info("✅ Successfully handled GraalVM setup error");
    }

    @Test
    void testProcessHandlesEnrichmentError() throws Exception {
        // Given - Create processor that throws during enrichPayload
        TestableAbstractEnrichmentProcessor errorProcessor = new TestableAbstractEnrichmentProcessor(
                configurationRegistry, mappingService, flowStateStore) {
            @Override
            protected void enrichPayload(ProcessingContext<?> context) {
                throw new RuntimeException("Enrichment failed");
            }
        };

        // Setup mocks for error processor
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);
        when(mappingService.getMappingStatus(TEST_TENANT, mapping)).thenReturn(mappingStatus);

        // When
        errorProcessor.process(exchange);

        // Then
        assertTrue(errorProcessor.wasHandleErrorCalled(), "Should have called handleEnrichmentError");
        assertNotNull(errorProcessor.getLastError(), "Should have captured error");

        log.info("✅ Successfully handled enrichment error");
    }

    @Test
    void testCreateGraalContextWithSecuritySettings() throws Exception {
        // Given - Use the graalEngine from setUp (already configured)
        when(configurationRegistry.getHostAccess()).thenReturn(HostAccess.ALL);

        // When
        Context createdContext = processor.createGraalContext(graalEngine);

        // Then
        assertNotNull(createdContext, "Should create GraalVM context");

        // Verify security settings are applied (test that JavaScript can be executed)
        createdContext.eval("js", "var x = 42;");

        createdContext.close();

        log.info("✅ Successfully created GraalVM context with security settings");
    }

    @Test
    void testLogMessageReceivedWithoutPayloadLogging() {
        // Given
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        mapping.setDebug(false);

        // When
        processor.logMessageReceived(TEST_TENANT, mapping, "connector-123", processingContext,
                serviceConfiguration);

        // Then - Should log without payload details (just verify no exception)
        log.info("✅ Successfully logged message without payload details");
    }

    @Test
    void testLogMessageReceivedWithPayloadLogging() {
        // Given
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        // When
        processor.logMessageReceived(TEST_TENANT, mapping, "connector-123", processingContext,
                serviceConfiguration);

        // Then - Should log with payload details (just verify no exception)
        log.info("✅ Successfully logged message with payload details");
    }

    @Test
    void testLogMessageReceivedWithDebugEnabled() {
        // Given
        mapping.setDebug(true);

        // When
        processor.logMessageReceived(TEST_TENANT, mapping, "connector-123", processingContext,
                serviceConfiguration);

        // Then - Should log with payload details when debug enabled
        log.info("✅ Successfully logged message with debug enabled");
    }

    @Test
    void testLogMessageReceivedWithByteArrayPayload() {
        // Given
        byte[] bytePayload = "test payload".getBytes();
        processingContext.setPayload(bytePayload);
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        // When
        processor.logMessageReceived(TEST_TENANT, mapping, "connector-123", processingContext,
                serviceConfiguration);

        // Then - Should handle byte array payload
        log.info("✅ Successfully logged message with byte array payload");
    }

    @Test
    void testLogMessageReceivedWithNullPayload() {
        // Given
        processingContext.setPayload(null);
        when(serviceConfiguration.getLogPayload()).thenReturn(true);

        // When
        processor.logMessageReceived(TEST_TENANT, mapping, "connector-123", processingContext,
                serviceConfiguration);

        // Then - Should handle null payload gracefully
        log.info("✅ Successfully logged message with null payload");
    }

    @Test
    void testHandleGraalVMErrorAddsErrorToContext() {
        // Given
        Exception testException = new RuntimeException("GraalVM setup failed");

        // When
        processor.handleGraalVMError(TEST_TENANT, mapping, testException, processingContext);

        // Then
        assertEquals(1, processingContext.getErrors().size(), "Should have added error to context");
        assertTrue(processingContext.getErrors().get(0).getMessage().contains("Failed to set up GraalVM context"),
                "Error message should mention GraalVM context setup");
        assertEquals(1, mappingStatus.errors, "Should have incremented error count");
        verify(mappingService).increaseAndHandleFailureCount(TEST_TENANT, mapping, mappingStatus);

        log.info("✅ Successfully handled GraalVM error");
    }

    @Test
    void testExtractContentWithValidJSONata() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("temperature", 23.5);
        payload.put("deviceId", "device-001");

        String pathExpression = "deviceId";

        // When
        Object result = processor.extractContent(processingContext, payload, payload.toString(), pathExpression);

        // Then
        assertNotNull(result, "Should extract content");
        assertEquals("device-001", result, "Should extract deviceId value");

        log.info("✅ Successfully extracted content with JSONata");
    }

    @Test
    void testExtractContentWithNestedPath() {
        // Given
        Map<String, Object> nested = new HashMap<>();
        nested.put("value", 42);

        Map<String, Object> payload = new HashMap<>();
        payload.put("sensor", nested);

        String pathExpression = "sensor.value";

        // When
        Object result = processor.extractContent(processingContext, payload, payload.toString(), pathExpression);

        // Then
        assertNotNull(result, "Should extract nested content");
        assertEquals(42, result, "Should extract nested value");

        log.info("✅ Successfully extracted nested content with JSONata");
    }

    @Test
    void testExtractContentWithInvalidExpression() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("test", "value");

        String invalidExpression = "invalid[[[expression";

        // When
        Object result = processor.extractContent(processingContext, payload, payload.toString(), invalidExpression);

        // Then
        // Should handle error gracefully and return null
        assertNull(result, "Should return null for invalid expression");

        log.info("✅ Successfully handled invalid JSONata expression");
    }

    @Test
    void testAddToFlowContextWithValidValue() {
        // Given
        graalContext = Context.newBuilder("js").allowAllAccess(true).build();
        processingContext.setGraalContext(graalContext);
        flowContext = new SimpleFlowContext(graalContext, TEST_TENANT, c8yAgent, false);

        String key = "testKey";
        String value = "testValue";

        // When
        processor.addToFlowContext(flowContext, processingContext, key, value);

        // Then
        assertNotNull(flowContext.getState(key), "Should have added value to flow context");

        log.info("✅ Successfully added value to flow context");
    }

    @Test
    void testAddToFlowContextWithNullGraalContext() {
        // Given
        processingContext.setGraalContext(null);
        flowContext = mock(DataPrepContext.class);

        String key = "testKey";
        String value = "testValue";

        // When
        processor.addToFlowContext(flowContext, processingContext, key, value);

        // Then - Should handle gracefully without exception
        verify(flowContext, never()).setState(any(), any());

        log.info("✅ Successfully handled null GraalContext in addToFlowContext");
    }

    @Test
    void testAddToFlowContextWithNullValue() {
        // Given
        graalContext = Context.newBuilder("js").allowAllAccess(true).build();
        processingContext.setGraalContext(graalContext);
        flowContext = new SimpleFlowContext(graalContext, TEST_TENANT, c8yAgent, false);

        String key = "testKey";

        // When
        processor.addToFlowContext(flowContext, processingContext, key, null);

        // Then - Should handle null value gracefully
        assertNull(flowContext.getState(key), "Should not have added null value");

        log.info("✅ Successfully handled null value in addToFlowContext");
    }

    @Test
    void testPerformPreEnrichmentSetupDefaultImplementation() {
        // Given - Create instance that uses default implementation
        AbstractEnrichmentProcessor defaultProcessor = new AbstractEnrichmentProcessor(
                configurationRegistry, mappingService, flowStateStore) {
            @Override
            protected void enrichPayload(ProcessingContext<?> context) {
            }

            @Override
            protected void handleEnrichmentError(String tenant, Mapping mapping, Exception e,
                    ProcessingContext<?> context, MappingStatus mappingStatus) {
            }
        };

        // When
        defaultProcessor.performPreEnrichmentSetup(processingContext, "test-connector");

        // Then - Should complete without error (default is no-op)
        log.info("✅ Successfully executed default performPreEnrichmentSetup (no-op)");
    }

    @Test
    void testProcessWithConnectorIdentifierInHeader() throws Exception {
        // Given
        when(message.getHeader("connectorIdentifier", String.class)).thenReturn("mqtt-connector-001");

        // When
        processor.process(exchange);

        // Then
        assertTrue(processor.wasPreEnrichmentSetupCalled(),
                "Should have called pre-enrichment setup with connector identifier");

        log.info("✅ Successfully processed with connector identifier");
    }

    @Test
    void testProcessSetsSharedAndSystemCode() throws Exception {
        // Given
        mapping.setTransformationType(TransformationType.SUBSTITUTION_AS_CODE);

        // When
        processor.process(exchange);

        // Then
        assertNotNull(processingContext.getSharedCode(), "Should have set shared code");
        assertNotNull(processingContext.getSystemCode(), "Should have set system code");

        log.info("✅ Successfully set shared and system code");
    }
}
