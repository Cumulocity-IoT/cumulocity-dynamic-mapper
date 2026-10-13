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
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import lombok.extern.slf4j.Slf4j;

/**
 * Pulsar test client for the local environment in
 * {@code resources/testing/environments/pulsar}. It sends one inbound test message
 * (key = DEVICE_ID; matches the {@code pulsar-test-in} Smart Function mapping) and consumes the topics the
 * outbound mapping {@code pulsar-test-out} publishes to.
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   PULSAR_BROKER_HOST  (default "pulsar://localhost:6650"; see AbstractPulsarTestClient)
 *   AUTH_NAME           (default "none"; see AbstractPulsarTestClient)
 *   AUTH_PARAMS         (required when AUTH_NAME != "none")
 *   SUBSCRIPTION_NAME   (default "pulsar-test-subscription")
 *   TOPIC               (default "persistent://t2050305588/mqtt/pulsar-test-inbound")
 *   TOPIC_PATTERN       (default "persistent://t2050305588/mqtt/pulsar-test-outbound-.*")
 *   DEVICE_ID           (default "pulsar-dev-001", external ID in the inbound payload)
 *   TEMPERATURE         (default "21.5")
 *   SEND                (default "true"; "false" = consume only)
 *   CONSUME_SECONDS     (default "60"; 0 = do not consume, exit after sending)
 * </pre>
 */
@Slf4j
public class PulsarTestClient extends AbstractPulsarTestClient {

    static final String SUBSCRIPTION_NAME = System.getenv().getOrDefault("SUBSCRIPTION_NAME",
            "pulsar-test-subscription");
    static final String TOPIC = System.getenv().getOrDefault("TOPIC",
            "persistent://t2050305588/mqtt/pulsar-test-inbound");
    static final String TOPIC_PATTERN = System.getenv().getOrDefault("TOPIC_PATTERN",
            "persistent://t2050305588/mqtt/pulsar-test-outbound-.*");
    static final String DEVICE_ID = System.getenv().getOrDefault("DEVICE_ID", "pulsar-dev-001");
    static final String TEMPERATURE = System.getenv().getOrDefault("TEMPERATURE", "21.5");
    static final boolean SEND = Boolean.parseBoolean(System.getenv().getOrDefault("SEND", "true"));
    static final int CONSUME_SECONDS = Integer.parseInt(System.getenv().getOrDefault("CONSUME_SECONDS", "60"));

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
            if (CONSUME_SECONDS > 0) {
                testClient.startConsumer();
            }
            if (SEND) {
                testClient.sendInboundMessage();
            }
            if (CONSUME_SECONDS > 0) {
                log.info("Consumer running for {}s — press Ctrl+C to stop", CONSUME_SECONDS);
                Thread.sleep(CONSUME_SECONDS * 1000L);
            }
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

    private void sendInboundMessage() throws PulsarClientException {
        log.info("Publishing message on topic: {}", TOPIC);

        producer = client.newProducer()
                .topic(TOPIC)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create();

        String payload = String.format("{\"deviceId\": \"%s\", \"temperature\": %s}", DEVICE_ID, TEMPERATURE);

        producer.newMessage().key(DEVICE_ID).value(payload.getBytes()).send();
        log.info("Message published: key={} payload={}", DEVICE_ID, payload);

        producer.close();
        producer = null;
        log.info("Producer closed");
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
