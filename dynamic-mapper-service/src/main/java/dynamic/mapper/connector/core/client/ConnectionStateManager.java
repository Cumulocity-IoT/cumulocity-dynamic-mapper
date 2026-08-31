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

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.model.ConnectorStatusEvent;
import dynamic.mapper.model.ConnectorStatusHistory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Manages connection state and status transitions for a connector.
 * Thread-safe state management with lifecycle callbacks.
 */
@Slf4j
public class ConnectionStateManager {
    
    private final String tenant;
    private final String connectorName;
    private final String connectorIdentifier;
    private final MutableBoolean connectionState = new MutableBoolean(false);
    
    @Getter
    private final AtomicReference<ConnectorStatusEvent> connectorStatus;
    
    private ConnectorStatus previousStatus = ConnectorStatus.UNKNOWN;

    /** Statuses that start a new connection-lifecycle session (new Event) rather than
     * appending to the currently open one (updated Event). */
    private static final Set<ConnectorStatus> SESSION_OPENING_STATUSES =
            Set.of(ConnectorStatus.CONNECTING, ConnectorStatus.DISCONNECTING);

    /** Statuses that close a session. Re-evaluated on every append (not sticky), so a session
     * can re-open (e.g. CONNECTED -> RETRYING) without starting a new Event. */
    private static final Set<ConnectorStatus> SESSION_TERMINAL_STATUSES =
            Set.of(ConnectorStatus.CONNECTED, ConnectorStatus.DISCONNECTED, ConnectorStatus.FAILED);

    /** Of the terminal statuses, only these are a HARD close: once a session ends on
     * DISCONNECTED/FAILED, nothing may append to it again, regardless of what status comes
     * next (e.g. routine housekeeping setting CONFIGURED after a dropped connection must not
     * silently re-open the old session — it isn't a continuation of that connect attempt).
     * CONNECTED is deliberately excluded: RETRYING legitimately follows a CONNECTED session
     * (subscription-init retry happens after the physical connection already succeeded), so
     * CONNECTED is allowed to re-open into CONNECTED -> RETRYING -> CONNECTED, one session. */
    private static final Set<ConnectorStatus> SESSION_HARD_CLOSING_STATUSES =
            Set.of(ConnectorStatus.DISCONNECTED, ConnectorStatus.FAILED);

    private final AtomicReference<ConnectorStatusHistory> activeSession = new AtomicReference<>();

    /** Called on every status change that fires (see {@link #notifyStatusChange}), with the
     * accumulated session and whether this transition just opened a new one. */
    private final BiConsumer<ConnectorStatusHistory, Boolean> statusChangeCallback;

    private ConnectorRegistry connectorRegistry;

    public ConnectionStateManager(String tenant,
                                 String connectorName,
                                 String connectorIdentifier,
                                 BiConsumer<ConnectorStatusHistory, Boolean> statusChangeCallback,
                                 ConnectorRegistry connectorRegistry) {
        this.tenant = tenant;
        this.connectorName = connectorName;
        this.connectorIdentifier = connectorIdentifier;
        this.statusChangeCallback = statusChangeCallback;
        this.connectorRegistry = connectorRegistry;
        this.connectorStatus = new AtomicReference<>(
            ConnectorStatusEvent.unknown(connectorName, connectorIdentifier));
    }
    
    public boolean isConnected() {
        return connectionState.booleanValue();
    }
    
    public void setConnected(boolean connected) {
        setConnected(connected, null);
    }

