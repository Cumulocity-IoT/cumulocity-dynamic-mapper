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

package dynamic.mapper.core;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.joda.time.DateTime;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.event.EventFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.model.status.ConnectorStatusEvent;
import dynamic.mapper.model.status.ConnectorStatusHistory;
import dynamic.mapper.model.status.LoggingEventType;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes the Cumulocity events that report the state of the connectors: one Event per
 * connection-lifecycle session of a connector, and the status of the Notification 2.0 connection.
 * <p>
 * A session Event is created by the first status transition of a session and updated in place by the
 * later ones. A session left open by an earlier run of the connector is closed when the connector
 * starts again. All calls share the request semaphore of {@link C8YAgent}, so these events count
 * against the same limit of concurrent Cumulocity requests as the agent's other calls.
 */
@Slf4j
@Service
public class ConnectorStatusEventService {

    private static final String C8Y_NOTIFICATION_CONNECTOR = "C8YNotificationConnector";

    private final EventApi eventApi;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ContextService<MicroserviceCredentials> contextService;
    private final IMapperConfiguration mapperConfiguration;
    private final ObjectMapper objectMapper;
    private final TenantRegistry tenantRegistry;
    private final C8YAgent c8yAgent;

    // The last status reported for the notification connection of each tenant, so only a change is sent
    private final Map<String, ConnectorStatus> previousConnectorStatus = new ConcurrentHashMap<>();

    public ConnectorStatusEventService(EventApi eventApi, MicroserviceSubscriptionsService subscriptionsService,
            ContextService<MicroserviceCredentials> contextService, IMapperConfiguration mapperConfiguration,
            ObjectMapper objectMapper, TenantRegistry tenantRegistry, C8YAgent c8yAgent) {
        this.eventApi = eventApi;
        this.subscriptionsService = subscriptionsService;
        this.contextService = contextService;
        this.mapperConfiguration = mapperConfiguration;
        this.objectMapper = objectMapper;
        this.tenantRegistry = tenantRegistry;
        this.c8yAgent = c8yAgent;
    }

    /** Forgets the last notification status of a tenant that unsubscribed. */
    @EventListener
    public void onTenantUnsubscribed(MicroserviceSubscriptionRemovedEvent event) {
        previousConnectorStatus.remove(event.getTenant());
    }

    private Semaphore semaphore() {
        return c8yAgent.getC8ySemaphore();
    }

    /**
     * Creates the Event that opens a new connector connection-lifecycle session, carrying a
     * {@code d11r_connectorStatusLog} fragment with the session's first {@link ConnectorStatusHistory}
     * entry. Returns the created Event's id (synchronously) so the caller can remember it and
     * append further transitions of the same session via {@link #updateConnectorStatusEvent}
     * instead of creating a new, independent Event per status change.
     *
     * @return the created event's id, or {@code null} if creation failed (logged, not thrown —
     *         callers treat a missing id as "start a fresh session on the next transition").
     */
    public GId createConnectorStatusEvent(String message, String severity, DateTime eventTime, String tenant,
            Map<String, String> properties, ConnectorStatusHistory session) {
        if (TenantCredentials.isRevoked(tenant)) {
            log.debug("{} - Skipping connector status event: tenant unsubscribed", tenant);
            return null;
        }
        MapperServiceRepresentation source = mapperConfiguration.getMapperServiceRepresentation(tenant);
        return subscriptionsService.callForTenant(tenant, () -> {
            MicroserviceCredentials context = C8YAgent.removeAppKeyHeaderFromContext(contextService.getContext());
            return contextService.callWithinContext(context, () -> {
                EventRepresentation er = new EventRepresentation();
                ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
                mor.setId(new GId(source.getId()));
                er.setSource(mor);
                er.setDateTime(eventTime);
                er.setType(LoggingEventType.CONNECTOR_EVENT_TYPE.getType());
                applyConnectorStatusFragments(er, message, severity, properties, session);

                try {
                    semaphore().acquire();
                    EventRepresentation created = eventApi.create(er);
                    return created.getId();
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for creating connector status event", tenant, e);
                    return null;
                } catch (SDKException e) {
                    log.warn("{} - Failed to create connector status event: {}", tenant, e.getMessage());
                    return null;
                } finally {
                    semaphore().release();
                }
            });
        });
    }

