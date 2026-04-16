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

/**
 * Standalone AMQP (RabbitMQ) test client that publishes or consumes messages.
 *
 * <p>Run with argument {@code subscribe} to start a consumer; without arguments
 * (or any other argument) it publishes one test message and exits.</p>
 *
 * <h3>Environment variables</h3>
 * <pre>
 *   AMQP_BROKER_HOST    (default "localhost")
 *   AMQP_BROKER_PORT    (default "5672")
 *   AMQP_VIRTUAL_HOST   (default "/")
 *   AMQP_USERNAME       (default "guest")
 *   AMQP_PASSWORD       (default "guest")
 *   AMQP_PROTOCOL       (default "amqp://"; use "amqps://" to enable TLS)
 *   AMQP_EXCHANGE       (default "" — uses the default exchange)
 *   AMQP_EXCHANGE_TYPE  (default "topic")
 *   AMQP_ROUTING_KEY    (default "test.measurement")
 *   AMQP_QUEUE          (default "test-queue")
 * </pre>
 */
@Slf4j
public class AMQPTestClient {
    private Connection connection;
    private Channel channel;

    static final String brokerHost    = System.getenv().getOrDefault("AMQP_BROKER_HOST",    "localhost");
    static final String brokerPort    = System.getenv().getOrDefault("AMQP_BROKER_PORT",    "5672");
    static final String virtualHost   = System.getenv().getOrDefault("AMQP_VIRTUAL_HOST",   "/");
    static final String username      = System.getenv().getOrDefault("AMQP_USERNAME",        "guest");
    static final String password      = System.getenv().getOrDefault("AMQP_PASSWORD",        "guest");
    static final String protocol      = System.getenv().getOrDefault("AMQP_PROTOCOL",        "amqp://");
    static final String exchange      = System.getenv().getOrDefault("AMQP_EXCHANGE",        "");
    static final String exchangeType  = System.getenv().getOrDefault("AMQP_EXCHANGE_TYPE",   "topic");
    static final String routingKey    = System.getenv().getOrDefault("AMQP_ROUTING_KEY",     "test.measurement");
    static final String queueName     = System.getenv().getOrDefault("AMQP_QUEUE",           "test-queue");

    public AMQPTestClient(Connection connection, Channel channel) {
        this.connection = connection;
        this.channel = channel;
    }

    public static void main(String[] args) {
        log.info("=== AMQP Test Client ===");
        log.info("Broker         : {}:{}", brokerHost, brokerPort);
        log.info("Virtual host   : {}", virtualHost);
        log.info("Username       : {}", username);
        log.info("Protocol       : {}", protocol);
        log.info("Exchange       : {}", exchange.isEmpty() ? "(default)" : exchange);
        log.info("Exchange type  : {}", exchangeType);
        log.info("Routing key    : {}", routingKey);
        log.info("Queue          : {}", queueName);
        log.info("========================");

        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(brokerHost);
            factory.setPort(Integer.parseInt(brokerPort));
            factory.setVirtualHost(virtualHost);
            factory.setUsername(username);
            factory.setPassword(password);

            if ("amqps://".equals(protocol)) {
                factory.useSslProtocol();
                log.info("SSL/TLS enabled");
            }

            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            AMQPTestClient client = new AMQPTestClient(connection, channel);

            if (args.length > 0 && "subscribe".equals(args[0])) {
                client.testSubscribe();
            } else {
                client.testPublish();
            }
        } catch (Exception e) {
            log.error("Error running AMQP test client", e);
        }
    }

    // ── Publish ────────────────────────────────────────────────────────────

    private void testPublish() {
        try {
            log.info("=== Testing AMQP Publish ===");

            if (!exchange.isEmpty()) {
                channel.exchangeDeclare(exchange, exchangeType, true);
                log.info("Exchange declared: {} (type: {})", exchange, exchangeType);
            }

            String payload = String.format(
                    "{ \"deviceId\": \"863859042393327\", \"version\": \"1\", \"deviceType\": \"20\","
                    + " \"deviceTimestamp\": \"%d\", \"deviceStatus\": \"BTR\", \"temperature\": 90 }",
                    System.currentTimeMillis());

            log.info("Publishing — exchange: '{}', routing key: '{}'", exchange, routingKey);
            log.info("Payload: {}", payload);

            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2)
                    .contentType("application/json")
                    .build();

            channel.basicPublish(exchange, routingKey, props, payload.getBytes(StandardCharsets.UTF_8));
            log.info("Message published successfully");

            channel.close();
            connection.close();
            log.info("Connection closed");
        } catch (Exception e) {
            log.error("Error publishing message", e);
        }
    }

    // ── Subscribe ──────────────────────────────────────────────────────────

    private void testSubscribe() {
        try {
            log.info("=== Testing AMQP Subscribe ===");

            if (!exchange.isEmpty()) {
                channel.exchangeDeclare(exchange, exchangeType, true);
                log.info("Exchange declared: {} (type: {})", exchange, exchangeType);
            }

            channel.queueDeclare(queueName, true, false, false, null);
            log.info("Queue declared: {}", queueName);

            if (!exchange.isEmpty()) {
                channel.queueBind(queueName, exchange, routingKey);
                log.info("Queue bound to exchange with routing key: {}", routingKey);
            }

            log.info("Consuming from queue: {} — press Ctrl+C to exit", queueName);

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message            = new String(delivery.getBody(), StandardCharsets.UTF_8);
                String receivedRoutingKey = delivery.getEnvelope().getRoutingKey();

                log.info("=== Message received ===");
                log.info("Routing key : {}", receivedRoutingKey);
                log.info("Payload     : {}", message);
                log.info("========================");

                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            };

            channel.basicConsume(queueName, false, deliverCallback,
                    consumerTag -> log.warn("Consumer cancelled: {}", consumerTag));

            synchronized (this) {
                this.wait();
            }
        } catch (Exception e) {
            log.error("Error subscribing to messages", e);
        } finally {
            try {
                if (channel != null && channel.isOpen())     channel.close();
                if (connection != null && connection.isOpen()) connection.close();
                log.info("Connection closed");
            } catch (IOException | TimeoutException e) {
                log.error("Error closing connection", e);
            }
        }
    }
}
