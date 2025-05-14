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

package dynamic.mapping.notification.websocket;

import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.C8YRequest;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.ProcessingResult;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class CustomWebSocketClient extends WebSocketClient {
    private final NotificationCallback callback;
    private ScheduledExecutorService executorService = null;
    private String tenant;
    private String connectorName;
    private ExecutorService virtualThreadPool;
    ServiceConfiguration serviceConfiguration;

    public CustomWebSocketClient(String tenant, ConfigurationRegistry configurationRegistry, URI serverUri,
            NotificationCallback callback, String connectorName) {
        super(serverUri);
        this.callback = callback;
        this.connectorName = connectorName;
        this.tenant = tenant;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(tenant);
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

        if (serviceConfiguration.logPayload) {
            log.info(
                    "Tenant {} - INITIAL: message on connector InternalWebSocket (notification 2.0) for outbound connector {}, API: {}, Operation: {}",
                    tenant, connectorName, notification.getApi(), notification.getOperation());
        }
        ProcessingResult<?> processedResults = this.callback.onNotification(notification);
        int mappingQos = processedResults.getConsolidatedQos().ordinal();
        int timeout = processedResults.getMaxCPUTimeMS();
        if (serviceConfiguration.logPayload) {
            log.info(
                    "Tenant {} - PROCESSED: message on connector InternalWebSocket (notification 2.0) for outbound connector {}, API: {}, Operation: {}, QoS mappings: {}",
                    tenant, connectorName, notification.getApi(), notification.getOperation(), mappingQos);
        }
        if (mappingQos > 0 || timeout > 0) {
            // Use the provided virtualThreadPool instead of creating a new thread
            virtualThreadPool.submit(() -> {
                try {
                    // Wait for the future to complete
                    // List<? extends ProcessingContext<?>> results =
                    // processedResults.getProcessingResult().get();
                    List<? extends ProcessingContext<?>> results;
                    if (timeout > 0) {
                        results = processedResults.getProcessingResult().get(timeout,
                                TimeUnit.MILLISECONDS);
                    } else {
                        results = processedResults.getProcessingResult().get();
                    }

                    // Check for errors in results
                    boolean hasErrors = false;
                    int httpStatusCode = 0;
                    if (results != null) {
                        for (ProcessingContext<?> context : results) {
                            List<C8YRequest> resultRequests = context.getRequests();
                            if (context.hasError() || resultRequests.stream().anyMatch(C8YRequest::hasError)) {
                                for (C8YRequest r : resultRequests) {
                                    if (r.hasError()) {
                                        Throwable e = r.getError();
                                        while (!(e instanceof ProcessingException) && e != e.getCause()) {
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
                        // No errors found, acknowledge the message
                        if (notification.getAckHeader() != null) {
                            log.info(
                                    "Tenant {} - END: Sending manual ack for message on connector InternalWebSocket (notification 2.0), API: {} api, QoS: {}",
                                    tenant, notification.getApi(), mappingQos);
                            send(notification.getAckHeader()); // ack message
                        } else {
                            throw new RuntimeException("No message id found for ack");
                        }
                    } else if (httpStatusCode < 500) {
                        // Errors found but not a server error, acknowledge the message
                        if (notification.getAckHeader() != null) {
                            log.info(
                                    "Tenant {} - END: Sending manual ack for message on connector InternalWebSocket (notification 2.0), API: {} api, QoS: {}, connector InternalWebSocket",
                                    tenant, notification.getApi(), mappingQos);
                            send(notification.getAckHeader()); // ack message
                        } else {
                            throw new RuntimeException("No message id found for ack");
                        }
                    } else {
                        // Server error, do not acknowledge
                        log.error(
                                "Tenant {} - END: Processing failed with server error. API: {} api, QoS: {}, connector: InternalWebSocket",
                                tenant, notification.getApi(), mappingQos);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // Processing failed, don't acknowledge to allow redelivery
                    // Thread.currentThread().interrupt();
                    log.warn("Tenant {} - END: Processing InterruptedException |  ExecutionException: {}",
                            tenant, e.getMessage());
                } catch (TimeoutException e) {
                    var cancelResult = processedResults.getProcessingResult().cancel(true);
                    log.warn(
                            "Tenant {} - END: Processing timed out with: {} milliseconds, connector InternalWebSocket, result of cancelling: {}",
                            tenant, timeout, cancelResult);
                }
                return null; // Proper return for Callable<Void>
            });
        } else {
            // For QoS 0 (or downgraded to 0), no need for special handling

            // If the original publish was QoS > 0 but got downgraded, we should still
            // acknowledge
            if (notification.getAckHeader() != null) {
                log.info(
                        "Tenant {} - END: Sending manual ack for Notification message. API: {} api, QoS: {}, Connector InternalWebSocket",
                        tenant, notification.getApi(), mappingQos);
                send(notification.getAckHeader()); // ack message
            } else {
                throw new RuntimeException("No message id found for ack");
            }
        }
    }

    @Override
    public void onClose(int statusCode, String reason, boolean remote) {
        log.info("Tenant {} - WebSocket closed{}statusCode: {}, reason: {}", tenant, remote ? "by server, " : ", ",
                statusCode, reason);
        if (this.executorService != null)
            this.executorService.shutdownNow();
        this.callback.onClose(statusCode, reason);
    }

    @Override
    public void onError(Exception e) {
        log.error("Tenant {} - WebSocket error: ", tenant, e);
        this.callback.onError(e);
    }
}
