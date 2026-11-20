/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.mapping.MappingService;
import dynamic.mapper.model.API;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.MappingAction;
import dynamic.mapper.processor.runtime.OutputCollector;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.RoutingContext;

class ExtensibleResultOutboundProcessorTest {

    private final ExtensibleResultOutboundProcessor processor = new ExtensibleResultOutboundProcessor(
            mock(MappingService.class), mock(C8YAgent.class), new ObjectMapper());

    private List<DynamicMapperRequest> run(DeviceMessage message) throws Exception {
        Mapping mapping = Mapping.builder().identifier("m1").name("ext").externalIdType("c8y_Serial")
                .publishTopic("device/out").build();
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant("t").mapping(mapping).sourceId("42").externalId("berlin_01")
                .extensionResult(new DeviceMessage[] { message }).build();
        OutputCollector output = new OutputCollector();
        processor.processExtensionResults(mock(RoutingContext.class), output, context);
        return output.getRequests();
    }

    @Test
    void theRequestHasAMethodSoTheWebhookConnectorCannotFailOnNull() throws Exception {
        DynamicMapperRequest r = run(DeviceMessage.forTopic("device/out").payload("{}").build()).get(0);

        assertEquals(RequestMethod.POST, r.getMethod());
        assertEquals("device/out", r.getPublishTopic());
        assertEquals("c8y_Serial", r.getExternalIdType());
        assertEquals("berlin_01", r.getExternalId());
        assertNull(r.getApi());
    }

    @Test
    void actionAndCumulocityTypeAreHonored() throws Exception {
        DeviceMessage message = DeviceMessage.forTopic("device/out").payload("{\"id\":\"7001\",\"text\":\"x\"}").build();
        message.setAction(MappingAction.UPDATE);
        message.setCumulocityType(CumulocityType.EVENT);

        DynamicMapperRequest r = run(message).get(0);

        assertEquals(RequestMethod.PUT, r.getMethod());
        assertEquals(API.EVENT, r.getApi());
        // the event's own id, not the source device (42)
        assertEquals(API.EVENT.path + "/7001", r.getPathCumulocity());
    }

    @Test
    void aTextPayloadIsSentUnchangedAndNeverRoutedToCumulocity() throws Exception {
        String command = "AT+GTUPD=gv300,0,,,http://example.com/fw.bin,,,$";
        // the topic starts with an API name: for JSON it would select the Cumulocity API
        DynamicMapperRequest r = run(DeviceMessage.forTopic("event/events").payload(command).build()).get(0);

        assertEquals(command, r.getRequest());
        assertEquals("text/plain", r.getContentType());
        assertNull(r.getBinaryPayload());
        assertNull(r.getApi());
        assertNull(r.getPathCumulocity());
    }

    @Test
    void aByteArrayPayloadIsSentAsBytes() throws Exception {
        byte[] bytes = { 0, 1, 2, (byte) 0xFF };

        DynamicMapperRequest r = run(DeviceMessage.forTopic("device/out").payload(bytes).build()).get(0);

        assertArrayEquals(bytes, r.getBinaryPayload());
        assertEquals("application/octet-stream", r.getContentType());
        assertEquals("AAEC/w==", r.getRequest(), "the request shows the Base64 text of the bytes");
    }

    @Test
    void aByteArrayOfJsonKeepsTheLegacyTextPath() throws Exception {
        byte[] json = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        DynamicMapperRequest r = run(DeviceMessage.forTopic("device/out").payload(json).build()).get(0);

        assertEquals("{\"a\":1}", r.getRequest());
        assertNull(r.getBinaryPayload());
    }

    @Test
    void payloadEncodingAndContentTypeAreHonored() throws Exception {
        DeviceMessage message = DeviceMessage.forTopic("device/fw").payload("AAEC/w==")
                .payloadEncoding(DeviceMessage.ENCODING_BASE64).contentType("application/x-protobuf").build();

        DynamicMapperRequest r = run(message).get(0);

        assertArrayEquals(new byte[] { 0, 1, 2, (byte) 0xFF }, r.getBinaryPayload());
        assertEquals("application/x-protobuf", r.getContentType());
    }

    @Test
    void anInvalidEncodingFailsTheMessage() {
        DeviceMessage message = DeviceMessage.forTopic("device/fw").payload("zz")
                .payloadEncoding(DeviceMessage.ENCODING_HEX).build();

        org.junit.jupiter.api.Assertions.assertThrows(dynamic.mapper.processor.ProcessingException.class,
                () -> run(message));
    }

    @Test
    void aJsonArrayStringKeepsItsPreviousContentTypeBehavior() throws Exception {
        DynamicMapperRequest r = run(DeviceMessage.forTopic("device/out").payload("[1,2]").build()).get(0);

        assertEquals("[1,2]", r.getRequest());
        assertNull(r.getContentType(), "the connector default, as before, not text/plain");
        assertNull(r.getBinaryPayload());
    }
}
