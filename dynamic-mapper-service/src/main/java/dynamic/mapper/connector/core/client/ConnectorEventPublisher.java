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

package dynamic.mapper.connector.core.client;

import static java.util.Map.entry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.joda.time.DateTime;

import com.cumulocity.model.idtype.GId;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConnectorStatusEventService;
import dynamic.mapper.model.status.ConnectorStatusEvent;
import dynamic.mapper.model.status.ConnectorStatusHistory;
import dynamic.mapper.model.status.LoggingEventType;

/**
 * Writes the Cumulocity events that report what a connector does: its connection-lifecycle sessions and
 * its topic subscriptions. Each is sent only if the tenant's service configuration enables it.
 * <p>
 * All transitions of one connection-lifecycle session (for example CONNECTING to CONNECTED, with RETRYING
 * in between) share one Event: the first transition creates it, later ones update it in place, the way a
 * Cumulocity Operation accumulates its history under one record. One instance belongs to one connector
 * client, because it remembers the Event of the open session.
 */
final class ConnectorEventPublisher {

    private final String tenant;
    private final String connectorName;
    private final String connectorIdentifier;
    private final Supplier<C8YAgent> c8yAgent;
    private final Supplier<ConnectorStatusEventService> statusEvents;
    private final Supplier<ServiceConfiguration> serviceConfiguration;

    // Id of the Event backing the currently open session, so further transitions of the same session
    // are appended (PUT) to it instead of creating an independent Event per status change.
    private final AtomicReference<GId> activeStatusEventId = new AtomicReference<>();

    ConnectorEventPublisher(String tenant, String connectorName, String connectorIdentifier,
            Supplier<C8YAgent> c8yAgent, Supplier<ConnectorStatusEventService> statusEvents,
            Supplier<ServiceConfiguration> serviceConfiguration) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.connectorIdentifier = connectorIdentifier;
        this.c8yAgent = c8yAgent;
        this.statusEvents = statusEvents;
        this.serviceConfiguration = serviceConfiguration;
    }

    /** Reports a transition of a connection-lifecycle session. */
    void sendConnectorLifecycle(ConnectorStatusHistory session, boolean isNewSession) {
        if (!serviceConfiguration.get().getSendConnectorLifecycle()) {
            return;
        }
        Map<String, String> statusMap = createStatusMap(session);
        String message = statusMap.get("message");
        String severity = session.getCurrentStatus().toSeverity();

        // Defensive fallback: also (re-)create if no event id is on record even though the
        // session isn't new (e.g. the id was lost across a microservice restart mid-session).
        GId eventId = isNewSession ? null : activeStatusEventId.get();
        if (eventId == null) {
            eventId = statusEvents.get().createConnectorStatusEvent(message, severity, DateTime.now(), tenant,
                    statusMap, session);
            activeStatusEventId.set(eventId);
        } else {
            statusEvents.get().updateConnectorStatusEvent(eventId, message, severity, DateTime.now(), tenant,
                    statusMap, session);
        }
    }

    /** Reports that a topic was subscribed or unsubscribed. */
    void sendSubscriptionEvent(String topic, String action) {
        if (!serviceConfiguration.get().getSendSubscriptionEvents()) {
            return;
        }
        String message = String.format("%s topic: %s", action, topic);
        c8yAgent.get().createLoggingEvent(
                message,
                LoggingEventType.SUBSCRIPTION_EVENT_TYPE,
                DateTime.now(),
                tenant,
                Map.ofEntries(
                        entry("message", message),
                        entry("connectorName", connectorName),
                        entry("connectorIdentifier", connectorIdentifier)));
    }

    private Map<String, String> createStatusMap(ConnectorStatusHistory session) {
        List<ConnectorStatusEvent> history = session.getHistory();
        ConnectorStatusEvent latest = history.get(history.size() - 1);
        String message = latest.getMessage();
        if (message == null || "".equals(message)) {
            message = String.format("Connector status: %s", session.getCurrentStatus());
        }
        return Map.ofEntries(
                entry("status", session.getCurrentStatus().name()),
                entry("message", message),
                entry("connectorName", connectorName),
                entry("connectorIdentifier", connectorIdentifier));
    }
}
