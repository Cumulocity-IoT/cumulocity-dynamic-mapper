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

package dynamic.mapper.client;

import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

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
 *   KAFKA_BROKER_HOST  (required)  e.g. "my-broker:9092" or "localhost:9093" for the local test broker
 *   SECURITY_PROTOCOL  (default "SASL_SSL")  set to "PLAINTEXT" for the local test broker (no auth)
 *   BROKER_USERNAME    (required unless SECURITY_PROTOCOL=PLAINTEXT)  SASL username
 *   BROKER_PASSWORD    (required unless SECURITY_PROTOCOL=PLAINTEXT)  SASL password
 *   TOPIC              (required)  target Kafka topic
 *   SASL_MECHANISM     (default "SCRAM-SHA-512")
 * </pre>
 */
@Slf4j
public class KafkaTestClient {

    static final String brokerHost       = System.getenv("KAFKA_BROKER_HOST");
    static final String brokerUsername   = System.getenv("BROKER_USERNAME");
    static final String brokerPassword   = System.getenv("BROKER_PASSWORD");
    static final String topic            = System.getenv("TOPIC");
    static final String securityProtocol = System.getenv().getOrDefault("SECURITY_PROTOCOL", "SASL_SSL");
    static final String saslMechanism    = System.getenv().getOrDefault("SASL_MECHANISM", "SCRAM-SHA-512");

    final KafkaProducer<String, String> testClient;
    final String testTopic;

    public KafkaTestClient(KafkaProducer<String, String> sampleClient) {
        this(sampleClient, topic);
    }

    /** Constructor used in unit tests to inject a topic without relying on the environment. */
    public KafkaTestClient(KafkaProducer<String, String> sampleClient, String topicOverride) {
        testClient = sampleClient;
        testTopic  = topicOverride;
    }

    public static void main(String[] args) {
        boolean requiresAuth = requiresAuth(securityProtocol);

        log.info("=== Kafka Test Client ===");
        log.info("Broker host       : {}", brokerHost);
        log.info("Topic             : {}", topic);
        log.info("Security protocol : {}", securityProtocol);
        if (requiresAuth) {
            log.info("SASL mechanism    : {}", saslMechanism);
        }
        log.info("========================");

        if (brokerHost == null || topic == null || (requiresAuth && (brokerUsername == null || brokerPassword == null))) {
            log.error("KAFKA_BROKER_HOST and TOPIC must be set; BROKER_USERNAME and BROKER_PASSWORD"
                    + " are required unless SECURITY_PROTOCOL=PLAINTEXT");
            return;
        }

        Properties props = buildProducerProps(brokerHost, securityProtocol, saslMechanism, brokerUsername, brokerPassword);

        KafkaTestClient client = new KafkaTestClient(new KafkaProducer<>(props));
        client.testSendMeasurement();
    }

    static boolean requiresAuth(String securityProtocol) {
        return !"PLAINTEXT".equalsIgnoreCase(securityProtocol);
    }

    /** Builds the Kafka producer {@link Properties}; package-visible so tests can verify it directly. */
    static Properties buildProducerProps(String brokerHost, String securityProtocol, String saslMechanism,
            String brokerUsername, String brokerPassword) {
        String serializer = StringSerializer.class.getName();

        Properties props = new Properties();
        props.put("key.serializer",    serializer);
        props.put("value.serializer",  serializer);
        props.put("security.protocol", securityProtocol);
        props.put("bootstrap.servers", brokerHost);

        if (requiresAuth(securityProtocol)) {
            String jaasTemplate =
                    "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
            props.put("sasl.mechanism",   saslMechanism);
            props.put("sasl.jaas.config", String.format(jaasTemplate, brokerUsername, brokerPassword));
        }
        return props;
    }

    static final String serialNumber = "863859042393327";

    public void testSendMeasurement() {
        log.info("Connecting to Kafka broker: {}", brokerHost);
        log.info("Publishing message on topic: {}", testTopic);

        double temperature = ThreadLocalRandom.current().nextDouble(15.0, 35.0);
        String payload = String.format(
                "{ \"deviceId\": \"%s\", \"version\": \"1\", \"deviceType\": \"20\","
                + " \"deviceTimestamp\": \"%d\", \"deviceStatus\": \"BTR\", \"temperature\": %.1f }",
                serialNumber, System.currentTimeMillis(), temperature);

        testClient.send(new ProducerRecord<>(testTopic, serialNumber, payload));
        testClient.close();

        log.info("Message published");
        log.info("Disconnected");
    }
}
