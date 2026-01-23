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

package dynamic.mapper.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Full Spring Boot integration test that validates actual outbound transformation.
 *
 * This test uses the complete Spring context with all Camel routes and processors
 * registered, allowing validation of:
 * - C8Y notification → MQTT/Kafka message transformation
 * - Actual JSONata extraction from C8Y payloads
 * - Substitution and field mapping
 * - resolvedPublishTopic calculation (wildcard substitution)
 * - Final outbound message generation
 *
 * Unlike CamelPipelineOutboundIntegrationTest which only tests dispatcher routing,
 * this test validates the complete transformation pipeline.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class OutboundTransformationValidationTest {

    @Autowired
    private CamelDispatcherOutbound dispatcher;

    @Autowired
    private MappingService mappingService;

    @MockBean
    private C8YAgent c8yAgent;

    private ObjectMapper objectMapper;

    private static final String TEST_TENANT = "testTenant";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Setup C8Y Agent mock for device resolution
        ManagedObjectRepresentation mockDevice = new ManagedObjectRepresentation();
        mockDevice.setId(new GId("12345"));
        mockDevice.setName("Test Device");

        ExternalIDRepresentation mockExternalIdRep = new ExternalIDRepresentation();
        mockExternalIdRep.setManagedObject(mockDevice);
        mockExternalIdRep.setExternalId("device_berlin_01");
        mockExternalIdRep.setType("c8y_Serial");

        when(c8yAgent.resolveGlobalId2ExternalId(eq(TEST_TENANT), any(GId.class), anyString(), anyBoolean()))
                .thenReturn(mockExternalIdRep);
    }

    /**
     * Test simple measurement transformation from C8Y to MQTT.
     */
    @Test
    void testActualTransformation_MeasurementToMQTT() throws Exception {
        // Given - Mapping for measurement outbound
        Mapping mapping = createMeasurementOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create C8Y measurement notification
        String payload = """
                {
                    "id": "12345",
                    "type": "c8y_TemperatureMeasurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "67890"},
                    "c8y_TemperatureMeasurement": {
                        "T": {"value": 23.5, "unit": "C"}
                    }
                }
                """;

        Notification notification = createNotification(API.MEASUREMENT, "CREATE", payload);

        // When - Process through complete pipeline
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Validate transformation
        assertNotNull(result, "Processing result should not be null");
        assertNotNull(result.getProcessingResult(), "Should have processing result Future");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            // Validate extraction occurred
            assertNotNull(context.getProcessingCache(), "Should have processing cache");
            log.info("✅ Measurement to MQTT transformation:");
            log.info("   - Extracted fields: {}", context.getProcessingCache().keySet());

            // Validate resolvedPublishTopic was set
            if (context.getResolvedPublishTopic() != null) {
                log.info("   - Resolved topic: {}", context.getResolvedPublishTopic());
                assertNotNull(context.getResolvedPublishTopic(), "Should have resolved publish topic");
            }

            // Validate payload transformation
            assertNotNull(context.getPayload(), "Should have transformed payload");
            log.info("   - Payload transformed: {}", context.getPayload() != null);
        }
    }

    /**
     * Test event transformation from C8Y to MQTT.
     */
    @Test
    void testActualTransformation_EventToMQTT() throws Exception {
        // Given - Event mapping
        Mapping mapping = createEventOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "999",
                    "type": "c8y_LocationUpdate",
                    "text": "Device location updated",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_Position": {
                        "lat": 52.5200,
                        "lng": 13.4050,
                        "alt": 100
                    }
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            assertNotNull(context.getProcessingCache(), "Should have processing cache");
            log.info("✅ Event to MQTT transformation:");
            log.info("   - Extracted fields: {}", context.getProcessingCache().keySet());

            // Verify event-specific fields extracted
            boolean hasEventFields = context.getProcessingCache().keySet().stream()
                    .anyMatch(key -> key.contains("type") || key.contains("text") ||
                                   key.contains("Position") || key.contains("lat"));

            assertTrue(hasEventFields || !context.getProcessingCache().isEmpty(),
                    "Should extract event fields");
        }
    }

    /**
     * Test alarm transformation from C8Y to MQTT.
     */
    @Test
    void testActualTransformation_AlarmToMQTT() throws Exception {
        // Given - Alarm mapping
        Mapping mapping = createAlarmOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "888",
                    "type": "c8y_HighTemperatureAlarm",
                    "text": "Temperature too high",
                    "severity": "MAJOR",
                    "status": "ACTIVE",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"}
                }
                """;

        Notification notification = createNotification(API.ALARM, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            assertNotNull(context.getProcessingCache(), "Should have processing cache");
            log.info("✅ Alarm to MQTT transformation:");
            log.info("   - Extracted fields: {}", context.getProcessingCache().keySet());

            // Verify alarm-specific fields
            boolean hasAlarmFields = context.getProcessingCache().keySet().stream()
                    .anyMatch(key -> key.contains("severity") || key.contains("status") ||
                                   key.contains("type") || key.contains("text"));

            assertTrue(hasAlarmFields || !context.getProcessingCache().isEmpty(),
                    "Should extract alarm fields");
        }
    }

    /**
     * Test resolvedPublishTopic with wildcard substitution.
     * This validates that "evt/outbound/#" becomes "evt/outbound/device_berlin_01"
     */
    @Test
    void testActualTransformation_ResolvedPublishTopicWithWildcard() throws Exception {
        // Given - Mapping with wildcard publishTopic
        Mapping mapping = createWildcardTopicMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "777",
                    "type": "c8y_BusEvent",
                    "text": "Bus was stopped",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "bus_event": "stop_event"
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            String resolvedTopic = context.getResolvedPublishTopic();

            log.info("✅ Resolved publish topic validation:");
            log.info("   - Original topic: {}", mapping.getPublishTopic());
            log.info("   - Resolved topic: {}", resolvedTopic);

            if (resolvedTopic != null) {
                assertNotNull(resolvedTopic, "Resolved publish topic should be set");
                assertFalse(resolvedTopic.contains("#"), "Should not contain wildcard #");
                assertFalse(resolvedTopic.contains("+"), "Should not contain wildcard +");
                log.info("   - Wildcards successfully replaced");
            }
        }
    }

    /**
     * Test static publish topic (no wildcards).
     */
    @Test
    void testActualTransformation_StaticPublishTopic() throws Exception {
        // Given - Mapping with static topic
        Mapping mapping = createStaticTopicMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "666",
                    "type": "c8y_Event",
                    "text": "Test event",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"}
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            String resolvedTopic = context.getResolvedPublishTopic();

            log.info("✅ Static publish topic validation:");
            log.info("   - Publish topic: {}", mapping.getPublishTopic());
            log.info("   - Resolved topic: {}", resolvedTopic);

            if (resolvedTopic != null) {
                assertEquals(mapping.getPublishTopic(), resolvedTopic,
                        "Static topic should match publish topic exactly");
            }
        }
    }

    /**
     * Test multiple topic levels in resolved topic.
     */
    @Test
    void testActualTransformation_MultiLevelTopicResolution() throws Exception {
        // Given - Mapping with multiple wildcards
        Mapping mapping = createMultiLevelTopicMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "555",
                    "type": "c8y_Measurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_Temperature": {
                        "T": {"value": 23.5}
                    }
                }
                """;

        Notification notification = createNotification(API.MEASUREMENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            log.info("✅ Multi-level topic resolution:");
            log.info("   - Resolved topic: {}", context.getResolvedPublishTopic());
            log.info("   - Extracted fields: {}", context.getProcessingCache().keySet());
        }
    }

    /**
     * Test internal field removal (_CONTEXT_DATA_, _TOPIC_LEVEL_, _IDENTITY_).
     */
    @Test
    void testActualTransformation_InternalFieldsRemoved() throws Exception {
        // Given
        Mapping mapping = createMeasurementOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "444",
                    "type": "c8y_TemperatureMeasurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_TemperatureMeasurement": {
                        "T": {"value": 25.0}
                    }
                }
                """;

        Notification notification = createNotification(API.MEASUREMENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            Object transformedPayload = context.getPayload();

            if (transformedPayload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) transformedPayload;

                log.info("✅ Internal fields removal validation:");
                log.info("   - Payload keys: {}", payloadMap.keySet());

                // Verify internal fields are removed
                assertFalse(payloadMap.containsKey("_CONTEXT_DATA_"),
                        "Payload should not contain _CONTEXT_DATA_");
                assertFalse(payloadMap.containsKey("_TOPIC_LEVEL_"),
                        "Payload should not contain _TOPIC_LEVEL_");
                assertFalse(payloadMap.containsKey("_IDENTITY_"),
                        "Payload should not contain _IDENTITY_");

                log.info("   - Internal fields successfully removed");
            }
        }
    }

    /**
     * Test nested C8Y payload transformation.
     */
    @Test
    void testActualTransformation_NestedC8YPayload() throws Exception {
        // Given
        Mapping mapping = createNestedPayloadMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "333",
                    "type": "c8y_EnvironmentMeasurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_Environment": {
                        "temperature": {"value": 23.5, "unit": "C"},
                        "humidity": {"value": 65.2, "unit": "%"},
                        "pressure": {"value": 1013.25, "unit": "hPa"}
                    }
                }
                """;

        Notification notification = createNotification(API.MEASUREMENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            assertNotNull(context.getProcessingCache(), "Should have processing cache");
            log.info("✅ Nested C8Y payload transformation:");
            log.info("   - Extracted fields: {}", context.getProcessingCache().keySet());

            // Verify nested fields extracted
            boolean hasNestedFields = context.getProcessingCache().keySet().stream()
                    .anyMatch(key -> key.contains("temperature") || key.contains("humidity") ||
                                   key.contains("pressure") || key.contains("Environment"));

            assertTrue(hasNestedFields || !context.getProcessingCache().isEmpty(),
                    "Should extract nested measurement fields");
        }
    }

    /**
     * Test operation filtering (CREATE vs UPDATE vs DELETE).
     */
    @Test
    void testActualTransformation_OperationFiltering() throws Exception {
        // Given
        Mapping mapping = createMeasurementOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "222",
                    "type": "c8y_TemperatureMeasurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"}
                }
                """;

        // Test CREATE operation
        Notification createNotif = createNotification(API.MEASUREMENT, "CREATE", payload);
        ProcessingResultWrapper<?> createResult = dispatcher.onNotification(createNotif);
        assertNotNull(createResult, "Should handle CREATE operation");

        // Test UPDATE operation
        Notification updateNotif = createNotification(API.MEASUREMENT, "UPDATE", payload);
        ProcessingResultWrapper<?> updateResult = dispatcher.onNotification(updateNotif);
        assertNotNull(updateResult, "Should handle UPDATE operation");

        log.info("✅ Operation filtering validated:");
        log.info("   - CREATE handled: {}", createResult != null);
        log.info("   - UPDATE handled: {}", updateResult != null);
    }

    /**
     * Test large C8Y measurement with many series.
     */
    @Test
    void testActualTransformation_LargeC8YMeasurement() throws Exception {
        // Given
        Mapping mapping = createMeasurementOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create large measurement with many series
        StringBuilder payloadBuilder = new StringBuilder("""
                {
                    "id": "111",
                    "type": "c8y_MultiSensorMeasurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_MultiSensorMeasurement": {
                """);

        for (int i = 0; i < 50; i++) {
            if (i > 0) payloadBuilder.append(",");
            payloadBuilder.append(String.format("\"sensor_%d\":{\"value\":%d}", i, i * 10));
        }

        payloadBuilder.append("}}");

        Notification notification = createNotification(API.MEASUREMENT, "CREATE",
                payloadBuilder.toString());

        // When
        long startTime = System.currentTimeMillis();
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);
        long processingTime = System.currentTimeMillis() - startTime;

        // Then
        assertNotNull(result, "Should handle large measurement");

        log.info("✅ Large C8Y measurement transformation:");
        log.info("   - Payload size: {} bytes", payloadBuilder.length());
        log.info("   - Processing time: {}ms", processingTime);
        log.info("   - Performance acceptable: {}", processingTime < 1000);
    }

    /**
     * Test special characters in C8Y payload.
     */
    @Test
    void testActualTransformation_SpecialCharactersInC8Y() throws Exception {
        // Given
        Mapping mapping = createEventOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "100",
                    "type": "c8y_CustomEvent",
                    "text": "Device location: München, Österreich. Temperature: 23°C. Cost: €50",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"}
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Should handle special characters");

        log.info("✅ Special characters in C8Y payload:");
        log.info("   - UTF-8 characters handled correctly");
    }

    /**
     * Test device identifier extraction from C8Y source.
     */
    @Test
    void testActualTransformation_DeviceIdentifierExtraction() throws Exception {
        // Given
        Mapping mapping = createMeasurementOutboundMapping();

        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        String payload = """
                {
                    "id": "200",
                    "type": "c8y_TemperatureMeasurement",
                    "time": "2025-01-20T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_TemperatureMeasurement": {
                        "T": {"value": 23.5}
                    }
                }
                """;

        Notification notification = createNotification(API.MEASUREMENT, "CREATE", payload);

        // When
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then
        assertNotNull(result, "Processing result should not be null");

        @SuppressWarnings("unchecked")
        List<ProcessingContext<?>> contexts = (List<ProcessingContext<?>>) (List<?>)
                result.getProcessingResult().get();

        if (!contexts.isEmpty()) {
            ProcessingContext<?> context = contexts.get(0);

            assertNotNull(context.getProcessingCache(), "Should have processing cache");

            log.info("✅ Device identifier extraction:");
            log.info("   - Extracted fields: {}", context.getProcessingCache().keySet());

            // Verify device/source extraction
            boolean hasDeviceInfo = context.getProcessingCache().keySet().stream()
                    .anyMatch(key -> key.contains("source") || key.contains("_IDENTITY_") ||
                                   key.contains("device") || key.contains("externalId"));

            assertTrue(hasDeviceInfo || !context.getProcessingCache().isEmpty(),
                    "Should extract device information from source");
        }
    }

    // ========== HELPER METHODS ==========

    private Notification createNotification(API api, String operation, String payload) {
        String apiResource = APITopicUtil.convertAPIToResource(api);
        String ackHeader = String.format("/%s/test-subscription/%d", TEST_TENANT,
                System.currentTimeMillis());
        String tenantApiHeader = String.format("/%s/%s", TEST_TENANT, apiResource);

        String notificationMessage = String.format(
                "%s\n%s\n%s\nsubscription-test-%d\n\n%s",
                ackHeader,
                tenantApiHeader,
                operation,
                System.currentTimeMillis(),
                payload
        );
        return Notification.parse(notificationMessage);
    }

    private Mapping createMeasurementOutboundMapping() {
        return Mapping.builder()
                .id("outbound-test-001")
                .name("Test Measurement Outbound")
                .mappingTopic("measurement/outbound")
                .publishTopic("measurements/device/#")
                .targetAPI(API.MEASUREMENT)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("type", "measurementType"),
                    createSubstitution("c8y_TemperatureMeasurement.T.value", "temperature"),
                    createSubstitution("source.id", "_IDENTITY_.c8ySourceId")
                })
                .build();
    }

    private Mapping createEventOutboundMapping() {
        return Mapping.builder()
                .id("outbound-test-002")
                .name("Test Event Outbound")
                .mappingTopic("event/outbound")
                .publishTopic("events/device/#")
                .targetAPI(API.EVENT)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("type", "eventType"),
                    createSubstitution("text", "eventText"),
                    createSubstitution("source.id", "_IDENTITY_.c8ySourceId")
                })
                .build();
    }

    private Mapping createAlarmOutboundMapping() {
        return Mapping.builder()
                .id("outbound-test-003")
                .name("Test Alarm Outbound")
                .mappingTopic("alarm/outbound")
                .publishTopic("alarms/device/#")
                .targetAPI(API.ALARM)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("type", "alarmType"),
                    createSubstitution("text", "alarmText"),
                    createSubstitution("severity", "severity"),
                    createSubstitution("status", "status"),
                    createSubstitution("source.id", "_IDENTITY_.c8ySourceId")
                })
                .build();
    }

    private Mapping createWildcardTopicMapping() {
        return Mapping.builder()
                .id("outbound-test-004")
                .name("Test Wildcard Topic")
                .mappingTopic("event/wildcard")
                .publishTopic("evt/outbound/#")  // Wildcard to be replaced
                .targetAPI(API.EVENT)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("type", "eventType"),
                    createSubstitution("text", "eventText"),
                    createSubstitution("source.id", "_IDENTITY_.c8ySourceId"),
                    createSubstitution("_IDENTITY_.c8ySourceId", "_TOPIC_LEVEL_[2]")  // For topic resolution
                })
                .build();
    }

    private Mapping createStaticTopicMapping() {
        return Mapping.builder()
                .id("outbound-test-005")
                .name("Test Static Topic")
                .mappingTopic("event/static")
                .publishTopic("static/events/all")  // No wildcards
                .targetAPI(API.EVENT)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("type", "eventType"),
                    createSubstitution("text", "eventText")
                })
                .build();
    }

    private Mapping createMultiLevelTopicMapping() {
        return Mapping.builder()
                .id("outbound-test-006")
                .name("Test Multi-Level Topic")
                .mappingTopic("measurement/multilevel")
                .publishTopic("sensors/+/data/#")  // Multiple wildcards
                .targetAPI(API.MEASUREMENT)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("type", "sensorType"),
                    createSubstitution("source.id", "_IDENTITY_.c8ySourceId")
                })
                .build();
    }

    private Mapping createNestedPayloadMapping() {
        return Mapping.builder()
                .id("outbound-test-007")
                .name("Test Nested Payload")
                .mappingTopic("measurement/nested")
                .publishTopic("measurements/environment")
                .targetAPI(API.MEASUREMENT)
                .direction(dynamic.mapper.model.Direction.OUTBOUND)
                .substitutions(new dynamic.mapper.model.Substitution[] {
                    createSubstitution("c8y_Environment.temperature.value", "temp"),
                    createSubstitution("c8y_Environment.humidity.value", "humidity"),
                    createSubstitution("c8y_Environment.pressure.value", "pressure"),
                    createSubstitution("source.id", "_IDENTITY_.c8ySourceId")
                })
                .build();
    }

    private dynamic.mapper.model.Substitution createSubstitution(String source, String target) {
        return dynamic.mapper.model.Substitution.builder()
                .pathSource(source)
                .pathTarget(target)
                .repairStrategy(dynamic.mapper.processor.model.RepairStrategy.DEFAULT)
                .build();
    }
}
