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
 *  @authors Christof Strack
 *
 */

package dynamic.mapper.service;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.model.ExplorerMessage;
import dynamic.mapper.model.ExplorerSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * Manages Message Explorer sessions.
 *
 * <p>Each session registers a listener on the targeted inbound {@link AConnectorClient}.
 * On every inbound message the listener appends an {@link ExplorerMessage} to the session's
 * bounded deque (capped at {@code maxMessages}).
 *
 * <p>A {@link Scheduled} watchdog expires sessions that have not been polled within
 * {@code SESSION_TTL_MS}. The TTL is intentionally generous so that sessions survive a
 * reasonable polling gap (default: 2 × 30 s = 60 s).
 */
@Slf4j
@Service
public class ExplorerService {

    /**
     * Session TTL in milliseconds: a session is automatically removed if the UI has not
     * polled within this window. Default = 2 × WATCHDOG_INTERVAL_MS.
     */
    static final long SESSION_TTL_MS = 60_000L;        // 60 seconds

    /** How often the TTL watchdog runs. */
    static final long WATCHDOG_INTERVAL_MS = 30_000L;  // 30 seconds

    static final int DEFAULT_MAX_MESSAGES = 50;

    @Autowired
    private ConnectorRegistry connectorRegistry;

    /**
     * sessions: tenant → (sessionId → ExplorerSession)
     * explorerListeners: sessionId → Consumer registered on the AConnectorClient
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ExplorerSession>> sessions
            = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Consumer<ConnectorMessage>> listenerRegistry
            = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start a new explorer session for the given connector + topic.
     *
     * @param tenant              tenant identifier
     * @param connectorIdentifier identifier of the inbound connector to listen on
     * @param topic               topic (may include wildcards if the connector supports them)
     * @param maxMessages         maximum messages to buffer (1–500); defaults to 50
     * @return the new session id
     * @throws ConnectorRegistryException if the connector is not registered for the tenant
     */
    public String startSession(String tenant, String connectorIdentifier, String topic, int maxMessages)
            throws ConnectorRegistryException {

        AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);

        int cappedMax = Math.max(1, Math.min(500, maxMessages > 0 ? maxMessages : DEFAULT_MAX_MESSAGES));

        String sessionId = UUID.randomUUID().toString();

        ExplorerSession session = ExplorerSession.builder()
                .sessionId(sessionId)
                .connectorIdentifier(connectorIdentifier)
                .connectorName(client.getConnectorName())
                .topic(topic)
                .tenant(tenant)
                .maxMessages(cappedMax)
                .lastPolledAt(System.currentTimeMillis())
                .messages(new ConcurrentLinkedDeque<>())
                .build();

        // Build the listener that filters by topic and appends to the session
        Consumer<ConnectorMessage> listener = buildListener(session, topic);

