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
import java.util.function.Consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.ProcessingResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTTCallback implements Consumer<Mqtt3Publish> {
    GenericMessageCallback genericMessageCallback;
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);
    String tenant;
    String connectorIdentifier;
    boolean supportsMessageContext;
    // Define callback with QoS OPTION_II
    // QOS qos;
    private ExecutorService virtualThreadPool;

    MQTTCallback(ConfigurationRegistry configurationRegistry, GenericMessageCallback callback, String tenant,
            String connectorIdentifier,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.supportsMessageContext = supportsMessageContext;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    }

    // Define callback with QoS OPTION_II
    // MQTTCallback(ConfigurationRegistry configurationRegistry,
    // GenericMessageCallback callback, String tenant,
    // String connectorIdentifier,
    // boolean supportsMessageContext, QOS qos) {
    // this.genericMessageCallback = callback;
    // this.tenant = tenant;
    // this.connectorIdentifier = connectorIdentifier;
    // this.supportsMessageContext = supportsMessageContext;
    // this.qos = qos;
    // this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
    // }

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

        log.info("Tenant {} - MQTT message received: topic: {}, QoS: {}, Connector {}",
                tenant,  mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
        // Determine downgraded QoS as the minimum of QoS in the message and the
        // consolidated QoS of the mappings
        int publishQos = mqttMessage.getQos().getCode();
        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int effectiveQos = Math.min(publishQos, mappingQos);
        log.info("Tenant {} - Calculated effective QoS: {} for MQTT message: topic: {}, Mapping QoS: {}, Publish QoS: {}",
                tenant,  effectiveQos, topic, mappingQos, publishQos);
        if (effectiveQos > 0) {
            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete
                    List<? extends ProcessingContext<?>> results = processedResults.getProcessingResult().get();

                    // Check for errors in results
                    boolean hasErrors = false;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            //We need to separate logic for different error cases otherwise it could be that message are never acked!
                            if (context.hasError()) {
                                hasErrors = true;
                                log.error("Tenant {} - Error in processing context for topic: {}, not sending ack to MQTT broker",
                                        tenant,  topic);
                                break;
                            }
                        }
                    }

                    if (!hasErrors) {
                        // No errors found, acknowledge the message
                        log.info("Tenant {} - Sending manual ack for MQTT message: topic: {}, QoS: {}, Connector {}",
                                tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
                        mqttMessage.acknowledge();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed, don't acknowledge to allow redelivery
                    Thread.currentThread().interrupt();
                }
                return null; // Proper return for Callable<Void>
            });
        } else {
            // For QoS 0 (or downgraded to 0), no need for special handling

           //Acknowledge message with QoS=0
            log.info("Tenant {} - Sending manual ack for MQTT message: topic: {}, QoS: {}, Connector {}",
                    tenant, mqttMessage.getTopic(), mqttMessage.getQos().ordinal(), connectorIdentifier);
            mqttMessage.acknowledge();

        }
    }

}