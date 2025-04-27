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
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.model.QOS;
import dynamic.mapping.processor.model.ProcessingContext;

public class MQTTCallback implements Consumer<Mqtt3Publish> {
    GenericMessageCallback genericMessageCallback;
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);
    String tenant;
    String connectorIdentifier;
    boolean supportsMessageContext;
    QOS qos;
    private ExecutorService virtualThreadPool;

    MQTTCallback(ConfigurationRegistry configurationRegistry, GenericMessageCallback callback, String tenant, String connectorIdentifier,
            boolean supportsMessageContext, QOS qos) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.supportsMessageContext = supportsMessageContext;
        this.qos = qos;
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

        connectorMessage.setSupportsMessageContext(supportsMessageContext);

        // Only manually handle acknowledgment for QoS 1 or 2
        if (mqttMessage.getQos().getCode() > 0) {
            Future<List<ProcessingContext<?>>> processedResults = genericMessageCallback.onMessage(connectorMessage);

            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete
                    List<ProcessingContext<?>> results = processedResults.get();

                    // Check for errors in results
                    boolean hasErrors = false;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            if (context.hasError()) {
                                hasErrors = true;
                                break;
                            }
                        }
                    }

                    if (!hasErrors) {
                        // No errors found, acknowledge the message
                        mqttMessage.acknowledge();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed, don't acknowledge to allow redelivery
                    Thread.currentThread().interrupt();
                }
                return null; // Return value needed for submit() method
            });
        } else {
            // For QoS 0, just process the message and acknowledgment
            genericMessageCallback.onMessage(connectorMessage);
            mqttMessage.acknowledge();
        }
    }

}