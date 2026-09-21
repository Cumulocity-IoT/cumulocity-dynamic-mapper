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
 */

package dynamic.mapper.connector.core.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.model.status.ConnectorStatusEvent;
import dynamic.mapper.model.status.ConnectorStatusHistory;

/**
 * Unit tests for {@link ConnectionStateManager}'s connection-lifecycle session bundling — the
 * state machine behind the "Details & logs" timeline's grouped rows (see
 * {@link ConnectionStateManager#updateSession}). Covers the previously untested pieces: which
 * transitions open a new session vs. append to the active one, the CONNECTED -> RETRYING ->
 * CONNECTED re-open case, the DISCONNECTED/FAILED hard-close, same-status dedup, and the
 * CONFIGURED/no-op-DISCONNECTING suppression.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectionStateManagerTest {

    private static final String TENANT = "test-tenant";
    private static final String CONNECTOR_NAME = "test-connector";
    private static final String CONNECTOR_IDENTIFIER = "conn_1";

    @Mock
    private ConnectorRegistry connectorRegistry;

    private Map<String, ConnectorStatusEvent> statusMap;
    private List<ConnectorStatusHistory> notifiedSessions;
    private List<Boolean> notifiedIsNewSession;
    private ConnectionStateManager manager;

    @BeforeEach
    void setUp() {
        statusMap = new HashMap<>();
        when(connectorRegistry.getConnectorStatusMap(TENANT)).thenReturn(statusMap);

        notifiedSessions = new ArrayList<>();
        notifiedIsNewSession = new ArrayList<>();

        manager = new ConnectionStateManager(TENANT, CONNECTOR_NAME, CONNECTOR_IDENTIFIER,
                (session, isNewSession) -> {
                    notifiedSessions.add(session);
                    notifiedIsNewSession.add(isNewSession);
                },
                connectorRegistry);
    }

    // ── opening / appending ─────────────────────────────────────────────────────────────

    @Test
    void firstTransition_opensNewSession() {
        manager.updateStatus(ConnectorStatus.CONNECTING, true, true);

        assertEquals(1, notifiedSessions.size());
        assertTrue(notifiedIsNewSession.get(0));
        assertEquals(1, notifiedSessions.get(0).getHistory().size());
        assertEquals(ConnectorStatus.CONNECTING, notifiedSessions.get(0).getCurrentStatus());
    }

    @Test
    void connectingThenConnected_appendsToSameSession() {
        manager.updateStatus(ConnectorStatus.CONNECTING, true, true);
        manager.setConnected(true);

        assertEquals(2, notifiedSessions.size());
        assertTrue(notifiedIsNewSession.get(0), "CONNECTING opens the session");
        assertFalse(notifiedIsNewSession.get(1), "CONNECTED appends to the same session");
        assertSame(notifiedSessions.get(0), notifiedSessions.get(1), "same session object throughout");
        assertEquals(2, notifiedSessions.get(1).getHistory().size());
        assertTrue(notifiedSessions.get(1).isSessionClosed(), "CONNECTED is a terminal status");
    }

    @Test
    void connectedThenRetryingThenConnected_staysOneSession() {
        manager.updateStatus(ConnectorStatus.CONNECTED, true, true);
        manager.updateStatusRetrying(new Exception("boom"), 10);
        manager.updateStatus(ConnectorStatus.CONNECTED, true, true);

        assertEquals(3, notifiedSessions.size());
        assertTrue(notifiedIsNewSession.get(0));
        assertFalse(notifiedIsNewSession.get(1), "RETRYING re-opens the same CONNECTED session");
        assertFalse(notifiedIsNewSession.get(2), "CONNECTED again still the same session");
        assertSame(notifiedSessions.get(0), notifiedSessions.get(2));

        ConnectorStatusHistory finalSession = notifiedSessions.get(2);
        assertEquals(3, finalSession.getHistory().size());
        assertTrue(finalSession.isSessionClosed());
        assertTrue(finalSession.isHadError(), "RETRYING makes the session sticky hadError=true");
    }

    // ── hard close ───────────────────────────────────────────────────────────────────────

    @Test
    void disconnectedSession_hardCloses_nextTransitionOpensNewSession() {
        manager.updateStatus(ConnectorStatus.CONNECTED, true, true);
        manager.updateStatus(ConnectorStatus.DISCONNECTED, true, true); // appended to same session (not a hard-closing *incoming* status)
        manager.updateStatus(ConnectorStatus.CONNECTED, true, true); // reconnect

        assertEquals(3, notifiedSessions.size());
        assertFalse(notifiedIsNewSession.get(1), "DISCONNECTED itself still folds into the session that just connected");
        assertTrue(notifiedSessions.get(1).isSessionClosed());
        assertTrue(notifiedIsNewSession.get(2), "next transition after a DISCONNECTED-closed session must start a new one");
        assertNotSame(notifiedSessions.get(1), notifiedSessions.get(2));
        assertEquals(1, notifiedSessions.get(2).getHistory().size());
    }

    @Test
    void failedSession_hardCloses_nextTransitionOpensNewSession() {
        manager.updateStatus(ConnectorStatus.CONNECTING, true, true);
        manager.updateStatusWithError(new Exception("boom"));
        manager.updateStatus(ConnectorStatus.CONNECTING, true, true);

        assertEquals(3, notifiedSessions.size());
        assertTrue(notifiedSessions.get(1).isSessionClosed());
        assertEquals(ConnectorStatus.FAILED, notifiedSessions.get(1).getCurrentStatus());
        assertTrue(notifiedIsNewSession.get(2), "CONNECTING after a FAILED-closed session opens a new one");
    }

    // ── dedup ────────────────────────────────────────────────────────────────────────────

    @Test
    void repeatedIdenticalStatus_isDeduped_doesNotReNotify() {
        manager.updateStatus(ConnectorStatus.CONNECTED, true, true);
        manager.updateStatus(ConnectorStatus.CONNECTED, true, true);

        assertEquals(1, notifiedSessions.size(), "second identical status must be a no-op");
        assertEquals(1, notifiedSessions.get(0).getHistory().size());
    }

    @Test
    void setConnected_calledTwiceWithSameValue_isNoOp() {
        manager.setConnected(true);
        manager.setConnected(true);

        assertEquals(1, notifiedSessions.size());
    }

    // ── suppressed transitions ──────────────────────────────────────────────────────────

    @Test
    void configuredStatus_isNeverReported() {
        manager.updateStatus(ConnectorStatus.CONFIGURED, true, true);

        assertTrue(notifiedSessions.isEmpty());
        // the live status/registry map still updates even though nothing is persisted
        assertEquals(ConnectorStatus.CONFIGURED, manager.getCurrentStatus());
        assertEquals(ConnectorStatus.CONFIGURED, statusMap.get(CONNECTOR_IDENTIFIER).getStatus());
    }

    @Test
    void disconnecting_withNoLiveConnection_isSuppressed() {
        // connectionState starts false; a defensive disconnect() call reporting DISCONNECTING
        // must not open a spurious session for a no-op.
        manager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        assertTrue(notifiedSessions.isEmpty());
    }

    @Test
    void disconnecting_withLiveConnection_isReported() {
        manager.setConnected(true);
        manager.updateStatus(ConnectorStatus.DISCONNECTING, true, true);

        assertEquals(2, notifiedSessions.size());
    }

    // ── live status / registry side effects ─────────────────────────────────────────────

    @Test
    void updateStatus_updatesCurrentStatusAndRegistryMap_regardlessOfSendEvent() {
        manager.updateStatus(ConnectorStatus.FAILED, true, false);

        assertEquals(ConnectorStatus.FAILED, manager.getCurrentStatus());
        assertEquals(ConnectorStatus.FAILED, statusMap.get(CONNECTOR_IDENTIFIER).getStatus());
        assertTrue(notifiedSessions.isEmpty(), "sendEvent=false must still suppress the callback");
    }
}
