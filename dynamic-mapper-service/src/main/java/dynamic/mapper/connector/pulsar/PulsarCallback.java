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

package dynamic.mapper.connector.pulsar;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClientException;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulsar callback for plain (non-MQTT-Service) Pulsar connectors.
 *
 * <p>Inherits shared infrastructure from {@link AbstractPulsarCallback}:
 * poison-pill detection, 2-second future drain after cancellation, and common fields.
 */
@Slf4j
public class PulsarCallback extends AbstractPulsarCallback {

    public PulsarCallback(String tenant, ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName) {
        super(tenant, configurationRegistry, callback, connectorIdentifier, connectorName);
    }

    @Override
    public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
        String topic = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_CHANNEL);
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
            log.info("{} - INITIAL: message on topic: [{}], connector: {}, {}",
                    tenant, topic, connectorName, connectorIdentifier);
        }

        ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);
        int timeout = processedResults.getPipelineTimeoutMS();

        if (serviceConfiguration.getLogPayload()) {
            log.info("{} - PREPARING_RESULTS: message on topic: [{}], connector {}",
                    tenant, topic, connectorIdentifier);
        }

        virtualThreadPool.submit(() -> {
            try {
                List<? extends ProcessingContext<?>> results;
                if (timeout > 0) {
                    results = processedResults.getProcessingResult().get(timeout, TimeUnit.MILLISECONDS);
                } else {
                    results = processedResults.getProcessingResult().get();
                }

                // JS CPU timeout may have fired before the wall-clock timeout.
                if (processedResults.getCancellationRequested().get()) {
                    log.warn(
                            "{} - JS CPU timeout fired: processing cancelled before wall-clock timeout, not ACKing. connector: {}",
                            tenant, connectorIdentifier);
                    handleFailureOrPoisonPill(consumer, message, messageId, topic);
                    return null;
                }

                int httpStatusCode = ProcessingResultHelper.extractMaxHttpStatus(
                        results, tenant, topic, log);

                if (httpStatusCode < 0) {
                    failureCountPerMessage.remove(messageId);
                    if (serviceConfiguration.getLogPayload()) {
                        log.debug("{} - END: Sending ack for Pulsar message: topic: [{}], connector: {}",
                                tenant, topic, connectorIdentifier);
                    }
                    consumer.acknowledge(message);
                } else if (httpStatusCode < 500) {
                    failureCountPerMessage.remove(messageId);
                    log.warn("{} - END: Sending ack due to client error for Pulsar message: topic: [{}], connector: {}",
                            tenant, topic, connectorIdentifier);
                    consumer.acknowledge(message);
                } else {
                    log.warn(
                            "{} - END: Server error (HTTP {}), sending negative ack for Pulsar redelivery. topic: [{}], connector: {}",
                            tenant, httpStatusCode, topic, connectorIdentifier);
                    handleFailureOrPoisonPill(consumer, message, messageId, topic);
                }
            } catch (InterruptedException | ExecutionException e) {
                log.warn("{} - END: Was interrupted for Pulsar message: topic: [{}], connector: {}",
                        tenant, topic, connectorIdentifier);
                handleFailureOrPoisonPill(consumer, message, messageId, topic);
            } catch (TimeoutException e) {
                log.warn("{} - Timeout occurred, initiating cancellation of processing task, connector: {}",
                        tenant, connectorIdentifier);
                boolean futureCompleted = cancelAndDrain(processedResults);
                log.warn(
                        "{} - END: Processing timed out after {}ms, sending negative ack for Pulsar redelivery. connector: {}, future completed: {}",
                        tenant, timeout, connectorIdentifier, futureCompleted);
                handleFailureOrPoisonPill(consumer, message, messageId, topic);
            } catch (PulsarClientException e) {
                log.error("{} - Error acknowledging Pulsar message: topic: [{}], connector: {}",
                        tenant, topic, connectorIdentifier, e);
            }
            return null;
        });
    }
}
