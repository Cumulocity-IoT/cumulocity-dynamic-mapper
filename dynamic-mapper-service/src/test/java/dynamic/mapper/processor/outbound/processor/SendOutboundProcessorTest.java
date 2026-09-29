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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.processor.runtime.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Covers the FAILED/SUCCESSFUL auto-ack fix in {@link SendOutboundProcessor}: a publish
 * failure must be visible via {@code context.hasError()} (which {@code process()} checks to
 * decide the operation auto-ack status), not just recorded on the individual request. Before
 * the fix, {@code processAndPrepareRequests} only called {@code request.setError(e)} /
 * {@code context.getCurrentRequest().setError(e)} on failure, never {@code context.addError(...)}
 * — since {@code ProcessingContext.hasError()} only inspects the context-level errors list, a
 * publish failure was silently swallowed and the operation got acked SUCCESSFUL regardless.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendOutboundProcessorTest {

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private ConnectorRegistry connectorRegistry;

    @Mock
    private MappingService mappingService;

    @Mock
    private ServiceConfiguration serviceConfiguration;

    @Mock
    private AConnectorClient connectorClient;

    private SendOutboundProcessor processor;

    private static final String TEST_TENANT = "testTenant";
    private static final String TEST_CONNECTOR = "mqtt-connector";

    private Mapping mapping;
    private ProcessingContext<Object> context;

    @BeforeEach
    void setUp() throws Exception {
        processor = new SendOutboundProcessor(c8yAgent, connectorRegistry, mappingService);

        mapping = Mapping.builder()
                .id("outbound-test-1")
                .identifier("outbound-test-ident")
                .name("Outbound Test Mapping")
                .targetAPI(API.CUSTOM)
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.JSONATA)
                .autoAckOperation(false)
                .debug(false)
                .build();

        when(mappingService.getMappingStatus(any(), any())).thenReturn(
                new MappingStatus("id-1", "Outbound Test Mapping", "outbound-test-ident",
                        Direction.OUTBOUND, null, "out/topic", 0L, 0L, 0L, null));

        context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .serviceConfiguration(serviceConfiguration)
                .api(API.CUSTOM)
                .sendPayload(true)
                .testing(false)
                .build();

        List<DynamicMapperRequest> requests = new ArrayList<>();
        requests.add(DynamicMapperRequest.builder()
                .predecessor(-1)
                .api(API.MEASUREMENT)
                .request("{\"value\":21.0}")
                .build());
        context.setRequests(requests);

        when(connectorRegistry.getClientForTenant(TEST_TENANT, TEST_CONNECTOR)).thenReturn(connectorClient);
        when(connectorClient.isConnected()).thenReturn(true);
    }

    @Test
    void publishFailure_isVisibleViaContextHasError() throws Exception {
        doThrow(new RuntimeException("connector publish failed")).when(connectorClient).publishMEAO(context);

        processor.process(context, TEST_CONNECTOR);

        assertTrue(context.hasError(),
                "A publishMEAO failure must be recorded on the context so the FAILED/SUCCESSFUL "
                        + "auto-ack reflects it, not just on context.getCurrentRequest()");
    }

    @Test
    void publishSuccess_noError() throws Exception {
        doNothing().when(connectorClient).publishMEAO(context);

        processor.process(context, TEST_CONNECTOR);

        assertFalse(context.hasError(), "A successful publish must not record any context-level error");
    }

    @Test
    void customRequestFailure_isVisibleViaContextHasError() throws Exception {
        DynamicMapperRequest customRequest = DynamicMapperRequest.builder()
                .predecessor(-1)
                .api(API.CUSTOM)
                .pathCumulocity("/service/example")
                .request("{}")
                .build();
        context.setRequests(List.of(customRequest));

        when(c8yAgent.createMEAO(same(context), eq(0))).thenThrow(new RuntimeException("microservice call failed"));

        processor.process(context, TEST_CONNECTOR);

        assertNotNull(customRequest.getError(), "The failing CUSTOM request must record its own error");
        assertTrue(context.hasError(),
                "A CUSTOM request failure must also be recorded on the context, not just the request");
    }
}
