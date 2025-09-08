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

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;

import dynamic.mapper.processor.extension.internal.InternalCustomAlarmOuter;
import dynamic.mapper.processor.extension.internal.InternalCustomAlarmOuter.InternalCustomAlarm;
import dynamic.mapper.processor.processor.fixed.InternalCustomMeasurementOuter;
import dynamic.mapper.processor.processor.fixed.InternalCustomMeasurementOuter.InternalCustomMeasurement;

public class ProtobufMqttClient {
    Mqtt3BlockingClient testClient;
    static String brokerHost = System.getenv("BROKER_HOST");
    static Integer brokerPort = Integer.valueOf(System.getenv("BROKER_PORT"));
    static String clientId = System.getenv("CLIENT_ID");
    static String brokerUsername = System.getenv("BROKER_USERNAME");
    static String brokerPassword = System.getenv("BROKER_PASSWORD");

    public ProtobufMqttClient(Mqtt3BlockingClient sampleClient) {
        testClient = sampleClient;
    }

    public static void main(String[] args) {
        Mqtt3BlockingClient sampleClient;
        if (brokerUsername == null || brokerUsername.isEmpty() ||
                brokerPassword == null || brokerPassword.isEmpty()) {
            sampleClient = Mqtt3Client.builder()
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)
                    .identifier(clientId)
                    .sslWithDefaultConfig()
                    .buildBlocking();
        } else {
            Mqtt3SimpleAuth simpleAuth = Mqtt3SimpleAuth.builder().username(brokerUsername)
                    .password(brokerPassword.getBytes()).build();
            sampleClient = Mqtt3Client.builder()
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)
                    .identifier(clientId)
                    .simpleAuth(simpleAuth)
                    .sslWithDefaultConfig()
                    .buildBlocking();
        }
        ProtobufMqttClient client = new ProtobufMqttClient(sampleClient);
        client.testSendMeasurement();
        client.testSendAlarm();

    }

    private void testSendMeasurement() {

        String topic = "protobuf/measurement";
        System.out.println("Connecting to server: ssl://" + brokerHost + ":" + brokerPort);
        testClient.connect();

        System.out.println("Publishing message on topic:" + topic);

        InternalCustomMeasurementOuter.InternalCustomMeasurement proto = InternalCustomMeasurement.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setUnit("C")
                .setTimestamp(System.currentTimeMillis())
                .setMeasurementType("c8y_GenericMeasurement")
                .setValue(99.7F)
                .build();

        Mqtt3AsyncClient sampleClientAsync = testClient.toAsync();
        sampleClientAsync.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(proto.toByteArray()).send();

        System.out.println("Message published");
        testClient.disconnect();
        System.out.println("Disconnected");

    }

    private void testSendAlarm() {

        String topic = "protobuf/alarm";
        System.out.println("Connecting to server: ssl://" + brokerHost + ":" + brokerPort);
        testClient.connect();

        System.out.println("Publishing message on topic:" + topic);

        InternalCustomAlarmOuter.InternalCustomAlarm proto = InternalCustomAlarm.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setTxt("Dummy Text")
                .setTimestamp(System.currentTimeMillis())
                .setAlarmType("c8y_ProtobufAlarmType")
                .build();
        Mqtt3AsyncClient sampleClientAsync = testClient.toAsync();
        sampleClientAsync.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(proto.toByteArray()).send();

        System.out.println("Message published");
        testClient.disconnect();
        System.out.println("Disconnected");

    }

}
