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

package dynamic.mapping.connector.mqtt;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.cumulocity.sdk.client.SDKException;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.ProcessingResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTTCallback implements Consumer<Mqtt3Publish> {
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);
    private GenericMessageCallback genericMessageCallback;
    private String tenant;
    private String connectorIdentifier;
    private boolean supportsMessageContext;
    private ConfigurationRegistry configurationRegistry;
    private ServiceConfiguration serviceConfiguration;
    private ExecutorService virtualThreadPool;

    MQTTCallback(String tenant, ConfigurationRegistry configurationRegistry, GenericMessageCallback callback,
            String connectorIdentifier,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.supportsMessageContext = supportsMessageContext;
        this.configurationRegistry = configurationRegistry;
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    }

    @Override
    public void accept(Mqtt3Publish mqttMessage) {
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        byte[] payloadBytes = mqttMessage.getPayload()
                .map(byteBuffer -> {
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    return bytes;
                })
                .orElse(null);
        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .supportsMessageContext(supportsMessageContext)
                .topic(topic)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .build();

        // Process the message
        ProcessingResult<?> processedResults = genericMessageCallback.onMessage(connectorMessage);
        // Determine downgraded QoS as the minimum of QoS in the message and the
        // consolidated QoS of the mappings
        int publishQos = mqttMessage.getQos().getCode();
        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getMaxCPUTimeMS();
        int effectiveQos = Math.min(publishQos, mappingQos);
        if (serviceConfiguration.logPayload) {
            log.info(
                    "Tenant {} - INITIAL: new message on topic: [{}], QoS message: {}, QoS effective: {}, QoS mappings: {}, Connector {}",
                    tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), effectiveQos, mappingQos,
                    connectorIdentifier);
        }
        if (effectiveQos > 0 || timeout > 0) {
            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete
                    List<? extends ProcessingContext<?>> results;
                    if (timeout > 0) {
                        results = processedResults.getProcessingResult().get(timeout,
                                TimeUnit.MILLISECONDS);
                    }
                    else {
                        results = processedResults.getProcessingResult().get();
                    }

                    // Check for errors in results
                    boolean hasErrors = false;
                    int httpStatusCode = 0;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            if (context.hasError()) {
                                for(Exception error : context.getErrors()) {
                                    if(error instanceof ProcessingException) {
                                        if( ((ProcessingException) error).getOriginException() instanceof SDKException) {
                                            if(((SDKException)((ProcessingException) error).getOriginException()).getHttpStatus() > httpStatusCode) {
                                                httpStatusCode = ((SDKException)((ProcessingException) error).getOriginException()).getHttpStatus();
                                            }
                                        }
                                    }
                                }
                                hasErrors = true;
                                log.error(
                                        "Tenant {} - Error in processing context for topic: [{}], not sending ack to MQTT broker",
                                        tenant, topic);
                                break;
                            }
                        }
                    }

                    if (!hasErrors) {
                        // No errors found, acknowledge the message
                        log.warn("Tenant {} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, Connector {}",
                                tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                        mqttMessage.acknowledge();
                    } else if(httpStatusCode < 500){
                        //Errors found but not a server error, acknowledge the message
                        log.warn("Tenant {} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, Connector {}",
                                tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                        mqttMessage.acknowledge();
                    } else {
                        //Not sending ack, trigger retransmission
                        //TODO Trigger Connector reconnect to after delay
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed, don't acknowledge to allow redelivery
                    // Thread.currentThread().interrupt();
                    log.warn("Tenant {} - END: Was interrupted for MQTT message: topic: [{}], QoS: {}, Connector {}",
                    tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                } catch (TimeoutException e) {
                    var cancelResult = processedResults.getProcessingResult().cancel(true);
                    log.warn("Tenant {} - END: Processing timed out with: {} milliseconds, connector {}, result of cancelling: {}",
                    tenant, timeout, connectorIdentifier, cancelResult);
                }
                return null; // Proper return for Callable<Void>
            });
        } else {
            // For QoS 0 (or downgraded to 0), no need for special handling

            // Acknowledge message with QoS=0
            if (serviceConfiguration.logPayload) {
                log.info("Tenant {} - END: Sending manual ack for MQTT message: topic: [{}], QoS: {}, Connector {}",
                        tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
            }
            mqttMessage.acknowledge();

        }
    }

}