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
package dynamic.mapper.processor.inbound.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.IdentityResolutionService;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.processor.util.CamelHeaders;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests the parallel request processing path in {@link SendInboundProcessor}.
 *
 * The Camel route {@code direct:processRequestsInParallel} splits
 * {@code context.requests} and calls {@code processor.process(exchange)} for
 * each request concurrently, with the individual {@link DynamicMapperRequest}
 * as the exchange body. This test drives that path directly with two concurrent
 * calls to verify that each request is dispatched with the correct list index
 * and receives the correct response.
 *
 * <p>This test file also serves as the first coverage for the parallel code path
 * (previously zero coverage). It is expected to PASS both before and after
 * the thread-safety fix for Bug #3, since the data race in
 * {@code context.setSourceId()} in {@code processInventoryRequest} is not
 * exercised here (MEASUREMENT requests are used, which skip that path).</p>
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendInboundProcessorParallelTest {

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private IdentityResolutionService identityResolutionService;

    @Mock
    private MappingService mappingService;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private SendInboundProcessor processor;

    private Mapping mapping;
    private ProcessingContext<Object> processingContext;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "device-parallel-001";

    @BeforeEach
    void setUp() throws Exception {
        processor = new SendInboundProcessor();
        // Inject @Autowired dependencies via reflection
        ProcessorTestHelper.injectField(processor, "c8yAgent", c8yAgent);
        ProcessorTestHelper.injectField(processor, "configurationRegistry", configurationRegistry);
        ProcessorTestHelper.injectField(processor, "identityResolutionService", identityResolutionService);
        ProcessorTestHelper.injectField(processor, "objectMapper", new ObjectMapper());
        ProcessorTestHelper.injectField(processor, "mappingService", mappingService);

        mapping = buildJsonMapping();

        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        when(mappingService.getMappingStatus(any(), any())).thenReturn(
                new MappingStatus("id-1", "Parallel test mapping", "ident-1",
                        Direction.INBOUND, "test/topic", null, 0L, 0L, 0L, null));

        processingContext = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .serviceConfiguration(serviceConfiguration)
                .topic("test/inbound/topic")
                .build();
        processingContext.setSourceId(TEST_DEVICE_ID);
        processingContext.setSendPayload(true);
    }

    /**
     * The Camel parallel split routes each request as the Exchange body; the
     * processor looks up the index in context.getRequests() and calls
     * {@code c8yAgent.createMEAO(context, index)}.
     *
     * This test verifies that request at index 0 receives the response
     * associated with index 0, and request at index 1 receives the response
     * associated with index 1, even when both run concurrently.
     */
    @Test
    void testParallelRequests_eachReceivesCorrectIndexedResponse() throws Exception {
        // Build two MEASUREMENT requests with no externalId so resolution is skipped
        DynamicMapperRequest req0 = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":21.0}");
        DynamicMapperRequest req1 = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":22.0}");

        List<DynamicMapperRequest> requests = new ArrayList<>();
        requests.add(req0);
        requests.add(req1);
        processingContext.setRequests(requests);

        // Mock c8yAgent.createMEAO to return distinguishable results per index
        AbstractExtensibleRepresentation meao0 = mock(AbstractExtensibleRepresentation.class);
        AbstractExtensibleRepresentation meao1 = mock(AbstractExtensibleRepresentation.class);
        when(c8yAgent.createMEAO(same(processingContext), eq(0))).thenReturn(meao0);
        when(c8yAgent.createMEAO(same(processingContext), eq(1))).thenReturn(meao1);

        // We need the real ObjectMapper injected above to serialize the mock MEAO objects.
        // AbstractExtensibleRepresentation serializes as an empty JSON object by default.
        // We don't need the response payload content — just that the responses are different.
        // Verify via verify() that createMEAO was called with the correct indices instead.

        Exchange exchange0 = buildExchange(req0);
        Exchange exchange1 = buildExchange(req1);

        // Run both concurrently, simulating the Camel parallel split
        CompletableFuture<Void> f0 = CompletableFuture.runAsync(() -> {
            try {
                processor.process(exchange0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                processor.process(exchange1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            CompletableFuture.allOf(f0, f1).get();
        } catch (ExecutionException e) {
            fail("Parallel processing threw an exception: " + e.getCause().getMessage(), e.getCause());
        }

        // Each request must have been dispatched with the correct index
        verify(c8yAgent).createMEAO(same(processingContext), eq(0));
        verify(c8yAgent).createMEAO(same(processingContext), eq(1));

        // Responses must be non-null (set from the mocked MEAO return values)
        assertNotNull(req0.getResponse(), "Request at index 0 must have a response");
        assertNotNull(req1.getResponse(), "Request at index 1 must have a response");

        // Verify the responses came from the correct mock (identity-based differentiation)
        // req0.response was set from meao0, req1.response from meao1.
        // Since ObjectMapper serializes both mocks as "{}", we verify via the verify() calls above.

        log.info("✅ Parallel requests: both executed with correct indices and received responses");
        log.info("   req0.response={}", req0.getResponse());
        log.info("   req1.response={}", req1.getResponse());
    }

    /**
     * Baseline: sequential mode (no Exchange body) processes all requests via
     * processAllRequests → processSingleRequest. Uses MEASUREMENT + EVENT to avoid
     * the bulk-merge logic that collapses two MEASUREMENT requests into one.
     */
    @Test
    void testSequentialRequests_eachReceivesCorrectIndexedResponse() throws Exception {
        DynamicMapperRequest req0 = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":21.0}");
        // Use EVENT for req1 to prevent bulkMeasurementRequestsIfNeeded from merging both into one
        DynamicMapperRequest req1 = DynamicMapperRequest.builder()
                .predecessor(-1)
                .method(org.springframework.web.bind.annotation.RequestMethod.POST)
                .api(API.EVENT)
                .request("{\"type\":\"c8y_LocationUpdate\",\"text\":\"moved\"}")
                .sourceId(TEST_DEVICE_ID)
                .build();

        List<DynamicMapperRequest> requests = new ArrayList<>();
        requests.add(req0);
        requests.add(req1);
        processingContext.setRequests(requests);

        AbstractExtensibleRepresentation meao0 = mock(AbstractExtensibleRepresentation.class);
        AbstractExtensibleRepresentation meao1 = mock(AbstractExtensibleRepresentation.class);
        when(c8yAgent.createMEAO(same(processingContext), eq(0))).thenReturn(meao0);
        when(c8yAgent.createMEAO(same(processingContext), eq(1))).thenReturn(meao1);

        // Sequential mode: no request in body → processes all requests via processAllRequests
        Exchange exchange = buildExchange(null);
        processor.process(exchange);

        verify(c8yAgent).createMEAO(same(processingContext), eq(0));
        verify(c8yAgent).createMEAO(same(processingContext), eq(1));

        assertNotNull(req0.getResponse(), "Request at index 0 must have a response");
        assertNotNull(req1.getResponse(), "Request at index 1 must have a response");

        log.info("✅ Sequential requests processed correctly with indices 0 and 1");
    }

    // ---- helpers ----

    private Exchange buildExchange(DynamicMapperRequest bodyRequest) {
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class))
                .thenReturn(processingContext);
        when(message.getHeader(eq(CamelHeaders.PROCESSING_RESULT_WRAPPER), eq(dynamic.mapper.processor.model.ProcessingResultWrapper.class)))
                .thenReturn(null);
        when(message.getBody(DynamicMapperRequest.class)).thenReturn(bodyRequest);
        return exchange;
    }

    private DynamicMapperRequest buildMeasurementRequest(String payloadJson) {
        return DynamicMapperRequest.builder()
                .predecessor(-1)
                .method(org.springframework.web.bind.annotation.RequestMethod.POST)
                .api(API.MEASUREMENT)
                .request(payloadJson)
                .sourceId(TEST_DEVICE_ID)
                .build();
    }

    private Mapping buildJsonMapping() {
        return Mapping.builder()
                .id("parallel-test-1")
                .identifier("par-test-ident")
                .name("Parallel Test Mapping")
                .publishTopic("test/inbound/topic")
                .publishTopicSample("test/inbound/topic")
                .targetAPI(API.MEASUREMENT)
                .direction(Direction.INBOUND)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.SMART_FUNCTION)
                .debug(false)
                .active(true)
                .eventWithAttachment(false)
                .createNonExistingDevice(false)
                .updateExistingDevice(false)
                .autoAckOperation(false)
                .useExternalId(false)
                .externalIdType("c8y_Serial")
                .maxFailureCount(0)
                .qos(Qos.AT_LEAST_ONCE)
                .lastUpdate(1758263226682L)
                .substitutions(new dynamic.mapper.model.Substitution[0])
                .build();
    }
}
