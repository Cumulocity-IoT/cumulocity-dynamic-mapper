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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClientException;

import com.cumulocity.sdk.client.SDKException;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PulsarCallback implements MessageListener<byte[]> {
    private GenericMessageCallback genericMessageCallback;
    private String tenant;
    private String connectorIdentifier;
    private String connectorName;
    private boolean supportsMessageContext;
    private ServiceConfiguration serviceConfiguration;
    private ExecutorService virtualThreadPool;

    public PulsarCallback(String tenant, ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.connectorName = connectorName;
        this.supportsMessageContext = supportsMessageContext;
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    }

    @Override
    public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
        String topic = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_CHANNEL);
        String client = message.getProperty(MQTTServicePulsarClient.PULSAR_PROPERTY_CLIENT_ID);
        byte[] payloadBytes = message.getData();

        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .supportsMessageContext(supportsMessageContext)
                .topic(topic)
                .clientId(client)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .build();

        if (serviceConfiguration.isLogPayload()) {
            log.info(
                    "{} - INITIAL: message on topic: [{}], connector: {}, {}",
                    tenant, topic, connectorName, connectorIdentifier);
        }

        // Process the message
        ProcessingResultWrapper<?> processedResults = genericMessageCallback.onMessage(connectorMessage);

        int timeout = processedResults.getMaxCPUTimeMS();

        if (serviceConfiguration.isLogPayload()) {
            log.info(
                    "{} - PREPARING_RESULTS: message on topic: [{}], connector {}",
                    tenant, topic, connectorIdentifier);
        }

        // Use the provided virtualThreadPool instead of creating a new thread
        virtualThreadPool.submit(() -> {
            try {
                // Wait for the future to complete
                List<? extends ProcessingContext<?>> results;
                if (timeout > 0) {
                    results = processedResults.getProcessingResult().get(timeout, TimeUnit.MILLISECONDS);
                } else {
                    results = processedResults.getProcessingResult().get();
                }

                // Check for errors in results
                boolean hasErrors = false;
                int httpStatusCode = 0;
                if (results != null) {
                    for (ProcessingContext<?> context : results) {
                        if (context.hasError()) {
                            for (Exception error : context.getErrors()) {
                                if (error instanceof ProcessingException) {
                                    if (((ProcessingException) error).getOriginException() instanceof SDKException) {
                                        if (((SDKException) ((ProcessingException) error).getOriginException())
                                                .getHttpStatus() > httpStatusCode) {
                                            httpStatusCode = ((SDKException) ((ProcessingException) error)
                                                    .getOriginException()).getHttpStatus();
                                        }
                                    }
                                }
                            }
                            hasErrors = true;
                            log.error("{} - Error in processing context for topic: [{}]", tenant, topic);
                            break;
                        }
                    }
                }

                if (!hasErrors) {
                    // No errors found, acknowledge based on original QoS requirements
                    if (serviceConfiguration.isLogPayload()) {
                        log.debug("{} - END: Sending ack for Pulsar message: topic: [{}], connector: {}",
                                tenant, topic, connectorIdentifier);
                    }
                    consumer.acknowledge(message);
                } else if (httpStatusCode < 500) {
                    // Client errors - acknowledge to prevent redelivery
                    log.warn("{} - END: Sending ack due to client error for Pulsar message: topic: [{}], connector: {}",
                            tenant, topic, connectorIdentifier);
                    consumer.acknowledge(message);
                } else {
                    // Server error, negative acknowledge to trigger redelivery
                    // But only if QoS requires reliability
                    log.warn(
                            "{} - END: Sending negative ack due to server error for Pulsar message: topic: [{}], connector: {}",
                            tenant, topic, connectorIdentifier);
                    consumer.negativeAcknowledge(message);
                }
            } catch (InterruptedException | ExecutionException e) {
                // Processing failed, negative acknowledge to allow redelivery
                log.warn("{} - END: Was interrupted for Pulsar message: topic: [{}], connector: {}",
                        tenant, topic, connectorIdentifier);
                consumer.negativeAcknowledge(message);
            } catch (TimeoutException e) {
                var cancelResult = processedResults.getProcessingResult().cancel(true);
                log.warn("{} - END: Processing timed out with: {} milliseconds, connector {}, result of cancelling: {}",
                        tenant, timeout, connectorIdentifier, cancelResult);
                consumer.negativeAcknowledge(message);
            } catch (PulsarClientException e) {
                log.error("{} - Error acknowledging Pulsar message: topic: [{}], connector: {}",
                        tenant, topic, connectorIdentifier, e);
            }
            return null;
        });
    }
}