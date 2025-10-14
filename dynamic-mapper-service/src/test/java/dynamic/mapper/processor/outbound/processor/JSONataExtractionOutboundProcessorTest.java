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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JSONataExtractionOutboundProcessorTest {

    @Mock
    private MappingService mappingService;

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private JSONataExtractionOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "6926746";
    private static final String TEST_EXTERNAL_ID = "berlin_01";
    private static final String TEST_EXTERNAL_ID_TYPE = "c8y_Serial";

    private Mapping mapping;
    private MappingStatus mappingStatus;
    private ProcessingContext<Object> processingContext;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        processor = new JSONataExtractionOutboundProcessor();
        objectMapper = new ObjectMapper();
        injectDependencies();

        mapping = createOutboundEventMapping();
        mappingStatus = new MappingStatus(
                "56265593",
                "Mapping - 52",
                "k4m4xjqn",
                Direction.OUTBOUND,
                "evt/outbound/#",
                null,
                0L, 0L, 0L, 0L, 0L, null);

        processingContext = createProcessingContext();

        // Setup basic mocks
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(processingContext);

        // FIX: Use eq() for specific tenant, any() for mapping
        when(mappingService.getMappingStatus(eq(TEST_TENANT), any(Mapping.class))).thenReturn(mappingStatus);

        when(serviceConfiguration.isLogPayload()).thenReturn(false);
        when(serviceConfiguration.isLogSubstitution()).thenReturn(false);
    }

    private void injectDependencies() throws Exception {
        injectField("mappingService", mappingService);
    }

    private void injectField(String fieldName, Object value) throws Exception {
        Field field = JSONataExtractionOutboundProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(processor, value);
    }

    private Mapping createOutboundEventMapping() {
        return Mapping.builder()
                .id("56265593")
                .identifier("k4m4xjqn")
                .name("Mapping - 52")
                .publishTopic("evt/outbound/#")
                .publishTopicSample("evt/outbound/berlin_01")
                .targetAPI(API.EVENT)
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.DEFAULT)
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
                .snoopStatus(SnoopStatus.STOPPED)
                .snoopedTemplates(createSnoopedTemplates())
                .filterMapping("$exists(reason)")
                .filterInventory("")
                .maxFailureCount(0)
                .qos(Qos.AT_LEAST_ONCE)
                .lastUpdate(1758901282690L)
                .sourceTemplate(createSourceTemplate())
                .targetTemplate(createTargetTemplate())
                .substitutions(createOutboundSubstitutions())
                .build();
    }

    private String createSourceTemplate() {
        return "{\"lastUpdated\":\"2025-09-17T10:40:36.496Z\",\"creationTime\":\"2025-09-17T10:40:36.496Z\",\"self\":\"https://t2050305588.eu-latest.cumulocity.com/event/events/266315\",\"id\":\"266315\",\"time\":\"2025-09-17T12:40:36.383+02:00\",\"text\":\"'Bus stopped at traffic light\",\"source\":{\"name\":\"PGW2x.100\",\"self\":\"https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6926746\",\"id\":\"6926746\"},\"type\":\"c8y_BusEvent\",\"bus_event\":\"stop_event\",\"reason\":\"poor road conditions now\"}";
    }

    private String createTargetTemplate() {
        return "{\"text\":\"This is a new test event.\",\"time\":\"2022-08-05T00:14:49.389+02:00\",\"type\":\"c8y_TestEvent\"}";
    }

    private Substitution[] createOutboundSubstitutions() {
        return new Substitution[] {
                Substitution.builder()
                        .pathSource("_IDENTITY_.externalId")
                        .pathTarget("_TOPIC_LEVEL_[2]")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("reason")
                        .pathTarget("text")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("$now()")
                        .pathTarget("time")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
    }

    private List<String> createSnoopedTemplates() {
        List<String> templates = new ArrayList<>();
        templates.add(
                "{\"lastUpdated\":\"2025-09-17T10:04:28.309Z\",\"creationTime\":\"2025-09-17T10:04:28.309Z\",\"self\":\"https://t2050305588.eu-latest.cumulocity.com/event/events/266308\",\"id\":\"266308\",\"time\":\"2025-09-17T12:04:28.180+02:00\",\"text\":\"'Bus stopped at traffic light\",\"source\":{\"name\":\"PGW2x.100\",\"self\":\"https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6926746\",\"id\":\"6926746\"},\"type\":\"c8y_BusEvent\",\"bus_event\":\"stop_event\",\"reason\":\"poor road conditions\",\"_IDENTITY_\":{\"c8ySourceId\":\"6926746\",\"externalId\":\"berlin_01\",\"externalIdType\":\"c8y_Serial\"},\"_TOPIC_LEVEL_\":[\"evt\",\"outbound\",\"#\"]}");
        templates.add(
                "{\"lastUpdated\":\"2025-09-17T10:40:36.496Z\",\"creationTime\":\"2025-09-17T10:40:36.496Z\",\"self\":\"https://t2050305588.eu-latest.cumulocity.com/event/events/266315\",\"id\":\"266315\",\"time\":\"2025-09-17T12:40:36.383+02:00\",\"text\":\"'Bus stopped at traffic light\",\"source\":{\"name\":\"PGW2x.100\",\"self\":\"https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6926746\",\"id\":\"6926746\"},\"type\":\"c8y_BusEvent\",\"bus_event\":\"stop_event\",\"reason\":\"poor road conditions now\",\"_IDENTITY_\":{\"c8ySourceId\":\"6926746\",\"externalId\":\"berlin_01\",\"externalIdType\":\"c8y_Serial\"},\"_TOPIC_LEVEL_\":[\"evt\",\"outbound\",\"#\"]}");
        return templates;
    }

    private ProcessingContext<Object> createProcessingContext() {
        Map<String, Object> payload = createEventPayload();

        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .serviceConfiguration(serviceConfiguration)
                .payload(payload)
                .build();

        return context;
    }

    private Map<String, Object> createEventPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("lastUpdated", "2025-09-17T10:40:36.496Z");
        payload.put("creationTime", "2025-09-17T10:40:36.496Z");
        payload.put("self", "https://t2050305588.eu-latest.cumulocity.com/event/events/266315");
        payload.put("id", "266315");
        payload.put("time", "2025-09-17T12:40:36.383+02:00");
        payload.put("text", "'Bus stopped at traffic light");
        payload.put("type", "c8y_BusEvent");
        payload.put("bus_event", "stop_event");
        payload.put("reason", "poor road conditions now");

        // Add source information
        Map<String, Object> source = new HashMap<>();
        source.put("name", "PGW2x.100");
        source.put("self", "https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6926746");
        source.put("id", TEST_DEVICE_ID);
        payload.put("source", source);

        // Add identity information for outbound processing
        Map<String, Object> identity = new HashMap<>();
        identity.put("c8ySourceId", TEST_DEVICE_ID);
        identity.put("externalId", TEST_EXTERNAL_ID);
        identity.put("externalIdType", TEST_EXTERNAL_ID_TYPE);
        payload.put("_IDENTITY_", identity);

        // Add topic level information
        List<String> topicLevels = new ArrayList<>();
        topicLevels.add("evt");
        topicLevels.add("outbound");
        topicLevels.add("#");
        payload.put("_TOPIC_LEVEL_", topicLevels);

        return payload;
    }

    @Test
    void testExtractFromSourceBasicSubstitutions() throws Exception {
        // When
        processor.process(exchange);

        // Then - Verify no exceptions and processing cache is populated
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();
        assertFalse(processingCache.isEmpty(), "Processing cache should be populated");

        // Verify external ID extraction to topic level
        assertTrue(processingCache.containsKey("_TOPIC_LEVEL_[2]"),
                "Should have extracted external ID to topic level");
        List<SubstituteValue> topicLevelValues = processingCache.get("_TOPIC_LEVEL_[2]");
        assertFalse(topicLevelValues.isEmpty(), "Should have topic level values");
        assertEquals(TEST_EXTERNAL_ID, topicLevelValues.get(0).getValue(),
                "Should extract external ID for topic level");

        // Verify reason extraction to text
        assertTrue(processingCache.containsKey("text"),
                "Should have extracted reason to text field");
        List<SubstituteValue> textValues = processingCache.get("text");
        assertFalse(textValues.isEmpty(), "Should have text values");
        assertEquals("poor road conditions now", textValues.get(0).getValue(),
                "Should extract reason value");

        // Verify $now() function extraction
        assertTrue(processingCache.containsKey("time"),
                "Should have extracted current time");
        List<SubstituteValue> timeValues = processingCache.get("time");
        assertFalse(timeValues.isEmpty(), "Should have time values");
        assertNotNull(timeValues.get(0).getValue(), "Time value should not be null");

        log.info("✅ Basic outbound substitutions test passed");
        log.info("   - External ID → Topic Level: {}", topicLevelValues.get(0).getValue());
        log.info("   - Reason → Text: {}", textValues.get(0).getValue());
        log.info("   - Current Time: {}", timeValues.get(0).getValue());
    }

    @Test
    void testExtractFromSourceWithDebugLogging() throws Exception {
        // Given - Enable debug logging
        mapping.setDebug(true);
        when(serviceConfiguration.isLogPayload()).thenReturn(true);
        when(serviceConfiguration.isLogSubstitution()).thenReturn(true);

        // When
        processor.process(exchange);

        // Then
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();
        assertFalse(processingCache.isEmpty(), "Processing cache should be populated");

        // Verify all expected substitutions are present
        assertEquals(3, processingCache.size(),
                "Should have processed all three substitutions");

        log.info("✅ Debug logging test passed with {} substitutions processed",
                processingCache.size());
    }

    @Test
    void testExtractFromSourceWithArrayExpansion() throws Exception {
        // Given - Modify mapping to have array expansion
        Substitution[] substitutions = mapping.getSubstitutions();
        substitutions[0].setExpandArray(true); // Enable array expansion for first substitution

        // Modify payload to have array in _TOPIC_LEVEL_
        Map<String, Object> payload = (Map<String, Object>) processingContext.getPayload();
        List<String> topicLevels = new ArrayList<>();
        topicLevels.add("evt");
        topicLevels.add("outbound");
        topicLevels.add("berlin_01");
        topicLevels.add("hamburg_02"); // Add second location
        payload.put("_TOPIC_LEVEL_", topicLevels);

        // When
        processor.process(exchange);

        // Then
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();
        assertTrue(processingCache.containsKey("_TOPIC_LEVEL_[2]"),
                "Should have processed topic level array");

        // Note: The actual array expansion behavior would depend on the specific
        // JSONata expression evaluation implementation
        log.info("✅ Array expansion test completed");
    }

    @Test
    void testExtractFromSourceWithComplexJSONataExpressions() throws Exception {
        // Given - Add more complex substitutions
        List<Substitution> substitutionList = new ArrayList<>();

        // Add original substitutions
        for (Substitution sub : mapping.getSubstitutions()) {
            substitutionList.add(sub);
        }

        // Add complex JSONata expressions
        substitutionList.add(Substitution.builder()
                .pathSource("source.name & ' - ' & type")
                .pathTarget("deviceInfo")
                .repairStrategy(RepairStrategy.DEFAULT)
                .expandArray(false)
                .build());

        substitutionList.add(Substitution.builder()
                .pathSource("$number(source.id)")
                .pathTarget("numericDeviceId")
                .repairStrategy(RepairStrategy.DEFAULT)
                .expandArray(false)
                .build());

        mapping.setSubstitutions(substitutionList.toArray(new Substitution[0]));

        // When
        processor.process(exchange);

        // Then
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();

        // Should have processed all substitutions
        assertTrue(processingCache.size() >= 3,
                "Should have processed at least the basic substitutions");

        log.info("✅ Complex JSONata expressions test passed");
        log.info("   - Processing cache contains {} entries", processingCache.size());
    }

    @Test
    void testExtractFromSourceWithInvalidJSONataExpression() throws Exception {
        // Given - Create substitutions with syntactically invalid JSONata
        Substitution[] problematicSubstitutions = new Substitution[] {
                Substitution.builder()
                        .pathSource("$invalid syntax that will fail to parse}") // Invalid JSONata
                        .pathTarget("testTarget")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
        mapping.setSubstitutions(problematicSubstitutions);

        // When - Should not throw exception, errors should be handled gracefully
        assertDoesNotThrow(() -> processor.process(exchange),
                "Processor should handle invalid JSONata expression gracefully");

        // Then - Check error handling
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();

        // The processor handles the error gracefully by:
        // 1. Logging the error (as seen in console output)
        // 2. Adding a null/empty entry to the processing cache
        // 3. Continuing processing without throwing exception

        assertTrue(processingCache.containsKey("testTarget"),
                "Should have entry for testTarget even with invalid JSONata");

        List<SubstituteValue> values = processingCache.get("testTarget");
        assertNotNull(values, "Should have values list");

        // The value will be null since the expression failed to evaluate
        if (!values.isEmpty()) {
            SubstituteValue value = values.get(0);
            assertNull(value.getValue(),
                    "Value should be null for invalid JSONata expression");
        }

        log.info("✅ Invalid JSONata expression test passed - error was handled gracefully");
        log.info("   - Processing cache size: {}", processingCache.size());
        log.info("   - Error was logged but processing continued");
    }

    @Test
    void testExtractFromSourceErrorHandling() throws Exception {
        // Test 1: Invalid JSONata syntax
        log.info("Testing invalid JSONata syntax...");
        Substitution[] invalidSyntax = new Substitution[] {
                Substitution.builder()
                        .pathSource("$invalid syntax that will fail}")
                        .pathTarget("invalidSyntax")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
        mapping.setSubstitutions(invalidSyntax);

        // Clear any previous errors
        processingContext.getErrors().clear();
        processingContext.getProcessingCache().clear();

        assertDoesNotThrow(() -> processor.process(exchange),
                "Should handle invalid JSONata syntax gracefully");

        // The processor logs the error but doesn't add it to
        // processingContext.getErrors()
        // Instead, it adds a null value to the processing cache
        assertTrue(processingContext.getProcessingCache().containsKey("invalidSyntax"),
                "Should have entry in processing cache even for invalid syntax");

        log.info("   ✓ Invalid syntax handled gracefully (error logged, null value in cache)");

        // Test 2: Missing path (most common real-world scenario)
        log.info("Testing missing path...");
        processingContext.getErrors().clear();
        processingContext.getProcessingCache().clear();

        Substitution[] missingPath = new Substitution[] {
                Substitution.builder()
                        .pathSource("nonexistent.path")
                        .pathTarget("missingPath")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
        mapping.setSubstitutions(missingPath);

        assertDoesNotThrow(() -> processor.process(exchange),
                "Should handle missing path gracefully");

        assertTrue(processingContext.getProcessingCache().containsKey("missingPath"),
                "Should have entry for missing path");

        List<SubstituteValue> values = processingContext.getProcessingCache().get("missingPath");
        if (!values.isEmpty()) {
            assertNull(values.get(0).getValue(),
                    "Value should be null for missing path");
        }

        log.info("   ✓ Missing path handled gracefully");

        log.info("✅ Error handling test passed - all scenarios handled correctly");
    }

    @Test
    void testExtractFromSourceWithNullPayload() throws Exception {
        // Given - Set payload to null to test error handling
        processingContext.setPayload(null);

        // Keep simple substitutions
        Substitution[] substitutions = new Substitution[] {
                Substitution.builder()
                        .pathSource("reason")
                        .pathTarget("testTarget")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
        mapping.setSubstitutions(substitutions);

        // When - Should handle gracefully without throwing exception
        assertDoesNotThrow(() -> processor.process(exchange),
                "Should handle null payload gracefully");

        // Then - Verify that processing handled the null payload
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();

        // The processor should either skip processing or handle it gracefully
        // Either way, it shouldn't crash
        log.info("✅ Null payload test passed - no exception thrown");
        log.info("   - Processing cache size: {}", processingCache.size());
        log.info("   - Errors recorded: {}", processingContext.getErrors().size());
    }

    @Test
    void testExtractFromSourceWithDifferentDataTypes() throws Exception {
        // Given - Payload with various data types
        Map<String, Object> payload = new HashMap<>();
        payload.put("stringValue", "test string");
        payload.put("numberValue", 42);
        payload.put("booleanValue", true);
        payload.put("arrayValue", List.of("item1", "item2", "item3"));

        Map<String, Object> nestedObject = new HashMap<>();
        nestedObject.put("nestedString", "nested value");
        nestedObject.put("nestedNumber", 3.14);
        payload.put("objectValue", nestedObject);

        // Add identity for consistency
        Map<String, Object> identity = new HashMap<>();
        identity.put("externalId", TEST_EXTERNAL_ID);
        payload.put("_IDENTITY_", identity);

        processingContext.setPayload(payload);

        // Create substitutions for different data types
        Substitution[] substitutions = new Substitution[] {
                Substitution.builder()
                        .pathSource("stringValue")
                        .pathTarget("extractedString")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("numberValue")
                        .pathTarget("extractedNumber")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("booleanValue")
                        .pathTarget("extractedBoolean")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("arrayValue[0]")
                        .pathTarget("firstArrayItem")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build(),
                Substitution.builder()
                        .pathSource("objectValue.nestedString")
                        .pathTarget("nestedExtraction")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
        mapping.setSubstitutions(substitutions);

        // When
        processor.process(exchange);

        // Then
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();

        assertEquals(5, processingCache.size(),
                "Should have processed all data type substitutions");

        assertTrue(processingCache.containsKey("extractedString"),
                "Should have extracted string value");
        assertTrue(processingCache.containsKey("extractedNumber"),
                "Should have extracted number value");
        assertTrue(processingCache.containsKey("extractedBoolean"),
                "Should have extracted boolean value");
        assertTrue(processingCache.containsKey("firstArrayItem"),
                "Should have extracted array item");
        assertTrue(processingCache.containsKey("nestedExtraction"),
                "Should have extracted nested value");

        log.info("✅ Different data types extraction test passed");
        log.info("   - String: {}", processingCache.get("extractedString").get(0).getValue());
        log.info("   - Number: {}", processingCache.get("extractedNumber").get(0).getValue());
        log.info("   - Boolean: {}", processingCache.get("extractedBoolean").get(0).getValue());
        log.info("   - Array item: {}", processingCache.get("firstArrayItem").get(0).getValue());
        log.info("   - Nested: {}", processingCache.get("nestedExtraction").get(0).getValue());
    }

    @Test
    void testExtractFromSourceWithEmptySubstitutions() throws Exception {
        // Given - No substitutions
        mapping.setSubstitutions(new Substitution[0]);

        // When
        processor.process(exchange);

        // Then
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();
        assertTrue(processingCache.isEmpty(),
                "Processing cache should be empty with no substitutions");

        log.info("✅ Empty substitutions test passed");
    }

    @Test
    void testExtractFromSourceCompleteOutboundFlow() throws Exception {
        // Given - Complete outbound event payload as it would appear in real processing
        Map<String, Object> completePayload = createCompleteOutboundEventPayload();
        processingContext.setPayload(completePayload);

        // When
        processor.process(exchange);

        // Then - Verify complete processing
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();

        assertFalse(processingCache.isEmpty(),
                "Should have populated processing cache");

        // Verify specific outbound mappings
        if (processingCache.containsKey("_TOPIC_LEVEL_[2]")) {
            assertEquals(TEST_EXTERNAL_ID, processingCache.get("_TOPIC_LEVEL_[2]").get(0).getValue(),
                    "Should map external ID to topic level for outbound routing");
        }

        if (processingCache.containsKey("text")) {
            assertEquals("poor road conditions now", processingCache.get("text").get(0).getValue(),
                    "Should map reason to text field");
        }

        if (processingCache.containsKey("time")) {
            assertNotNull(processingCache.get("time").get(0).getValue(),
                    "Should have current timestamp");
        }

        log.info("✅ Complete outbound flow test passed:");
        log.info("   - Mapping: {} ({})", mapping.getName(), mapping.getIdentifier());
        log.info("   - Direction: {}", mapping.getDirection());
        log.info("   - Target API: {}", mapping.getTargetAPI());
        log.info("   - Substitutions processed: {}", processingCache.size());

        processingCache.forEach((key, values) -> {
            if (!values.isEmpty()) {
                log.info("   - {} → {}", key, values.get(0).getValue());
            }
        });
    }

    private Map<String, Object> createCompleteOutboundEventPayload() {
        try {
            String jsonPayload = "{\n" +
                    "  \"lastUpdated\": \"2025-09-17T10:40:36.496Z\",\n" +
                    "  \"creationTime\": \"2025-09-17T10:40:36.496Z\",\n" +
                    "  \"self\": \"https://t2050305588.eu-latest.cumulocity.com/event/events/266315\",\n" +
                    "  \"id\": \"266315\",\n" +
                    "  \"time\": \"2025-09-17T12:40:36.383+02:00\",\n" +
                    "  \"text\": \"'Bus stopped at traffic light\",\n" +
                    "  \"source\": {\n" +
                    "    \"name\": \"PGW2x.100\",\n" +
                    "    \"self\": \"https://t2050305588.eu-latest.cumulocity.com/inventory/managedObjects/6926746\",\n"
                    +
                    "    \"id\": \"" + TEST_DEVICE_ID + "\"\n" +
                    "  },\n" +
                    "  \"type\": \"c8y_BusEvent\",\n" +
                    "  \"bus_event\": \"stop_event\",\n" +
                    "  \"reason\": \"poor road conditions now\",\n" +
                    "  \"_IDENTITY_\": {\n" +
                    "    \"c8ySourceId\": \"" + TEST_DEVICE_ID + "\",\n" +
                    "    \"externalId\": \"" + TEST_EXTERNAL_ID + "\",\n" +
                    "    \"externalIdType\": \"" + TEST_EXTERNAL_ID_TYPE + "\"\n" +
                    "  },\n" +
                    "  \"_TOPIC_LEVEL_\": [\"evt\", \"outbound\", \"#\"]\n" +
                    "}";

            JsonNode jsonNode = objectMapper.readTree(jsonPayload);
            return objectMapper.convertValue(jsonNode, Map.class);
        } catch (Exception e) {
            log.error("Failed to create complete payload", e);
            return createEventPayload(); // Fallback to simple payload
        }
    }

    @Test
    void testExtractFromSourceWithMissingPath() throws Exception {
        // Given - Create substitutions with a path that doesn't exist in the payload
        Substitution[] substitutions = new Substitution[] {
                Substitution.builder()
                        .pathSource("nonexistent.deeply.nested.path")
                        .pathTarget("testTarget")
                        .repairStrategy(RepairStrategy.DEFAULT)
                        .expandArray(false)
                        .build()
        };
        mapping.setSubstitutions(substitutions);

        // When
        processor.process(exchange);

        // Then - The processor should handle the missing path gracefully
        Map<String, List<SubstituteValue>> processingCache = processingContext.getProcessingCache();

        // Should have created an entry for the target
        assertTrue(processingCache.containsKey("testTarget"),
                "Should have entry for testTarget even with missing source path");

        List<SubstituteValue> values = processingCache.get("testTarget");
        assertNotNull(values, "Should have values list");

        // The value will be null since the path doesn't exist
        if (!values.isEmpty()) {
            SubstituteValue value = values.get(0);
            assertNull(value.getValue(),
                    "Value should be null for non-existent path");
            log.info("✅ Missing path test passed - null value recorded for non-existent path");
        } else {
            log.info("✅ Missing path test passed - empty values list for non-existent path");
        }
    }

}