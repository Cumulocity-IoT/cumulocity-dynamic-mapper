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

import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import lombok.extern.slf4j.Slf4j;

/**
 * Pulsar test client for the Cumulocity MQTT Service (N-2 mapping model).
 *
 * <p>In the N-2 model the original MQTT topic is carried as a Pulsar message
 * property ({@value #PULSAR_PROPERTY_CHANNEL}) rather than being the Pulsar
 * topic name.  All inbound device traffic arrives on a single
 * {@code from-device} topic; all outbound traffic is sent to a single
 * {@code to-device} topic.</p>
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   PULSAR_BROKER_HOST  (default "pulsar://localhost:6650"; see AbstractPulsarTestClient)
 *   AUTH_NAME / AUTH_PARAMS  (see AbstractPulsarTestClient)
 *   SUBSCRIPTION_NAME   (default "mqtt-service-test-subscription")
 *   TENANT              (default "t2050305588")
 *   PULSAR_NAMESPACE    (default "mqtt")
 * </pre>
 */
@Slf4j
public class MQTTServicePulsarTestClient extends AbstractPulsarTestClient {

    // ── MQTT Service environment ────────────────────────────────────────────
    static final String SUBSCRIPTION_NAME  = System.getenv().getOrDefault("SUBSCRIPTION_NAME",
            "mqtt-service-test-subscription");
    static final String TENANT             = System.getenv().getOrDefault("TENANT",           "t2050305588");
    static final String PULSAR_NAMESPACE   = System.getenv().getOrDefault("PULSAR_NAMESPACE", "mqtt");

    // N-2 topics — built from TENANT and PULSAR_NAMESPACE
    static final String TOWARDS_DEVICE_TOPIC   =
            String.format("persistent://%s/%s/to-device",   TENANT, PULSAR_NAMESPACE);
    static final String TOWARDS_PLATFORM_TOPIC =
            String.format("persistent://%s/%s/from-device", TENANT, PULSAR_NAMESPACE);

    // Message property keys
    public static final String PULSAR_PROPERTY_CHANNEL = "channel";
    public static final String PULSAR_PROPERTY_CLIENT  = "client";

    // ── Entry point ────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        log.info("=== Cumulocity MQTT Service Pulsar Test Client ===");
        log.info("Broker host            : {}", PULSAR_BROKER_HOST);
        log.info("Tenant                 : {}", TENANT);
        log.info("Namespace              : {}", PULSAR_NAMESPACE);
        log.info("Towards-device topic   : {}", TOWARDS_DEVICE_TOPIC);
        log.info("Towards-platform topic : {}", TOWARDS_PLATFORM_TOPIC);
        log.info("Subscription           : {}", SUBSCRIPTION_NAME);
        log.info("==================================================");

