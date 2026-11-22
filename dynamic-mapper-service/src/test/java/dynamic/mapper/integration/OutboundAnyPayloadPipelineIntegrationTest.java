/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapper.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ExtensionInboundRegistry;
import dynamic.mapper.core.GraalVMContextService;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.model.TransformationType;
import dynamic.mapper.model.extension.Extension;
import dynamic.mapper.model.extension.ExtensionEntry;
import dynamic.mapper.model.extension.ExtensionType;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import dynamic.mapper.processor.outbound.OutboundMessageRouter;
import dynamic.mapper.processor.outbound.processor.DeserializationOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.EnrichmentOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.ExtensibleOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.ExtensibleResultOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowResultOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.JSONataOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SendOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SubstitutionResultOutboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import lombok.extern.slf4j.Slf4j;

/**
 * End-to-end test of an outbound mapping of type Any Payload: a Cumulocity operation goes through the real
 * {@link OutboundMessageRouter} with every real processor (deserialization, enrichment, the GraalVM Smart Function
 * runtime or a Java extension, result processing, send) and ends in a connector, which records what it was asked to
 * publish. Only the transport and Cumulocity are mocked, so JavaScript really runs, and the Smart Function's return
 * value really crosses the GraalVM boundary into the Java pipeline.
 */
@Slf4j
class OutboundAnyPayloadPipelineIntegrationTest {

    private static final String TENANT = "testTenant";
    private static final String CONNECTOR = "connector-1";
    private static final String DEVICE_ID = "12345";
    private static final String COMMAND = "AT+GTUPD=gv300,0,,,http://example.com/fw.bin,,,$";

    private GraalVMContextService graalVMContextService;
    private ExecutorService executor;
    private OutboundMessageRouter router;
    private ServiceConfiguration serviceConfiguration;
    private AConnectorClient connector;
    private ExtensionInboundRegistry extensionRegistry;

    /** The requests of every publishMEAO call, copied at the time of the call. */
    private final List<List<DynamicMapperRequest>> published = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        serviceConfiguration = new ServiceConfiguration();
        Map<String, CodeTemplate> templates = new HashMap<>();
        templates.put(TemplateType.SHARED.name(), template("// shared"));
        templates.put(TemplateType.SYSTEM.name(), template("// system"));
        serviceConfiguration.setCodeTemplates(templates);

        graalVMContextService = new GraalVMContextService();
        graalVMContextService.initializeTenant(TENANT, serviceConfiguration);

