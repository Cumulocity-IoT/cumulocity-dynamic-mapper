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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Properties;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link KafkaTestClient}.
 *
 * <p>The {@link KafkaProducer} is mocked so no real broker is required.
 * Tests verify that:
 * <ul>
 *   <li>{@code testSendMeasurement()} invokes {@code producer.send()} at least once.</li>
 *   <li>The published payload is valid JSON containing the expected fields.</li>
 *   <li>The record key is non-null and non-blank.</li>
 *   <li>The SASL SCRAM-SHA-512 properties are built correctly.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaTestClientTest {

    @Mock
    private KafkaProducer<String, String> kafkaProducer;

    private KafkaTestClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(kafkaProducer.send(any(ProducerRecord.class))).thenReturn(mock(Future.class));
        // Inject a synthetic topic since the TOPIC env var is not set in unit tests
        client = new KafkaTestClient(kafkaProducer, "test-topic");
    }

    // ── send() is called ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void testSendMeasurement_invokesSendAtLeastOnce() {
        client.testSendMeasurement();
        verify(kafkaProducer, atLeastOnce()).send(any(ProducerRecord.class));
    }

    // ── Payload shape ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void publishedPayload_isValidJsonWithExpectedFields() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        client.testSendMeasurement();
        verify(kafkaProducer).send(captor.capture());

        String value = captor.getValue().value();
        assertNotNull(value, "Payload must not be null");
        assertTrue(value.startsWith("{") && value.endsWith("}"),
                "Payload must be a JSON object, was: " + value);
        assertTrue(value.contains("\"deviceId\""),    "Payload must contain deviceId field");
        assertTrue(value.matches("(?s).*\"temperature\":\\s*-?\\d+\\.\\d.*"),
                "temperature must be a randomized decimal value, was: " + value);
    }

    // ── Record key ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void publishedRecord_keyIsNonBlank() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        client.testSendMeasurement();
        verify(kafkaProducer).send(captor.capture());

        String key = captor.getValue().key();
        assertNotNull(key,     "Record key must not be null");
        assertFalse(key.isBlank(), "Record key must not be blank");
    }

    // ── SASL properties ───────────────────────────────────────────────────

    @Test
    void saslProperties_scramSha512_configuredCorrectly() {
        Properties props = KafkaTestClient.buildProducerProps(
                "broker:9092", "SASL_SSL", "SCRAM-SHA-512", "testUser", "testPass");

        assertEquals("SASL_SSL",         props.getProperty("security.protocol"),
                "security.protocol must be SASL_SSL");
        assertEquals("SCRAM-SHA-512",    props.getProperty("sasl.mechanism"),
                "sasl.mechanism must match");
        assertEquals("broker:9092",      props.getProperty("bootstrap.servers"),
                "bootstrap.servers must be set");
        assertEquals(StringSerializer.class.getName(), props.getProperty("key.serializer"),
                "key.serializer must be StringSerializer");
        assertEquals(StringSerializer.class.getName(), props.getProperty("value.serializer"),
                "value.serializer must be StringSerializer");

        String jaasCfg = props.getProperty("sasl.jaas.config");
        assertNotNull(jaasCfg, "sasl.jaas.config must be set");
        assertTrue(jaasCfg.contains("ScramLoginModule"), "JAAS config must use ScramLoginModule");
        assertTrue(jaasCfg.contains("testUser"),         "JAAS config must contain username");
        assertTrue(jaasCfg.contains("testPass"),         "JAAS config must contain password");
    }

    @Test
    void saslProperties_plain_configuredCorrectly() {
        Properties props = KafkaTestClient.buildProducerProps(
                "broker:9093", "SASL_SSL", "PLAIN", "alice", "secret");

        assertEquals("PLAIN", props.getProperty("sasl.mechanism"));
    }

    @Test
    void multipleBootstrapServers_areAccepted() {
        Properties props = KafkaTestClient.buildProducerProps(
                "broker1:9092,broker2:9092", "SASL_SSL", "SCRAM-SHA-512", "u", "p");

        assertEquals("broker1:9092,broker2:9092", props.getProperty("bootstrap.servers"));
    }

    // ── Local (PLAINTEXT) test broker scenario ──────────────────────────────

    @Test
    void plaintextProperties_forLocalTestBroker_haveNoSaslConfig() {
        Properties props = KafkaTestClient.buildProducerProps(
                "localhost:9093", "PLAINTEXT", "SCRAM-SHA-512", null, null);

        assertEquals("PLAINTEXT",      props.getProperty("security.protocol"));
        assertEquals("localhost:9093", props.getProperty("bootstrap.servers"));
        assertNull(props.getProperty("sasl.mechanism"),
                "PLAINTEXT config must not set sasl.mechanism");
        assertNull(props.getProperty("sasl.jaas.config"),
                "PLAINTEXT config must not set sasl.jaas.config");
    }

    @Test
    void requiresAuth_isFalseOnlyForPlaintext() {
        assertFalse(KafkaTestClient.requiresAuth("PLAINTEXT"));
        assertFalse(KafkaTestClient.requiresAuth("plaintext"));
        assertTrue(KafkaTestClient.requiresAuth("SASL_SSL"));
        assertTrue(KafkaTestClient.requiresAuth("SASL_PLAINTEXT"));
    }

    @Test
    void publishToLocalPlaintextBroker_sendsExpectedRecord() {
        // Simulates the local docker-compose Kafka test broker (resources/test-env/kafka),
        // which runs PLAINTEXT with no SASL auth on localhost:9093.
        Properties props = KafkaTestClient.buildProducerProps(
                "localhost:9093", "PLAINTEXT", "SCRAM-SHA-512", null, null);
        assertEquals("PLAINTEXT", props.getProperty("security.protocol"));

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        client.testSendMeasurement();
        verify(kafkaProducer).send(captor.capture());

        assertEquals("test-topic", captor.getValue().topic());
        assertTrue(captor.getValue().value().contains("\"deviceId\""));
    }
}
