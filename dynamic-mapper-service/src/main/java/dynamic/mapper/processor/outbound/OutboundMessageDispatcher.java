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
package dynamic.mapper.processor.outbound;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.mapping.MappingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * In-process replacement for the former Camel-backed {@code CamelDispatcherOutbound}. Resolves
 * the mappings applicable to an outbound {@link C8YMessage} and hands them to
 * {@link OutboundMessageRouter} for processing, on a virtual thread, instead of sending a Camel
 * {@code Exchange} through {@code direct:processOutboundMessage}.
 */
@Slf4j
public class OutboundMessageDispatcher implements NotificationCallback {

    @Getter
    private AConnectorClient connectorClient;
    private ExecutorService virtualThreadPool;
    private NotificationSubscriber notificationSubscriber;
    private MappingService mappingService;
    private ServiceRegistry serviceRegistry;
    private OutboundMessageRouter outboundMessageRouter;
    private final Timer outboundProcessingTimer;
    private final Counter outboundProcessingCounter;
    /**
     * Constructor matching DispatcherInbound signature
     */
    public OutboundMessageDispatcher(ServiceRegistry serviceRegistry,
            AConnectorClient connectorClient) {
        this.mappingService = serviceRegistry.getMappingService();
        this.virtualThreadPool = serviceRegistry.getVirtualThreadPool();
        this.connectorClient = connectorClient;
        this.serviceRegistry = serviceRegistry;
        this.notificationSubscriber = serviceRegistry.getNotificationSubscriber();
        this.outboundMessageRouter = serviceRegistry.getOutboundMessageRouter();

        this.outboundProcessingTimer = Timer.builder("dynmapper_outbound_processing_time")
                .tag("tenant", connectorClient.getTenant())
                .tag("connector", connectorClient.getConnectorIdentifier())
                .description("Processing time of outbound messages").register(Metrics.globalRegistry);
        this.outboundProcessingCounter = Counter.builder("dynmapper_outbound_message_total")
                .tag("tenant", connectorClient.getTenant()).description("Total number of outbound messages")
                .tag("connector", connectorClient.getConnectorIdentifier()).register(Metrics.globalRegistry);
    }

