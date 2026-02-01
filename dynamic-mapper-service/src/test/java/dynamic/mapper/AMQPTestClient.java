/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import com.rabbitmq.client.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AMQPTestClient {
    private Connection connection;
    private Channel channel;

    // Environment variables
    static String brokerHost = System.getenv().getOrDefault("AMQP_BROKER_HOST", "46.101.117.78");
    static String brokerPort = System.getenv().getOrDefault("AMQP_BROKER_PORT", "5672");
    static String virtualHost = System.getenv().getOrDefault("AMQP_VIRTUAL_HOST", "/");
    static String username = System.getenv().getOrDefault("AMQP_USERNAME", "guest");
    static String password = System.getenv().getOrDefault("AMQP_PASSWORD", "guest");
    static String protocol = System.getenv().getOrDefault("AMQP_PROTOCOL", "amqp://");
    static String exchange = System.getenv().getOrDefault("AMQP_EXCHANGE", "");
    static String exchangeType = System.getenv().getOrDefault("AMQP_EXCHANGE_TYPE", "topic");
    static String routingKey = System.getenv().getOrDefault("AMQP_ROUTING_KEY", "test.measurement");
    static String queueName = System.getenv().getOrDefault("AMQP_QUEUE", "test-queue");

    public AMQPTestClient(Connection connection, Channel channel) {
        this.connection = connection;
        this.channel = channel;
    }

    public static void main(String[] args) {
        log.info("=== AMQP Test Client ===");
        log.info("Broker: {}:{}", brokerHost, brokerPort);
        log.info("Virtual Host: {}", virtualHost);
        log.info("Username: {}", username);
        log.info("Protocol: {}", protocol);
        log.info("Exchange: {}", exchange.isEmpty() ? "(default)" : exchange);
        log.info("Exchange Type: {}", exchangeType);
        log.info("Routing Key: {}", routingKey);
        log.info("Queue: {}", queueName);
        log.info("========================");

        try {
            // Create connection factory
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(brokerHost);
            factory.setPort(Integer.parseInt(brokerPort));
            factory.setVirtualHost(virtualHost);
            factory.setUsername(username);
            factory.setPassword(password);

            // Configure SSL if needed
            if ("amqps://".equals(protocol)) {
                factory.useSslProtocol();
                log.info("SSL/TLS enabled");
            }

            // Create connection and channel
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();

            AMQPTestClient client = new AMQPTestClient(connection, channel);

            // Run tests
            if (args.length > 0 && "subscribe".equals(args[0])) {
                client.testSubscribe();
            } else {
                client.testPublish();
            }

        } catch (Exception e) {
            log.error("Error running AMQP test client", e);
        }
    }

    /**
     * Test publishing messages to AMQP broker
     */
    private void testPublish() {
        try {
            log.info("=== Testing AMQP Publish ===");

            // Declare exchange if specified
            if (!exchange.isEmpty()) {
                channel.exchangeDeclare(exchange, exchangeType, true);
                log.info("Exchange declared: {} (type: {})", exchange, exchangeType);
            }

            // Create test payload
            String payload = "{ \"deviceId\": \"863859042393327\", \"version\": \"1\", \"deviceType\": \"20\", " +
                    "\"deviceTimestamp\": \"1665473038000\", \"deviceStatus\": \"BTR\", \"temperature\": 90 }";

            log.info("Publishing message to exchange: '{}', routing key: '{}'", exchange, routingKey);
            log.info("Payload: {}", payload);

            // Build message properties
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2) // Persistent
                    .contentType("application/json")
                    .build();

            // Publish message
            channel.basicPublish(exchange, routingKey, props, payload.getBytes(StandardCharsets.UTF_8));

            log.info("✅ Message published successfully!");

            // Close connection
            channel.close();
            connection.close();
            log.info("Connection closed");

        } catch (Exception e) {
            log.error("❌ Error publishing message", e);
        }
    }

    /**
     * Test subscribing and consuming messages from AMQP broker
     */
    private void testSubscribe() {
        try {
            log.info("=== Testing AMQP Subscribe ===");

            // Declare exchange if specified
            if (!exchange.isEmpty()) {
                channel.exchangeDeclare(exchange, exchangeType, true);
                log.info("Exchange declared: {} (type: {})", exchange, exchangeType);
            }

            // Declare queue
            channel.queueDeclare(queueName, true, false, false, null);
            log.info("Queue declared: {}", queueName);

            // Bind queue to exchange if exchange is specified
            if (!exchange.isEmpty()) {
                channel.queueBind(queueName, exchange, routingKey);
                log.info("Queue bound to exchange with routing key: {}", routingKey);
            }

            log.info("Starting to consume messages from queue: {}", queueName);
            log.info("Press CTRL+C to exit...");

            // Create consumer
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                String receivedRoutingKey = delivery.getEnvelope().getRoutingKey();

                log.info("=== Message Received ===");
                log.info("Routing Key: {}", receivedRoutingKey);
                log.info("Payload: {}", message);
                log.info("========================");

                // Acknowledge message
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            };

            CancelCallback cancelCallback = consumerTag -> {
                log.warn("Consumer cancelled: {}", consumerTag);
            };

            // Start consuming
            channel.basicConsume(queueName, false, deliverCallback, cancelCallback);

            // Keep the application running
            synchronized (this) {
                this.wait();
            }

        } catch (Exception e) {
            log.error("❌ Error subscribing to messages", e);
        } finally {
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
                if (connection != null && connection.isOpen()) {
                    connection.close();
                }
                log.info("Connection closed");
            } catch (IOException | TimeoutException e) {
                log.error("Error closing connection", e);
            }
        }
    }

    /**
     * Test both publish and subscribe in sequence
     */
    public void testPublishAndSubscribe() {
        try {
            log.info("=== Testing AMQP Publish & Subscribe ===");

            // Declare exchange if specified
            if (!exchange.isEmpty()) {
                channel.exchangeDeclare(exchange, exchangeType, true);
                log.info("Exchange declared: {} (type: {})", exchange, exchangeType);
            }

            // Declare queue
            channel.queueDeclare(queueName, true, false, false, null);
            log.info("Queue declared: {}", queueName);

            // Bind queue to exchange
            if (!exchange.isEmpty()) {
                channel.queueBind(queueName, exchange, routingKey);
                log.info("Queue bound to exchange with routing key: {}", routingKey);
            }

            // Set up consumer first
            boolean[] messageReceived = new boolean[]{false};

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                log.info("✅ Message received: {}", message);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                messageReceived[0] = true;
            };

            channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {});
            log.info("Consumer started");

            // Publish test message
            String payload = "{ \"test\": \"message\", \"timestamp\": " + System.currentTimeMillis() + " }";
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2)
                    .contentType("application/json")
                    .build();

            channel.basicPublish(exchange, routingKey, props, payload.getBytes(StandardCharsets.UTF_8));
            log.info("✅ Message published: {}", payload);

            // Wait for message to be received
            Thread.sleep(2000);

            if (messageReceived[0]) {
                log.info("✅ Test completed successfully!");
            } else {
                log.warn("⚠️  Message was not received");
            }

            // Close connection
            channel.close();
            connection.close();
            log.info("Connection closed");

        } catch (Exception e) {
            log.error("❌ Error in publish and subscribe test", e);
        }
    }
}
