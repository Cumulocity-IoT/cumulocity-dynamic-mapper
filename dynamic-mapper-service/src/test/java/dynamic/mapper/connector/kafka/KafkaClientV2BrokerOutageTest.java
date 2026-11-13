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

package dynamic.mapper.connector.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.ConnectionStateManager;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ConnectorNotConnectedException;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * An outbound message that cannot be published because the broker is down must be retried, not
 * acknowledged: the requests carry a {@link ConnectorNotConnectedException} (HTTP 503).
 */
class KafkaClientV2BrokerOutageTest {

    private KafkaClientV2 client;
    private ConnectionStateManager state;

    @BeforeEach
    void setUp() {
        client = new KafkaClientV2();
        state = mock(ConnectionStateManager.class);
        ReflectionTestUtils.setField(client, "tenant", "t1");
        ReflectionTestUtils.setField(client, "connectorName", "kafka");
        ReflectionTestUtils.setField(client, "connectionStateManager", state);
        ReflectionTestUtils.setField(client, "serviceConfiguration", new ServiceConfiguration());
    }

    private ProcessingContext<Object> context() {
        return ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .resolvedPublishTopic("topic")
                .requests(List.of(DynamicMapperRequest.builder().request("{}").build()))
                .build();
    }

    @Test
    void holdsOutboundWhileDisconnected() {
        assertTrue(client.holdsOutboundWhileDisconnected());
    }

    @Test
    void aDisconnectedConnectorFailsTheRequestsInsteadOfDroppingThem() {
        when(state.isConnected()).thenReturn(false);
        ReflectionTestUtils.setField(client, "kafkaProducer", mock(KafkaProducer.class));
        ProcessingContext<Object> context = context();

        client.publishMEAO(context);

        assertTrue(context.getRequests().get(0).getError() instanceof ConnectorNotConnectedException);
        assertEquals(503, ((ProcessingException) context.getErrors().get(0)).getHttpStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendTimeoutIsRetried() {
        when(state.isConnected()).thenReturn(true);
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> failed = new CompletableFuture<>();
        failed.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("expired"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);
        ReflectionTestUtils.setField(client, "kafkaProducer", producer);
        ProcessingContext<Object> context = context();

        client.publishMEAO(context);

        assertTrue(context.getRequests().get(0).getError() instanceof ConnectorNotConnectedException);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProblemOfTheMessageIsNotRetried() {
        when(state.isConnected()).thenReturn(true);
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        when(producer.send(any(ProducerRecord.class)))
                .thenThrow(new org.apache.kafka.common.errors.RecordTooLargeException("too large"));
        ReflectionTestUtils.setField(client, "kafkaProducer", producer);
        ProcessingContext<Object> context = context();

        client.publishMEAO(context);

        assertFalse(context.getRequests().get(0).getError() instanceof ConnectorNotConnectedException);
        assertEquals(0, ((ProcessingException) context.getErrors().get(0)).getHttpStatusCode());
    }
}
