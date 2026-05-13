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

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Callback handler for incoming AMQP 1.0 messages via JMS.
 * Implements {@link MessageListener} to handle JMS message delivery from
 * an Apache Qpid JMS consumer.
 */
@Slf4j
public class AMQP10Callback implements MessageListener {

    private final GenericMessageCallback genericMessageCallback;
    private final String tenant;
    private final String connectorIdentifier;
    private final String connectorName;
    private final String subscriptionTopic;
    private final ServiceConfiguration serviceConfiguration;

    /**
     * Constructor
     *
     * @param tenant               tenant identifier
     * @param configurationRegistry configuration registry for service lookup
     * @param callback             the generic message callback to dispatch to
     * @param connectorIdentifier  connector identifier
     * @param connectorName        connector display name
     * @param subscriptionTopic    the topic/address this consumer was subscribed on
     */
    public AMQP10Callback(String tenant,
            ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback,
            String connectorIdentifier,
            String connectorName,
            String subscriptionTopic) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.subscriptionTopic = subscriptionTopic;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
    }

    @Override
    public void onMessage(Message message) {
        String address = resolveAddress(message);

        try {
            byte[] payload = extractPayload(message);
            if (payload == null) {
                return;
            }

            ConnectorMessage connectorMessage = ConnectorMessage.builder()
                    .tenant(tenant)
                    .topic(address)
                    .sendPayload(true)
                    .connectorIdentifier(connectorIdentifier)
                    .payload(payload)
                    .build();

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - INITIAL: AMQP 1.0 message on address: [{}], connector: {}, {}",
                        tenant, address, connectorName, connectorIdentifier);
            }

            genericMessageCallback.onMessage(connectorMessage);

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - PROCESSING_COMPLETED: AMQP 1.0 message on address: [{}], connector: {}",
                        tenant, address, connectorIdentifier);
            }

        } catch (Exception e) {
            log.error("{} - Error processing AMQP 1.0 message on address: [{}]",
                    tenant, address, e);
        }
    }

    /**
     * Extract the byte payload from a JMS message.
     * Supports {@link TextMessage} and {@link BytesMessage}.
     * Returns {@code null} for unsupported message types.
     */
    private byte[] extractPayload(Message message) {
        try {
            if (message instanceof TextMessage textMessage) {
                String text = textMessage.getText();
                return text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
            } else if (message instanceof BytesMessage bytesMessage) {
                long bodyLength = bytesMessage.getBodyLength();
                byte[] bytes = new byte[(int) bodyLength];
                bytesMessage.readBytes(bytes);
                return bytes;
            } else {
                log.warn("{} - Unsupported JMS message type on address [{}]: {}",
                        tenant, subscriptionTopic, message.getClass().getSimpleName());
                return null;
            }
        } catch (Exception e) {
            log.error("{} - Failed to extract payload from AMQP 1.0 message on address [{}]",
                    tenant, subscriptionTopic, e);
            return null;
        }
    }

    /**
     * Resolve the delivery address from the JMS message destination.
     * Falls back to the subscription topic if the destination cannot be read.
     */
    private String resolveAddress(Message message) {
        try {
            Destination dest = message.getJMSDestination();
            if (dest instanceof Queue q) {
                return q.getQueueName();
            } else if (dest instanceof Topic t) {
                return t.getTopicName();
            }
        } catch (Exception e) {
            log.debug("{} - Could not read JMS destination, using subscription topic", tenant);
        }
        return subscriptionTopic;
    }
}
