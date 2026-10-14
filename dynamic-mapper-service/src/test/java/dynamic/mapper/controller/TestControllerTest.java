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

package dynamic.mapper.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.connector.test.TestClient;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.TestContext;
import dynamic.mapper.model.TestResult;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.model.TransformationType;

/**
 * Unit tests for {@link TestController#testMapping}, which previously had no direct
 * test coverage despite being the sole place that assembles {@link TestResult} from a
 * {@link ProcessingContext} and maps failures onto HTTP status codes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestControllerTest {

    private static final String TENANT = "testTenant";

    @Mock
    private ConnectorRegistry connectorRegistry;

    @Mock
    private C8YAgent c8YAgent;

    @Mock
    private ContextService<UserCredentials> contextService;

    @Mock
    private AConnectorClient connectorClient;

    @Mock
    private GenericMessageCallback dispatcher;

    private TestController controller;

    @BeforeEach
    void setUp() {
        controller = new TestController(connectorRegistry, c8YAgent, contextService);

        UserCredentials creds = mock(UserCredentials.class);
        when(creds.getTenant()).thenReturn(TENANT);
        when(contextService.getContext()).thenReturn(creds);
    }

    private Mapping makeInboundMapping() {
        Mapping mapping = new Mapping();
        mapping.setId("m1");
        mapping.setName("test-mapping");
        mapping.setDirection(Direction.INBOUND);
        mapping.setTargetAPI(API.MEASUREMENT);
        mapping.setMappingType(MappingType.JSON);
        mapping.setTransformationType(TransformationType.JSONATA);
        mapping.setMappingTopicSample("device/berlin_01");
        mapping.setSourceTemplate("{}");
        mapping.setFilterMapping("true");
        return mapping;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void stubDispatcherResult(ProcessingContext<?> resultContext) throws Exception {
        when(connectorRegistry.getClientForTenant(TENANT, TestClient.TEST_CONNECTOR_IDENTIFIER))
                .thenReturn(connectorClient);
        when(connectorClient.getDispatcher()).thenReturn(dispatcher);
        when(connectorClient.getConnectorIdentifier()).thenReturn(TestClient.TEST_CONNECTOR_IDENTIFIER);

        ProcessingResultWrapper wrapper = ProcessingResultWrapper.builder()
                .processingResult(CompletableFuture.completedFuture(List.of((ProcessingContext) resultContext)))
                .build();
        when(dispatcher.onTestMessage(any(ConnectorMessage.class), any(Mapping.class))).thenReturn(wrapper);
    }

    @Test
    void testMapping_returnsSuccessResultWithRequests() throws Exception {
        Mapping mapping = makeInboundMapping();
        DynamicMapperRequest request = DynamicMapperRequest.builder()
                .api(API.MEASUREMENT)
                .request("{\"c8y_Temperature\":42}")
                .build();
        ProcessingContext<Object> resultContext = ProcessingContext.builder()
                .mapping(mapping)
                .requests(List.of(request))
                .key("record-key-1")
                .build();
        stubDispatcherResult(resultContext);

        TestContext ctx = new TestContext();
        ctx.setMapping(mapping);
        ctx.setPayload("{\"temp\":42}");
        ctx.setSend(false);

        ResponseEntity<TestResult> response = controller.testMapping(ctx);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        TestResult body = response.getBody();
        assertTrue(body.getSuccess());
        assertEquals(1, body.getRequests().size());
        assertEquals("record-key-1", body.getKey());
        assertTrue(body.getErrors().isEmpty());
    }

    @Test
    void testMapping_synthesizesFilterWarningWhenNoRequestsProduced() throws Exception {
        Mapping mapping = makeInboundMapping();
        mapping.setFilterMapping("$.temp > 100");
        ProcessingContext<Object> resultContext = ProcessingContext.builder()
                .mapping(mapping)
                .build();
        stubDispatcherResult(resultContext);

        TestContext ctx = new TestContext();
        ctx.setMapping(mapping);
        ctx.setPayload("{\"temp\":42}");
        ctx.setSend(false);

        TestResult body = controller.testMapping(ctx).getBody();

        assertTrue(body.getRequests().isEmpty());
        assertEquals(1, body.getWarnings().size());
        assertTrue(body.getWarnings().get(0).contains("$.temp > 100"));
    }

    @Test
    void testMapping_flattensErrorCauseChain() throws Exception {
        Mapping mapping = makeInboundMapping();
        Exception cause = new IllegalStateException("root cause");
        Exception wrapped = new RuntimeException("outer failure", cause);
        ProcessingContext<Object> resultContext = ProcessingContext.builder()
                .mapping(mapping)
                .errors(List.of(wrapped))
                .build();
        stubDispatcherResult(resultContext);

        TestContext ctx = new TestContext();
        ctx.setMapping(mapping);
        ctx.setPayload("{}");
        ctx.setSend(false);

        TestResult body = controller.testMapping(ctx).getBody();

        assertTrue(!body.getSuccess());
        assertEquals(1, body.getErrors().size());
        assertTrue(body.getErrors().get(0).contains("outer failure"));
        assertTrue(body.getErrors().get(0).contains("root cause"));
    }

    @Test
    void testMapping_connectorNotFound_yields404() throws Exception {
        when(connectorRegistry.getClientForTenant(eq(TENANT), anyString()))
                .thenThrow(new ConnectorRegistryException("no test connector registered"));

        TestContext ctx = new TestContext();
        ctx.setMapping(makeInboundMapping());
        ctx.setPayload("{}");
        ctx.setSend(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.testMapping(ctx));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── Mock cache lifecycle (isolation requirement 2: clear between runs) ──

    private TestContext dryRunContext() {
        TestContext ctx = new TestContext();
        ctx.setMapping(makeInboundMapping());
        ctx.setPayload("{}");
        ctx.setSend(false);
        return ctx;
    }

    @Test
    void testMapping_clearsMockCachesBeforeAndAfterTheRun() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());

        controller.testMapping(dryRunContext());

        InOrder order = inOrder(c8YAgent, dispatcher);
        order.verify(c8YAgent).clearMockIdentityCache(TENANT);
        order.verify(c8YAgent).clearMockInventoryCache(TENANT);
        order.verify(dispatcher).onTestMessage(any(ConnectorMessage.class), any(Mapping.class));
        order.verify(c8YAgent).clearMockIdentityCache(TENANT);
        order.verify(c8YAgent).clearMockInventoryCache(TENANT);
    }

    @Test
    void testMapping_clearsMockCachesAfterAFailedRun() throws Exception {
        when(connectorRegistry.getClientForTenant(eq(TENANT), anyString()))
                .thenThrow(new ConnectorRegistryException("no test connector registered"));

        assertThrows(ResponseStatusException.class, () -> controller.testMapping(dryRunContext()));

        // once on entry, once in the finally block — a failed run must not leave state behind
        verify(c8YAgent, times(2)).clearMockIdentityCache(TENANT);
        verify(c8YAgent, times(2)).clearMockInventoryCache(TENANT);
    }

    @Test
    void testMapping_failingCleanupDoesNotMaskTheResult() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        doThrow(new IllegalStateException("No tenant context available"))
                .when(c8YAgent).clearMockIdentityCache(TENANT);

        ResponseEntity<TestResult> response = controller.testMapping(dryRunContext());

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testMapping_liveTest_reportsWhetherTheTestDeviceWasCreatedOrReused() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString()))
                .thenReturn(new C8YAgent.TestDevice("77", false));

        TestContext ctx = dryRunContext();
        ctx.setSend(true);
        TestResult reused = controller.testMapping(ctx).getBody();

        assertEquals("77", reused.getTestDeviceId());
        assertEquals(Boolean.FALSE, reused.getTestDeviceCreated(),
                "an existing device is used as is and must not be reported as a created test device");

        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString()))
                .thenReturn(new C8YAgent.TestDevice("78", true));
        TestResult created = controller.testMapping(ctx).getBody();

        assertEquals("78", created.getTestDeviceId());
        assertEquals(Boolean.TRUE, created.getTestDeviceCreated());
    }

    @Test
    void testMapping_liveTest_alwaysUsesATestDevice_evenWhenTheLegacyFlagIsOff() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString()))
                .thenReturn(new C8YAgent.TestDevice("77", true));

        TestContext ctx = dryRunContext();
        ctx.setSend(true);
        ctx.setCreateTestDevice(false); // deprecated, ignored

        controller.testMapping(ctx);

        verify(c8YAgent).ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString());
    }

    @Test
    void testMapping_dryRun_neverTouchesTestDevices() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());

        controller.testMapping(dryRunContext());

        verify(c8YAgent, org.mockito.Mockito.never()).ensureTestDevice(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testMapping_liveTest_failsWithoutSendingWhenTheTestDeviceCannotBeProvided() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString())).thenReturn(null);

        TestContext ctx = dryRunContext();
        ctx.setSend(true);
        TestResult result = controller.testMapping(ctx).getBody();

        assertTrue(!result.getSuccess());
        assertTrue(result.getErrors().get(0).contains("could not be created or found"));
        // Only the dry run that derives the external ID ran; the live message was never dispatched, so
        // the mapping did not get the chance to create a device itself.
        verify(dispatcher, times(1)).onTestMessage(any(ConnectorMessage.class), any(Mapping.class));
    }

    @Test
    void testMapping_liveTest_registersTheTestDeviceUnderAPrefixedExternalId() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString()))
                .thenReturn(new C8YAgent.TestDevice("77", true));

        TestContext ctx = dryRunContext();
        ctx.setSend(true);
        controller.testMapping(ctx);

        // The device must not own the external ID the real device will use, or the real device's
        // first message would be written to the test device.
        org.mockito.ArgumentCaptor<String> externalId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(c8YAgent).ensureTestDevice(eq(TENANT), externalId.capture(), externalId.capture(), anyString());
        externalId.getAllValues().forEach(v -> assertTrue(v.startsWith(ProcessingContext.TEST_EXTERNAL_ID_PREFIX), v));

        // ...and the live message is processed against that same prefix
        org.mockito.ArgumentCaptor<ConnectorMessage> sent = org.mockito.ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(dispatcher, times(2)).onTestMessage(sent.capture(), any(Mapping.class));
        ConnectorMessage live = sent.getAllValues().get(1);
        assertEquals(ProcessingContext.TEST_EXTERNAL_ID_PREFIX, live.getTestExternalIdPrefix());
        // the dry run that derives the external ID runs unprefixed against the mock
        assertEquals(null, sent.getAllValues().get(0).getTestExternalIdPrefix());
    }

    @Test
    void testMapping_dryRun_carriesNoExternalIdPrefix() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());

        controller.testMapping(dryRunContext());

        org.mockito.ArgumentCaptor<ConnectorMessage> sent = org.mockito.ArgumentCaptor.forClass(ConnectorMessage.class);
        verify(dispatcher).onTestMessage(sent.capture(), any(Mapping.class));
        assertEquals(null, sent.getValue().getTestExternalIdPrefix());
    }

    private ProcessingContext<Object> contextFailingWithDeletedDevice() {
        ProcessingContext<Object> failed = ProcessingContext.builder().mapping(makeInboundMapping()).build();
        failed.addError(new dynamic.mapper.processor.ProcessingException("Could not sent payload to c8y",
                new com.cumulocity.sdk.client.SDKException(404,
                        "Finding device data from database failed : No managedObject for id '581685638'!")));
        return failed;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ProcessingResultWrapper wrapperOf(ProcessingContext<?> context) {
        return ProcessingResultWrapper.builder()
                .processingResult(CompletableFuture.completedFuture(List.of((ProcessingContext) context))).build();
    }

    @Test
    void testMapping_liveTest_staleDeletedTestDevice_evictsReEnsuresAndRetriesOnce() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        // dry run (derives the external ID), live send fails on the deleted device, retry succeeds
        when(dispatcher.onTestMessage(any(ConnectorMessage.class), any(Mapping.class))).thenReturn(
                wrapperOf(ProcessingContext.builder().mapping(makeInboundMapping()).build()),
                wrapperOf(contextFailingWithDeletedDevice()),
                wrapperOf(ProcessingContext.builder().mapping(makeInboundMapping()).build()));
        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString()))
                .thenReturn(new C8YAgent.TestDevice("old", false), new C8YAgent.TestDevice("new", true));

        TestContext ctx = dryRunContext();
        ctx.setSend(true);
        TestResult result = controller.testMapping(ctx).getBody();

        assertTrue(result.getSuccess(), String.valueOf(result.getErrors()));
        assertEquals("new", result.getTestDeviceId());
        assertEquals(Boolean.TRUE, result.getTestDeviceCreated());
        verify(c8YAgent).evictExternalIdFromCaches(eq(TENANT), any(com.cumulocity.model.ID.class));
        verify(c8YAgent, times(2)).ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString());
        verify(dispatcher, times(3)).onTestMessage(any(ConnectorMessage.class), any(Mapping.class));
    }

    @Test
    void testMapping_liveTest_staleTestDeviceThatStaysStale_givesAnActionableMessage() throws Exception {
        stubDispatcherResult(ProcessingContext.builder().mapping(makeInboundMapping()).build());
        when(dispatcher.onTestMessage(any(ConnectorMessage.class), any(Mapping.class))).thenReturn(
                wrapperOf(ProcessingContext.builder().mapping(makeInboundMapping()).build()),
                wrapperOf(contextFailingWithDeletedDevice()),
                wrapperOf(contextFailingWithDeletedDevice()));
        when(c8YAgent.ensureTestDevice(eq(TENANT), anyString(), anyString(), anyString()))
                .thenReturn(new C8YAgent.TestDevice("old", false));

        TestContext ctx = dryRunContext();
        ctx.setSend(true);
        TestResult result = controller.testMapping(ctx).getBody();

        assertTrue(!result.getSuccess());
        // retried once, not forever
        verify(dispatcher, times(3)).onTestMessage(any(ConnectorMessage.class), any(Mapping.class));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Clear the Inbound ID cache")),
                String.valueOf(result.getErrors()));
    }

    @Test
    void testMapping_dryRunAndOtherErrors_neverTriggerTheStaleDeviceRecovery() throws Exception {
        stubDispatcherResult(contextFailingWithDeletedDevice());

        controller.testMapping(dryRunContext());

        verify(c8YAgent, org.mockito.Mockito.never()).evictExternalIdFromCaches(anyString(), any());
        verify(dispatcher, times(1)).onTestMessage(any(ConnectorMessage.class), any(Mapping.class));
    }

    @Test
    void testMapping_executionExceptionDuringDispatch_yields500() throws Exception {
        when(connectorRegistry.getClientForTenant(TENANT, TestClient.TEST_CONNECTOR_IDENTIFIER))
                .thenReturn(connectorClient);
        when(connectorClient.getDispatcher()).thenReturn(dispatcher);
        when(connectorClient.getConnectorIdentifier()).thenReturn(TestClient.TEST_CONNECTOR_IDENTIFIER);

        CompletableFuture failingFuture = new CompletableFuture<>();
        failingFuture.completeExceptionally(new ExecutionException("boom", new RuntimeException("boom")));
        ProcessingResultWrapper wrapper = ProcessingResultWrapper.builder()
                .processingResult(failingFuture)
                .build();
        when(dispatcher.onTestMessage(any(ConnectorMessage.class), any(Mapping.class))).thenReturn(wrapper);

        TestContext ctx = new TestContext();
        ctx.setMapping(makeInboundMapping());
        ctx.setPayload("{}");
        ctx.setSend(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.testMapping(ctx));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
    }

    @Test
    void echoInput_returnsBodyUnchanged() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/webhook/echo/foo");
        when(request.getQueryString()).thenReturn(null);

        String result = controller.echoInput(request, "{\"hello\":\"world\"}");

        assertEquals("{\"hello\":\"world\"}", result);
    }

    @Test
    void echoHealth_returns200() {
        jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost/webhook"));
        when(request.getQueryString()).thenReturn(null);

        ResponseEntity<String> response = controller.echoHealth(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
