/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.connector.pulsar;

import dynamic.mapper.util.TenantLogContext;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulsar callback for the Cumulocity MQTT Service (messages arriving via the C8Y Pulsar broker).
 *
 * <p>Inherits shared infrastructure from {@link AbstractPulsarCallback}:
 * poison-pill detection, 2-second future drain after cancellation, and common fields.
 *
 * <p>Additional behavior specific to the MQTT Service:
 * <ul>
 *   <li>Separates the internal Pulsar topic ({@code towardsDeviceTopic}) from the
 *       MQTT topic carried as a message property</li>
 *   <li>Exponential timeout scaling on retransmission</li>
 *   <li>JS CPU-timeout early-cancellation guard ({@code cancellationRequested})</li>
 * </ul>
 */
@Slf4j
public class MQTTServicePulsarCallback extends AbstractPulsarCallback {

    public MQTTServicePulsarCallback(String tenant, ServiceRegistry serviceRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName) {
        super(tenant, serviceRegistry, callback, connectorIdentifier, connectorName);
    }

    @Override
    public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            String towardsDeviceTopic = message.getTopicName();
            String topic = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_TOPIC);
            String client = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_CLIENT_ID);
            byte[] payloadBytes = message.getData();
            String messageId = message.getMessageId().toString();

            ConnectorMessage connectorMessage = ConnectorMessage.builder()
                    .tenant(tenant)
                    .topic(topic)
                    .clientId(client)
                    .sendPayload(true)
                    .connectorIdentifier(connectorIdentifier)
                    .payload(payloadBytes)
                    .build();

            if (serviceConfiguration.getLogPayload()) {
                log.info("{} - INITIAL: message {} on topic: [{}], connector: {}, {}",
                        tenant, messageId, towardsDeviceTopic, connectorName, connectorIdentifier);
            }

            ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);
            int timeout = processedResults.getPipelineTimeoutMS();

            virtualThreadPool.submit(() -> {
                try {
                    // The dispatcher returns early, without a processing future, for messages it
                    // deliberately skips (null/$SYS topic, null payload, mapping resolution error).
                    // Acknowledge those; otherwise they stay unacknowledged forever.
                    if (processedResults.getProcessingResult() == null) {
                        log.warn("{} - No processing result for Pulsar message (skipped by dispatcher), acknowledging. topic: [{}], mqtt topic: [{}], connector: {}",
                                tenant, towardsDeviceTopic, topic, connectorIdentifier);
                        consumer.acknowledge(message);
                        return null;
                    }
                    // Give a retransmission more time than the first attempt: a timeout can be caused
                    // by a transiently slow platform, not by a broken mapping. Capped at
                    // MAX_TIMEOUT_ESCALATION_FACTOR x the base budget.
                    Long timeoutOverride = null;
                    if (timeout > 0) {
                        int attempt = failureTracker.failures(messageId);
                        timeoutOverride = Math.min(
                                (long) timeout * (attempt + 1),
                                (long) timeout * MAX_TIMEOUT_ESCALATION_FACTOR);
                        if (attempt > 0) {
                            log.info(
                                    "{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                    tenant, attempt + 1, timeoutOverride, timeout, connectorIdentifier);
                        }
                    }
                    AckOutcome outcome = ProcessingResultHelper.awaitOutcome(
                            processedResults, tenant, towardsDeviceTopic, log, timeoutOverride);
                    if (outcome == AckOutcome.ACK) {
                        failureTracker.clear(messageId);
                        if (serviceConfiguration.getLogPayload()) {
                            log.info("{} - END: Sending ack for Pulsar message: topic: [{}], connector: {}",
                                    tenant, towardsDeviceTopic, connectorIdentifier);
                        }
                        consumer.acknowledge(message);
                    } else {
                        handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
                    }
                } catch (PulsarClientException e) {
                    log.error("{} - Error acknowledging Pulsar message: topic: [{}], connector: {}",
                            tenant, towardsDeviceTopic, connectorIdentifier, e);
                } catch (Exception e) {
                    // Anything unexpected must not leave the message silently unacknowledged
                    // (a task submitted to the pool swallows exceptions).
                    log.error("{} - Unexpected error processing Pulsar message, sending negative ack. topic: [{}], connector: {}",
                            tenant, towardsDeviceTopic, connectorIdentifier, e);
                    handleFailureOrPoisonPill(consumer, message, messageId, towardsDeviceTopic);
                }
                return null;
            });
        }
    }
}
