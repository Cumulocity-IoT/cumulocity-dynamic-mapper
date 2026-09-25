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
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.core.IdentityResolutionService;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.processor.util.CamelHeaders;
import dynamic.mapper.mapping.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests the parallel request-dispatch entry points on {@link SendInboundProcessor}:
 * {@code prepareRequests} (once, before fan-out), {@code processSplitRequest} (once per
 * request, concurrently), and {@code finalizeAfterRequests} (once, after all requests join).
 *
 * <p>The real Camel route {@code direct:processRequestsInParallel} in
 * {@code DynamicMapperInboundRoutes} wires these three steps around a
 * {@code .split(...).parallelProcessing(true)...end()} block. This test drives the same
 * three steps directly (without a real Camel context) to verify that each request is
 * dispatched with the correct list index and receives the correct response even when
 * multiple requests are sent concurrently, and that finalize-time bookkeeping (alarms,
 * mapping status) happens exactly once regardless of how many requests were processed.
 *
 * <p>Every mapping now dispatches its requests in parallel unconditionally — see
 * {@code attic/feature/parallel-processing/PARALLEL_PROCESSING_CAMEL.md} for why the old
 * {@code createNonExistingDevice}-gated {@code CamelHeaders.PARALLEL_PROCESSING} header was
 * removed instead of fixed: {@code IdentityResolutionService.getOrCreateDeviceThreadSafe}'s
 * per-external-ID locking already makes concurrent device resolution/creation safe.</p>
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendInboundProcessorParallelTest {

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private IdentityResolutionService identityResolutionService;

    @Mock
    private MappingService mappingService;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    private SendInboundProcessor processor;

    private Mapping mapping;
    private ProcessingContext<Object> processingContext;
    private MappingStatus mappingStatus;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "device-parallel-001";

    @BeforeEach
    void setUp() throws Exception {
        processor = new SendInboundProcessor(c8yAgent, serviceRegistry, identityResolutionService,
                new ObjectMapper(), mappingService);

        mapping = buildJsonMapping();
        mappingStatus = new MappingStatus("id-1", "Parallel test mapping", "ident-1",
                Direction.INBOUND, "test/topic", null, 0L, 0L, 0L, null);

        when(serviceConfiguration.getLogPayload()).thenReturn(false);
        when(mappingService.getMappingStatus(any(), any())).thenReturn(mappingStatus);

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
     * Simulates the real route's split loop: {@code processSplitRequest} is called once per
     * request, concurrently. Each request must be dispatched with the correct list index and
     * receive the response associated with that index, even though both run at the same time.
     */
    @Test
    void testParallelRequests_eachReceivesCorrectIndexedResponse() throws Exception {
        DynamicMapperRequest req0 = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":21.0}");
        DynamicMapperRequest req1 = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":22.0}");

        List<DynamicMapperRequest> requests = new ArrayList<>();
        requests.add(req0);
        requests.add(req1);
        processingContext.setRequests(requests);

        AbstractExtensibleRepresentation meao0 = mock(AbstractExtensibleRepresentation.class);
        AbstractExtensibleRepresentation meao1 = mock(AbstractExtensibleRepresentation.class);
        when(c8yAgent.createMEAO(same(processingContext), eq(0))).thenReturn(meao0);
        when(c8yAgent.createMEAO(same(processingContext), eq(1))).thenReturn(meao1);

        Exchange exchange0 = buildSplitExchange(req0);
        Exchange exchange1 = buildSplitExchange(req1);

        // Run both concurrently, simulating the parallel split
        CompletableFuture<Void> f0 = CompletableFuture.runAsync(() -> {
            try {
                processor.processSplitRequest(exchange0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
            try {
                processor.processSplitRequest(exchange1);
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
        assertFalse(processingContext.hasError(), "Both requests succeeding must leave the context error-free");

        log.info("Parallel requests: both executed with correct indices and received responses");
    }

    /**
     * One request failing must not prevent the other from succeeding, and the failure must be
     * recorded on the context (not rethrown) so finalizeAfterRequests still runs.
     */
    @Test
    void testParallelRequests_oneFailureDoesNotAbortSiblingOrRethrow() throws Exception {
        DynamicMapperRequest okRequest = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":21.0}");
        DynamicMapperRequest failingRequest = buildMeasurementRequest("{\"type\":\"c8y_Temp\",\"value\":22.0}");

        List<DynamicMapperRequest> requests = new ArrayList<>();
        requests.add(okRequest);
        requests.add(failingRequest);
        processingContext.setRequests(requests);

        AbstractExtensibleRepresentation meao0 = mock(AbstractExtensibleRepresentation.class);
        when(c8yAgent.createMEAO(same(processingContext), eq(0))).thenReturn(meao0);
        when(c8yAgent.createMEAO(same(processingContext), eq(1)))
                .thenThrow(new RuntimeException("C8Y unavailable"));

        // processSplitRequest must not throw even though the underlying send fails
        assertDoesNotThrow(() -> processor.processSplitRequest(buildSplitExchange(okRequest)));
        assertDoesNotThrow(() -> processor.processSplitRequest(buildSplitExchange(failingRequest)));

        assertNotNull(okRequest.getResponse(), "The succeeding request must still get a response");
        assertNotNull(failingRequest.getError(), "The failing request must record its own error");
        assertTrue(processingContext.hasError(), "The failure must be visible on the shared context");
    }

    /**
     * finalizeAfterRequests must run its bookkeeping (alarms, mapping status) exactly once,
     * regardless of how many requests were processed beforehand.
     */
    @Test
    void testFinalizeAfterRequests_updatesMappingStatusOnceWhenContextHasError() throws Exception {
        processingContext.addError(new dynamic.mapper.processor.ProcessingException("boom", new RuntimeException()));

        processor.finalizeAfterRequests(buildContextOnlyExchange());

        verify(mappingService, times(1)).increaseAndHandleFailureCount(eq(TEST_TENANT), eq(mapping), eq(mappingStatus));
        assertEquals(1L, mappingStatus.errors);
    }

    @Test
    void testFinalizeAfterRequests_noMappingStatusUpdateWhenNoError() throws Exception {
        processor.finalizeAfterRequests(buildContextOnlyExchange());

        verify(mappingService, never()).increaseAndHandleFailureCount(any(), any(), any());
    }

    /**
     * prepareRequests must merge multiple MEASUREMENT requests into one bulk request before
     * the split happens (it can only see/replace the whole list before fan-out, not after).
     */
    @Test
    void testPrepareRequests_bulkMergesMeasurementsBeforeSplit() throws Exception {
        DynamicMapperRequest m0 = buildMeasurementRequest("{\"measurements\":[{\"value\":21.0}]}");
        DynamicMapperRequest m1 = buildMeasurementRequest("{\"measurements\":[{\"value\":22.0}]}");
        List<DynamicMapperRequest> requests = new ArrayList<>();
        requests.add(m0);
        requests.add(m1);
        processingContext.setRequests(requests);

        processor.prepareRequests(buildContextOnlyExchange());

        assertEquals(1, processingContext.getRequests().size(),
                "Two MEASUREMENT requests must be merged into one bulk request before the split");
    }

    /**
     * A cancelled (timed-out) context must be left with zero requests so the split iterates
     * zero times, rather than continuing to dispatch after the caller gave up.
     */
    @Test
    void testPrepareRequests_cancelledContextClearsRequests() throws Exception {
        processingContext.setRequests(List.of(buildMeasurementRequest("{\"value\":1}")));

        ProcessingResultWrapper<Object> wrapper = ProcessingResultWrapper.builder().build();
        wrapper.getCancellationRequested().set(true);

        processor.prepareRequests(buildContextOnlyExchange(wrapper));

        assertTrue(processingContext.getRequests().isEmpty(),
                "A cancelled context must have its requests cleared before the split");
    }

    // ---- helpers ----

    private Exchange buildSplitExchange(DynamicMapperRequest bodyRequest) {
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class))
                .thenReturn(processingContext);
        when(message.getBody(DynamicMapperRequest.class)).thenReturn(bodyRequest);
        return exchange;
    }

    private Exchange buildContextOnlyExchange() {
        return buildContextOnlyExchange(null);
    }

    private Exchange buildContextOnlyExchange(ProcessingResultWrapper<Object> wrapper) {
        Exchange exchange = mock(Exchange.class);
        Message message = mock(Message.class);
        when(exchange.getIn()).thenReturn(message);
        when(message.getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class))
                .thenReturn(processingContext);
        when(message.getHeader(eq(CamelHeaders.PROCESSING_RESULT_WRAPPER), eq(ProcessingResultWrapper.class)))
                .thenReturn(wrapper);
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
