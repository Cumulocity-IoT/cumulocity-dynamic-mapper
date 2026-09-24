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
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.mapping.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Tests {@link SendInboundProcessor}'s request-indexing behaviour.
 *
 * <p>Camel's {@code direct:processRequestsInParallel} route (which split
 * {@code context.requests} and dispatched each as a separate Exchange body) was
 * dead code: the {@code PARALLEL_PROCESSING} header that selected it was set by
 * {@code SubstitutionResultInboundProcessor} only for the JSONata pipeline, but
 * read only in the mutually-exclusive Extension pipeline branch, so it never
 * fired in production. It was removed along with the rest of Camel; requests are
 * now always processed sequentially via {@code processAllRequests}.
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

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_DEVICE_ID = "device-parallel-001";

    @BeforeEach
    void setUp() throws Exception {
        processor = new SendInboundProcessor(c8yAgent, serviceRegistry, identityResolutionService,
                new ObjectMapper(), mappingService);

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
     * All requests in context.getRequests() are processed via processAllRequests →
     * processSingleRequest. Uses MEASUREMENT + EVENT to avoid the bulk-merge logic
     * that collapses two MEASUREMENT requests into one.
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

        processor.process(processingContext);

        verify(c8yAgent).createMEAO(same(processingContext), eq(0));
        verify(c8yAgent).createMEAO(same(processingContext), eq(1));

        assertNotNull(req0.getResponse(), "Request at index 0 must have a response");
        assertNotNull(req1.getResponse(), "Request at index 1 must have a response");

        log.info("Sequential requests processed correctly with indices 0 and 1");
    }

    // ---- helpers ----

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
