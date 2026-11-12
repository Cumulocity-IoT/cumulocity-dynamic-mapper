/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import java.util.function.Predicate;

import dynamic.mapper.model.Qos;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides when an explorer session may subscribe or unsubscribe a broker topic, extracted out of
 * {@link AConnectorClient}. A topic that a mapping already subscribes is never subscribed twice and
 * never unsubscribed while the mapping still needs it. The broker calls themselves are supplied by
 * the connector, so this logic is testable without a connector instance.
 */
@Slf4j
class ExplorerSubscriptionHandler {

    /** Broker subscribe call that may throw {@link ConnectorException}. */
    @FunctionalInterface
    interface SubscribeAction {
        void subscribe(String topic, Qos qos) throws ConnectorException;
    }

    /** Broker unsubscribe call that may throw {@link ConnectorException}. */
    @FunctionalInterface
    interface UnsubscribeAction {
        void unsubscribe(String topic) throws ConnectorException;
    }

    private final String tenant;
    private final String connectorName;
    private final Predicate<String> subscribedByMapping;
    private final SubscribeAction subscribeAction;
    private final UnsubscribeAction unsubscribeAction;

    ExplorerSubscriptionHandler(String tenant, String connectorName, Predicate<String> subscribedByMapping,
            SubscribeAction subscribeAction, UnsubscribeAction unsubscribeAction) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.subscribedByMapping = subscribedByMapping;
        this.subscribeAction = subscribeAction;
        this.unsubscribeAction = unsubscribeAction;
    }

    /**
     * Subscribe to a topic on behalf of an explorer session. Best-effort.
     *
     * @return {@code null} on success, including the no-op cases (a mapping already subscribes the
     *         topic, or the connector does not support subscriptions at all, e.g. WebHook); otherwise
     *         a human-readable warning message so callers can surface the failure.
     */
    String subscribe(String topic) {
        try {
            if (subscribedByMapping.test(topic)) {
                log.debug("{} - Explorer topic [{}] already subscribed via mapping — skipping duplicate subscribe", tenant, topic);
                return null;
            }
            subscribeAction.subscribe(topic, Qos.AT_LEAST_ONCE);
            log.info("{} - Explorer subscribed to topic: [{}] on connector: {}", tenant, topic, connectorName);
            return null;
        } catch (jakarta.ws.rs.NotSupportedException e) {
            // Some connectors (HTTP, WebHook) don't support topic subscriptions at all — that's fine
            log.debug("{} - Explorer topic subscription not applicable for connector {}: {}", tenant, connectorName, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("{} - Explorer topic subscription failed for connector {}: {}", tenant, connectorName, e.getMessage());
            return e.getMessage();
        }
    }

    /** Unsubscribe a topic an explorer session subscribed, unless an active mapping still uses it. */
    void unsubscribe(String topic) {
        try {
            if (!subscribedByMapping.test(topic)) {
                unsubscribeAction.unsubscribe(topic);
                log.info("{} - Explorer unsubscribed from topic: [{}] on connector: {}", tenant, topic, connectorName);
            } else {
                log.debug("{} - Kept broker subscription for topic [{}] — still used by a mapping", tenant, topic);
            }
        } catch (Exception e) {
            log.debug("{} - Explorer topic unsubscription not applicable for connector {}: {}", tenant, connectorName, e.getMessage());
        }
    }
}
