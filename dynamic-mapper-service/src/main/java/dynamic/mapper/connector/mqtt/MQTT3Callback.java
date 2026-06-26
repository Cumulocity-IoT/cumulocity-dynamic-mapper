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

import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.core.ConfigurationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * MQTT 3.1.1 broker callback. Delegates all shared ACK/reconnect/poison-pill logic
 * to {@link AbstractMqttCallback}; only supplies the four MQTT3-specific operations.
 */
@Slf4j
public class MQTT3Callback extends AbstractMqttCallback<Mqtt3Publish> {

    MQTT3Callback(String tenant, ConfigurationRegistry configurationRegistry,
            GenericMessageCallback callback, String connectorIdentifier, String connectorName,
            String clientId, Runnable reconnectTrigger) { // clientId unused in MQTT3 but kept for API symmetry
        super(tenant, configurationRegistry, callback, connectorIdentifier, connectorName, reconnectTrigger);
    }

    @Override
    protected String extractTopic(Mqtt3Publish message) {
        return String.join(TOPIC_LEVEL_SEPARATOR, message.getTopic().getLevels());
    }

    @Override
    protected byte[] extractPayload(Mqtt3Publish message) {
        return message.getPayload().map(bb -> {
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            return bytes;
        }).orElse(null);
    }

    @Override
    protected int extractQos(Mqtt3Publish message) {
        return message.getQos().getCode();
    }

    @Override
    protected ConnectorMessage buildConnectorMessage(Mqtt3Publish message, String topic, byte[] payload) {
        return ConnectorMessage.builder()
                .tenant(tenant)
                .topic(topic)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payload)
                .build();
    }

    @Override
    protected void acknowledgeMessage(Mqtt3Publish message) {
        message.acknowledge();
    }
}