        C8YAgent c8yAgent = mock(C8YAgent.class);
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getGraalVMContextService()).thenReturn(graalVMContextService);
        when(serviceRegistry.getC8yAgent()).thenReturn(c8yAgent);

        MappingService mappingService = mock(MappingService.class);
        MappingStatus status = new MappingStatus("id", "Any Payload", "identifier", Direction.OUTBOUND, "command/#",
                "queclink/cmd", 0L, 0L, 0L, null);
        when(mappingService.getMappingStatus(anyString(), any(Mapping.class))).thenReturn(status);

        FlowStateStore flowStateStore = mock(FlowStateStore.class);
        extensionRegistry = mock(ExtensionInboundRegistry.class);

        connector = mock(AConnectorClient.class);
        when(connector.isConnected()).thenReturn(true);
        when(connector.isReadyForOutbound()).thenReturn(true);
        when(connector.isMappingOutboundDeployed(anyString())).thenReturn(true);
        doAnswer(invocation -> {
            ProcessingContext<?> context = invocation.getArgument(0);
            published.add(new ArrayList<>(context.getRequests()));
            return null;
        }).when(connector).publishMEAO(any());
        ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
        when(connectorRegistry.getClientForTenant(TENANT, CONNECTOR)).thenReturn(connector);

        executor = Executors.newVirtualThreadPerTaskExecutor();
        router = new OutboundMessageRouter(
                connectorRegistry,
                executor,
                new EnrichmentOutboundProcessor(serviceRegistry, mappingService, c8yAgent, flowStateStore),
                new ExtensibleOutboundProcessor(mappingService, extensionRegistry, flowStateStore),
                new ExtensibleResultOutboundProcessor(mappingService, c8yAgent, objectMapper),
                new FlowOutboundProcessor(mappingService, graalVMContextService, c8yAgent),
                mock(SubstitutionResultOutboundProcessor.class),
                new DeserializationOutboundProcessor(),
                mock(JSONataOutboundProcessor.class),
                new FlowResultOutboundProcessor(mappingService, objectMapper, null),
                new SendOutboundProcessor(c8yAgent, connectorRegistry, mappingService),
                new ConsolidationProcessor(mappingService));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        graalVMContextService.removeTenant(TENANT);
    }

    // ----------------------------------------------------------------------------------------------------------
    // Smart Function
    // ----------------------------------------------------------------------------------------------------------

    @Test
    void aSmartFunctionSendsARawTextCommandExactlyAsReturned() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    var command = msg.payload["c8y_Command"]["text"];
                    return { topic: "queclink/cmd/" + msg.sourceId, payload: "AT+" + command };
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        DynamicMapperRequest request = onlyRequest();
        assertEquals(COMMAND, request.getRequest(), "no quotes, no JSON wrapping, no added line break");
        assertEquals("queclink/cmd/" + DEVICE_ID, request.getPublishTopic());
        assertEquals("text/plain", request.getContentType());
        assertNull(request.getBinaryPayload());
        assertNull(request.getPathCumulocity(), "a text payload must never be routed to a Cumulocity API");
    }

    @Test
    void aRawSmartFunctionResultIgnoresCumulocityTypeAndStaysOnTheBrokerPath() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    return [
                        { topic: "device/cmd", payload: "AT+X$", payloadEncoding: "text", cumulocityType: "event" },
                        { topic: "device/fw", payload: "AAEC/w==", payloadEncoding: "base64",
                          cumulocityType: "operation" },
                        { topic: "/service/my-service/x", payload: "plain", cumulocityType: "custom",
                          targetPath: "/service/my-service/x" }
                    ];
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        List<DynamicMapperRequest> requests = onlyPublish();
        assertEquals(3, requests.size());
        assertEquals("AT+X$", requests.get(0).getRequest());
        assertEquals("text/plain", requests.get(0).getContentType());
        assertArrayEquals(new byte[] { 0, 1, 2, (byte) 0xFF }, requests.get(1).getBinaryPayload());
        assertEquals("plain", requests.get(2).getRequest(), "not JSON-serialized");
        for (DynamicMapperRequest request : requests) {
            assertTrue(request.isRawPayload());
            assertNull(request.getPathCumulocity(), "a raw payload is never routed to a Cumulocity API");
        }
    }

    @Test
    void aSmartFunctionSendsBase64AndHexAsBytes() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    return [
                        { topic: "device/fw", payload: "AAEC/w==", payloadEncoding: "base64",
                          contentType: "application/x-protobuf" },
                        { topic: "device/fw", payload: "00 01 02 ff", payloadEncoding: "hex" }
                    ];
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        List<DynamicMapperRequest> requests = onlyPublish();
        assertEquals(2, requests.size());
        byte[] expected = { 0, 1, 2, (byte) 0xFF };
        assertArrayEquals(expected, requests.get(0).getBinaryPayload());
        assertEquals("application/x-protobuf", requests.get(0).getContentType());
        assertArrayEquals(expected, requests.get(1).getBinaryPayload());
        assertEquals("application/octet-stream", requests.get(1).getContentType());
    }

    @Test
    void aSmartFunctionMixesJsonTextAndBinaryInOneCall() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    return [
                        { topic: "device/json", payload: { command: "start" } },
                        { topic: "device/text", payload: "START$" },
                        { topic: "device/bin", payload: "AQID", payloadEncoding: "base64" }
                    ];
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        List<DynamicMapperRequest> requests = onlyPublish();
        assertEquals(3, requests.size());
        assertEquals("{\"command\":\"start\"}", requests.get(0).getRequest());
        assertNull(requests.get(0).getContentType(), "JSON keeps the connector default content type");
        assertEquals("START$", requests.get(1).getRequest());
        assertEquals("text/plain", requests.get(1).getContentType());
        assertArrayEquals(new byte[] { 1, 2, 3 }, requests.get(2).getBinaryPayload());
    }

    @Test
    void aSmartFunctionReturningJsonStringifyIsSentUnchanged() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    return { topic: "device/json", payload: JSON.stringify({ temperature: 21.5 }) };
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        DynamicMapperRequest request = onlyRequest();
        assertEquals("{\"temperature\":21.5}", request.getRequest());
        assertNull(request.getContentType());
    }

    @Test
    void aSmartFunctionTextStartingWithABraceNeedsTheTextEncodingOnlyForTheContentType() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    return { topic: "device/cmd", payload: "{not json}", payloadEncoding: "text" };
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        assertEquals("{not json}", onlyRequest().getRequest());
        assertEquals("text/plain", onlyRequest().getContentType());
    }

    @Test
    void anInvalidEncodingFailsTheMappingAndPublishesNothing() {
        Mapping mapping = smartFunctionMapping("""
                function onMessage(msg, context) {
                    return { topic: "device/fw", payload: "not base64!", payloadEncoding: "base64" };
                }
                """);

        ProcessingContext<Object> result = run(mapping);

        assertTrue(result.hasError(), "invalid Base64 must be reported");
        assertTrue(published.isEmpty() || published.get(0).isEmpty(), "nothing may be published");
    }

    // ----------------------------------------------------------------------------------------------------------
    // Java extension
    // ----------------------------------------------------------------------------------------------------------

    @Test
    void aJavaExtensionSendsTextAndBytes() {
        Mapping mapping = extensionMapping((message, context) -> new DeviceMessage[] {
                DeviceMessage.forTopic("queclink/cmd/" + DEVICE_ID).payload(COMMAND).build(),
                DeviceMessage.forTopic("device/fw").payload(new byte[] { 0, 1, 2, (byte) 0xFF }).build(),
                DeviceMessage.forTopic("device/fw").payload("AAEC/w==").payloadEncoding(DeviceMessage.ENCODING_BASE64)
                        .contentType("application/x-protobuf").build()
        });

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        List<DynamicMapperRequest> requests = onlyPublish();
        assertEquals(3, requests.size());
        assertEquals(COMMAND, requests.get(0).getRequest());
        assertEquals("text/plain", requests.get(0).getContentType());
        assertNull(requests.get(0).getBinaryPayload());
        assertArrayEquals(new byte[] { 0, 1, 2, (byte) 0xFF }, requests.get(1).getBinaryPayload());
        assertEquals("application/octet-stream", requests.get(1).getContentType());
        assertArrayEquals(new byte[] { 0, 1, 2, (byte) 0xFF }, requests.get(2).getBinaryPayload());
        assertEquals("application/x-protobuf", requests.get(2).getContentType());
    }

    @Test
    void aJavaExtensionKeepsItsJsonBehaviorForExistingPayloads() {
        Mapping mapping = extensionMapping((message, context) -> new DeviceMessage[] {
                DeviceMessage.forTopic("device/a").payload("{\"a\":1}").build(),
                DeviceMessage.forTopic("device/b").payload("[1,2]").build(),
                DeviceMessage.forTopic("device/c").payload(Map.of("c", 3)).build()
        });

        ProcessingContext<Object> result = run(mapping);

        assertNoErrors(result);
        List<DynamicMapperRequest> requests = onlyPublish();
        assertEquals(3, requests.size());
        assertEquals("{\"a\":1}", requests.get(0).getRequest());
        assertEquals("[1,2]", requests.get(1).getRequest());
        assertEquals("{\"c\":3}", requests.get(2).getRequest());
        requests.forEach(r -> assertNull(r.getContentType(), "JSON keeps the connector default: " + r.getRequest()));
        requests.forEach(r -> assertNull(r.getBinaryPayload()));
    }

    // ----------------------------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------------------------

    private static CodeTemplate template(String code) {
        CodeTemplate template = new CodeTemplate();
        template.setCode(Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
        return template;
    }

    private Mapping baseMapping(TransformationType transformationType) {
        return Mapping.builder()
                .id("any-payload-out")
                .identifier("any-payload-out")
                .name("Any Payload outbound")
                .direction(Direction.OUTBOUND)
                .mappingType(MappingType.ANY_PAYLOAD)
                .transformationType(transformationType)
                .publishTopic("queclink/cmd")
                .targetAPI(API.OPERATION)
                .useExternalId(false)
                .externalIdType("c8y_Serial")
                .active(true)
                .debug(false)
                .build();
    }

    private Mapping smartFunctionMapping(String code) {
        Mapping mapping = baseMapping(TransformationType.SMART_FUNCTION);
        // The runtime loads the function from the module's exports, as the code of a real mapping declares them
        String module = code + "\nexport { onMessage };\n";
        mapping.setCode(Base64.getEncoder().encodeToString(module.getBytes(StandardCharsets.UTF_8)));
        return mapping;
    }

    private Mapping extensionMapping(ProcessorExtensionOutbound<Object> implementation) {
        ExtensionEntry entry = ExtensionEntry.builder()
                .extensionName("any-payload-extension")
                .eventName("command")
                .fqnClassName(implementation.getClass().getName())
                .extensionType(ExtensionType.EXTENSION_OUTBOUND)
                .direction(Direction.OUTBOUND)
                .loaded(true)
                .extensionImplOutbound(implementation)
                .build();
        Extension extension = new Extension("any-payload-extension", "any-payload-extension");
        extension.getExtensionEntries().put("command", entry);
        when(extensionRegistry.getExtension(TENANT, "any-payload-extension")).thenReturn(extension);

        Mapping mapping = baseMapping(TransformationType.EXTENSION_JAVA);
        mapping.setExtension(entry);
        return mapping;
    }

    /** A Cumulocity operation for the device, as the outbound notification delivers it. */
    private C8YMessage operation() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "1");
        payload.put("deviceId", DEVICE_ID);
        payload.put("status", "PENDING");
        payload.put("c8y_Command", new HashMap<>(Map.of("text", "GTUPD=gv300,0,,,http://example.com/fw.bin,,,$")));

        C8YMessage message = new C8YMessage();
        message.setSourceId(DEVICE_ID);
        message.setDeviceName("device");
        message.setMessageId("1");
        message.setPayload("{}");
        message.setParsedPayload(payload);
        message.setApi(API.OPERATION);
        message.setTenant(TENANT);
        message.setSendPayload(true);
        message.setOperation("CREATE");
        return message;
    }

    private ProcessingContext<Object> run(Mapping mapping) {
        List<ProcessingContext<Object>> results = router.processOutboundMessage(operation(), CONNECTOR,
                List.of(mapping), false, serviceConfiguration, null);
        assertEquals(1, results.size(), "the router must return the context of the one mapping");
        return results.get(0);
    }

    private void assertNoErrors(ProcessingContext<Object> result) {
        assertFalse(result.hasError(), "Unexpected errors: " + result.getErrors());
    }

    private List<DynamicMapperRequest> onlyPublish() {
        assertEquals(1, published.size(), "the connector must be asked to publish exactly once");
        return published.get(0);
    }

    private DynamicMapperRequest onlyRequest() {
        List<DynamicMapperRequest> requests = onlyPublish();
        assertEquals(1, requests.size());
        DynamicMapperRequest request = requests.get(0);
        assertNotNull(request);
        return request;
    }
}
