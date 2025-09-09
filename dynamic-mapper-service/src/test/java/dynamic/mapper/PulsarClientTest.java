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
import java.util.regex.Pattern;

import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;

public class PulsarClientTest {
    private PulsarClient client;
    private Producer<byte[]> producer;
    private Consumer<byte[]> consumer;
    private ExecutorService executorService;

    // Environment variables - similar to Kafka client
    static String PULSAR_BROKER_HOST = System.getenv().getOrDefault("PULSAR_BROKER_HOST", "pulsar://localhost:6650");
    static String BROKER_USERNAME = System.getenv("BROKER_USERNAME");
    static String BROKER_PASSWORD = System.getenv("BROKER_PASSWORD");
    static String AUTH_NAME = System.getenv().getOrDefault("AUTH_NAME", "none"); // none, token, oauth2
    static String AUTH_PARAMS = System.getenv("AUTH_PARAMS");
    static String SUBSCRIPTION_NAME = System.getenv().getOrDefault("SUBSCRIPTION_NAME", "pulsar-test-subscription");
    static String topic = System.getenv().getOrDefault("TOPIC", "persistent://public/default/measurement-kobu-webhook-001");
    static String TOPIC_PATTERN = System.getenv().getOrDefault("TOPIC_PATTERN", "persistent://public/default/measurement-kobu-webhook-[0-9]{3}");

    public PulsarClientTest() {
        this.executorService = Executors.newCachedThreadPool();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Pulsar Test Client...");
        System.out.println("Broker Host: " + PULSAR_BROKER_HOST);
        System.out.println("Topic: " + topic);
        System.out.println("Topic Pattern: " + TOPIC_PATTERN);
        System.out.println("Subscription: " + SUBSCRIPTION_NAME);

        PulsarClientTest testClient = new PulsarClientTest();
        
        try {
            // Initialize client
            testClient.initialize();
            
            // Start consumer in background
            testClient.startConsumer();
            
            // Send test messages
            // testClient.testSendMeasurement();
            
            // Keep consumer running for a while
            System.out.println("Consumer running... Press Ctrl+C to stop");
            Thread.sleep(60000); // Run for 1 minute
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            testClient.cleanup();
        }
    }

    private void initialize() throws PulsarClientException {
        System.out.println("Initializing Pulsar client...");
        
        var clientBuilder = org.apache.pulsar.client.api.PulsarClient.builder()
                .serviceUrl(PULSAR_BROKER_HOST)
                .connectionTimeout(30, TimeUnit.SECONDS)
                .operationTimeout(30, TimeUnit.SECONDS);

        // Configure authentication if provided
        if (!"none".equals(AUTH_NAME) && AUTH_PARAMS != null && !AUTH_PARAMS.isEmpty()) {
            configureAuthentication(clientBuilder);
        }

        client = clientBuilder.build();
        System.out.println("Pulsar client initialized successfully!");
    }

    private void configureAuthentication(org.apache.pulsar.client.api.ClientBuilder clientBuilder) {
        System.out.println("Configuring authentication method: " + AUTH_NAME);
        
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
                    System.out.println("Unknown authentication method: " + AUTH_NAME);
                    break;
            }
        } catch (Exception e) {
            System.err.println("Failed to configure authentication: " + e.getMessage());
        }
    }

    private void startConsumer() throws PulsarClientException {
        System.out.println("Starting consumer for topic pattern: " + TOPIC_PATTERN);
        
        consumer = client.newConsumer()
                .topicsPattern(Pattern.compile(TOPIC_PATTERN))
                .subscriptionName(SUBSCRIPTION_NAME)
                .subscriptionType(SubscriptionType.Shared)
                .messageListener(new PulsarMessageListener())
                .subscribe();
        
        System.out.println("Consumer started successfully!");
    }

    private void testSendMeasurement() throws PulsarClientException {
        System.out.println("Connecting to Pulsar broker: " + PULSAR_BROKER_HOST + "!");
        System.out.println("Publishing message on topic: " + topic);

        producer = client.newProducer()
                .topic(topic)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create();

        String payload = "{ \"deviceId\": \"863859042393327\", \"version\": \"1\",\"deviceType\": \"20\", \"deviceTimestamp\": \"" + 
                System.currentTimeMillis() + "\", \"deviceStatus\": \"BTR\", \"temperature\": 90 }";

        // Send message synchronously
        producer.send(payload.getBytes());
        System.out.println("Message published: " + payload);

        // Send a few more test messages to different device IDs
        sendAdditionalTestMessages();
        
        producer.close();
        System.out.println("Producer closed");
    }

    private void sendAdditionalTestMessages() throws PulsarClientException {
        System.out.println("Sending additional test messages...");
        
        String[] deviceIds = {"001", "002", "003", "123", "999"};
        
        for (String deviceId : deviceIds) {
            String testTopic = "persistent://public/default/measurement-kobu-webhook-" + deviceId;
            
            try (Producer<byte[]> testProducer = client.newProducer()
                    .topic(testTopic)
                    .sendTimeout(10, TimeUnit.SECONDS)
                    .create()) {
                
                String payload = String.format(
                        "{ \"deviceId\": \"86385904239332%s\", \"version\": \"1\", \"deviceType\": \"20\", " +
                        "\"deviceTimestamp\": \"%d\", \"deviceStatus\": \"BTR\", \"temperature\": %d }",
                        deviceId, System.currentTimeMillis(), 20 + Integer.parseInt(deviceId));
                
                testProducer.send(payload.getBytes());
                System.out.println("Sent message to topic: " + testTopic);
                
                // Small delay between messages
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void cleanup() {
        System.out.println("Cleaning up resources...");
        
        try {
            if (consumer != null) {
                consumer.close();
                System.out.println("Consumer closed");
            }
        } catch (PulsarClientException e) {
            System.err.println("Error closing consumer: " + e.getMessage());
        }

        try {
            if (producer != null) {
                producer.close();
                System.out.println("Producer closed");
            }
        } catch (PulsarClientException e) {
            System.err.println("Error closing producer: " + e.getMessage());
        }

        try {
            if (client != null) {
                client.close();
                System.out.println("Pulsar client closed");
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
        
        System.out.println("Cleanup completed");
    }

    /**
     * Message listener for consuming messages
     */
    private static class PulsarMessageListener implements MessageListener<byte[]> {
        @Override
        public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
            try {
                String payload = new String(message.getData());
                String topic = message.getTopicName();
                String messageId = message.getMessageId().toString();
                long publishTime = message.getPublishTime();
                
                System.out.printf("[RECEIVED] Topic: %s | MessageId: %s | PublishTime: %d%n", 
                        topic, messageId, publishTime);
                System.out.printf("[PAYLOAD] %s%n", payload);
                System.out.println("----------------------------------------");
                
                // Acknowledge the message
                consumer.acknowledge(message);
                
            } catch (Exception e) {
                System.err.println("Error processing message: " + e.getMessage());
                // Negative acknowledgment to retry
                consumer.negativeAcknowledge(message);
            }
        }
    }
}