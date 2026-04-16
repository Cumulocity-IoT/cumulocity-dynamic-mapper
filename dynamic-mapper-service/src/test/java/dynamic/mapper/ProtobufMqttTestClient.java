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
import dynamic.mapper.processor.processor.fixed.InternalCustomMeasurementOuter.InternalCustomMeasurement;
import lombok.extern.slf4j.Slf4j;

/**
 * Standalone MQTT test client that publishes Protobuf-encoded measurement and
 * alarm messages using the project's internal proto schemas.
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   BROKER_HOST      (required)  e.g. "localhost"
 *   BROKER_PORT      (default 8883)
 *   CLIENT_ID        (default "protobuf-test-client")
 *   BROKER_USERNAME  (optional — enables simple auth + TLS when set)
 *   BROKER_PASSWORD  (optional)
 * </pre>
 */
@Slf4j
public class ProtobufMqttTestClient {

    static final String  brokerHost     = System.getenv("BROKER_HOST");
    static final int     brokerPort     = Integer.parseInt(System.getenv().getOrDefault("BROKER_PORT", "8883"));
    static final String  clientId       = System.getenv().getOrDefault("CLIENT_ID", "protobuf-test-client");
    static final String  brokerUsername = System.getenv("BROKER_USERNAME");
    static final String  brokerPassword = System.getenv("BROKER_PASSWORD");
    /** TLS is required.  Defaults to {@code true} when port == 8883, overridable via {@code BROKER_SSL=true|false}. */
    static final boolean brokerSsl      = Boolean.parseBoolean(
            System.getenv().getOrDefault("BROKER_SSL", brokerPort == 8883 ? "true" : "false"));

    final Mqtt3BlockingClient testClient;

    public ProtobufMqttTestClient(Mqtt3BlockingClient sampleClient) {
        testClient = sampleClient;
    }

    public static void main(String[] args) {
        log.info("=== Protobuf MQTT Test Client ===");
        log.info("Broker host : {}", brokerHost);
        log.info("Broker port : {}", brokerPort);
        log.info("TLS/SSL     : {}", brokerSsl);
        log.info("Client ID   : {}", clientId);
        log.info("Auth        : {}", (brokerUsername != null && !brokerUsername.isEmpty()) ? "username/password" : "none");
        log.info("=================================");

        var builder = Mqtt3Client.builder()
                .serverHost(brokerHost)
                .serverPort(brokerPort)
                .identifier(clientId);

        if (brokerUsername != null && !brokerUsername.isEmpty()
                && brokerPassword != null && !brokerPassword.isEmpty()) {
            builder = builder.simpleAuth(Mqtt3SimpleAuth.builder()
                    .username(brokerUsername)
                    .password(brokerPassword.getBytes())
                    .build());
        }

        if (brokerSsl) {
            builder = builder.sslWithDefaultConfig();
        }

        Mqtt3BlockingClient sampleClient = builder.buildBlocking();

        ProtobufMqttTestClient client = new ProtobufMqttTestClient(sampleClient);
        client.testSendMeasurement();
        client.testSendAlarm();
    }

    // ── Measurement ────────────────────────────────────────────────────────

    private void testSendMeasurement() {
        String topic = "protobuf/measurement";
        log.info("Connecting to ssl://{}:{}", brokerHost, brokerPort);
        testClient.connect();

        log.info("Publishing message on topic: {}", topic);

        InternalCustomMeasurement proto = InternalCustomMeasurement.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setUnit("C")
                .setTimestamp(System.currentTimeMillis())
                .setMeasurementType("c8y_GenericMeasurement")
                .setValue(99.7F)
                .build();

        Mqtt3AsyncClient asyncClient = testClient.toAsync();
        asyncClient.publishWith()
                .topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                .payload(proto.toByteArray())
                .send();

        log.info("Message published");
        testClient.disconnect();
        log.info("Disconnected");
    }

    // ── Alarm ──────────────────────────────────────────────────────────────

    private void testSendAlarm() {
        String topic = "protobuf/alarm";
        log.info("Connecting to ssl://{}:{}", brokerHost, brokerPort);
        testClient.connect();

        log.info("Publishing message on topic: {}", topic);

        InternalCustomAlarmOuter.InternalCustomAlarm proto = InternalCustomAlarm.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setTxt("Dummy Text")
                .setTimestamp(System.currentTimeMillis())
                .setAlarmType("c8y_ProtobufAlarmType")
                .build();

        Mqtt3AsyncClient asyncClient = testClient.toAsync();
        asyncClient.publishWith()
                .topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                .payload(proto.toByteArray())
                .send();

        log.info("Message published");
        testClient.disconnect();
        log.info("Disconnected");
    }
}