    /**
     * Appends a further transition to an already-open connector connection-lifecycle session by
     * updating (PUT) the existing Event identified by {@code eventId} in place, instead of
     * creating a new Event — analogous to how a Cumulocity Operation accumulates its
     * "history of changes" via repeated PUTs to the same operation id.
     */
    public void updateConnectorStatusEvent(GId eventId, String message, String severity, DateTime eventTime,
            String tenant, Map<String, String> properties, ConnectorStatusHistory session) {
        if (TenantCredentials.isRevoked(tenant)) {
            return;
        }
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials context = C8YAgent.removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(context, () -> {
                EventRepresentation er = new EventRepresentation();
                er.setId(eventId);
                applyConnectorStatusFragments(er, message, severity, properties, session);

                try {
                    semaphore().acquire();
                    eventApi.update(er);
                } catch (InterruptedException e) {
                    log.error("{} - Failed to acquire semaphore for updating connector status event", tenant, e);
                } catch (SDKException e) {
                    log.warn("{} - Failed to update connector status event {}: {}",
                            tenant, eventId.getValue(), e.getMessage());
                } finally {
                    semaphore().release();
                }
            });
        });
    }

    private void applyConnectorStatusFragments(EventRepresentation er, String message, String severity,
            Map<String, String> properties, ConnectorStatusHistory session) {
        er.setText(message);
        if (properties != null) {
            er.setProperty(LoggingEventType.CONNECTOR_EVENT_TYPE.getComponent(), properties);
        }
        er.setProperty("d11r_connectorStatusLog", session);

        Map<String, String> metadata = Map.of(
            "component", LoggingEventType.CONNECTOR_EVENT_TYPE.getComponent(),
            "componentDisplayName", LoggingEventType.CONNECTOR_EVENT_TYPE.getComponentDisplayName(),
            "severity", severity != null ? severity : LoggingEventType.CONNECTOR_EVENT_TYPE.getSeverity(),
            "description", LoggingEventType.CONNECTOR_EVENT_TYPE.getDescription()
        );
        er.setProperty("d11r_metadata", metadata);
    }

    /** Bounds how many of the agent's most recent connector status events {@link
     * #closeOrphanedConnectorSession} scans looking for this connector's newest one. A session
     * normally has only a handful of entries and connectors don't reconnect constantly, so the
     * target event is almost always within the first few; this is just a safety cap against
     * scanning unboundedly on a tenant with many connectors sharing the same agent source. */
    private static final int ORPHAN_SESSION_SCAN_LIMIT = 100;

    /**
     * Closes a connection-lifecycle session left open (sessionClosed=false) by a connector's
     * PREVIOUS run, if one exists. Called once per {@link dynamic.mapper.connector.core.client.AConnectorClient}
     * construction (see {@code initializeManagers()}), before that run's first status transition.
     * <p>
     * The in-memory {@link dynamic.mapper.connector.core.client.ConnectionStateManager} always
     * starts with no active session — not just after a microservice restart, but also whenever a
     * connector is disabled and re-enabled (a fresh client instance is created either way, see
     * {@code ConnectorClientFactory}). If that previous run's last transition wasn't a clean
     * DISCONNECTED/FAILED (e.g. the process was killed mid-flap, CONNECTED -> RETRYING), the old
     * Event is left at {@code sessionClosed=false} forever, since nothing will ever append to it
     * again — it would otherwise keep showing up under "only open sessions" filtering (see
     * MappingServiceEventComponent) indefinitely, with no link to this new run's session.
     */
    public void closeOrphanedConnectorSession(String tenant, String connectorName, String connectorIdentifier) {
        MapperServiceRepresentation source = mapperConfiguration.getMapperServiceRepresentation(tenant);
        subscriptionsService.runForTenant(tenant, () -> {
            MicroserviceCredentials context = C8YAgent.removeAppKeyHeaderFromContext(contextService.getContext());
            contextService.runWithinContext(context, () -> {
                try {
                    EventFilter filter = new EventFilter()
                            .byType(LoggingEventType.CONNECTOR_EVENT_TYPE.getType())
                            .bySource(new GId(source.getId()));
                    List<EventRepresentation> events = new ArrayList<>();
                    for (EventRepresentation e : eventApi.getEventsByFilter(filter)
                            .get(ORPHAN_SESSION_SCAN_LIMIT).elements(ORPHAN_SESSION_SCAN_LIMIT)) {
                        events.add(e);
                    }
                    events.sort(Comparator.comparing(EventRepresentation::getDateTime).reversed());

                    for (EventRepresentation event : events) {
                        Object rawLog = event.getProperty("d11r_connectorStatusLog");
                        if (rawLog == null) {
                            continue;
                        }
                        ConnectorStatusHistory session = objectMapper.convertValue(rawLog, ConnectorStatusHistory.class);
                        if (!connectorIdentifier.equals(session.getConnectorIdentifier())) {
                            continue;
                        }
                        // Newest event for THIS connector found. Any earlier one can't possibly
                        // still be open (a session hard-closes before a later one opens), so this
                        // is the only candidate — close it if needed, then stop scanning either way.
                        if (!session.isSessionClosed()) {
                            ConnectorStatusEvent closing = new ConnectorStatusEvent(ConnectorStatus.DISCONNECTED);
                            closing.setConnectorName(connectorName);
                            closing.setConnectorIdentifier(connectorIdentifier);
                            closing.setMessage("Session ended: connector restarted before this session reached a terminal status");
                            session.append(closing);
                            session.setSessionClosed(true);

                            EventRepresentation update = new EventRepresentation();
                            update.setId(event.getId());
                            Map<String, String> statusMap = Map.ofEntries(
                                    entry("status", session.getCurrentStatus().name()),
                                    entry("message", closing.getMessage()),
                                    entry("connectorName", connectorName),
                                    entry("connectorIdentifier", connectorIdentifier));
                            applyConnectorStatusFragments(update, closing.getMessage(),
                                    ConnectorStatus.DISCONNECTED.toSeverity(), statusMap, session);

                            semaphore().acquire();
                            try {
                                eventApi.update(update);
                            } finally {
                                semaphore().release();
                            }
                            log.info("{} - Closed orphaned connector status session for connector {} (event {})",
                                    tenant, connectorIdentifier, event.getId().getValue());
                        }
                        return;
                    }
                } catch (Exception e) {
                    log.warn("{} - Failed to reconcile orphaned connector status session for connector {}: {}",
                            tenant, connectorIdentifier, e.getMessage());
                }
            });
        });
    }

    public void sendNotificationLifecycle(String tenant, ConnectorStatus connectorStatus, String message) {
        if (TenantCredentials.isRevoked(tenant)) {
            return;
        }
        if (tenantRegistry.getServiceConfiguration(tenant).getSendNotificationLifecycle()
                && !connectorStatus.equals(previousConnectorStatus.get(tenant))) {
            previousConnectorStatus.put(tenant, connectorStatus);
            Map<String, String> stMap = Map.ofEntries(
                    entry("status", connectorStatus.name()),
                    entry("message",
                            message == null ? C8Y_NOTIFICATION_CONNECTOR + ": " + connectorStatus.name() : message),
                    entry("connectorName", C8Y_NOTIFICATION_CONNECTOR),
                    entry("connectorIdentifier", "000000"));
            c8yAgent.createLoggingEvent(String.format("Connector status: %s", connectorStatus.name()),
                    LoggingEventType.NOTIFICATION_EVENT_TYPE, connectorStatus.toSeverity(), DateTime.now(),
                    tenant,
                    stMap);
        }
    }
}
