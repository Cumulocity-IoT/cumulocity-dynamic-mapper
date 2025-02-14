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

package dynamic.mapping.connector.webhook;

import java.util.function.Consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;

public class WebhookCallback implements Consumer<Mqtt3Publish> {
    GenericMessageCallback genericMessageCallback;
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);
    String tenant;
    String connectorIdentifier;
    boolean supportsMessageContext;

    WebhookCallback(GenericMessageCallback callback, String tenant, String connectorIdentifier,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.supportsMessageContext = supportsMessageContext;
    }

    @Override
    public void accept(Mqtt3Publish mqttMessage) {
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        // if (mqttMessage.getPayload().isPresent()) {
        // ByteBuffer byteBuffer = mqttMessage.getPayload().get();
        // byte[] byteArray = new byte[byteBuffer.remaining()];
        // byteBuffer.get(byteArray);
        // connectorMessage.setPayload(byteArray);
        // }
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
        genericMessageCallback.onMessage(connectorMessage);
    }

}