        MQTTServicePulsarTestClient testClient = new MQTTServicePulsarTestClient();
        try {
            testClient.initialize();

            // Uncomment the desired consumer direction:
            // testClient.startPlatformConsumer();
            testClient.startDeviceConsumer();

            // Uncomment to also publish test messages:
            // testClient.testSendMQTTServiceMessages();

            log.info("Consumer running — press Ctrl+C to stop");
            Thread.sleep(600_000); // 10 minutes
        } catch (Exception e) {
            log.error("Fatal error in MQTT Service Pulsar test client", e);
        } finally {
            testClient.cleanup();
        }
    }

    // ── Consumers ─────────────────────────────────────────────────────────

    /** Subscribe to the {@code from-device} topic (inbound MQTT traffic). */
    @SuppressWarnings("unused")
    private void startPlatformConsumer() throws PulsarClientException {
        log.info("Starting consumer for platform topic: {}", TOWARDS_PLATFORM_TOPIC);

        consumer = client.newConsumer()
                .topic(TOWARDS_PLATFORM_TOPIC)
                .subscriptionName(SUBSCRIPTION_NAME)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(new MQTTServiceMessageListener())
                .subscribe();

        log.info("Consumer started for platform topic");
    }

    /** Subscribe to the {@code to-device} topic (outbound MQTT traffic). */
    private void startDeviceConsumer() throws PulsarClientException {
        log.info("Starting consumer for device topic: {}", TOWARDS_DEVICE_TOPIC);

        consumer = client.newConsumer()
                .topic(TOWARDS_DEVICE_TOPIC)
                .subscriptionName(SUBSCRIPTION_NAME)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(new MQTTServiceMessageListener())
                .subscribe();

        log.info("Consumer started for device topic");
    }

    // ── Producer ───────────────────────────────────────────────────────────

    /**
     * Publishes a small set of test messages to the towards-device topic,
     * each carrying a different simulated MQTT topic as a message property.
     */
    @SuppressWarnings("unused")
    private void testSendMQTTServiceMessages() throws PulsarClientException {
        log.info("Publishing test messages to device topic: {}", TOWARDS_DEVICE_TOPIC);

        producer = client.newProducer()
                .topic(TOWARDS_DEVICE_TOPIC)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create();

        sendMQTTServiceMessage("measurement/kobu-webhook-001", "temperature", 25.5);
        sendMQTTServiceMessage("measurement/kobu-webhook-002", "humidity",    65.0);
        sendMQTTServiceMessage("device/sensor-123/status",    "status",       "online");
        sendMQTTServiceMessage("alarm/critical/device-456",   "alert",        "battery_low");

        producer.close();
        producer = null;
        log.info("Producer closed");
    }

    private void sendMQTTServiceMessage(String mqttTopic, String measurementType, Object value)
            throws PulsarClientException {

        String payload = String.format(
                "{ \"deviceId\": \"test-device-%d\", \"timestamp\": \"%d\", \"%s\": %s }",
                System.currentTimeMillis() % 1000,
                System.currentTimeMillis(),
                measurementType,
                value instanceof String ? "\"" + value + "\"" : value);

        producer.newMessage()
                .value(payload.getBytes())
                .property(PULSAR_PROPERTY_CHANNEL, mqttTopic)
                .property(PULSAR_PROPERTY_CLIENT,  "test-client-" + System.currentTimeMillis() % 100)
                .property("tenant",                TENANT)
                .property("messageType",           measurementType)
                .send();

        log.info("Sent — MQTT topic (property): {} | Pulsar topic: {} | payload: {}",
                mqttTopic, TOWARDS_DEVICE_TOPIC, payload);
    }

    // ── Message listener ───────────────────────────────────────────────────

    /**
     * Logs every received message, including all Pulsar message properties
     * (which carry the original MQTT topic in the N-2 mapping model).
     */
    private static class MQTTServiceMessageListener implements MessageListener<byte[]> {
        @Override
        public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
            try {
                String payload     = new String(message.getData());
                String pulsarTopic = message.getTopicName();
                String mqttTopic   = message.getProperty(PULSAR_PROPERTY_CHANNEL);
                String clientId    = message.getProperty(PULSAR_PROPERTY_CLIENT);
                String messageId   = message.getMessageId().toString();
                long   publishTime = message.getPublishTime();

                log.info("=== MQTT Service message received ===");
                log.info("Pulsar topic           : {}", pulsarTopic);
                log.info("MQTT topic (property)  : {}", mqttTopic  != null ? mqttTopic  : "N/A");
                log.info("Client ID (property)   : {}", clientId   != null ? clientId   : "N/A");
                log.info("Message ID             : {}", messageId);
                log.info("Publish time           : {}", publishTime);
                message.getProperties().forEach((k, v) -> log.info("  property {} = {}", k, v));
                log.info("Payload                : {}", payload);
                log.info("=====================================");

                consumer.acknowledge(message);
            } catch (Exception e) {
                log.error("Error processing MQTT Service message", e);
                consumer.negativeAcknowledge(message);
            }
        }
    }
}
