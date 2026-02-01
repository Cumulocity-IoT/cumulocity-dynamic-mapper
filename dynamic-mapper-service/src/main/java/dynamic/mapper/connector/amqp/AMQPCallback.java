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

package dynamic.mapper.connector.amqp;

import com.rabbitmq.client.*;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Callback handler for incoming AMQP messages.
 * Implements RabbitMQ's DefaultConsumer to handle message delivery.
 */
@Slf4j
public class AMQPCallback extends DefaultConsumer {

    private final GenericMessageCallback genericMessageCallback;
    private final String tenant;
    private final String connectorIdentifier;
    private final String connectorName;
    private final ServiceConfiguration serviceConfiguration;
    private final ExecutorService virtualThreadPool;

    /**
     * Constructor
     */
    public AMQPCallback(String tenant,
            ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback,
            String connectorIdentifier,
            String connectorName) {
        super(null); // Channel will be set when basicConsume is called
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    }

    @Override
    public void handleDelivery(String consumerTag,
            Envelope envelope,
            AMQP.BasicProperties properties,
            byte[] body) throws IOException {

        String routingKey = envelope.getRoutingKey();
        long deliveryTag = envelope.getDeliveryTag();

        // Convert routing key to topic format (replace . with /)
        String topic = routingKey.replace(".", "/");

        try {
            // Build connector message
            ConnectorMessage connectorMessage = ConnectorMessage.builder()
                    .tenant(tenant)
                    .topic(topic)
                    .sendPayload(true)
                    .connectorIdentifier(connectorIdentifier)
                    .payload(body)
                    .build();

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - INITIAL: message on routing key: [{}], connector: {}, {}",
                        tenant, routingKey, connectorName, connectorIdentifier);
            }

            // Process the message
            ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - PREPARING_RESULTS: message on routing key: [{}], connector: {}",
                        tenant, routingKey, connectorIdentifier);
            }

            // Acknowledge message if not auto-ack
            // Note: If autoAck is true in basicConsume, this won't be needed
            if (this.getChannel() != null && !envelope.isRedeliver()) {
                this.getChannel().basicAck(deliveryTag, false);
            }

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - PROCESSING_COMPLETED: message on routing key: [{}], connector: {}",
                        tenant, routingKey, connectorIdentifier);
            }

        } catch (Exception e) {
            log.error("{} - Error processing AMQP message on routing key: [{}]",
                    tenant, routingKey, e);

            // Reject message and requeue
            try {
                if (this.getChannel() != null) {
                    this.getChannel().basicNack(deliveryTag, false, true);
                }
            } catch (IOException ex) {
                log.error("{} - Error rejecting message", tenant, ex);
            }
        }
    }

    @Override
    public void handleConsumeOk(String consumerTag) {
        log.debug("{} - Consumer registered: {}", tenant, consumerTag);
    }

    @Override
    public void handleCancelOk(String consumerTag) {
        log.debug("{} - Consumer cancelled: {}", tenant, consumerTag);
    }

    @Override
    public void handleCancel(String consumerTag) throws IOException {
        log.warn("{} - Consumer cancelled by broker: {}", tenant, consumerTag);
    }

    @Override
    public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
        if (sig.isInitiatedByApplication()) {
            log.debug("{} - Consumer shutdown initiated by application: {}", tenant, consumerTag);
        } else {
            log.warn("{} - Consumer shutdown: {}, reason: {}", tenant, consumerTag, sig.getReason());
        }
    }
}
