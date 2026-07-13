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

package dynamic.mapper.connector.core.client;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Reusable pub/sub registry for explorer sessions that want to observe every raw message flowing
 * through a connector (inbound or outbound), independent of any mapping/topic subscription.
 * {@link AConnectorClient} owns one instance for inbound messages and one for outbound messages.
 */
@Slf4j
public class ExplorerListenerRegistry {

    private final String tenant;
    private final String direction;
    private final CopyOnWriteArrayList<Consumer<ConnectorMessage>> listeners = new CopyOnWriteArrayList<>();

    public ExplorerListenerRegistry(String tenant, String direction) {
        this.tenant = tenant;
        this.direction = direction;
    }

    /** Register a listener that receives every {@link ConnectorMessage}. */
    public void add(Consumer<ConnectorMessage> listener) {
        listeners.add(listener);
    }

    /** Remove a previously registered listener. */
    public void remove(Consumer<ConnectorMessage> listener) {
        listeners.remove(listener);
    }

    /** Notify all registered listeners. Exceptions from individual listeners are logged and swallowed. */
    public void notifyListeners(ConnectorMessage message) {
        for (Consumer<ConnectorMessage> listener : listeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.warn("{} - {} explorer listener error on topic [{}]: {}", tenant, direction, message.getTopic(), e.getMessage());
            }
        }
    }
}
