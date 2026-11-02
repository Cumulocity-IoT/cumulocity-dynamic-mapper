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

import dynamic.mapper.util.TenantLogContext;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.callback.MessageFailureTracker;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Callback handler for incoming AMQP 1.0 messages via JMS.
 * Implements {@link MessageListener} to handle JMS message delivery from
 * an Apache Qpid JMS consumer.
 *
 * <p>The session backing this consumer is always {@code CLIENT_ACKNOWLEDGE}
 * (see {@link AMQP10Client#connect()}), so every message must be explicitly
 * acknowledged or it is redelivered. The acknowledgement point is chosen per
 * the subscription's {@link Qos}: AT_MOST_ONCE acknowledges before dispatch
 * (never redelivered, even if processing fails), AT_LEAST_ONCE acknowledges
 * only after dispatch completes successfully (redelivered on failure).</p>
 */
@Slf4j
public class AMQP10Callback implements MessageListener {

    private final GenericMessageCallback genericMessageCallback;
    private final String tenant;
    private final String connectorIdentifier;
    private final String connectorName;
    private final String subscriptionTopic;
    private final Qos qos;
    private final ServiceConfiguration serviceConfiguration;
    private final Session session;
    private final MessageFailureTracker failureTracker;

    /**
     * Constructor
     *
     * @param tenant               tenant identifier
     * @param serviceRegistry configuration registry for service lookup
     * @param callback             the generic message callback to dispatch to
     * @param connectorIdentifier  connector identifier
     * @param connectorName        connector display name
     * @param subscriptionTopic    the topic/address this consumer was subscribed on
     * @param qos                  the QoS this consumer was subscribed with
     * @param session              the CLIENT_ACKNOWLEDGE session the consumer belongs to, recovered to
     *                             redeliver a failed message (acknowledge() would also settle it)
     */
    public AMQP10Callback(String tenant,
            ServiceRegistry serviceRegistry,
            GenericMessageCallback callback,
            String connectorIdentifier,
            String connectorName,
            String subscriptionTopic,
            Qos qos,
            Session session) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.subscriptionTopic = subscriptionTopic;
        this.qos = qos;
        this.serviceConfiguration = serviceRegistry.getServiceConfiguration(tenant);
        this.session = session;
        this.failureTracker = MessageFailureTracker.forConfiguration(
                () -> serviceRegistry.getServiceConfiguration(tenant));
    }

    @Override
    public void onMessage(Message message) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            String address = resolveAddress(message);

            try {
                byte[] payload = extractPayload(message);
                if (payload == null) {
                    acknowledgeQuietly(message, address);
                    return;
                }

                // AT_MOST_ONCE: ack now so a failure below never causes redelivery.
                if (qos == Qos.AT_MOST_ONCE) {
                    acknowledgeQuietly(message, address);
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

                var processedResults = genericMessageCallback.onMessage(connectorMessage);

                // AT_LEAST_ONCE: ack only once the pipeline has succeeded. Not acknowledging is not
                // enough to get a redelivery: in a CLIENT_ACKNOWLEDGE session the acknowledge() of a
                // later message settles every consumed message, including the failed one. A failed
                // message therefore recovers the session (redelivering it before the next message is
                // delivered, as the listener runs serially), until it failed maxProcessingAttempts
                // times: then it is dropped (acknowledged).
                if (qos != Qos.AT_MOST_ONCE) {
                    settle(message, address, payload, processedResults);
                }

                if (serviceConfiguration.getLogPayload()) {
                    log.info("{} - PROCESSING_COMPLETED: AMQP 1.0 message on address: [{}], connector: {}",
                            tenant, address, connectorIdentifier);
                }

            } catch (Exception e) {
                log.error("{} - Error processing AMQP 1.0 message on address: [{}]",
                        tenant, address, e);
            }
        }
    }

    private void settle(Message message, String address, byte[] payload,
            ProcessingResultWrapper<?> processedResults) {
        String failureKey = failureKey(message, address, payload);
        if (ProcessingResultHelper.awaitAckable(processedResults, tenant, address, log)) {
            failureTracker.clear(failureKey);
            acknowledgeQuietly(message, address);
        } else if (failureTracker.recordFailureReachedLimit(failureKey)) {
            log.error("{} - Dropping AMQP 1.0 message after {} failed attempts: address: [{}], connector: {}",
                    tenant, failureTracker.limit(), address, connectorIdentifier);
            acknowledgeQuietly(message, address);
        } else {
            recoverQuietly(address);
        }
    }

    /**
     * Identifies a message across redeliveries: the JMS message id when the producer set one, otherwise the
     * address and a hash of the payload. The provider may hand out a new {@link Message} object for each
     * redelivery, so the object identity cannot be used.
     */
    private static String failureKey(Message message, String address, byte[] payload) {
        try {
            String id = message.getJMSMessageID();
            if (id != null) {
                return "id:" + id;
            }
        } catch (JMSException e) {
            // fall back to the payload
        }
        return address + "#" + java.util.Arrays.hashCode(payload);
    }

    /** Redeliver the unacknowledged messages of the session, swallowing (and logging) any JMSException. */
    private void recoverQuietly(String address) {
        try {
            session.recover();
        } catch (JMSException e) {
            log.error("{} - Failed to recover AMQP 1.0 session for redelivery, address: [{}]",
                    tenant, address, e);
        }
    }

    /** Acknowledge the message, swallowing (and logging) any JMSException. */
    private void acknowledgeQuietly(Message message, String address) {
        try {
            message.acknowledge();
        } catch (JMSException e) {
            log.error("{} - Failed to acknowledge AMQP 1.0 message on address: [{}]",
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
