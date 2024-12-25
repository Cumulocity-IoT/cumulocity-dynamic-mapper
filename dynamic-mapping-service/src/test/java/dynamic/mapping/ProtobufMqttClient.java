/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
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

package dynamic.mapping;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;

import dynamic.mapping.processor.extension.internal.InternalCustomAlarmOuter;
import dynamic.mapping.processor.extension.internal.InternalCustomAlarmOuter.InternalCustomAlarm;
import dynamic.mapping.processor.processor.fixed.StaticCustomMeasurementOuter;
import dynamic.mapping.processor.processor.fixed.StaticCustomMeasurementOuter.StaticCustomMeasurement;

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

        if (broker_username == null || broker_username.isEmpty() ||
                broker_password == null || broker_password.isEmpty()) {
            Mqtt3BlockingClient sampleClient = Mqtt3Client.builder()
                    .serverHost(broker_host)
                    .serverPort(broker_port)
                    .identifier(client_id)
                    .sslWithDefaultConfig()
                    .buildBlocking();
            ProtobufMqttClient client = new ProtobufMqttClient(sampleClient);
            client.testSendMeasurement();
            client.testSendAlarm();
        } else {
            Mqtt3SimpleAuth simpleAuth = Mqtt3SimpleAuth.builder().username(broker_username)
                    .password(broker_password.getBytes()).build();
            Mqtt3BlockingClient sampleClient = Mqtt3Client.builder()
                    .serverHost(broker_host)
                    .serverPort(broker_port)
                    .identifier(client_id)
                    .simpleAuth(simpleAuth)
                    .sslWithDefaultConfig()
                    .buildBlocking();
            ProtobufMqttClient client = new ProtobufMqttClient(sampleClient);
            client.testSendMeasurement();
            client.testSendAlarm();
        }

    }

    private void testSendMeasurement() {

        String topic = "protobuf/measurement";
        System.out.println("Connecting to broker: ssl://" + broker_host + ":" + broker_port);
        testClient.connect();

        System.out.println("Publishing message on topic" + topic);

        StaticCustomMeasurementOuter.StaticCustomMeasurement proto = StaticCustomMeasurement.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setUnit("C")
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
        System.out.println("Connecting to broker: ssl://" + broker_host + ":" + broker_port);
        testClient.connect();

        System.out.println("Publishing message on topic" + topic);

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
