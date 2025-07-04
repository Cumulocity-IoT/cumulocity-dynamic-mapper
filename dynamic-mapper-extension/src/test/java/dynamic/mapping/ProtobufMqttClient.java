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

package dynamic.mapper;

import java.util.Date;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;

import dynamic.mapper.processor.extension.external.CustomEventOuter;
import dynamic.mapper.processor.extension.external.CustomEventOuter.CustomEvent;

public class ProtobufMqttClient {
    Mqtt3BlockingClient testClient;
    static String broker_host = System.getenv("broker_host");
    static Integer broker_port = Integer.valueOf(System.getenv("broker_port"));
    static String client_id = System.getenv("client_id");
    static String broker_username = System.getenv("broker_username");
    static String broker_password = System.getenv("broker_password");

    public ProtobufMqttClient(Mqtt3BlockingClient sampleClient) {
        testClient = sampleClient;
    }

    public static void main(String[] args) {
        Mqtt3BlockingClient sampleClient;
        if (broker_username == null || broker_username.isEmpty() ||
                broker_password == null || broker_password.isEmpty()) {
            sampleClient = Mqtt3Client.builder()
                    .serverHost(broker_host)
                    .serverPort(broker_port)
                    .identifier(client_id)
                    .sslWithDefaultConfig()
                    .buildBlocking();
        } else {
            Mqtt3SimpleAuth simpleAuth = Mqtt3SimpleAuth.builder().username(broker_username)
                    .password(broker_password.getBytes()).build();
            sampleClient = Mqtt3Client.builder()
                    .serverHost(broker_host)
                    .serverPort(broker_port)
                    .identifier(client_id)
                    .simpleAuth(simpleAuth)
                    .sslWithDefaultConfig()
                    .buildBlocking();
        }
        ProtobufMqttClient client = new ProtobufMqttClient(sampleClient);
        client.testSendEvent();
    }

    private void testSendEvent() {
        String topic = "protobuf/event";

        System.out.println("Connecting to server: ssl://" + broker_host + ":" + broker_port);

        // testClient.connect();
        Mqtt3ConnAck ack = testClient.connectWith()
                .cleanSession(true)
                .keepAlive(60)
                .send();
        if (!ack.getReturnCode().equals(Mqtt3ConnAckReturnCode.SUCCESS)) {
            // throw new ConnectorException("Tenant " + tenant + " - Error connecting to
            // broker:"
            // + mqttClient.getConfig().getServerHost() + ". Error code: "
            // + ack.getReturnCode().name());
            System.out.println("Error connecting to server:"
                    + broker_host + ". Error code: "
                    + ack.getReturnCode().name());
        }

        System.out.println("Publishing message on topic" + topic);

        CustomEventOuter.CustomEvent proto = CustomEvent.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setTxt("Stop at petrol station: " + (new Date().toString()))
                .setEventType("c8y_ProtobufEventType")
                .setTimestamp(System.currentTimeMillis())
                .build();

        Mqtt3AsyncClient sampleClientAsync = testClient.toAsync();
        sampleClientAsync.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(proto.toByteArray()).send();

        System.out.println("Message published");
        testClient.disconnect();
        System.out.println("Disconnected");
    }
}
