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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTTServicePulsarClientTest {
    private PulsarClient client;
    private Producer<byte[]> producer;
    private Consumer<byte[]> consumer;
    private ExecutorService executorService;

    // Environment variables for Cumulocity MQTT Service
    static String PULSAR_BROKER_HOST = System.getenv().getOrDefault("PULSAR_BROKER_HOST", "pulsar://localhost:6650");
    static String BROKER_USERNAME = System.getenv("BROKER_USERNAME");
    static String BROKER_PASSWORD = System.getenv("BROKER_PASSWORD");
    static String AUTH_NAME = System.getenv().getOrDefault("AUTH_NAME", "none");
    static String AUTH_PARAMS = System.getenv("AUTH_PARAMS");
    static String SUBSCRIPTION_NAME = System.getenv().getOrDefault("SUBSCRIPTION_NAME",
            "mqtt-service-test-subscription");
    static String TENANT = System.getenv().getOrDefault("TENANT", "t2050305588");
    static String PULSAR_NAMESPACE = System.getenv().getOrDefault("PULSAR_NAMESPACE", "mqtt");

    // Cumulocity MQTT Service specific topics (N-2 mapping model)
    static String TOWARDS_DEVICE_TOPIC;
    static String TOWARDS_PLATFORM_TOPIC;

    // Message properties constants (from MQTTServicePulsarClient)
    public static final String PULSAR_PROPERTY_CHANNEL = "channel";
    public static final String PULSAR_PROPERTY_CLIENT = "client";

    public MQTTServicePulsarClientTest() {
        this.executorService = Executors.newCachedThreadPool();
        // Build topics using N-2 model: persistent://tenant/namespace/topic-name
        TOWARDS_DEVICE_TOPIC = String.format("persistent://%s/%s/to-device", TENANT, PULSAR_NAMESPACE);
        TOWARDS_PLATFORM_TOPIC = String.format("persistent://%s/%s/from-device", TENANT, PULSAR_NAMESPACE);
    }

    public static void main(String[] args) throws Exception {
        log.info("Starting Cumulocity MQTT Service Pulsar Test Client...");
        log.info("Broker Host: " + PULSAR_BROKER_HOST);
        log.info("Tenant: " + TENANT);
        log.info("Namespace: " + PULSAR_NAMESPACE);
        log.info("Towards Device Topic: " + TOWARDS_DEVICE_TOPIC);
        log.info("Towards Platform Topic: " + TOWARDS_PLATFORM_TOPIC);
        log.info("Subscription: " + SUBSCRIPTION_NAME);

        MQTTServicePulsarClientTest testClient = new MQTTServicePulsarClientTest();

        try {
            // Initialize client
            testClient.initialize();

            // Start consumer for platform topic (inbound messages)
            // testClient.startPlatformConsumer();
            testClient.startDeviceConsumer();

            // Send test messages to device topic (outbound messages)
            // testClient.testSendMQTTServiceMessages();

            // Keep consumer running for a while
            log.info("Consumer running... Press Ctrl+C to stop");
            Thread.sleep(600000); // Run for 60 minutes

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            testClient.cleanup();
        }
    }

    private void initialize() throws PulsarClientException {
        log.info("Initializing Pulsar client for Cumulocity MQTT Service...");

        var clientBuilder = org.apache.pulsar.client.api.PulsarClient.builder()
                .serviceUrl(PULSAR_BROKER_HOST)
                .connectionTimeout(30, TimeUnit.SECONDS)
                .operationTimeout(30, TimeUnit.SECONDS);

        // Configure authentication if provided
        if (!"none".equals(AUTH_NAME) && AUTH_PARAMS != null && !AUTH_PARAMS.isEmpty()) {
            configureAuthentication(clientBuilder);
        }

        client = clientBuilder.build();
        log.info("Pulsar client initialized successfully!");
    }

    private void configureAuthentication(org.apache.pulsar.client.api.ClientBuilder clientBuilder) {
        log.info("Configuring authentication method: " + AUTH_NAME);

        try {
            switch (AUTH_NAME.toLowerCase()) {
                case "token":
                    clientBuilder.authentication(AuthenticationFactory.token(AUTH_PARAMS));
                    break;
                case "oauth2":
                    clientBuilder.authentication(
                            AuthenticationFactory.create(
                                    "org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2",
                                    AUTH_PARAMS));
                    break;
                case "tls":
                    clientBuilder.authentication(
                            AuthenticationFactory.create(
                                    "org.apache.pulsar.client.impl.auth.AuthenticationTls",
                                    AUTH_PARAMS));
                    break;
                case "basic":
                    if (BROKER_USERNAME != null && BROKER_PASSWORD != null) {
                        String basicAuth = String.format("{\"userId\":\"%s\",\"password\":\"%s\"}",
                                BROKER_USERNAME, BROKER_PASSWORD);
                        clientBuilder.authentication(
                                AuthenticationFactory.create(
                                        "org.apache.pulsar.client.impl.auth.AuthenticationBasic",
                                        basicAuth));
                    }
                    break;
                default:
                    log.info("Unknown authentication method: " + AUTH_NAME);
                    break;
            }
        } catch (Exception e) {
            System.err.println("Failed to configure authentication: " + e.getMessage());
        }
    }

    /**
     * Start consumer for towardsPlatformTopic to receive inbound messages from MQTT
     * Service
     */
    private void startPlatformConsumer() throws PulsarClientException {
        log.info("Starting consumer for MQTT Service platform topic: " + TOWARDS_PLATFORM_TOPIC);

        consumer = client.newConsumer()
                .topic(TOWARDS_PLATFORM_TOPIC)
                .subscriptionName(SUBSCRIPTION_NAME)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(new MQTTServiceMessageListener())
                .subscribe();

        log.info("Consumer started successfully for platform topic!");
    }

    /**
     * Start consumer for towardsDeviceTopic to receive inbound messages from MQTT
     * Service
     */
    private void startDeviceConsumer() throws PulsarClientException {
        log.info("Starting consumer for MQTT Service device topic: " + TOWARDS_DEVICE_TOPIC);

        consumer = client.newConsumer()
                .topic(TOWARDS_DEVICE_TOPIC)
                .subscriptionName(SUBSCRIPTION_NAME)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(new MQTTServiceMessageListener())
                .subscribe();

        log.info("Consumer started successfully towards device topic!");
    }

    /**
     * Send test messages using Cumulocity MQTT Service model
     * All messages go to towardsDeviceTopic with MQTT topic as property
     */
    private void testSendMQTTServiceMessages() throws PulsarClientException {
        log.info("Connecting to Pulsar broker: " + PULSAR_BROKER_HOST);
        log.info("Publishing messages to device topic: " + TOWARDS_DEVICE_TOPIC);

        producer = client.newProducer()
                .topic(TOWARDS_DEVICE_TOPIC)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create();

        // Send messages with different MQTT topics as properties
        sendMQTTServiceMessage("measurement/kobu-webhook-001", "temperature", 25.5);
        sendMQTTServiceMessage("measurement/kobu-webhook-002", "humidity", 65.0);
        sendMQTTServiceMessage("device/sensor-123/status", "status", "online");
        sendMQTTServiceMessage("alarm/critical/device-456", "alert", "battery_low");

        producer.close();
        log.info("Producer closed");
    }

    /**
     * Send a message using MQTT Service model with topic as property
     */
    private void sendMQTTServiceMessage(String mqttTopic, String measurementType, Object value)
            throws PulsarClientException {
        String payload = String.format("{ \"deviceId\": \"test-device-%d\", \"timestamp\": \"%d\", \"%s\": %s }",
                System.currentTimeMillis() % 1000,
                System.currentTimeMillis(),
                measurementType,
                value instanceof String ? "\"" + value + "\"" : value);

        // Send with MQTT topic as property (N-2 mapping model)
        producer.newMessage()
                .value(payload.getBytes())
                .property(PULSAR_PROPERTY_CHANNEL, mqttTopic) // Original MQTT topic
                .property(PULSAR_PROPERTY_CLIENT, "test-client-" + System.currentTimeMillis() % 100) // Client ID
                .property("tenant", TENANT) // Tenant for routing
                .property("messageType", measurementType) // Additional metadata
                .send();

        log.info("Sent message to MQTT Service:");
        log.info("  MQTT Topic (property): " + mqttTopic);
        log.info("  Pulsar Topic: " + TOWARDS_DEVICE_TOPIC);
        log.info("  Payload: " + payload);
        log.info("  ---");
    }

    /**
     * Test sending messages that simulate inbound traffic from devices
     */
    private void simulateInboundMessages() throws PulsarClientException {
        log.info("Simulating inbound messages to platform topic...");

        try (Producer<byte[]> platformProducer = client.newProducer()
                .topic(TOWARDS_PLATFORM_TOPIC)
                .sendTimeout(10, TimeUnit.SECONDS)
                .create()) {

            String[] mqttTopics = {
                    "measurement/device-001/temperature",
                    "measurement/device-002/humidity",
                    "event/device-003/startup",
                    "alarm/device-004/battery_low"
            };

            for (String mqttTopic : mqttTopics) {
                String payload = String.format(
                        "{ \"deviceId\": \"%s\", \"timestamp\": \"%d\", \"value\": %d, \"type\": \"inbound\" }",
                        "device-" + (System.currentTimeMillis() % 1000),
                        System.currentTimeMillis(),
                        (int) (Math.random() * 100));

                platformProducer.newMessage()
                        .value(payload.getBytes())
                        .property(PULSAR_PROPERTY_CHANNEL, mqttTopic)
                        .property(PULSAR_PROPERTY_CLIENT, "simulated-device-" + System.currentTimeMillis() % 10)
                        .property("direction", "inbound")
                        .send();

                log.info("Sent inbound message for MQTT topic: " + mqttTopic);
                Thread.sleep(500); // Small delay
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanup() {
        log.info("Cleaning up resources...");

        try {
            if (consumer != null) {
                consumer.close();
                log.info("Consumer closed");
            }
        } catch (PulsarClientException e) {
            System.err.println("Error closing consumer: " + e.getMessage());
        }

        try {
            if (producer != null) {
                producer.close();
                log.info("Producer closed");
            }
        } catch (PulsarClientException e) {
            System.err.println("Error closing producer: " + e.getMessage());
        }

        try {
            if (client != null) {
                client.close();
                log.info("Pulsar client closed");
            }
        } catch (PulsarClientException e) {
            System.err.println("Error closing client: " + e.getMessage());
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }

        log.info("Cleanup completed");
    }

    /**
     * Message listener for consuming messages from MQTT Service
     * Handles N-2 topic mapping where topic info is in message properties
     */
    private static class MQTTServiceMessageListener implements MessageListener<byte[]> {
        @Override
        public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
            try {
                String payload = new String(message.getData());
                String pulsarTopic = message.getTopicName();
                String mqttTopic = message.getProperty(PULSAR_PROPERTY_CHANNEL);
                String clientId = message.getProperty(PULSAR_PROPERTY_CLIENT);
                String messageId = message.getMessageId().toString();
                long publishTime = message.getPublishTime();

                log.info("=== MQTT SERVICE MESSAGE RECEIVED ===");
                System.out.printf("Pulsar Topic: %s%n", pulsarTopic);
                System.out.printf("MQTT Topic (property): %s%n", mqttTopic != null ? mqttTopic : "N/A");
                System.out.printf("Client ID (property): %s%n", clientId != null ? clientId : "N/A");
                System.out.printf("Message ID: %s%n", messageId);
                System.out.printf("Publish Time: %d%n", publishTime);

                // Display all properties
                log.info("Properties:");
                message.getProperties().forEach((key, value) -> System.out.printf("  %s: %s%n", key, value));

                System.out.printf("Payload: %s%n", payload);
                log.info("=====================================");

                // Acknowledge the message
                consumer.acknowledge(message);

            } catch (Exception e) {
                System.err.println("Error processing MQTT Service message: " + e.getMessage());
                // Negative acknowledgment to retry
                consumer.negativeAcknowledge(message);
            }
        }
    }
}