        sessions.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>()).put(sessionId, session);
        listenerRegistry.put(sessionId, listener);
        client.addExplorerListener(listener);

        // Subscribe to the topic on the broker so messages actually arrive
        // (no-op for connectors that don't require explicit subscriptions, e.g. HTTP)
        client.subscribeExplorerTopic(topic);

        log.info("{} - Explorer session started: sessionId={}, connector={}, topic={}",
                tenant, sessionId, connectorIdentifier, topic);
        return sessionId;
    }

    /**
     * Stop and remove an explorer session, unregistering its listener from the connector.
     *
     * @param tenant    tenant identifier
     * @param sessionId session to stop
     */
    public void stopSession(String tenant, String sessionId) {
        Map<String, ExplorerSession> tenantSessions = sessions.get(tenant);
        if (tenantSessions == null) return;

        ExplorerSession session = tenantSessions.remove(sessionId);
        if (session == null) return;

        unregisterListener(tenant, sessionId, session);
        log.info("{} - Explorer session stopped: sessionId={}", tenant, sessionId);
    }

    /**
     * Return a snapshot of the buffered messages and update {@code lastPolledAt} to prevent TTL
     * expiry.
     *
     * @param tenant    tenant identifier
     * @param sessionId session to query
     * @return list of captured messages (oldest first), or empty list if session not found
     */
    public List<ExplorerMessage> getMessages(String tenant, String sessionId) {
        ExplorerSession session = findSession(tenant, sessionId);
        if (session == null) return Collections.emptyList();

        session.setLastPolledAt(System.currentTimeMillis());
        return new ArrayList<>(session.getMessages());
    }

    /**
     * Clear all buffered messages for the session without stopping it.
     *
     * @param tenant    tenant identifier
     * @param sessionId session to clear
     */
    public void clearMessages(String tenant, String sessionId) {
        ExplorerSession session = findSession(tenant, sessionId);
        if (session == null) return;
        session.getMessages().clear();
        log.debug("{} - Explorer session messages cleared: sessionId={}", tenant, sessionId);
    }

    /**
     * Return {@code true} if the session exists.
     */
    public boolean sessionExists(String tenant, String sessionId) {
        return findSession(tenant, sessionId) != null;
    }

    // -------------------------------------------------------------------------
    // TTL watchdog
    // -------------------------------------------------------------------------

    /**
     * Scheduled watchdog — runs every {@link #WATCHDOG_INTERVAL_MS} ms.
     * Expires sessions whose {@code lastPolledAt} is older than {@link #SESSION_TTL_MS}.
     */
    @Scheduled(fixedDelay = WATCHDOG_INTERVAL_MS)
    public void expireIdleSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ConcurrentHashMap<String, ExplorerSession>> tenantEntry : sessions.entrySet()) {
            String tenant = tenantEntry.getKey();
            Iterator<Map.Entry<String, ExplorerSession>> it = tenantEntry.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ExplorerSession> entry = it.next();
                ExplorerSession session = entry.getValue();
                if (now - session.getLastPolledAt() > SESSION_TTL_MS) {
                    it.remove();
                    unregisterListener(tenant, session.getSessionId(), session);
                    log.info("{} - Explorer session expired (TTL): sessionId={}", tenant, session.getSessionId());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ExplorerSession findSession(String tenant, String sessionId) {
        Map<String, ExplorerSession> tenantSessions = sessions.get(tenant);
        return tenantSessions != null ? tenantSessions.get(sessionId) : null;
    }

    private void unregisterListener(String tenant, String sessionId, ExplorerSession session) {
        Consumer<ConnectorMessage> listener = listenerRegistry.remove(sessionId);
        if (listener == null) return;
        try {
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant, session.getConnectorIdentifier());
            client.removeExplorerListener(listener);
            // Unsubscribe the broker topic if no mapping still needs it
            client.unsubscribeExplorerTopic(session.getTopic());
        } catch (ConnectorRegistryException e) {
            // Connector may already be gone — safe to ignore
            log.debug("{} - Could not unregister explorer listener (connector gone?): {}", tenant, e.getMessage());
        }
    }

    private Consumer<ConnectorMessage> buildListener(ExplorerSession session, String topicFilter) {
        return message -> {
            // Optional topic filter — skip messages that don't match
            if (!topicMatches(topicFilter, message.getTopic())) {
                return;
            }

            String payload;
            boolean binary = false;
            if (message.getPayload() == null) {
                payload = "";
            } else {
                // Try UTF-8 first; fall back to Base64 if it contains non-printable bytes
                byte[] bytes = message.getPayload();
                if (isPrintableUtf8(bytes)) {
                    payload = new String(bytes, StandardCharsets.UTF_8);
                } else {
                    payload = Base64.getEncoder().encodeToString(bytes);
                    binary = true;
                }
            }

            ExplorerMessage msg = ExplorerMessage.builder()
                    .topic(message.getTopic())
                    .connectorIdentifier(session.getConnectorIdentifier())
                    .connectorName(session.getConnectorName())
                    .receivedAt(System.currentTimeMillis())
                    .payload(payload)
                    .binary(binary)
                    .build();

            ConcurrentLinkedDeque<ExplorerMessage> deque = session.getMessages();
            deque.addLast(msg);
            // Trim oldest messages once the limit is exceeded
            while (deque.size() > session.getMaxMessages()) {
                deque.pollFirst();
            }
        };
    }

    /**
     * Lightweight MQTT-style wildcard matching: {@code +} matches a single level, {@code #}
     * matches the rest of the path. Plain topics are matched exactly.
     */
    static boolean topicMatches(String filter, String topic) {
        if (filter == null || filter.isEmpty() || filter.equals("#")) return true;
        if (filter.equals(topic)) return true;
        if (!filter.contains("+") && !filter.contains("#")) return false;

        String[] filterParts = filter.split("/", -1);
        String[] topicParts = topic.split("/", -1);

        for (int i = 0; i < filterParts.length; i++) {
            if ("#".equals(filterParts[i])) return true;
            if (i >= topicParts.length) return false;
            if (!"+".equals(filterParts[i]) && !filterParts[i].equals(topicParts[i])) return false;
        }
        return filterParts.length == topicParts.length;
    }

    /** Returns {@code true} if the bytes can be decoded as printable UTF-8 text. */
    private static boolean isPrintableUtf8(byte[] bytes) {
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            for (char c : s.toCharArray()) {
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
