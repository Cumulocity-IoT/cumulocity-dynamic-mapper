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

package dynamic.mapper.connector.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.hivemq.client.mqtt.exceptions.MqttClientStateException;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.API;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.ConnectorNotConnectedException;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.runtime.ProcessingContext;

/**
 * The loop every broker connector shares ({@code AConnectorClient.forEachPublishable}), exercised through the MQTT 5
 * client, and the classification of a lost connection as retryable.
 */
class PublishLoopTest {

    private MQTT5Client client;
    private Mqtt5BlockingClient mqttClient;

    @BeforeEach
    void setUp() {
        client = new MQTT5Client() {
            @Override
            protected boolean isReadyToPublish() {
                return true;
            }
        };
        mqttClient = mock(Mqtt5BlockingClient.class);
        ReflectionTestUtils.setField(client, "tenant", "t1");
        ReflectionTestUtils.setField(client, "connectorName", "mqtt5");
        ReflectionTestUtils.setField(client, "serviceConfiguration", new ServiceConfiguration());
        ReflectionTestUtils.setField(client, "mqttClient", mqttClient);
    }

    private ProcessingContext<Object> publish(String resolvedTopic, DynamicMapperRequest... requests) {
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(Qos.AT_LEAST_ONCE)
                .serviceConfiguration(new ServiceConfiguration())
                .resolvedPublishTopic(resolvedTopic)
                .requests(List.of(requests))
                .build();
        client.publishMEAO(context);
        return context;
    }

    private DynamicMapperRequest text(String payload) {
        return DynamicMapperRequest.builder().request(payload).build();
    }

    @Test
    void aRequestWithoutAPayloadIsSkippedAndTheOthersArePublished() {
        DynamicMapperRequest empty = DynamicMapperRequest.builder().build();

        ProcessingContext<Object> context = publish("device/out", empty, text("{\"a\":1}"));

        verify(mqttClient, times(1)).publish(any(Mqtt5Publish.class));
        assertTrue(context.getErrors().isEmpty());
        assertNull(empty.getError(), "skipped, not failed");
    }

    @Test
    void aRequestWithoutATopicGetsAnErrorAndTheOthersArePublished() {
        DynamicMapperRequest noTopic = text("{\"a\":1}");
        DynamicMapperRequest withTopic = DynamicMapperRequest.builder().request("{\"b\":2}").publishTopic("own/topic")
                .build();

        // no topic on the context either: only the request that carries its own can be published
        ProcessingContext<Object> context = publish(null, noTopic, withTopic);

        assertEquals("No publish topic specified", noTopic.getError().getMessage());
        assertNull(withTopic.getError());
        verify(mqttClient, times(1)).publish(any(Mqtt5Publish.class));
        assertTrue(context.getErrors().isEmpty(), "the missing topic is recorded on the request, as before");
    }

    @Test
    void aRequestsOwnTopicWinsOverTheContextsTopic() {
        DynamicMapperRequest request = DynamicMapperRequest.builder().request("{}").publishTopic("own/topic").build();

        publish("context/topic", request);

        org.mockito.ArgumentCaptor<Mqtt5Publish> captured = org.mockito.ArgumentCaptor.forClass(Mqtt5Publish.class);
        verify(mqttClient).publish(captured.capture());
        assertEquals("own/topic", captured.getValue().getTopic().toString());
    }

    @Test
    void anApiThatTheConnectorDoesNotPublishIsSkipped() {
        // API.CUSTOM requests are routed to Cumulocity, a broker connector has no topic for them
        ProcessingContext<Object> context = publish("device/out",
                DynamicMapperRequest.builder().request("{}").api(API.CUSTOM).build());

        verify(mqttClient, never()).publish(any(Mqtt5Publish.class));
        assertTrue(context.getErrors().isEmpty());
    }

    @Test
    void aFailureOfOneRequestDoesNotStopTheNext() {
        DynamicMapperRequest first = text("{\"a\":1}");
        DynamicMapperRequest second = text("{\"b\":2}");
        org.mockito.Mockito.when(mqttClient.publish(any(Mqtt5Publish.class)))
                .thenThrow(new IllegalArgumentException("bad message"))
                .thenReturn(null);

        ProcessingContext<Object> context = publish("device/out", first, second);

        assertNotNull(first.getError());
        assertNull(second.getError());
        assertEquals(1, context.getErrors().size());
        verify(mqttClient, times(2)).publish(any(Mqtt5Publish.class));
    }

    @Test
    void aProblemOfTheMessageIsAMessageErrorThatIsNotRetried() {
        doThrow(new IllegalArgumentException("bad message")).when(mqttClient).publish(any(Mqtt5Publish.class));
        DynamicMapperRequest request = text("{}");

        ProcessingContext<Object> context = publish("device/out", request);

        assertFalse(request.getError() instanceof ConnectorNotConnectedException);
        assertEquals(0, ((ProcessingException) context.getErrors().get(0)).getHttpStatusCode());
    }

    @Test
    void aConnectionThatDroppedBetweenTheReadinessCheckAndThePublishIsRetried() {
        // the client says it is not connected: not a problem of the message. It used to be acknowledged and lost.
        doThrow(mock(MqttClientStateException.class)).when(mqttClient).publish(any(Mqtt5Publish.class));
        DynamicMapperRequest request = text("{}");

        ProcessingContext<Object> context = publish("device/out", request);

        assertTrue(request.getError() instanceof ConnectorNotConnectedException);
        assertEquals(503, ((ProcessingException) context.getErrors().get(0)).getHttpStatusCode());
    }

    @Test
    void noRequestsMeansNothingToPublish() {
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .requests(List.of())
                .build();

        client.publishMEAO(context);

        verify(mqttClient, never()).publish(any(Mqtt5Publish.class));
        assertTrue(context.getErrors().isEmpty());
    }
}
