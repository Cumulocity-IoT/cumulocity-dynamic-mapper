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

package dynamic.mapper.connector.mqtt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** What the MQTT 5 connector publishes: the payload bytes and, when the request has one, the content type. */
class MQTT5ClientPublishTest {

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

    private Mqtt5Publish publish(DynamicMapperRequest request) {
        ProcessingContext<Object> context = ProcessingContext.builder()
                .mapping(Mapping.builder().name("outbound").debug(false).build())
                .qos(Qos.AT_LEAST_ONCE)
                .serviceConfiguration(new ServiceConfiguration())
                .resolvedPublishTopic("device/out")
                .requests(List.of(request))
                .build();

        client.publishMEAO(context);

        assertTrue(context.getErrors().isEmpty(), "Unexpected errors: " + context.getErrors());
        ArgumentCaptor<Mqtt5Publish> captured = ArgumentCaptor.forClass(Mqtt5Publish.class);
        verify(mqttClient).publish(captured.capture());
        return captured.getValue();
    }

    @Test
    void theContentTypeOfTheRequestIsCarriedAsTheMqtt5ContentType() {
        byte[] bytes = { 0, 1, 2, (byte) 0xFF };

        Mqtt5Publish message = publish(DynamicMapperRequest.builder().request("AAEC/w==").binaryPayload(bytes)
                .contentType("application/x-protobuf").build());

        assertArrayEquals(bytes, message.getPayloadAsBytes());
        assertEquals("application/x-protobuf", message.getContentType().map(Object::toString).orElse(null));
    }

    @Test
    void noContentTypeIsSetWhenTheRequestHasNone() {
        Mqtt5Publish message = publish(DynamicMapperRequest.builder().request("{\"a\":1}").build());

        assertEquals("{\"a\":1}",
                new String(message.getPayloadAsBytes(), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(message.getContentType().isPresent());
    }
}
