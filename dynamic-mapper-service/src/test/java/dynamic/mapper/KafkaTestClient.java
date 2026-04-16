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

import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import lombok.extern.slf4j.Slf4j;

/**
 * Standalone Kafka test client that publishes a measurement payload to a
 * configurable topic using SASL/SSL authentication.
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   KAFKA_BROKER_HOST  (required)  e.g. "my-broker:9092"
 *   BROKER_USERNAME    (required)  SASL username
 *   BROKER_PASSWORD    (required)  SASL password
 *   GROUP_ID           (optional)  consumer group id
 *   TOPIC              (required)  target Kafka topic
 *   SASL_MECHANISM     (default "SCRAM-SHA-512")
 * </pre>
 */
@Slf4j
public class KafkaTestClient {

    static final String brokerHost     = System.getenv("KAFKA_BROKER_HOST");
    static final String brokerUsername = System.getenv("BROKER_USERNAME");
    static final String brokerPassword = System.getenv("BROKER_PASSWORD");
    static final String groupId        = System.getenv("GROUP_ID");
    static final String topic          = System.getenv("TOPIC");
    static final String saslMechanism  = System.getenv().getOrDefault("SASL_MECHANISM", "SCRAM-SHA-512");

    final KafkaProducer<String, String> testClient;

    public KafkaTestClient(KafkaProducer<String, String> sampleClient) {
        testClient = sampleClient;
    }

    public static void main(String[] args) {
        log.info("=== Kafka Test Client ===");
        log.info("Broker host    : {}", brokerHost);
        log.info("Topic          : {}", topic);
        log.info("SASL mechanism : {}", saslMechanism);
        log.info("========================");

        if (brokerHost == null || brokerUsername == null || brokerPassword == null || topic == null) {
            log.error("KAFKA_BROKER_HOST, BROKER_USERNAME, BROKER_PASSWORD and TOPIC must all be set");
            return;
        }

        String jaasTemplate =
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
        String jaasCfg     = String.format(jaasTemplate, brokerUsername, brokerPassword);
        String serializer  = StringSerializer.class.getName();

        Properties props = new Properties();
        props.put("key.serializer",    serializer);
        props.put("value.serializer",  serializer);
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism",    saslMechanism);
        props.put("bootstrap.servers", brokerHost);
        props.put("group.id",          groupId);
        props.put("sasl.jaas.config",  jaasCfg);

        KafkaTestClient client = new KafkaTestClient(new KafkaProducer<>(props));
        client.testSendMeasurement();
    }

    private void testSendMeasurement() {
        log.info("Connecting to Kafka broker: {}", brokerHost);
        log.info("Publishing message on topic: {}", topic);

        String payload = String.format(
                "{ \"deviceId\": \"863859042393327\", \"version\": \"1\", \"deviceType\": \"20\","
                + " \"deviceTimestamp\": \"%d\", \"deviceStatus\": \"BTR\", \"temperature\": 90 }",
                System.currentTimeMillis());
        String key = "863859042393327";

        testClient.send(new ProducerRecord<>(topic, key, payload));
        testClient.close();

        log.info("Message published");
        log.info("Disconnected");
    }
}
