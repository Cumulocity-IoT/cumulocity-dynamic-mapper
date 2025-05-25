/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

package dynamic.mapping.connector.kafka;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TopicConsumerCallback implements TopicConsumerListener {
    GenericMessageCallback genericMessageCallback;
    String tenant;
    String topic;
    String connectorIdentifier;
    boolean supportsMessageContext;

    TopicConsumerCallback(GenericMessageCallback callback, String tenant, String connectorIdentifier, String topic,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.topic = topic;
        this.connectorIdentifier = connectorIdentifier;
        this.supportsMessageContext = supportsMessageContext;
    }

    @Override
    public void onEvent(byte[] key, byte[] event) throws Exception {
        ConnectorMessage connectorMessage =  ConnectorMessage.builder()
        .tenant(tenant)
        .supportsMessageContext(supportsMessageContext)
        .topic(topic)
        .sendPayload(true)
        .connectorIdentifier(connectorIdentifier)
        .key(key)
        .payload(event)
        .build();
        genericMessageCallback.onMessage(connectorMessage);
    }

    @Override
    public void onStarted() {
        log.info("{} - Called method Called method 'onStarted'", tenant);
    }

    @Override
    public void onStoppedByErrorAndReconnecting(Exception error) {
        log.error("{} - Called method 'onStoppedByErrorAndReconnecting'",tenant,  error);

    }

    @Override
    public void onStopped() {
        log.info("{} - Called method 'onStopped'", tenant);
    }

}