    @Override
    public void onOpen(URI serverUri) {
        String tenant = connectorClient.getTenant() != null ? connectorClient.getTenant() : "UNKNOWN";
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, linked to connector: {}",
                tenant, connectorClient.getConnectorName());
        if (connectorClient.getTenant() != null) {
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 200);
        }
    }

    @Override
    public ProcessingResultWrapper<?> onNotification(Notification notification) {
        // Notify outbound explorer listeners BEFORE the TEST connector guard so that
        // the Message Explorer works regardless of which connector type owns the WebSocket.
        // This is necessary in multi-tenant setups where the TEST connector may be the
        // only (or first) dispatcher available for opening the explorer WebSocket.
        if (notification != null && notification.getMessage() != null
                && ("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))
                && !(notification.getApi() != null && notification.getApi().equals(API.OPERATION)
                        && "UPDATE".equals(notification.getOperation()))) {
            String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
            String explorerTopic = (notification.getApi() != null ? notification.getApi().name() : "UNKNOWN")
                    + "/" + notification.getOperation();
            String sourceId = null;
            try {
                Map parsedForExplorer = (Map) Json.parseJson(notification.getMessage());
                var expr = jsonata(notification.getApi().identifier);
                Object sourceIdResult = expr.evaluate(parsedForExplorer);
                sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
            } catch (Exception ignored) {
                // not critical — explorer will show messages without device filtering
            }
            ConnectorMessage explorerMsg = ConnectorMessage.builder()
                    .topic(explorerTopic)
                    .payload(notification.getMessage().getBytes(StandardCharsets.UTF_8))
                    .tenant(tenant)
                    .connectorIdentifier(connectorClient.getConnectorIdentifier())
                    .sourceId(sourceId)
                    .build();
            connectorClient.notifyOutboundExplorerListeners(explorerMsg);
        }

        if (connectorClient.getConnectorType() == ConnectorType.TEST) {
            log.debug("{} - Skipping live notification for TEST connector — only processes via TestController",
                    connectorClient.getTenant());
            return ProcessingResultWrapper.builder().consolidatedQos(Qos.AT_MOST_ONCE).build();
        }
        try {
            return processNotification(notification, null, true);
        } catch (Exception e) {
            log.error("{} - Unexpected error processing outbound notification, API: {}, Operation: {}: {}",
                    connectorClient.getTenant(),
                    notification != null ? notification.getApi() : "null",
                    notification != null ? notification.getOperation() : "null",
                    e.getMessage(), e);
            // Return a safe default — QoS 0 so the message is ACKed (not re-delivered)
            // to avoid an infinite retry loop for a permanently broken notification.
            return ProcessingResultWrapper.builder().consolidatedQos(Qos.AT_MOST_ONCE).build();
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - We got an exception: ", connectorClient.getTenant(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed", connectorClient.getTenant());
        if (reason != null && reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), null);
    }

    @Override
    public ProcessingResultWrapper<?> onTestNotification(Notification notification, Mapping mapping, boolean send) {
        return processNotification(notification, mapping, send);
    }

    /**
     * Process notification with optional test mapping
     */
    private ProcessingResultWrapper<?> processNotification(Notification notification, Mapping testMapping, boolean send) {
        // Outbound: testing=true always routes identity lookups to mocks, even when sendPayload=true.
        // Unlike inbound, the outbound source payload is always synthetic (built from a template),
        // so there is no real C8Y source device to resolve — mocks are required regardless of send.
        boolean testing = testMapping != null;

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResultWrapper<?> result = ProcessingResultWrapper.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Extract tenant from notification
        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());

        // Check connector connection status (skip for testing)
        if (!testing && !connectorClient.isConnected() && connectorClient.getConnectorType() != ConnectorType.TEST) {
            log.warn("{} - Notification message received but connector {} is not connected. Ignoring message..",
                    tenant, connectorClient.getConnectorName());
            return result;
        }

        // Filter operations - only process CREATE and UPDATE
        if (!("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))) {
            log.debug("{} - Ignoring notification with operation: {}", tenant, notification.getOperation());
            return result;
        }

        // Skip UPDATE operations for OPERATION API (unless testing)
        if (!testing && "UPDATE".equals(notification.getOperation()) && API.OPERATION.equals(notification.getApi())) {
            log.info("{} - Update Operation message for connector: {} is received, ignoring it",
                    tenant, connectorClient.getConnectorName());
            return result;
        }

        // Convert Notification to C8YMessage
        C8YMessage c8yMessage = convertNotificationToC8YMessage(notification, tenant, send);

        // Process the message
        return processMessage(c8yMessage, testMapping, testing);
    }

    /**
     * Convert Notification to C8YMessage
     */
    private C8YMessage convertNotificationToC8YMessage(Notification notification, String tenant, boolean sendPayload) {
        C8YMessage c8yMessage = new C8YMessage();

        // Parse payload
        Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
        c8yMessage.setParsedPayload(parsedPayload);

        // Set API and operation
        c8yMessage.setApi(notification.getApi());
        c8yMessage.setOperation(notification.getOperation());

        // Extract message ID
        String messageId = String.valueOf(parsedPayload.get("id"));
        c8yMessage.setMessageId(messageId);

        // Extract source ID
        try {
            var expression = jsonata(notification.getApi().identifier);
            Object sourceIdResult = expression.evaluate(parsedPayload);
            String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
            c8yMessage.setSourceId(sourceId);
        } catch (Exception e) {
            log.debug("{} - Could not extract source.id: {}", tenant, e.getMessage());
        }

        // Set payload and tenant
        c8yMessage.setPayload(notification.getMessage());
        c8yMessage.setTenant(tenant);
        c8yMessage.setSendPayload(sendPayload);

        return c8yMessage;
    }

    /**
     * Process C8Y message via {@link OutboundMessageRouter}
     */
    private ProcessingResultWrapper<?> processMessage(C8YMessage c8yMessage, Mapping testMapping, boolean testing) {
        Timer.Sample timer = Timer.start(Metrics.globalRegistry);
        String tenant = c8yMessage.getTenant();
        ServiceConfiguration serviceConfiguration = serviceRegistry.getServiceConfiguration(tenant);

        // Log incoming message if configured
        if (serviceConfiguration.getLogPayload()) {
            log.info("{} - PROCESSING: C8Y message, API: {}, device: {}, connector: {}, message id: {}",
                    tenant,
                    c8yMessage.getApi(),
                    c8yMessage.getSourceId(),
                    connectorClient.getConnectorName(),
                    c8yMessage.getMessageId());
        }
        this.outboundProcessingCounter.increment();

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResultWrapper<?> result = ProcessingResultWrapper.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Validate payload
        if (c8yMessage.getPayload() == null) {
            log.warn("{} - C8Y message has null payload, skipping processing", tenant);
            return result;
        }

        // Declare final variables for use in lambda
        List<Mapping> resolvedMappings;
        int maxCPUTime;

        try {
            // Resolve mappings
            if (testMapping != null) {
                resolvedMappings = new ArrayList<>();
                resolvedMappings.add(testMapping);
            } else {
                resolvedMappings = mappingService.resolveMappingOutbound(tenant, c8yMessage, serviceConfiguration);
            }

            // Determine consolidated QoS
            consolidatedQos = connectorClient.determineMaxQosOutbound(resolvedMappings);
            result.setConsolidatedQos(consolidatedQos);

            // For code-based (Smart Function) mappings the JS execution is bounded by
            // serviceConfiguration.getMaxCPUTimeMS() via GraalVM; the Pulsar wait must
            // additionally cover broker publish latency. Use 30 s to match
            // MQTTServicePulsarCallback.MAX_PROCESSING_TIMEOUT.
            int tempMaxCPUTime = 0;
            for (Mapping mapping : resolvedMappings) {
                if (mapping.isTransformationAsCode()) {
                    tempMaxCPUTime = serviceConfiguration.getEffectivePipelineTimeoutMS();
                    break;
                }
            }
            maxCPUTime = tempMaxCPUTime; // Now final
            result.setPipelineTimeoutMS(maxCPUTime);

        } catch (Exception e) {
            log.warn("{} - Error resolving appropriate mapping for C8Y message. Could NOT be parsed. Ignoring this message!",
                    tenant);
            log.debug("Error resolving appropriate mapping: {}", e.getMessage(), e);

            // Update unspecified mapping status
            MappingStatus mappingStatusUnspecified = mappingService.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
            if (mappingStatusUnspecified != null) {
                mappingStatusUnspecified.incrementErrors();
            }

            return result;
        }

        // Process via the in-process router, asynchronously.
        // NOTE: This inner virtual thread must respond to cancellation signals emitted by
        // CustomWebSocketClient when a processing timeout is detected. Cancellation happens
        // via ProcessingResultWrapper.cancelProcessing() which:
        //   1. sets cancellationRequested=true
        //   2. calls Future.cancel(true) on this future → sends interrupt to this thread
        //   3. runs GraalVM cancel-actions (Context.close) to stop CPU-bound JS execution
        // All three paths are handled below.
        final String connectorIdentifier = connectorClient.getConnectorIdentifier();
        Future<List<ProcessingContext<Object>>> futureProcessingResult = virtualThreadPool.submit(() -> {
            // ── Early-exit path ──────────────────────────────────────────────────────────
            // If cancelProcessing() was already called (e.g. the timeout fired before this
            // thread was scheduled), skip processing entirely.
            // Lets a timed-out callback verify that this thread really left — Future.isDone()
            // cannot tell it that, see ProcessingResultWrapper.workerCompleted.
            result.markWorkerStarted();
            if (result.getCancellationRequested().get() || Thread.currentThread().isInterrupted()) {
                log.info("{} - Outbound processing thread cancelled before routing started, skipping. connector: {}",
                        tenant, connectorIdentifier);
                result.markWorkerCompleted();
                return new ArrayList<>();
            }
            try {
                List<ProcessingContext<Object>> contexts = outboundMessageRouter.processOutboundMessage(
                        c8yMessage, connectorIdentifier, resolvedMappings, testing, serviceConfiguration, result);
                return contexts != null ? contexts : new ArrayList<>();

            } catch (Exception e) {
                // ── Cancellation-induced exception path ──────────────────────────────────
                // Future.cancel(true) sends an interrupt to this thread. If the router was
                // blocking on I/O the interrupt causes an InterruptedException. GraalVM
                // context-close likewise aborts JS and throws a PolyglotException. In both
                // cases we detect the cancellation via the flag (the interrupt flag itself
                // may already be cleared by the time we reach here) and return an empty list
                // instead of propagating a noisy RuntimeException.
                if (result.getCancellationRequested().get()) {
                    log.info("{} - Outbound processing thread interrupted due to cancellation, aborting: {}. connector: {}",
                            tenant, e.getMessage(), connectorIdentifier);
                    Thread.interrupted(); // clear residual interrupt flag to avoid cascading effects
                    return new ArrayList<>();
                }
                log.error("{} - Error processing outbound message: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Outbound processing failed", e);
            } finally {
                timer.stop(outboundProcessingTimer);
                result.markWorkerCompleted();
            }
        });
        result.setProcessingResult((Future) futureProcessingResult);
        return result;
    }

    /**
     * Extract tenant from notification headers
     */
    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        if (notificationHeaders != null && !notificationHeaders.isEmpty()) {
            String firstHeader = notificationHeaders.get(0);
            String[] parts = firstHeader.split("/");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return connectorClient.getTenant(); // Fallback to connector's tenant
    }
}
