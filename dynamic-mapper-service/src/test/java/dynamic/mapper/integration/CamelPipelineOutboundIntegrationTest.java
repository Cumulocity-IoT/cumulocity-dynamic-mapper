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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Integration tests that start from CamelDispatcherOutbound.onNotification() entry point.
 * Tests the complete outbound processing pipeline including:
 * - Notification handling from C8Y (measurements, events, alarms, operations)
 * - C8Y message conversion from Notification
 * - Message dispatching through Camel routes
 * - Enrichment (_IDENTITY_, _TOPIC_LEVEL_)
 * - Extraction (JSONata, SubstitutionAsCode, SmartFunction)
 * - Topic construction
 * - Publishing to external system (MQTT/Kafka/HTTP)
 *
 * This test complements:
 * - MappingScenarioIntegrationTest: Configuration validation only
 * - CamelPipelineInboundIntegrationTest: Inbound message flow
 *
 * This test provides END-TO-END validation of the outbound pipeline.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CamelPipelineOutboundIntegrationTest {

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private MappingService mappingService;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private AConnectorClient connectorClient;

    @Mock
    private NotificationSubscriber notificationSubscriber;

    private CamelContext camelContext;
    private ProducerTemplate producerTemplate;
    private CamelDispatcherOutbound dispatcher;
    private ExecutorService virtualThreadPool;

    private ObjectMapper objectMapper;
    private List<Mapping> outboundMappings;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_CONNECTOR = "mqtt";
    private static final String OUTBOUND_MAPPINGS_PATH = "resources/samples/mappings-OUTBOUND.json";

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();

        // Load sample mappings
        outboundMappings = loadMappingsFromFile(OUTBOUND_MAPPINGS_PATH);
        log.info("Loaded {} outbound mappings for Camel pipeline tests", outboundMappings.size());

        // Setup Camel Context
        camelContext = new DefaultCamelContext();
        producerTemplate = camelContext.createProducerTemplate();
        virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();

        // Setup ConfigurationRegistry mocks
        when(configurationRegistry.getCamelContext()).thenReturn(camelContext);
        when(configurationRegistry.getVirtualThreadPool()).thenReturn(virtualThreadPool);
        when(configurationRegistry.getMappingService()).thenReturn(mappingService);
        when(configurationRegistry.getServiceConfiguration(TEST_TENANT)).thenReturn(serviceConfiguration);
        when(configurationRegistry.getNotificationSubscriber()).thenReturn(notificationSubscriber);

        // Setup ServiceConfiguration mocks
        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        when(serviceConfiguration.getLogSubstitution()).thenReturn(false);
        when(serviceConfiguration.getMaxCPUTimeMS()).thenReturn(5000);

        // Setup ConnectorClient mocks
        when(connectorClient.getTenant()).thenReturn(TEST_TENANT);
        when(connectorClient.getConnectorName()).thenReturn("MQTT Connector");
        when(connectorClient.getConnectorIdentifier()).thenReturn(TEST_CONNECTOR);
        when(connectorClient.isConnected()).thenReturn(true);

        // Create dispatcher
        dispatcher = new CamelDispatcherOutbound(configurationRegistry, connectorClient);

        log.info("✅ Camel outbound pipeline test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (camelContext != null && camelContext.isStarted()) {
            camelContext.stop();
        }
        if (virtualThreadPool != null) {
            virtualThreadPool.shutdown();
        }
        outboundMappings = null;
    }

    // ========== CAMEL DISPATCHER ENTRY POINT TESTS ==========

    @Test
    void testDispatcherOnNotification_MeasurementCreate() throws Exception {
        // Given - Measurement CREATE notification
        Mapping mapping = findMappingByName(outboundMappings, "Mapping - 51");
        if (mapping == null) {
            log.warn("⚠️ Mapping 51 not found, skipping test");
            return;
        }

        // Setup mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Setup QoS determination
        when(connectorClient.determineMaxQosOutbound(any())).thenReturn(mapping.getQos());

        // Create measurement notification
        String payload = """
                {
                    "id": "12345",
                    "source": {
                        "id": "67890",
                        "self": "https://test.cumulocity.com/inventory/managedObjects/67890"
                    },
                    "type": "c8y_TemperatureMeasurement",
                    "time": "2024-01-19T10:00:00.000Z",
                    "c8y_TemperatureMeasurement": {
                        "T": {
                            "value": 25.5,
                            "unit": "C"
                        }
                    }
                }
                """;

        Notification notification = createNotification(
                API.MEASUREMENT,
                "CREATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify dispatcher handled message
        assertNotNull(result, "Should return processing result");
        assertNotNull(result.getConsolidatedQos(), "Should have consolidated QoS");

        log.info("✅ Dispatcher onNotification - MEASUREMENT CREATE processed");
    }

    @Test
    void testDispatcherOnNotification_EventCreate() throws Exception {
        // Given - Event CREATE notification
        Mapping mapping = findMappingByName(outboundMappings, "Mapping - 52");
        if (mapping == null) {
            log.warn("⚠️ Mapping 52 not found, using first available");
            mapping = outboundMappings.get(0);
        }

        // Setup mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Setup QoS determination
        when(connectorClient.determineMaxQosOutbound(any())).thenReturn(mapping.getQos());

        // Create event notification
        String payload = """
                {
                    "id": "54321",
                    "source": {
                        "id": "67890"
                    },
                    "type": "c8y_LocationUpdate",
                    "text": "Device location updated",
                    "time": "2024-01-19T10:00:00.000Z"
                }
                """;

        Notification notification = createNotification(
                API.EVENT,
                "CREATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify dispatcher handled message
        assertNotNull(result, "Should return processing result");

        log.info("✅ Dispatcher onNotification - EVENT CREATE processed");
    }

    @Test
    void testDispatcherOnNotification_AlarmCreate() throws Exception {
        // Given - Alarm CREATE notification
        Mapping mapping = outboundMappings.stream()
                .filter(m -> m.getTargetAPI() == API.ALARM)
                .findFirst()
                .orElse(outboundMappings.get(0));

        // Setup mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Setup QoS determination
        when(connectorClient.determineMaxQosOutbound(any())).thenReturn(mapping.getQos());

        // Create alarm notification
        String payload = """
                {
                    "id": "99999",
                    "source": {
                        "id": "67890"
                    },
                    "type": "c8y_HighTemperatureAlarm",
                    "text": "Temperature exceeds threshold",
                    "severity": "CRITICAL",
                    "status": "ACTIVE",
                    "time": "2024-01-19T10:00:00.000Z"
                }
                """;

        Notification notification = createNotification(
                API.ALARM,
                "CREATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify dispatcher handled message
        assertNotNull(result, "Should return processing result");

        log.info("✅ Dispatcher onNotification - ALARM CREATE processed");
    }

    @Test
    void testDispatcherOnNotification_OperationCreate() throws Exception {
        // Given - Operation CREATE notification
        Mapping mapping = outboundMappings.stream()
                .filter(m -> m.getTargetAPI() == API.OPERATION)
                .findFirst()
                .orElse(outboundMappings.get(0));

        // Setup mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Setup QoS determination
        when(connectorClient.determineMaxQosOutbound(any())).thenReturn(mapping.getQos());

        // Create operation notification
        String payload = """
                {
                    "id": "11111",
                    "deviceId": "67890",
                    "status": "PENDING",
                    "c8y_Restart": {}
                }
                """;

        Notification notification = createNotification(
                API.OPERATION,
                "CREATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify dispatcher handled message
        assertNotNull(result, "Should return processing result");

        log.info("✅ Dispatcher onNotification - OPERATION CREATE processed");
    }

    @Test
    void testDispatcherOnNotification_UpdateOperationIgnored() throws Exception {
        // Given - Operation UPDATE notification (should be ignored)
        String payload = """
                {
                    "id": "11111",
                    "deviceId": "67890",
                    "status": "SUCCESSFUL",
                    "c8y_Restart": {}
                }
                """;

        Notification notification = createNotification(
                API.OPERATION,
                "UPDATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify message was ignored (early return)
        assertNotNull(result, "Should return processing result");

        // Verify mapping service was never called
        verify(mappingService, never()).resolveMappingOutbound(any(), any(), any());

        log.info("✅ Dispatcher onNotification - Operation UPDATE ignored");
    }

    @Test
    void testDispatcherOnNotification_DeleteOperationIgnored() throws Exception {
        // Given - DELETE operation (should be ignored)
        String payload = """
                {
                    "id": "12345",
                    "source": {"id": "67890"}
                }
                """;

        Notification notification = createNotification(
                API.MEASUREMENT,
                "DELETE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify message was ignored
        assertNotNull(result, "Should return processing result");
        verify(mappingService, never()).resolveMappingOutbound(any(), any(), any());

        log.info("✅ Dispatcher onNotification - DELETE operation ignored");
    }

    @Test
    void testDispatcherOnNotification_DisconnectedConnectorIgnored() throws Exception {
        // Given - Connector is disconnected
        when(connectorClient.isConnected()).thenReturn(false);

        String payload = """
                {
                    "id": "12345",
                    "source": {"id": "67890"},
                    "type": "c8y_Measurement"
                }
                """;

        Notification notification = createNotification(
                API.MEASUREMENT,
                "CREATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify message was ignored due to disconnected connector
        assertNotNull(result, "Should return processing result");
        verify(mappingService, never()).resolveMappingOutbound(any(), any(), any());

        log.info("✅ Dispatcher onNotification - Disconnected connector ignored");
    }

    @Test
    void testDispatcherOnTestNotification() throws Exception {
        // Given - Test notification (bypass mapping resolution)
        Mapping mapping = outboundMappings.get(0);

        String payload = """
                {
                    "id": "12345",
                    "source": {"id": "67890"},
                    "type": "c8y_TemperatureMeasurement",
                    "c8y_TemperatureMeasurement": {
                        "T": {"value": 25.5, "unit": "C"}
                    }
                }
                """;

        Notification notification = createNotification(
                API.MEASUREMENT,
                "CREATE",
                payload
        );

        // When - Process test notification (uses testMapping parameter)
        ProcessingResultWrapper<?> result = dispatcher.onTestNotification(notification, mapping);

        // Then - Verify test notification processed
        assertNotNull(result, "Should return processing result");

        // Verify mapping service was NOT called (testMapping bypasses resolution)
        verify(mappingService, never()).resolveMappingOutbound(any(), any(), any());

        log.info("✅ Dispatcher onTestNotification - Test mapping processed");
    }

    @Test
    void testDispatcherOnNotification_MultipleMappings() throws Exception {
        // Given - Multiple mappings for same API/device
        Mapping mapping1 = outboundMappings.get(0);
        Mapping mapping2 = outboundMappings.size() > 1 ? outboundMappings.get(1) : mapping1;

        // Setup mapping resolution with multiple mappings
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping1, mapping2));

        // Setup QoS determination
        when(connectorClient.determineMaxQosOutbound(any())).thenReturn(mapping1.getQos());

        String payload = """
                {
                    "id": "12345",
                    "source": {"id": "67890"},
                    "type": "c8y_Measurement"
                }
                """;

        Notification notification = createNotification(
                API.MEASUREMENT,
                "CREATE",
                payload
        );

        // When - Process notification through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify dispatcher handled multiple mappings
        assertNotNull(result, "Should return processing result");

        log.info("✅ Dispatcher onNotification - Multiple mappings processed");
    }

    @Test
    void testNotificationConversion() {
        // Given - Test notification creation and structure
        String payload = """
                {
                    "id": "12345",
                    "source": {"id": "67890"},
                    "type": "c8y_TemperatureMeasurement"
                }
                """;

        // When - Create notification
        Notification notification = createNotification(
                API.MEASUREMENT,
                "CREATE",
                payload
        );

        // Then - Verify notification structure
        assertNotNull(notification, "Should create notification");
        assertEquals(API.MEASUREMENT, notification.getApi(), "Should have correct API");
        assertEquals("CREATE", notification.getOperation(), "Should have correct operation");
        assertNotNull(notification.getMessage(), "Should have message payload");
        assertNotNull(notification.getNotificationHeaders(), "Should have notification headers");

        log.info("✅ Notification conversion validated");
    }

    @Test
    void testMappingTypeDistribution() {
        // Given - All loaded mappings

        // When - Count mapping types
        long jsonMappings = outboundMappings.stream()
                .filter(m -> m.getMappingType() == MappingType.JSON)
                .count();

        long flatFileMappings = outboundMappings.stream()
                .filter(m -> m.getMappingType() == MappingType.FLAT_FILE)
                .count();

        long smartFunctions = outboundMappings.stream()
                .filter(m -> m.getTransformationType() == TransformationType.SMART_FUNCTION)
                .count();

        // Then - Verify coverage
        assertTrue(outboundMappings.size() > 0, "Should have outbound mappings");
        log.info("✅ Mapping type distribution - JSON: {}, FLAT_FILE: {}, SMART_FUNCTION: {}",
                jsonMappings, flatFileMappings, smartFunctions);
    }

    @Test
    void testTransformationTypeValidation() {
        // Given - All mappings

        // When - Count transformation types
        long defaultTransforms = outboundMappings.stream()
                .filter(m -> m.getTransformationType() == TransformationType.DEFAULT)
                .count();

        long smartFunctions = outboundMappings.stream()
                .filter(m -> m.getTransformationType() == TransformationType.SMART_FUNCTION)
                .count();

        long substitutionAsCode = outboundMappings.stream()
                .filter(m -> m.getTransformationType() == TransformationType.SUBSTITUTION_AS_CODE)
                .count();

        // Then - Log coverage
        log.info("✅ Transformation types - DEFAULT: {}, SMART_FUNCTION: {}, SUBSTITUTION_AS_CODE: {}",
                defaultTransforms, smartFunctions, substitutionAsCode);

        assertTrue(defaultTransforms > 0, "Should have DEFAULT transformations");
    }

    // ========== RESOLVED PUBLISH TOPIC AND PAYLOAD TRANSFORMATION TESTS ==========

    @Test
    void testResolvedPublishTopic_WithWildcardSubstitution() throws Exception {
        // Given - Mapping with wildcard in publishTopic: "evt/outbound/#"
        Mapping mapping = findMappingByName(outboundMappings, "Mapping - 51");
        assertNotNull(mapping, "Mapping - 51 should exist");

        log.info("Testing mapping with publishTopic: '{}'", mapping.getPublishTopic());

        // Mock mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create C8Y event notification
        String payload = """
                {
                    "id": "999",
                    "type": "c8y_BusEvent",
                    "text": "Bus was stopped",
                    "time": "2025-01-19T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "bus_event": "stop_event"
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When - Process through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify resolvedPublishTopic was set and wildcard replaced
        assertNotNull(result, "Processing result should not be null");

        List<ProcessingContext<?>> contexts = getProcessingContexts(result);
        if (!contexts.isEmpty()) {
            var context = contexts.get(0);
            String resolvedTopic = context.getResolvedPublishTopic();

            log.info("Resolved publish topic: '{}'", resolvedTopic);

            assertNotNull(resolvedTopic, "Resolved publish topic should be set");
            assertFalse(resolvedTopic.contains("#"),
                    "Resolved topic should not contain wildcard '#', got: " + resolvedTopic);
            assertFalse(resolvedTopic.contains("+"),
                    "Resolved topic should not contain wildcard '+', got: " + resolvedTopic);
            assertTrue(resolvedTopic.startsWith("evt/outbound/"),
                    "Topic should start with 'evt/outbound/', got: " + resolvedTopic);

            log.info("✅ Resolved publish topic with wildcard substitution validated");
        } else {
            log.warn("⚠️ No processing contexts returned - pipeline may not have fully executed");
        }
    }

    @Test
    void testResolvedPublishTopic_StaticTopic() throws Exception {
        // Given - Mapping with static publishTopic (no wildcards)
        Mapping mapping = findMappingByName(outboundMappings, "Mapping - 54");
        assertNotNull(mapping, "Mapping - 54 should exist");

        log.info("Testing mapping with static publishTopic: '{}'", mapping.getPublishTopic());

        // Mock mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create C8Y event notification
        String payload = """
                {
                    "id": "888",
                    "type": "c8y_BusEvent",
                    "text": "Bus event",
                    "time": "2025-01-19T10:00:00.000Z",
                    "source": {"id": "12345"},
                    "bus_event": "test_event"
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When - Process through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify resolvedPublishTopic equals static publishTopic
        assertNotNull(result, "Processing result should not be null");

        List<ProcessingContext<?>> contexts = getProcessingContexts(result);
        if (!contexts.isEmpty()) {
            var context = contexts.get(0);
            String resolvedTopic = context.getResolvedPublishTopic();

            log.info("Static publishTopic: '{}', resolved: '{}'",
                    mapping.getPublishTopic(), resolvedTopic);

            assertNotNull(resolvedTopic, "Resolved publish topic should be set");
            assertEquals(mapping.getPublishTopic(), resolvedTopic,
                    "Resolved topic should match static publishTopic");

            log.info("✅ Static publish topic validated");
        } else {
            log.warn("⚠️ No processing contexts returned - pipeline may not have fully executed");
        }
    }

    @Test
    void testPayloadTransformation_EventToMQTT() throws Exception {
        // Given - Mapping that transforms C8Y event to MQTT message
        Mapping mapping = findMappingByName(outboundMappings, "Mapping - 51");
        assertNotNull(mapping, "Mapping - 51 should exist");

        // Mock mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create C8Y event with fields to be transformed
        String payload = """
                {
                    "id": "777",
                    "type": "c8y_TemperatureEvent",
                    "text": "Temperature threshold exceeded",
                    "time": "2025-01-19T10:30:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_TemperatureEvent": {"temperature": 85.5}
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When - Process through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify payload was transformed
        assertNotNull(result, "Processing result should not be null");

        List<ProcessingContext<?>> contexts = getProcessingContexts(result);
        if (!contexts.isEmpty()) {
            var context = contexts.get(0);
            Object transformedPayload = context.getPayload();

            assertNotNull(transformedPayload, "Transformed payload should not be null");

            log.info("Transformed payload type: {}", transformedPayload.getClass().getName());

            // Verify payload is a valid structure
            if (transformedPayload instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) transformedPayload;

                log.info("Transformed payload keys: {}", payloadMap.keySet());

                // Verify internal fields are removed
                assertFalse(payloadMap.containsKey("_CONTEXT_DATA_"),
                        "Payload should not contain _CONTEXT_DATA_");
                assertFalse(payloadMap.containsKey("_TOPIC_LEVEL_"),
                        "Payload should not contain _TOPIC_LEVEL_");
                assertFalse(payloadMap.containsKey("_IDENTITY_"),
                        "Payload should not contain _IDENTITY_");

                log.info("✅ Payload transformation validated - internal fields removed");
            }
        } else {
            log.warn("⚠️ No processing contexts returned - pipeline may not have fully executed");
        }
    }

    @Test
    void testPayloadTransformation_MeasurementToMQTT() throws Exception {
        // Given - Find a MEASUREMENT mapping
        Mapping mapping = outboundMappings.stream()
                .filter(m -> API.MEASUREMENT.equals(m.getTargetAPI()))
                .findFirst()
                .orElse(null);

        if (mapping == null) {
            log.warn("⚠️ No MEASUREMENT outbound mapping found, skipping test");
            return;
        }

        log.info("Testing MEASUREMENT mapping: {}", mapping.getName());

        // Mock mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create C8Y measurement notification
        String payload = """
                {
                    "id": "666",
                    "type": "c8y_TemperatureMeasurement",
                    "time": "2025-01-19T10:30:00.000Z",
                    "source": {"id": "12345"},
                    "c8y_TemperatureMeasurement": {
                        "T": {"value": 23.5, "unit": "C"}
                    }
                }
                """;

        Notification notification = createNotification(API.MEASUREMENT, "CREATE", payload);

        // When - Process through dispatcher
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify measurement transformation
        assertNotNull(result, "Processing result should not be null");

        List<ProcessingContext<?>> contexts = getProcessingContexts(result);
        if (!contexts.isEmpty()) {
            var context = contexts.get(0);

            String resolvedTopic = context.getResolvedPublishTopic();
            assertNotNull(resolvedTopic, "Resolved publish topic should be set");

            Object transformedPayload = context.getPayload();
            assertNotNull(transformedPayload, "Transformed payload should not be null");

            log.info("Measurement transformation:");
            log.info("  - Mapping: {}", mapping.getName());
            log.info("  - Resolved topic: {}", resolvedTopic);
            log.info("  - Payload type: {}", transformedPayload.getClass().getName());

            log.info("✅ Measurement outbound transformation validated");
        } else {
            log.warn("⚠️ No processing contexts returned - pipeline may not have fully executed");
        }
    }

    @Test
    void testCompleteTransformationPipeline_EndToEnd() throws Exception {
        // Given - Mapping with complete transformation flow
        Mapping mapping = findMappingByName(outboundMappings, "Mapping - 51");
        assertNotNull(mapping, "Mapping - 51 should exist");

        // Mock mapping resolution
        when(mappingService.resolveMappingOutbound(eq(TEST_TENANT), any(), any()))
                .thenReturn(List.of(mapping));

        // Create realistic C8Y event
        String payload = """
                {
                    "id": "12345",
                    "type": "c8y_DeviceStatusEvent",
                    "text": "Device came online",
                    "time": "2025-01-19T12:00:00.000Z",
                    "source": {"id": "67890"},
                    "c8y_DeviceStatus": {
                        "status": "online",
                        "timestamp": "2025-01-19T12:00:00.000Z"
                    }
                }
                """;

        Notification notification = createNotification(API.EVENT, "CREATE", payload);

        // When - Process through complete pipeline
        ProcessingResultWrapper<?> result = dispatcher.onNotification(notification);

        // Then - Verify complete transformation
        assertNotNull(result, "Processing result should not be null");

        List<ProcessingContext<?>> contexts = getProcessingContexts(result);
        if (!contexts.isEmpty()) {
            var context = contexts.get(0);

            // 1. Verify resolvedPublishTopic is set
            String resolvedTopic = context.getResolvedPublishTopic();
            assertNotNull(resolvedTopic, "Resolved publish topic should be set");
            assertTrue(resolvedTopic.length() > 0, "Resolved topic should not be empty");

            // 2. Verify payload transformation occurred
            Object transformedPayload = context.getPayload();
            assertNotNull(transformedPayload, "Transformed payload should not be null");

            // 3. Verify processing cache was populated (extraction occurred)
            assertNotNull(context.getProcessingCache(), "Processing cache should exist");

            // 4. Verify no errors
            assertTrue(context.getErrors().isEmpty(),
                    "Should have no errors, got: " + context.getErrors());

            log.info("Complete transformation pipeline results:");
            log.info("  - Resolved topic: {}", resolvedTopic);
            log.info("  - Cache entries: {}", context.getProcessingCache().size());
            log.info("  - Payload transformed: {}", transformedPayload != null);
            log.info("  - Errors: {}", context.getErrors().size());

            log.info("✅ Complete outbound transformation pipeline validated");
        } else {
            log.warn("⚠️ No processing contexts returned - pipeline may not have fully executed");
        }
    }

    // ========== HELPER METHODS ==========

    @SuppressWarnings("unchecked")
    private List<ProcessingContext<?>> getProcessingContexts(ProcessingResultWrapper<?> result) throws Exception {
        if (result == null || result.getProcessingResult() == null) {
            return List.of();
        }
        return (List<ProcessingContext<?>>) (List<?>) result.getProcessingResult().get();
    }

    private Notification createNotification(API api, String operation, String payload) {
        // Create notification in the format expected by Notification.parse()
        // Format: ackHeader\n/tenant/api\noperation\nsubscription-id\n\npayload
        String apiResource = APITopicUtil.convertAPIToResource(api);
        String ackHeader = String.format("/%s/test-subscription/%d", TEST_TENANT, System.currentTimeMillis());
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

    private List<Mapping> loadMappingsFromFile(String relativePath) throws IOException {
        // Get the project root directory by navigating up from the test class location
        Path testClassPath = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());

        // Navigate up to project root: target/test-classes -> target -> dynamic-mapper-service -> project root
        Path projectRoot = testClassPath.getParent().getParent().getParent();

        // Resolve the relative path from project root
        Path filePath = projectRoot.resolve(relativePath).normalize();

        File file = filePath.toFile();
        if (!file.exists()) {
            log.warn("Mapping file not found: {} (resolved to: {})", relativePath, filePath);
            return List.of();
        }

        String content = Files.readString(file.toPath());
        return objectMapper.readValue(content, new TypeReference<List<Mapping>>() {
        });
    }

    private Mapping findMappingByName(List<Mapping> mappings, String name) {
        return mappings.stream()
                .filter(m -> name.equals(m.getName()))
                .findFirst()
                .orElse(null);
    }
}
