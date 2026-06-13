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

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import lombok.extern.slf4j.Slf4j;

/**
 * Pulsar test client that consumes messages matching a topic pattern and
 * optionally publishes test measurement payloads.
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   PULSAR_BROKER_HOST  (default "pulsar://localhost:6650"; see AbstractPulsarTestClient)
 *   AUTH_NAME           (default "none"; see AbstractPulsarTestClient)
 *   AUTH_PARAMS         (required when AUTH_NAME != "none")
 *   SUBSCRIPTION_NAME   (default "pulsar-test-subscription")
 *   TOPIC               (default "persistent://public/default/measurement-kobu-webhook-001")
 *   TOPIC_PATTERN       (default "persistent://public/default/measurement-kobu-webhook-[0-9]{3}")
 * </pre>
 */
@Slf4j
public class PulsarTestClient extends AbstractPulsarTestClient {

    static final String SUBSCRIPTION_NAME = System.getenv().getOrDefault("SUBSCRIPTION_NAME",
            "pulsar-test-subscription");
    static final String TOPIC = System.getenv().getOrDefault("TOPIC",
            "persistent://public/default/measurement-kobu-webhook-001");
    static final String TOPIC_PATTERN = System.getenv().getOrDefault("TOPIC_PATTERN",
            "persistent://public/default/measurement-kobu-webhook-[0-9]{3}");

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        log.info("=== Pulsar Test Client ===");
        log.info("Broker host   : {}", PULSAR_BROKER_HOST);
        log.info("Topic         : {}", TOPIC);
        log.info("Topic pattern : {}", TOPIC_PATTERN);
        log.info("Subscription  : {}", SUBSCRIPTION_NAME);
        log.info("=========================");

        PulsarTestClient testClient = new PulsarTestClient();
        try {
            testClient.initialize();
            testClient.startConsumer();
            // testClient.testSendMeasurement();

            log.info("Consumer running — press Ctrl+C to stop");
            Thread.sleep(60_000);
        } catch (Exception e) {
            log.error("Fatal error in Pulsar test client", e);
        } finally {
            testClient.cleanup();
        }
    }

    // ── Consumer ───────────────────────────────────────────────────────────

    private void startConsumer() throws PulsarClientException {
        log.info("Starting consumer for topic pattern: {}", TOPIC_PATTERN);

        consumer = client.newConsumer()
                .topicsPattern(Pattern.compile(TOPIC_PATTERN))
                .subscriptionName(SUBSCRIPTION_NAME)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(new PulsarMessageListener())
                .subscribe();

        log.info("Consumer started");
    }

    // ── Producer ───────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private void testSendMeasurement() throws PulsarClientException {
        log.info("Publishing message on topic: {}", TOPIC);

        producer = client.newProducer()
                .topic(TOPIC)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create();

        String payload = String.format(
                "{ \"deviceId\": \"863859042393327\", \"version\": \"1\", \"deviceType\": \"20\","
                + " \"deviceTimestamp\": \"%d\", \"deviceStatus\": \"BTR\", \"temperature\": 90 }",
                System.currentTimeMillis());

        producer.send(payload.getBytes());
        log.info("Message published: {}", payload);

        sendAdditionalTestMessages();

        producer.close();
        producer = null;
        log.info("Producer closed");
    }

    private void sendAdditionalTestMessages() throws PulsarClientException {
        log.info("Sending additional test messages...");

        String[] deviceIds = {"001", "002", "003", "123", "999"};
        for (String deviceId : deviceIds) {
            String testTopic = "persistent://public/default/measurement-kobu-webhook-" + deviceId;

            try (Producer<byte[]> testProducer = client.newProducer()
                    .topic(testTopic)
                    .sendTimeout(10, TimeUnit.SECONDS)
                    .create()) {

                String payload = String.format(
                        "{ \"deviceId\": \"86385904239332%s\", \"version\": \"1\", \"deviceType\": \"20\","
                        + " \"deviceTimestamp\": \"%d\", \"deviceStatus\": \"BTR\", \"temperature\": %d }",
                        deviceId, System.currentTimeMillis(), 20 + Integer.parseInt(deviceId));

                testProducer.send(payload.getBytes());
                log.info("Sent message to topic: {}", testTopic);

                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ── Message listener ───────────────────────────────────────────────────

    private static class PulsarMessageListener implements MessageListener<byte[]> {
        @Override
        public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
            try {
                String payload   = new String(message.getData());
                String topic     = message.getTopicName();
                String messageId = message.getMessageId().toString();
                long publishTime = message.getPublishTime();

                log.info("[RECEIVED] topic={} | messageId={} | publishTime={}", topic, messageId, publishTime);
                log.info("[PAYLOAD]  {}", payload);

                consumer.acknowledge(message);
            } catch (Exception e) {
                log.error("Error processing message", e);
                consumer.negativeAcknowledge(message);
            }
        }
    }
}
