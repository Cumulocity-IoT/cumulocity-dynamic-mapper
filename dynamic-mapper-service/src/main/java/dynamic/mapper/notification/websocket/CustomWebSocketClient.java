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

package dynamic.mapper.notification.websocket;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class CustomWebSocketClient extends WebSocketClient {
    /**
     * Maximum number of consecutive failures (timeout or error) before treating
     * a specific message as a poison pill and ACKing it to break the infinite redelivery loop.
     * After this threshold, the message is acknowledged (discarded) and a hard error is logged.
     * The counter is reset to zero on every successful message processing.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /**
     * Maximum time a mapping can use CPU-time to process end to end. Only used in error cases,
     * otherwise configured timeout is used.
     */
    private static final int MAX_PROCESSING_TIMEOUT = 30000;

    private final NotificationCallback callback;
    private ScheduledExecutorService executorService = null;
    private String tenant;

    @Getter
    private ConnectorId connectorId;

    @Getter
    private volatile String lastCloseReason;

    private ExecutorService virtualThreadPool;
    ServiceConfiguration serviceConfiguration;

    /**
     * Counts consecutive failures per message (identified by API#Operation).
     * Each notification is tracked independently to prevent one failing message from affecting others.
     * When a message's counter exceeds {@link #MAX_CONSECUTIVE_FAILURES}, it is treated as a poison pill
     * and ACKed to break an infinite redelivery loop.
     */
    private final Map<String, AtomicInteger> failureCountPerMessage = new ConcurrentHashMap<>();

    public CustomWebSocketClient(String tenant, ConfigurationRegistry configurationRegistry, URI serverUri,
            NotificationCallback callback, ConnectorId connectorId) {
        super(serverUri);
        this.callback = callback;
        this.connectorId = connectorId;
        this.tenant = tenant;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        this.executorService = Executors.newScheduledThreadPool(1);
        this.callback.onOpen(this.uri);
        // send(ByteBuffer.allocate(0));
        executorService.scheduleAtFixedRate(this::sendPing, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void onMessage(String message) {
        Notification notification = Notification.parse(message);
        String messageId = notification.getAckHeader();

        if (serviceConfiguration.getLogPayload()) {
            log.info(
                    "{} - INITIAL: message on connector InternalWebSocket (notification 2.0) for outbound connector {}, API: {}, Operation: {}",
                    tenant, connectorId.getIdentifier(), notification.getApi(), notification.getOperation());
        }
        ProcessingResultWrapper<?> processedResults = this.callback.onNotification(notification);
        if (processedResults == null) {
            // Defensive guard: callback should never return null, but if it does,
            // ACK the message to prevent Cumulocity from endlessly re-delivering it.
            log.error("{} - onNotification returned null for API: {}, Operation: {} — ACKing to prevent infinite redelivery, connector: InternalWebSocket",
                    tenant, notification.getApi(), notification.getOperation());
            if (notification.getAckHeader() != null) {
                send(notification.getAckHeader());
            }
            return;
        }
        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getPipelineTimeoutMS();

        if (mappingQos > 0) {
            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                long effectiveTimeout = timeout;
                try {
                    // Wait for the future to complete.
                    // Multiply timeout by (attempt + 1) so each retransmission gets progressively
                    // more processing time — useful when the first timeout was caused by a slow server.
                    // attempt = current failure count for this message:
                    //   0 → first try   → 1× timeout
                    //   1 → 2nd try     → 2× timeout
                    //   …  capped at MAX_PROCESSING_TIMEOUT
                    List<? extends ProcessingContext<?>> results = null;
                    if (timeout > 0 && processedResults.getProcessingResult() != null) {
                        int attempt = failureCountPerMessage.getOrDefault(messageId, new AtomicInteger(0)).get();
                        effectiveTimeout = Math.min(
                                (long) timeout * (attempt + 1),
                                MAX_PROCESSING_TIMEOUT);
                        if (attempt > 0) {
                            log.info("{} - Retransmission attempt {}: using increased timeout {}ms (base: {}ms), connector: {}",
                                    tenant, attempt + 1, effectiveTimeout, timeout, connectorId.getIdentifier());
                        }
                        results = processedResults.getProcessingResult().get(effectiveTimeout,
                                TimeUnit.MILLISECONDS);
                    } else if(processedResults.getProcessingResult() != null) {
                        results = processedResults.getProcessingResult().get();
                    }

                    // Check for errors in results
                    boolean hasErrors = false;
                    int httpStatusCode = 0;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            List<DynamicMapperRequest> resultRequests = context.getRequests();
                            if (context.hasError() || resultRequests.stream().anyMatch(DynamicMapperRequest::hasError)) {
                                for (DynamicMapperRequest r : resultRequests) {
                                    if (r.hasError()) {
                                        Throwable e = r.getError();
                                        while (!(e instanceof ProcessingException) && e.getCause() != null && e != e.getCause()) {
                                            e = e.getCause();
                                        }
                                        if (e instanceof ProcessingException) {
                                            ProcessingException processingException = (ProcessingException) e;
                                            httpStatusCode = Math.max(processingException.getHttpStatusCode(),
                                                    httpStatusCode);
                                        }
                                    }
                                }
                                hasErrors = true;
                                // break;
                            }
                        }
                    }

                    if (!hasErrors) {
                        // No errors found, acknowledge the message and reset failure counter
                        failureCountPerMessage.remove(messageId);
                        if (notification.getAckHeader() != null) {
                            log.info(
                                    "{} - END: Sending manual ack for message on Internal WebSocket connector (notification 2.0), API: {}, QoS: {}, Outbound Connector: {}",
                                    tenant, notification.getApi(), mappingQos, connectorId.getIdentifier());
                            send(notification.getAckHeader()); // ack message
                        } else {
                            throw new RuntimeException("No message id found for ack");
                        }
                    } else if (httpStatusCode < 500) {
                        // Errors found but not a server error, acknowledge the message and reset failure counter
                        failureCountPerMessage.remove(messageId); // client-side error is not a transient failure
                        if (notification.getAckHeader() != null) {
                            log.info(
                                    "{} - END: Sending manual ack for message on Internal WebSocket connector (notification 2.0), API: {}, QoS: {}, Outbound Connector: {}",
                                    tenant, notification.getApi(), mappingQos, connectorId.getIdentifier());
                            send(notification.getAckHeader()); // ack message
                        } else {
                            throw new RuntimeException("No message id found for ack");
                        }
                    } else {
                        // Server error (>=500): trigger WebSocket reconnect for retransmission
                        log.warn(
                                "{} - END: Server error (HTTP {}), triggering WebSocket reconnect for retransmission. API: {}, connector: {}",
                                tenant, httpStatusCode, notification.getApi(), connectorId.getIdentifier());
                        handleFailureOrRetransmit(notification, messageId);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed, check failure threshold
                    log.warn("{} - END: Processing InterruptedException | ExecutionException: {}",
                            tenant, e.getMessage());
                    handleFailureOrRetransmit(notification, messageId);
                } catch (TimeoutException e) {
                    // cancelProcessing() calls Future.cancel(true) to interrupt IO-blocked threads
                    // AND closes any active GraalVM context via Context.close(cancelIfExecuting=true)
                    // — the only reliable way to stop CPU-bound JS execution.
                    log.warn("{} - Timeout occurred, initiating cancellation of processing task, connector {}", tenant, connectorId.getName());
                    var cancelResult = processedResults.cancelProcessing();
                    log.info("{} - Cancellation result: future was cancelled={}, connector {}", tenant, cancelResult, connectorId.getName());

                    // Wait for the future to actually terminate after cancellation.
                    // The active cancellation checks in C8YAgent.createMEAO() should prevent any new HTTP calls,
                    // allowing threads to exit quickly. We wait up to 2 seconds as a fail-safe in case a thread
                    // is already mid-flight in an HTTP call that cannot be interrupted immediately.
                    boolean futureCompleted = false;
                    int maxWaitIterations = 20; // 20 × 100ms = 2 seconds
                    for (int i = 0; i < maxWaitIterations; i++) {
                        if (processedResults.getProcessingResult().isDone()) {
                            futureCompleted = true;
                            log.info("{} - Future completed after {}ms wait, connector {}", tenant, (i + 1) * 100, connectorId.getName());
                            break;
                        }
                        try {
                            Thread.sleep(100); //NOSONAR intentional wait for future completion
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.warn("{} - Interrupted while waiting for future completion, connector {}", tenant, connectorId.getName());
                            break;
                        }
                    }

                    if (!futureCompleted) {
                        log.error("{} - Future did NOT complete within 2 seconds after cancellation! " +
                                "This indicates that a thread is stuck in a blocking operation that cannot be interrupted. " +
                                "Check for long-running HTTP calls or other blocking I/O. connector: {}", tenant, connectorId.getName());
                    }

                    log.warn(
                            "{} - END: Processing timed out after {}ms, triggering WebSocket reconnect for retransmission. connector: {}, cancel result: {}, future completed: {}",
                            tenant, effectiveTimeout, connectorId.getIdentifier(), cancelResult, futureCompleted);
                    handleFailureOrRetransmit(notification, messageId);
                } catch (Exception e) {
                    // Handle other exceptions
                    log.error("{} - END: Processing failed with exception: {}", tenant, e.getMessage(), e);
                    handleFailureOrRetransmit(notification, messageId);
                }
                return null; // Proper return for Callable<Void>
            });
        } else {
            // For QoS 0 (or downgraded to 0), no need for special handling

            // If the original publish was QoS > 0 but got downgraded, we should still
            // acknowledge
            if (notification.getAckHeader() != null) {
                log.debug(
                        "{} - END: Sending manual ack for message on Internal WebSocket connector (notification 2.0), API: {}, QoS: {}, Outbound Connector: {}",
                        tenant, notification.getApi(), mappingQos, connectorId.getIdentifier());
                send(notification.getAckHeader()); // ack message
            } else {
                throw new RuntimeException("No message id found for ack");
            }
        }
    }

    @Override
    public void onClose(int statusCode, String reason, boolean remote) {
        log.info("{} - WebSocket closed{}statusCode: {}, reason: {}", tenant, remote ? "by server, " : ", ",
                statusCode, reason);
        this.lastCloseReason = reason;
        if (this.executorService != null)
            this.executorService.shutdownNow();
        this.callback.onClose(statusCode, reason);
    }

    public boolean isConflict() {
        return lastCloseReason != null && lastCloseReason.contains("409");
    }

    /**
     * Handles failure-to-acknowledge or poison-pill detection for unreliable messages.
     * Each notification is tracked independently by its messageId, so one failing message does not affect others.
     * <ul>
     *   <li>If the consecutive-failure counter for this specific message has reached
     *       {@link #MAX_CONSECUTIVE_FAILURES}: the message is treated as a <em>poison pill</em>
     *       — it is ACKed (discarded) and a hard error is logged. This breaks an infinite redelivery
     *       loop caused by a permanently unprocessable message.</li>
     *   <li>Otherwise the WebSocket is disconnected to trigger Cumulocity to retransmit the unACKed message.</li>
     * </ul>
     *
     * @param notification the unacknowledged notification
     * @param messageId    unique identifier for tracking failures per notification
     */
    private void handleFailureOrRetransmit(Notification notification, String messageId) {
        // Track failures per message ID independently
        int failureCount = failureCountPerMessage
            .computeIfAbsent(messageId, k -> new AtomicInteger(0))
            .incrementAndGet();

        if (failureCount >= MAX_CONSECUTIVE_FAILURES) {
            log.error("{} - POISON PILL: {} consecutive failures without success — ACKing message to prevent "
                    + "infinite redelivery loop. Message is discarded. messageId: {}, API: {}, connector: {}",
                    tenant, failureCount, messageId, notification.getApi(), connectorId.getIdentifier());
            failureCountPerMessage.remove(messageId);
            if (notification.getAckHeader() != null) {
                send(notification.getAckHeader());
            }
            return;
        }

        if (failureCount > 1) {
            log.warn("{} - Redelivery attempt {} for messageId: {}, API: {}, connector: {}",
                    tenant, failureCount, messageId, notification.getApi(), connectorId.getIdentifier());
        }

        // Disconnect WebSocket to trigger Cumulocity to retransmit unACKed messages
        log.info("{} - Reconnecting WebSocket to trigger retransmission for messageId: {}, API: {}, connector: {}",
                tenant, messageId, notification.getApi(), connectorId.getIdentifier());
        this.reconnect();
    }

    @Override
    public void onError(Exception e) {
        log.error("{} - WebSocket error: ", tenant, e);
        this.callback.onError(e);
    }
}
