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

package dynamic.mapper.connector.amqp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.ConnectionStateManager;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/** What the AMQP 1.0 connector hands to JMS: a bytes message for binary, a text message otherwise. */
class AMQP10ClientPublishTest {

    private AMQP10Client client;
    private Session session;
    private MessageProducer producer;
    private BytesMessage bytesMessage;
    private TextMessage textMessage;

    @BeforeEach
    void setUp() throws Exception {
        client = new AMQP10Client();
        session = mock(Session.class);
        producer = mock(MessageProducer.class);
        bytesMessage = mock(BytesMessage.class);
        textMessage = mock(TextMessage.class);
        ConnectionStateManager state = mock(ConnectionStateManager.class);
        when(state.isConnected()).thenReturn(true);
        ConnectorConfiguration configuration = mock(ConnectorConfiguration.class);
        when(configuration.getProperties()).thenReturn(Map.of("contentType", "application/configured"));
        when(session.createQueue(anyString())).thenReturn(mock(Queue.class));
        when(session.createProducer(any(Destination.class))).thenReturn(producer);
        when(session.createBytesMessage()).thenReturn(bytesMessage);
        when(session.createTextMessage(anyString())).thenReturn(textMessage);
        ReflectionTestUtils.setField(client, "tenant", "t1");
        ReflectionTestUtils.setField(client, "connectorName", "amqp10");
        ReflectionTestUtils.setField(client, "connectionStateManager", state);
        ReflectionTestUtils.setField(client, "serviceConfiguration", new ServiceConfiguration());
        ReflectionTestUtils.setField(client, "connectorConfiguration", configuration);
        ReflectionTestUtils.setField(client, "connection", mock(Connection.class));
        ReflectionTestUtils.setField(client, "session", session);
        ReflectionTestUtils.setField(client, "physicallyConnected", true);
    }

    private ProcessingContext<Object> context(DynamicMapperRequest request) {
        return ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(Qos.AT_LEAST_ONCE)
                .serviceConfiguration(new ServiceConfiguration())
                .resolvedPublishTopic("queue/a")
                .requests(List.of(request))
                .build();
    }

    @Test
    void aBinaryPayloadIsSentAsABytesMessageWithItsContentType() throws Exception {
        byte[] bytes = { 0, 1, 2, (byte) 0xFF };
        ProcessingContext<Object> context = context(DynamicMapperRequest.builder().request("AAEC/w==")
                .binaryPayload(bytes).contentType("application/x-protobuf").build());

        client.publishMEAO(context);

        ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
        verify(bytesMessage).writeBytes(written.capture());
        assertArrayEquals(bytes, written.getValue());
        verify(bytesMessage).setStringProperty("JMS_AMQP_ContentType", "application/x-protobuf");
        verify(session, never()).createTextMessage(anyString());
        verify(producer).send(bytesMessage);
        assertTrue(context.getErrors().isEmpty(), "Unexpected errors: " + context.getErrors());
    }

    @Test
    void aTextPayloadIsSentAsATextMessageWithTheRequestContentType() throws Exception {
        ProcessingContext<Object> context = context(DynamicMapperRequest.builder().request("AT+GTUPD=gv300$")
                .contentType("text/plain").build());

        client.publishMEAO(context);

        verify(session).createTextMessage("AT+GTUPD=gv300$");
        verify(textMessage).setStringProperty("JMS_AMQP_ContentType", "text/plain");
        verify(producer).send(textMessage);
        assertTrue(context.getErrors().isEmpty(), "Unexpected errors: " + context.getErrors());
    }

    @Test
    void theConnectorContentTypeStillAppliesWhenTheRequestHasNone() throws Exception {
        ProcessingContext<Object> context = context(DynamicMapperRequest.builder().request("{}").build());

        client.publishMEAO(context);

        verify(textMessage).setStringProperty("JMS_AMQP_ContentType", "application/configured");
        verify(producer).send(textMessage);
    }
}