    /**
     * @param cause when {@code connected} is false, the exception that caused the disconnect
     *              (if known). Its message is attached to the DISCONNECTED event so the UI shows
     *              the actual reason (e.g. "UnknownHostException: broker.example.com") instead of
     *              a bare status name. Pass {@code null} when no cause is available.
     */
    public void setConnected(boolean connected, Throwable cause) {
        boolean wasConnected = connectionState.booleanValue();
        connectionState.setValue(connected);

        if (wasConnected != connected) {
            log.info("{} - Connection state changed: {} -> {} for connector: {}",
                    tenant, wasConnected, connected, connectorName);

            if (connected) {
                updateStatus(ConnectorStatus.CONNECTED, true, true);
            } else if (cause != null) {
                updateStatus(ConnectorStatus.DISCONNECTED, buildErrorMessage(cause), true);
            } else {
                updateStatus(ConnectorStatus.DISCONNECTED, true, true);
            }
        }
    }

    public void updateStatus(ConnectorStatus status, boolean clearMessage, boolean sendEvent) {
        updateStatus(status, clearMessage ? null : connectorStatus.get().getMessage(), sendEvent);
    }

    private void updateStatus(ConnectorStatus status, String message, boolean sendEvent) {
        // Suppress reporting a DISCONNECTING transition when there's no live connection to tear
        // down in the first place — e.g. reconnect()'s disconnect-initialize-connect sequence
        // runs its defensive disconnect() step even for a connector that was never connected,
        // which would otherwise open a spurious session for a no-op. DISCONNECTED already gets
        // the same treatment for free via the wasConnected check in setConnected() (it simply
        // never fires when nothing was connected); DISCONNECTING has no such guard of its own
        // since callers report it directly via updateStatus(), not through setConnected().
        if (status == ConnectorStatus.DISCONNECTING && !connectionState.booleanValue()) {
            sendEvent = false;
        }

        // CONFIGURED is never itself a connect/disconnect attempt or outcome — it's a settle/idle
        // marker meaning "config is valid, nothing happening right now", fired by housekeeping's
        // periodic check or by submitInitialize(). Reporting it as its own lifecycle event/session
        // is pure noise (it can't attach cleanly to a hard-closed session without special-casing,
        // and stranding it as its own bundle produces a low-signal extra row on every disconnect/
        // reconnect cycle). The live status below (connectorStatus / registry map, used by the
        // monitoring UI's real-time badge) still updates regardless — only the persisted
        // Event/session reporting is skipped.
        if (status == ConnectorStatus.CONFIGURED) {
            sendEvent = false;
        }

        // Copy-on-write: create a new event instead of mutating the shared object,
        // so concurrent readers never see a partially-updated state.
        ConnectorStatusEvent newStatus = new ConnectorStatusEvent(status);
        newStatus.connectorName = connectorName;
        newStatus.connectorIdentifier = connectorIdentifier;
        if (message != null) {
            newStatus.setMessage(message);
        }
        connectorStatus.set(newStatus);

        Map<String, ConnectorStatusEvent> statusMap = connectorRegistry.getConnectorStatusMap(tenant);
        if (statusMap != null) {
            statusMap.put(connectorIdentifier, newStatus);
        } else {
            log.warn("{} - Status map not initialised for tenant, skipping registry update (connector: {})",
                    tenant, connectorIdentifier);
        }

        if (sendEvent && !status.equals(previousStatus)) {
            previousStatus = status;
            notifyStatusChange(newStatus);
        }
    }

    public void updateStatusWithError(Exception e) {
        // Copy-on-write: create a new event instead of mutating the shared object.
        ConnectorStatusEvent newStatus = new ConnectorStatusEvent(ConnectorStatus.FAILED);
        newStatus.connectorName = connectorName;
        newStatus.connectorIdentifier = connectorIdentifier;
        newStatus.setMessage(buildErrorMessage(e));
        connectorStatus.set(newStatus);

        Map<String, ConnectorStatusEvent> statusMap = connectorRegistry.getConnectorStatusMap(tenant);
        if (statusMap != null) {
            statusMap.put(connectorIdentifier, newStatus);
        }

        if (!ConnectorStatus.FAILED.equals(previousStatus)) {
            previousStatus = ConnectorStatus.FAILED;
            notifyStatusChange(newStatus);
        }
    }
    
