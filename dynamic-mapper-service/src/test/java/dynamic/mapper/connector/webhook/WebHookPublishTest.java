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

package dynamic.mapper.connector.webhook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpServer;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * What an external webhook receives over the wire: the body bytes and the Content-Type header. A real local HTTP
 * server records them, so the encoder WebClient picks for the body is part of what is tested.
 */
class WebHookPublishTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private WebHook webHook;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of("cumulocityInternal", false));
        webHook = new WebHook();
        ReflectionTestUtils.setField(webHook, "tenant", "t1");
        ReflectionTestUtils.setField(webHook, "connectorName", "webhook");
        ReflectionTestUtils.setField(webHook, "connectorConfiguration", configuration);
        ReflectionTestUtils.setField(webHook, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(webHook, "baseUrlEndsWithSlash", false);
        ReflectionTestUtils.setField(webHook, "webhookClient", WebClient.create());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ProcessingContext<Object> publish(RequestMethod requestMethod, DynamicMapperRequest.DynamicMapperRequestBuilder request) {
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .serviceConfiguration(new ServiceConfiguration())
                .resolvedPublishTopic("device/out")
                .requests(List.of(request.method(requestMethod).publishTopic("device/out").build()))
                .build();
        webHook.publishMEAO(context);
        assertTrue(context.getErrors().isEmpty(), "Unexpected errors: " + context.getErrors());
        return context;
    }

    @Test
    void aJsonPayloadIsStillSentAsApplicationJson() {
        publish(RequestMethod.POST, DynamicMapperRequest.builder().request("{\"a\":1}"));

        assertEquals("POST", method.get());
        assertTrue(contentType.get().startsWith("application/json"), contentType.get());
        assertEquals("{\"a\":1}", new String(body.get(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aTextPayloadIsSentUnchangedWithTheStatedContentType() {
        publish(RequestMethod.POST, DynamicMapperRequest.builder().request("AT+GTUPD=gv300,0,,,,,,$")
                .contentType("text/plain"));

        assertTrue(contentType.get().startsWith("text/plain"), contentType.get());
        assertEquals("AT+GTUPD=gv300,0,,,,,,$", new String(body.get(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aBinaryPayloadIsSentAsBytes() {
        byte[] bytes = { 0, 1, 2, (byte) 0xFF, (byte) 0x80 };

        publish(RequestMethod.POST, DynamicMapperRequest.builder().request("AAEC/4A=").binaryPayload(bytes)
                .contentType("application/octet-stream"));

        assertEquals("application/octet-stream", contentType.get());
        assertArrayEquals(bytes, body.get());
    }

    @Test
    void aBinaryPayloadWithoutAContentTypeIsStillSentAsBytes() {
        byte[] bytes = { (byte) 0xC3, 0x28, (byte) 0xFF }; // not valid UTF-8

        publish(RequestMethod.POST, DynamicMapperRequest.builder().request("wygo").binaryPayload(bytes));

        assertArrayEquals(bytes, body.get());
    }

    @Test
    void putAndPatchCarryTheSameBodyAndContentType() {
        publish(RequestMethod.PUT, DynamicMapperRequest.builder().request("raw-put").contentType("text/plain"));
        assertEquals("PUT", method.get());
        assertEquals("raw-put", new String(body.get(), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(contentType.get().startsWith("text/plain"), contentType.get());

        publish(RequestMethod.PATCH, DynamicMapperRequest.builder().request("raw-patch").contentType("text/plain"));
        assertEquals("PATCH", method.get());
        assertEquals("raw-patch", new String(body.get(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
