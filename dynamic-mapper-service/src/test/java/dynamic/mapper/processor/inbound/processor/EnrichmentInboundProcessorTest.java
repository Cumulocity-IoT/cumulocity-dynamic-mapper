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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapper.service.cache.FlowStateStore;
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
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Behavioural tests for {@link EnrichmentInboundProcessor}.
 *
 * <p>These tests drive a <em>real</em> {@link ProcessingContext} with a real
 * payload through the processor and assert the concrete side effects of
 * enrichment (token injection, QoS propagation, message counting, error
 * recording) rather than merely asserting that processing did not throw.
 * Smart-function / GraalVM enrichment is covered by
 * {@code AbstractEnrichmentProcessorTest} which uses a real GraalVM engine.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrichmentInboundProcessorTest {

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

    private EnrichmentInboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_TOPIC = "device/sensor01/measurement";

    private Mapping mapping;
    private MappingStatus mappingStatus;

    @BeforeEach
    void setUp() {
        mapping = Mapping.builder()
                .identifier("test-mapping")
                .name("Test Mapping")
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON)
                .build();

        mappingStatus = new MappingStatus(
                "test-id",
                "Test Mapping",
                "test-mapping",
                Direction.INBOUND,
                "test/topic",
                "output/topic",
                0L, // messagesReceived
                0L, // errors
                0L, // currentFailureCount
                null // loadingError
        );

        processor = new EnrichmentInboundProcessor(configurationRegistry, mappingService, flowStateStore);

        when(exchange.getIn()).thenReturn(message);
        when(mappingService.getMappingStatus(any(), any(Mapping.class))).thenReturn(mappingStatus);
    }

    /**
     * Builds a real ProcessingContext and wires it onto the exchange header the
     * way the upstream DeserializationInboundProcessor would.
     */
    private ProcessingContext<Object> stageContext(Object payload, String key) {
        ProcessingContext<Object> context = ProcessingContext.builder()
                .tenant(TEST_TENANT)
                .topic(TEST_TOPIC)
                .mapping(mapping)
                .mappingType(mapping.getMappingType())
                .serviceConfiguration(serviceConfiguration)
                .api(mapping.getTargetAPI())
                .key(key)
                .payload(payload)
                .build();
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(context);
        when(message.getHeader("connectorIdentifier", String.class)).thenReturn("connector-1");
        return context;
    }

    @SuppressWarnings("unchecked")
    @Test
    void injectsTopicLevelTokenIntoMapPayload() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("temperature", 21.5);
        ProcessingContext<Object> context = stageContext(payload, null);

        processor.process(exchange);

        assertTrue(payload.containsKey(Mapping.TOKEN_TOPIC_LEVEL),
                "enrichment must inject the _TOPIC_LEVEL_ token into a Map payload");
        Object topicLevel = payload.get(Mapping.TOKEN_TOPIC_LEVEL);
        assertInstanceOf(List.class, topicLevel, "_TOPIC_LEVEL_ must be the split topic as a list");
        assertTrue(((List<String>) topicLevel).contains("sensor01"),
                "split topic should contain each topic segment");
        assertFalse(context.hasError(), "valid enrichment must not record an error");
    }

    @Test
    void propagatesQosFromMappingToContext() throws Exception {
        ProcessingContext<Object> context = stageContext(new HashMap<>(), null);

        processor.process(exchange);

        assertEquals(Qos.AT_LEAST_ONCE, context.getQos(),
                "performPreEnrichmentSetup must copy the mapping QoS onto the context");
    }

    @Test
    void incrementsMessagesReceivedExactlyOnce() throws Exception {
        stageContext(new HashMap<>(), null);

        processor.process(exchange);

        assertEquals(1L, mappingStatus.messagesReceived,
                "each processed message must increment messagesReceived once");
        assertEquals(0L, mappingStatus.errors, "a successful enrichment must not increment errors");
    }

    @SuppressWarnings("unchecked")
    @Test
    void injectsContextDataTokenWhenMessageKeyPresent() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        stageContext(payload, "partition-key-7");

        processor.process(exchange);

        assertTrue(payload.containsKey(Mapping.TOKEN_CONTEXT_DATA),
                "a present message key must produce a _CONTEXT_DATA_ token");
        Map<String, String> contextData = (Map<String, String>) payload.get(Mapping.TOKEN_CONTEXT_DATA);
        assertEquals("partition-key-7", contextData.get(Mapping.CONTEXT_DATA_KEY_NAME),
                "the message key must be carried in _CONTEXT_DATA_");
        assertEquals(API.MEASUREMENT.toString(), contextData.get("api"),
                "the target API must be carried in _CONTEXT_DATA_");
    }

    @Test
    void doesNotInjectContextDataWhenNoKey() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        stageContext(payload, null);

        processor.process(exchange);

        assertFalse(payload.containsKey(Mapping.TOKEN_CONTEXT_DATA),
                "absent message key must not create a _CONTEXT_DATA_ token");
    }

    @Test
    void leavesNonMapPayloadUntouched() throws Exception {
        // A byte[] payload (e.g. a custom format handled later by an extension)
        // must pass through enrichment without modification or error.
        byte[] payload = "raw-binary".getBytes();
        ProcessingContext<Object> context = stageContext(payload, null);

        processor.process(exchange);

        assertSame(payload, context.getPayload(), "non-Map payload must not be replaced");
        assertFalse(context.hasError(), "passing through a non-Map payload is not an error");
        assertEquals(1L, mappingStatus.messagesReceived);
    }

    @Test
    void recordsEnrichmentFailureAsProcessingException() throws Exception {
        // An immutable payload map makes the in-place _TOPIC_LEVEL_ injection throw,
        // exercising the handleEnrichmentError path.
        ProcessingContext<Object> context = stageContext(Map.of("temperature", 21.5), null);

        processor.process(exchange);

        assertTrue(context.hasError(), "an enrichment exception must be recorded on the context");
        assertInstanceOf(ProcessingException.class, context.getErrors().get(0),
                "errors must be wrapped as ProcessingException");
        assertEquals(1L, mappingStatus.errors, "enrichment failure must increment the error count");
        verify(mappingService).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
    }

    @Test
    void skipsEnrichmentGracefullyWhenContextHeaderMissing() throws Exception {
        // Upstream deserialization failed and never set the processingContext header.
        when(message.getHeader("processingContext", ProcessingContext.class)).thenReturn(null);

        assertDoesNotThrow(() -> processor.process(exchange));

        // No work should have been attempted against the (absent) mapping.
        verify(mappingService, never()).getMappingStatus(any(), any());
        assertEquals(0L, mappingStatus.messagesReceived);
    }
}