    /**
     * Marks the connector as RETRYING after a transient error (e.g. platform 502/503/504)
     * delayed subscription/mapping initialization. Unlike {@link #updateStatusWithError}, this
     * does not move the connector to FAILED, since the underlying connection is still alive and
     * a retry is already scheduled.
     */
    public void updateStatusRetrying(Exception e, long nextRetryDelaySeconds) {
        ConnectorStatusEvent newStatus = new ConnectorStatusEvent(ConnectorStatus.RETRYING);
        newStatus.connectorName = connectorName;
        newStatus.connectorIdentifier = connectorIdentifier;
        newStatus.setMessage(buildErrorMessage(e) + String.format(" --- Retrying in %ds", nextRetryDelaySeconds));
        connectorStatus.set(newStatus);

        Map<String, ConnectorStatusEvent> statusMap = connectorRegistry.getConnectorStatusMap(tenant);
        if (statusMap != null) {
            statusMap.put(connectorIdentifier, newStatus);
        }

        if (!ConnectorStatus.RETRYING.equals(previousStatus)) {
            previousStatus = ConnectorStatus.RETRYING;
            notifyStatusChange(newStatus);
        }
    }

    private String buildErrorMessage(Throwable e) {
        StringBuilder messageBuilder = new StringBuilder()
                .append(" --- ")
                .append(e.getClass().getName())
                .append(": ")
                .append(e.getMessage());
        
        Optional.ofNullable(e.getCause()).ifPresent(cause ->
                messageBuilder.append(" --- Caused by ")
                        .append(cause.getClass().getName())
                        .append(": ")
                        .append(cause.getMessage()));
        
        return messageBuilder.toString();
    }
    
    private void notifyStatusChange(ConnectorStatusEvent status) {
        if (statusChangeCallback != null) {
            try {
                boolean isNewSession = updateSession(status);
                statusChangeCallback.accept(activeSession.get(), isNewSession);
            } catch (Exception e) {
                log.error("{} - Error in status change callback: {}", tenant, e.getMessage(), e);
            }
        }
    }

    /**
     * Assembles the connection-lifecycle session this status transition belongs to: opens a new
     * one (fresh {@link ConnectorStatusHistory}) on {@link #SESSION_OPENING_STATUSES}, when none
     * is active yet (e.g. after a restart), or when the active session already ended on a
     * {@link #SESSION_HARD_CLOSING_STATUSES} status — otherwise appends to the currently active
     * session. {@code sessionClosed} is re-evaluated (not sticky) on every append, so a session
     * that reached CONNECTED can still re-open — e.g. CONNECTED -> RETRYING -> CONNECTED stays
     * one session — but DISCONNECTED/FAILED are a hard stop: nothing appends after those,
     * regardless of what status comes next (e.g. routine housekeeping setting CONFIGURED after a
     * dropped connection starts a new session rather than reviving the old one).
     *
     * @return {@code true} if this transition just opened a new session
     */
    private boolean updateSession(ConnectorStatusEvent status) {
        ConnectorStatusHistory activeBefore = activeSession.get();
        boolean opensNewSession = activeBefore == null
                || SESSION_OPENING_STATUSES.contains(status.getStatus())
                || (activeBefore.isSessionClosed()
                        && SESSION_HARD_CLOSING_STATUSES.contains(activeBefore.getCurrentStatus()));

        ConnectorStatusHistory session = opensNewSession ? new ConnectorStatusHistory() : activeBefore;
        if (opensNewSession) {
            session.setConnectorName(connectorName);
            session.setConnectorIdentifier(connectorIdentifier);
        }
        session.append(status);
        session.setSessionClosed(SESSION_TERMINAL_STATUSES.contains(status.getStatus()));
        activeSession.set(session);

        return opensNewSession;
    }
    
    public ConnectorStatus getCurrentStatus() {
        return connectorStatus.get().getStatus();
    }
    
    public String getCurrentMessage() {
        return connectorStatus.get().getMessage();
    }
}
