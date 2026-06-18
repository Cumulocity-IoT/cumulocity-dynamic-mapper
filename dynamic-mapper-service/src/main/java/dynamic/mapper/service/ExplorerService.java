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

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.connector.core.registry.ConnectorRegistryException;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.ExplorerMessage;
import dynamic.mapper.model.ExplorerSession;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.Utils;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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

    @Autowired
    @Lazy
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    @Lazy
    private NotificationSubscriber notificationSubscriber;

    @Autowired
    @Lazy
    private C8YAgent c8yAgent;

    /**
     * sessions: tenant → (sessionId → ExplorerSession)
     * explorerListeners: sessionId → Consumer registered on the AConnectorClient
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ExplorerSession>> sessions
            = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Consumer<ConnectorMessage>> listenerRegistry
            = new ConcurrentHashMap<>();

    /**
     * Short-lived deduplication cache for OUTBOUND messages.
     * Each Notification 2.0 event is delivered once per connector, so N connectors
     * would produce N identical explorer entries. We suppress duplicates seen within
     * OUTBOUND_DEDUP_WINDOW_MS milliseconds using a key of (tenant, topic, payloadHash).
     */
    private final ConcurrentHashMap<String, Long> outboundDedupCache = new ConcurrentHashMap<>();
    static final long OUTBOUND_DEDUP_WINDOW_MS = 3_000L;

    /**
     * Short-lived deduplication cache for INBOUND messages.
     * Overlapping MQTT subscriptions (e.g. mapping on "a/b" + explorer on "a/#") cause the
     * broker to deliver the same message once per matching subscription. We suppress duplicates
     * seen within INBOUND_DEDUP_WINDOW_MS using a key of (sessionId, topic, payloadHash).
     */
    private final ConcurrentHashMap<String, Long> inboundDedupCache = new ConcurrentHashMap<>();
    static final long INBOUND_DEDUP_WINDOW_MS = 500L;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start a new explorer session for the given connector + topic.
     *
     * <p>For <b>INBOUND</b>: listens on the specific connector and subscribes the topic on the broker.
     * <p>For <b>OUTBOUND</b>: {@code connectorIdentifier} is ignored — the listener is registered on
     * <em>all</em> connectors for the tenant so every outbound publish is captured regardless of
     * which connector sends it. The session stores connectorIdentifier as {@code "*"} and
     * connectorName as {@code "(all)"}.
     *
     * @param tenant              tenant identifier
     * @param connectorIdentifier identifier of the inbound connector (INBOUND only; ignored for OUTBOUND)
     * @param topic               topic filter (MQTT wildcards supported)
     * @param maxMessages         maximum messages to buffer (1–500); defaults to 50
     * @param direction           "INBOUND" or "OUTBOUND"
     * @param sourceId            C8Y managed object ID (device or group) to filter (OUTBOUND only; required — without a source ID no Notification 2.0 subscription is created and no events will be captured)
     * @param deviceType          C8Y device type filter (OUTBOUND only; optional). Only messages from devices whose type matches are forwarded.
     * @return the new session id
     * @throws ConnectorRegistryException if the connector is not registered for the tenant (INBOUND only)
     */
    public String startSession(String tenant, String userId, String connectorIdentifier, String topic, int maxMessages,
            String direction, String sourceId, String deviceType)
            throws ConnectorRegistryException {

        int cappedMax = Math.max(1, Math.min(500, maxMessages > 0 ? maxMessages : DEFAULT_MAX_MESSAGES));
        String dir = (direction != null && direction.equalsIgnoreCase("OUTBOUND")) ? "OUTBOUND" : "INBOUND";

        String sessionId = UUID.randomUUID().toString();

        ExplorerSession session;
        Consumer<ConnectorMessage> listener;

        if ("OUTBOUND".equals(dir)) {
            // Outbound: intercept published messages on ALL connectors for this tenant
            String resolvedSourceId = (sourceId != null && !sourceId.isBlank()) ? sourceId.trim() : null;
            String resolvedDeviceType = (deviceType != null && !deviceType.isBlank()) ? deviceType.trim() : null;
            String connName = resolvedSourceId != null ? "source:" + resolvedSourceId
                    : (resolvedDeviceType != null ? "type:" + resolvedDeviceType : "(all)");

            session = ExplorerSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .connectorIdentifier("*")
                    .connectorName(connName)
                    .topic(topic)
                    .tenant(tenant)
                    .maxMessages(cappedMax)
                    .direction(dir)
                    .sourceId(resolvedSourceId)
                    .deviceType(resolvedDeviceType)
                    .lastPolledAt(System.currentTimeMillis())
                    .messages(new ConcurrentLinkedDeque<>())
                    .build();

            listener = buildListener(session, topic);

            // Stop any existing sessions for the same device to prevent ghost subscribers.
            // This is the primary guard for the case where the browser reloads without calling
            // DELETE /session/{id} (e.g. page refresh, tab close, or ngOnDestroy fire-and-forget).
            ConcurrentHashMap<String, ExplorerSession> tenantSessions =
                    sessions.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>());
            List<String> stale = tenantSessions.entrySet().stream()
                    .filter(e -> "OUTBOUND".equals(e.getValue().getDirection())
                            && Objects.equals(resolvedSourceId, e.getValue().getSourceId())
                            && Objects.equals(userId, e.getValue().getUserId()))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toList());
            for (String staleId : stale) {
                ExplorerSession staleSession = tenantSessions.remove(staleId);
                if (staleSession != null) {
                    unregisterListener(tenant, staleId, staleSession);
                    log.info("{} - Stopped stale outbound explorer session before creating new one: sessionId={}",
                            tenant, staleId);
                }
            }

            tenantSessions.put(sessionId, session);
            listenerRegistry.put(sessionId, listener);

            Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
            for (AConnectorClient client : clients.values()) {
                client.addOutboundExplorerListener(listener);
            }

            // If a specific device is requested, create a dedicated Notification 2.0 subscription
            // independent of STATIC/DYNAMIC subscriptions, so notifications arrive even when
            // no outbound mapping exists for that device.
            if (resolvedSourceId != null) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, resolvedSourceId, false);
                if (mor != null) {
                    notificationSubscriber.subscribeDeviceAndConnect(tenant, mor, API.ALL,
                            Utils.EXPLORER_DEVICE_SUBSCRIPTION);
                    // Also open a subscriber WebSocket for this session so events actually arrive
                    notificationSubscriber.initializeExplorerDeviceClient(tenant, sessionId);
                    log.info("{} - Explorer subscription created for source {}", tenant, resolvedSourceId);
                } else {
                    log.warn("{} - Source {} not found; explorer will rely on existing subscriptions",
                            tenant, resolvedSourceId);
                }
            } else if (resolvedDeviceType != null) {
                // Subscribe all existing devices of the given type using the same pattern as
                // UpdateSubscriptionDeviceTypeTask — one N2.0 subscription per device, all
                // delivered on a single EXPLORER_DEVICE_SUBSCRIPTION WebSocket.
                List<ManagedObjectRepresentation> deviceMors =
                        c8yAgent.getManagedObjectsByType(tenant, resolvedDeviceType, false);
                for (ManagedObjectRepresentation deviceMor : deviceMors) {
                    String devId = deviceMor.getId().getValue();
                    notificationSubscriber.subscribeDeviceAndConnect(tenant, deviceMor, API.ALL,
                            Utils.EXPLORER_DEVICE_SUBSCRIPTION);
                    session.addSubscribedDeviceId(devId);
                }
                if (!session.getSubscribedDeviceIds().isEmpty()) {
                    notificationSubscriber.initializeExplorerDeviceClient(tenant, sessionId);
                    log.info("{} - Explorer subscribed {} devices of type {} for session {}",
                            tenant, session.getSubscribedDeviceIds().size(), resolvedDeviceType, sessionId);
                } else {
                    log.info("{} - No devices found for type {} at session start; relying on per-message filter",
                            tenant, resolvedDeviceType);
                }
            }

            log.info("{} - Outbound explorer session started: sessionId={}, source={}, deviceType={}, connectors={}",
                    tenant, sessionId,
                    resolvedSourceId != null ? resolvedSourceId : "(all)",
                    resolvedDeviceType != null ? resolvedDeviceType : "(any)",
                    clients.size());
        } else {
            AConnectorClient client = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);

            session = ExplorerSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .connectorIdentifier(connectorIdentifier)
                    .connectorName(client.getConnectorName())
                    .topic(topic)
                    .tenant(tenant)
                    .maxMessages(cappedMax)
                    .direction(dir)
                    .lastPolledAt(System.currentTimeMillis())
                    .messages(new ConcurrentLinkedDeque<>())
                    .build();

            listener = buildListener(session, topic);

            // Stop any existing sessions for the same connector + topic to prevent ghost listeners.
            ConcurrentHashMap<String, ExplorerSession> tenantSessionsInbound =
                    sessions.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>());
            List<String> staleInbound = tenantSessionsInbound.entrySet().stream()
                    .filter(e -> "INBOUND".equals(e.getValue().getDirection())
                            && connectorIdentifier.equals(e.getValue().getConnectorIdentifier())
                            && topic.equals(e.getValue().getTopic())
                            && Objects.equals(userId, e.getValue().getUserId()))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toList());
            for (String staleId : staleInbound) {
                ExplorerSession staleSession = tenantSessionsInbound.remove(staleId);
                if (staleSession != null) {
                    unregisterListener(tenant, staleId, staleSession);
                    log.info("{} - Stopped stale inbound explorer session before creating new one: sessionId={}",
                            tenant, staleId);
                }
            }

            tenantSessionsInbound.put(sessionId, session);
            listenerRegistry.put(sessionId, listener);

            client.addExplorerListener(listener);
            // Subscribe to the topic on the broker so messages actually arrive
            // (no-op for connectors that don't require explicit subscriptions, e.g. HTTP)
            client.subscribeExplorerTopic(topic);
            log.info("{} - Inbound explorer session started: sessionId={}, connector={}, topic={}",
                    tenant, sessionId, connectorIdentifier, topic);
        }

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
            long sessionTTLMs = resolveSessionTtlMs(tenant);
            Iterator<Map.Entry<String, ExplorerSession>> it = tenantEntry.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ExplorerSession> entry = it.next();
                ExplorerSession session = entry.getValue();
                if (now - session.getLastPolledAt() > sessionTTLMs) {
                    it.remove();
                    unregisterListener(tenant, session.getSessionId(), session);
                    log.info("{} - Explorer session expired (TTL={}min): sessionId={}", tenant,
                            sessionTTLMs / 60_000, session.getSessionId());
                }
            }
        }
        // Purge stale outbound deduplication entries older than the window
        outboundDedupCache.entrySet().removeIf(e -> now - e.getValue() > OUTBOUND_DEDUP_WINDOW_MS);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long resolveSessionTtlMs(String tenant) {
        try {
            ServiceConfiguration config = configurationRegistry.getServiceConfiguration(tenant);
            if (config != null && config.getExplorerSessionTTLMinutes() != null
                    && config.getExplorerSessionTTLMinutes() > 0) {
                return config.getExplorerSessionTTLMinutes() * 60_000L;
            }
        } catch (Exception e) {
            log.debug("{} - Could not read service configuration for explorer TTL: {}", tenant, e.getMessage());
        }
        return SESSION_TTL_MS;
    }

    private ExplorerSession findSession(String tenant, String sessionId) {
        Map<String, ExplorerSession> tenantSessions = sessions.get(tenant);
        return tenantSessions != null ? tenantSessions.get(sessionId) : null;
    }

    private void unregisterListener(String tenant, String sessionId, ExplorerSession session) {
        Consumer<ConnectorMessage> listener = listenerRegistry.remove(sessionId);
        if (listener == null) return;
        try {
            if ("OUTBOUND".equals(session.getDirection())) {
                // Registered on all connectors — remove from all
                Map<String, AConnectorClient> clients = connectorRegistry.getClientsForTenant(tenant);
                for (AConnectorClient client : clients.values()) {
                    client.removeOutboundExplorerListener(listener);
                }
                // Remove the dedicated explorer source subscription if one was created
                if (session.getSourceId() != null) {
                    // Close the WebSocket first
                    notificationSubscriber.closeExplorerDeviceClient(session.getSessionId());
                    ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(
                            tenant, session.getSourceId(), false);
                    if (mor != null) {
                        notificationSubscriber.unsubscribeDeviceAndDisconnect(
                                tenant, mor, Utils.EXPLORER_DEVICE_SUBSCRIPTION);
                        log.info("{} - Explorer subscription removed for source {}", tenant, session.getSourceId());
                    }
                } else if (!session.getSubscribedDeviceIds().isEmpty()) {
                    // Close the shared WebSocket for device-type sessions
                    notificationSubscriber.closeExplorerDeviceClient(session.getSessionId());
                    for (String devId : session.getSubscribedDeviceIds()) {
                        ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, devId, false);
                        if (mor != null) {
                            notificationSubscriber.unsubscribeDeviceAndDisconnect(
                                    tenant, mor, Utils.EXPLORER_DEVICE_SUBSCRIPTION);
                        }
                    }
                    log.info("{} - Explorer subscriptions removed for {} devices of type {}",
                            tenant, session.getSubscribedDeviceIds().size(), session.getDeviceType());
                }
            } else {
                AConnectorClient client = connectorRegistry.getClientForTenant(tenant, session.getConnectorIdentifier());
                client.removeExplorerListener(listener);
                // Unsubscribe the broker topic if no mapping still needs it
                client.unsubscribeExplorerTopic(session.getTopic());
            }
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

            // Optional source filter for OUTBOUND sessions (device or group)
            if (session.getSourceId() != null && !session.getSourceId().equals(message.getSourceId())) {
                return;
            }

            // Optional device-type filter for OUTBOUND sessions.
            // The resolved type is cached per session so each unique sourceId is looked up at most once.
            if (session.getDeviceType() != null && message.getSourceId() != null) {
                String resolvedType = session.getDeviceTypeCache().computeIfAbsent(
                        message.getSourceId(),
                        sid -> {
                            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(
                                    session.getTenant(), sid, false);
                            return (mor != null && mor.getType() != null) ? mor.getType() : "";
                        });
                if (!session.getDeviceType().equals(resolvedType)) {
                    return;
                }
            }

            int payloadHash = Arrays.hashCode(message.getPayload());
            String dedupKey = session.getSessionId() + "::" + message.getTopic() + "::" + payloadHash;
            long now = System.currentTimeMillis();

            if ("OUTBOUND".equals(session.getDirection())) {
                // Notification 2.0 events are delivered once per connector; N connectors → N copies.
                Long prev = outboundDedupCache.put(dedupKey, now);
                if (prev != null && now - prev < OUTBOUND_DEDUP_WINDOW_MS) {
                    return;
                }
            } else {
                // Overlapping MQTT subscriptions (e.g. mapping on "a/b" + explorer on "a/#") cause
                // the broker to deliver the same message once per matching subscription.
                Long prev = inboundDedupCache.put(dedupKey, now);
                if (prev != null && now - prev < INBOUND_DEDUP_WINDOW_MS) {
                    return;
                }
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
                    .clientId(message.getClientId())
                    .direction(session.getDirection())
                    .receivedAt(System.currentTimeMillis())
                    .payload(payload)
                    .binary(binary)
                    .sourceId(message.getSourceId())
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
