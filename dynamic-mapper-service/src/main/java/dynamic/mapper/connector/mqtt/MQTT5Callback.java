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

package dynamic.mapper.connector.mqtt;

import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * MQTT 5 broker callback. Delegates all shared ACK/reconnect/poison-pill logic
 * to {@link AbstractMqttCallback}; adds MQTT5-specific client-ID extraction from user properties.
 */
@Slf4j
public class MQTT5Callback extends AbstractMqttCallback<Mqtt5Publish> {

    MQTT5Callback(String tenant, ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName,
            Runnable reconnectTrigger) {
        super(tenant, configurationRegistry, callback, connectorIdentifier, connectorName, reconnectTrigger);
    }

    @Override
    protected String extractTopic(Mqtt5Publish message) {
        return String.join(TOPIC_LEVEL_SEPARATOR, message.getTopic().getLevels());
    }

    @Override
    protected byte[] extractPayload(Mqtt5Publish message) {
        return message.getPayload().map(bb -> {
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            return bytes;
        }).orElse(null);
    }

    @Override
    protected int extractQos(Mqtt5Publish message) {
        return message.getQos().getCode();
    }

    @Override
    protected ConnectorMessage buildConnectorMessage(Mqtt5Publish message, String topic, byte[] payload) {
        // MQTT5 user properties may carry the publisher's client ID
        String publisherClientId = message.getUserProperties().asList().stream()
                .filter(p -> "clientId".equals(p.getName().toString()))
                .map(p -> p.getValue().toString())
                .findFirst()
                .orElse(null);
        if (publisherClientId != null && serviceConfiguration.getLogPayload()) {
            log.info("{} - Publisher client ID from user properties: {}", tenant, publisherClientId);
        }
        return ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .clientId(publisherClientId)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payload)
                .build();
    }

    @Override
    protected void acknowledgeMessage(Mqtt5Publish message) {
        message.acknowledge();
    }
}
