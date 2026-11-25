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

package dynamic.mapper.notification.websocket;

import dynamic.mapper.util.TenantLogContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.MessageFailureTracker;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CustomWebSocketClient extends WebSocketClient {
    /** Wait before a retransmission requested because the broker connection is down. */
    private static final long CONNECTOR_UNAVAILABLE_RETRY_DELAY_MS = 5_000;

    private final NotificationCallback callback;
    /** One pending delayed reconnect at a time, however many messages hit the outage together. */
    private final java.util.concurrent.atomic.AtomicBoolean retransmitScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private ScheduledExecutorService executorService = null;
    private String tenant;

    @Getter
    private ConnectorId connectorId;

    @Getter
    private volatile String lastCloseReason;

    /** Message of the last WebSocket error; a rejected handshake (for example HTTP 409) reports its status here. */
    @Getter
    private volatile String lastErrorMessage;

    private ExecutorService virtualThreadPool;
    ServiceConfiguration serviceConfiguration;

    /**
     * Counts failures per message (identified by its ack header, which stays the same on redelivery).
     * Each notification is tracked independently to prevent one failing message from affecting others.
     * When a message reaches {@code maxProcessingAttempts} failures it is treated as a poison pill
     * and ACKed to break an infinite redelivery loop.
     */
    private final MessageFailureTracker failureTracker;

    public CustomWebSocketClient(String tenant, ServiceRegistry serviceRegistry, URI serverUri,
            NotificationCallback callback, ConnectorId connectorId) {
        super(serverUri);
        this.callback = callback;
        this.connectorId = connectorId;
        this.tenant = tenant;
        this.virtualThreadPool = serviceRegistry.getVirtualThreadPool();
        this.serviceConfiguration = serviceRegistry.getServiceConfiguration(tenant);
        this.failureTracker = MessageFailureTracker.forConfiguration(() -> serviceRegistry.getServiceConfiguration(tenant));
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            if (this.executorService != null) {
                this.executorService.shutdownNow();
            }
            this.executorService = Executors.newScheduledThreadPool(1);
            this.callback.onOpen(this.uri);
            // send(ByteBuffer.allocate(0));
            executorService.scheduleAtFixedRate(this::sendPing, 1, 1, TimeUnit.MINUTES);
        }
    }

    @Override
    public void onMessage(String message) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            Notification notification = Notification.parse(message);
            String messageId = notification.getAckHeader();

            if (serviceConfiguration.getLogPayload()) {
                log.info(
                        "{} - INITIAL: message on connector InternalWebSocket (notification 2.0) for outbound connector {}, API: {}, Operation: {}",
                        tenant, connectorId.displayName(), notification.getApi(), notification.getOperation());
            }
            ProcessingResultWrapper<?> processedResults = this.callback.onNotification(notification);
            if (processedResults == null) {
                // Defensive guard: callback should never return null, but if it does,
                // ACK the message to prevent Cumulocity from endlessly re-delivering it.
                log.error("{} - onNotification returned null for API: {}, Operation: {} — ACKing to prevent infinite redelivery, connector: InternalWebSocket",
                        tenant, notification.getApi(), notification.getOperation());
                ackIfPresent(notification);
                return;
            }
            int mappingQos = processedResults.getConsolidatedQos().getLevel();
            int timeout = processedResults.getPipelineTimeoutMS();

            if (mappingQos > 0) {
                // Use the provided virtualThreadPool instead of creating a new thread
                virtualThreadPool.submit(() -> {
                    try {
                        // A retransmission gets more time than the first attempt (see escalatedTimeoutMs)
                        Long timeoutOverride = null;
                        if (processedResults.getProcessingResult() != null) {
                            timeoutOverride = failureTracker.escalatedTimeoutMs(messageId, timeout);
                            int attempt = failureTracker.failures(messageId);
                            if (timeoutOverride != null && attempt > 0) {
                                log.info("{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                        tenant, attempt + 1, timeoutOverride, timeout, connectorId.displayName());
                            }
                        }

                        // The shared rule. Outbound publishing records its failures on the requests, so
                        // their HTTP status counts too: a webhook answering 503 is retried, a 400 is not.
                        AckOutcome outcome = ProcessingResultHelper.awaitOutcome(processedResults, tenant,
                                notification.getApi() + "#" + notification.getOperation(), log, timeoutOverride,
                                true);
                        if (outcome == AckOutcome.ACK) {
                            failureTracker.clear(messageId);
                            log.debug(
                                    "{} - END: Sending manual ack for message on Internal WebSocket connector (notification 2.0), API: {}, QoS: {}, Outbound Connector: {}",
                                    tenant, notification.getApi(), mappingQos, connectorId.displayName());
                            ack(notification);
                        } else if (outcome == AckOutcome.RETRY_CONNECTOR_UNAVAILABLE) {
                            // The broker connection is down, the message is fine: do not count the attempt
                            // toward the poison-pill limit, and wait before the retransmission
                            retransmitLater(notification, messageId);
                        } else {
                            // Not ACKed: reconnect so Cumulocity retransmits the message (until the limit)
                            handleFailureOrRetransmit(notification, messageId);
                        }
                    } catch (Exception e) {
                        // Anything unexpected, including a missing ack header
                        log.error("{} - END: Processing failed with exception: {}", tenant, e.getMessage(), e);
                        handleFailureOrRetransmit(notification, messageId);
                    }
                    return null; // Proper return for Callable<Void>
                });
            } else {
                // For QoS 0 (or downgraded to 0), no need for special handling

                // If the original publish was QoS > 0 but got downgraded, we should still
                // acknowledge
                log.debug(
                        "{} - END: Sending manual ack for message on Internal WebSocket connector (notification 2.0), API: {}, QoS: {}, Outbound Connector: {}",
                        tenant, notification.getApi(), mappingQos, connectorId.displayName());
                ack(notification);
            }
        }
    }

    @Override
    public void onClose(int statusCode, String reason, boolean remote) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            log.info("{} - WebSocket closed{}statusCode: {}, reason: {}", tenant, remote ? "by server, " : ", ",
                    statusCode, reason);
            this.lastCloseReason = reason;
            if (this.executorService != null)
                this.executorService.shutdownNow();
            this.callback.onClose(statusCode, reason);
        }
    }

    public boolean isConflict() {
        return (lastCloseReason != null && lastCloseReason.contains("409"))
                || (lastErrorMessage != null && lastErrorMessage.contains("409"));
    }

    /** Acknowledges the notification on this WebSocket. A notification without an ack header cannot be settled. */
    private void ack(Notification notification) {
        if (notification.getAckHeader() == null) {
            throw new IllegalStateException("No message id found for ack");
        }
        send(notification.getAckHeader());
    }

    /** Acknowledges the notification if it carries an ack header, and does nothing if it does not. */
    private void ackIfPresent(Notification notification) {
        if (notification.getAckHeader() != null) {
            send(notification.getAckHeader());
        }
    }

    private void retransmitLater(Notification notification, String messageId) {
        reconnectAfter(CONNECTOR_UNAVAILABLE_RETRY_DELAY_MS,
                String.format("Broker connection of connector %s is down: message not acknowledged, reconnecting in "
                        + "%dms so Cumulocity retransmits it (attempt not counted), messageId: %s, API: %s",
                        connectorId.displayName(), CONNECTOR_UNAVAILABLE_RETRY_DELAY_MS, messageId,
                        notification.getApi()));
    }

    /**
     * Reconnects once after the delay, so Cumulocity retransmits the unacknowledged messages. However many messages
     * failed together, one reconnect redelivers them all: the others return at once.
     */
    private void reconnectAfter(long delayMs, String reason) {
        if (!retransmitScheduled.compareAndSet(false, true)) {
            return; // a reconnect is already pending and redelivers this message too
        }
        try {
            log.warn("{} - {}", tenant, reason);
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (isOpen()) { // closed means the connector was paused: it opens a new WebSocket when it is up
                this.reconnect();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            retransmitScheduled.set(false);
        }
    }

    /**
     * Handles failure-to-acknowledge or poison-pill detection for unreliable messages.
     * Each notification is tracked independently by its messageId, so one failing message does not affect others.
     * <ul>
     *   <li>If the failure counter for this specific message has reached {@code maxProcessingAttempts}: the message is treated as a <em>poison pill</em>
     *       — it is ACKed (discarded) and a hard error is logged. This breaks an infinite redelivery
     *       loop caused by a permanently unprocessable message.</li>
     *   <li>Otherwise the WebSocket is disconnected to trigger Cumulocity to retransmit the unACKed message.</li>
     * </ul>
     *
     * @param notification the unacknowledged notification
     * @param messageId    unique identifier for tracking failures per notification
     */
    private void handleFailureOrRetransmit(Notification notification, String messageId) {
        // A closed WebSocket was closed on purpose (the connector is paused while its broker is down) and has
        // been dropped by its owner. Reconnecting it would open one nobody tracks or can pause again, and it
        // would consume messages during the outage. Cumulocity keeps the unacknowledged message and delivers
        // it when the owner opens a new WebSocket.
        if (!isOpen()) {
            log.info("{} - WebSocket is closed, not reconnecting: the message stays queued in Cumulocity, "
                    + "messageId: {}, API: {}, connector: {}",
                    tenant, messageId, notification.getApi(), connectorId.displayName());
            return;
        }

        if (failureTracker.recordFailureReachedLimit(messageId)) {
            log.error("{} - POISON PILL: {} failed attempts without success: ACKing message to prevent "
                    + "infinite redelivery loop. Message is discarded. messageId: {}, API: {}, connector: {}",
                    tenant, failureTracker.limit(), messageId, notification.getApi(), connectorId.displayName());
            ackIfPresent(notification);
            return;
        }

        // Disconnect WebSocket to trigger Cumulocity to retransmit unACKed messages, after a delay that grows with
        // the failures of the message: a reconnect redelivers at once, and an outage of the platform of a few
        // seconds used up all attempts of every message
        long delay = failureTracker.retryDelayMillis(messageId);
        reconnectAfter(delay, String.format("Reconnecting WebSocket in %dms to trigger retransmission for "
                + "messageId: %s, API: %s, connector: %s", delay, messageId, notification.getApi(),
                connectorId.displayName()));
    }

    @Override
    public void onError(Exception e) {
        try (TenantLogContext ignored = TenantLogContext.open(tenant)) {
            log.error("{} - WebSocket error: ", tenant, e);
            this.lastErrorMessage = e.getMessage();
            this.callback.onError(e);
        }
    }
}
