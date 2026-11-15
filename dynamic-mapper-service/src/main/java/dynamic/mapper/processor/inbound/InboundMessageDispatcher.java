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

package dynamic.mapper.processor.inbound;

import dynamic.mapper.util.TenantLogContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.cumulocity.model.ID;
import com.cumulocity.sdk.client.SDKException;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.callback.GenericMessageCallback;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.mapping.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * In-process replacement for the former Camel-backed {@code CamelDispatcherInbound}. Resolves
 * the mappings applicable to an inbound {@link ConnectorMessage} and hands them to
 * {@link InboundMessageRouter} for processing, on a virtual thread, instead of sending a Camel
 * {@code Exchange} through {@code direct:processInboundMessage}.
 */
@Slf4j
public class InboundMessageDispatcher implements GenericMessageCallback {

    private final AConnectorClient connectorClient;
    private final ExecutorService virtualThreadPool;
    private final MappingService mappingService;
    private final ServiceRegistry serviceRegistry;
    private final InboundMessageRouter inboundMessageRouter;

    private final Timer inboundProcessingTimer;
    private final Counter inboundProcessingCounter;
    private final Counter inboundErrorCounter;


    /**
     * Constructor matching DispatcherInbound signature
     */
    public InboundMessageDispatcher(ServiceRegistry serviceRegistry,
            AConnectorClient connectorClient, InboundMessageRouter inboundMessageRouter) {
        this.connectorClient = connectorClient;
        this.virtualThreadPool = serviceRegistry.getVirtualThreadPool();
        this.mappingService = connectorClient.getMappingService();
        this.serviceRegistry = serviceRegistry;
        this.inboundMessageRouter = inboundMessageRouter;

        String connectorNameTag = connectorClient.getConnectorName() != null
                ? connectorClient.getConnectorName() : "unknown";
        String connectorTypeTag = connectorClient.getConnectorType() != null
                ? connectorClient.getConnectorType().name() : "UNKNOWN";
        this.inboundProcessingTimer = Timer.builder("dynmapper_inbound_processing_time")
                .tag("tenant", connectorClient.getTenant())
                .tag("connectorName", connectorNameTag)
                .tag("connectorType", connectorTypeTag)
                .description("Processing time of inbound messages")
                .publishPercentileHistogram()
                .register(Metrics.globalRegistry);
        this.inboundProcessingCounter = Counter.builder("dynmapper_inbound_message_total")
                .tag("tenant", connectorClient.getTenant()).description("Total number of inbound messages")
                .tag("connectorName", connectorNameTag)
                .tag("connectorType", connectorTypeTag)
                .register(Metrics.globalRegistry);
        this.inboundErrorCounter = Counter.builder("dynmapper_inbound_processing_errors_total")
                .tag("tenant", connectorClient.getTenant())
                .tag("connectorName", connectorNameTag)
                .tag("connectorType", connectorTypeTag)
                .description("Total number of inbound messages that failed processing")
                .register(Metrics.globalRegistry);
    }

    @Override
    public ProcessingResultWrapper<?> onMessage(ConnectorMessage message) {
        try (TenantLogContext ignored = TenantLogContext.open(message.getTenant())) {
            // Notify any explorer sessions listening on this connector
            connectorClient.notifyExplorerListeners(message);
            return processMessage(message, null);
        }
    }

    @Override
    public void onClose(String closeMessage, Throwable closeException) {
        // Handle connection close
    }

    @Override
    public void onError(Throwable errorException) {
        // Handle connection errors
        log.error("Connection error: {}", errorException.getMessage(), errorException);
    }

    /**
     * Process message via {@link InboundMessageRouter} - matches DispatcherInbound.processMessage
     * signature
     */
    private ProcessingResultWrapper<?> processMessage(ConnectorMessage connectorMessage, Mapping testMapping) {
        Timer.Sample timer = Timer.start(Metrics.globalRegistry);
        // Inbound: testing=true routes identity/inventory lookups to mocks.
        // When sendPayload=true the user created a real test device in C8Y beforehand,
        // so we must use real C8Y services to resolve its identity — testing is disabled.
        boolean testing = testMapping != null && !Boolean.TRUE.equals(connectorMessage.getSendPayload());
        String topic = connectorMessage.getTopic();
        String tenant = connectorMessage.getTenant();
        ServiceConfiguration serviceConfiguration = serviceRegistry.getServiceConfiguration(tenant);

        // Log incoming message if configured
        if (serviceConfiguration.getLogPayload()) {
            if (connectorMessage.getPayload() != null) {
                String payload = dynamic.mapper.processor.util.PayloadClassifier.describeForLog(connectorMessage.getPayload());
                log.info("{} - PROCESSING: message on topic: [{}], payload: {}", tenant, topic, payload);
            }
        }
        if (testMapping == null) { // test traffic is not operational traffic
            this.inboundProcessingCounter.increment();
        }

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResultWrapper<?> result = ProcessingResultWrapper.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        // Early return for system topics or null payload
        if (topic == null || topic.startsWith("$SYS") || connectorMessage.getPayload() == null) {
            return result;
        }

        // Declare final variables for use in lambda
        List<Mapping> resolvedMappings;
        int pipelineTimeout;

        try {
            // Resolve mappings for the topic
            if (testMapping != null) {
                resolvedMappings = new ArrayList<>();
                resolvedMappings.add(testMapping);
            } else {
                resolvedMappings = mappingService.resolveMappingInbound(tenant, topic);
            }
            if (resolvedMappings == null || resolvedMappings.isEmpty()) {
                // Count it on the catch-all status: a message arriving on a topic no mapping
                // covers is the single most common "my device publishes but nothing happens"
                // situation, and it was previously only visible in the log — no counter moved
                // anywhere, so the monitoring screen showed a perfectly idle system.
                if (!testing) {
                    mappingService.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING)
                            .incrementMessagesReceived();
                }
                log.info("{} - No mapping found for topic {}. Processing stopped.", tenant, topic);
            } else {
                log.info("{} - Resolved {} mapping(s) for topic {}", tenant, resolvedMappings.size(), topic);
            }
            result.setConsolidatedQos(connectorClient.determineMaxQos(resolvedMappings));

            // For code-based (Smart Function) mappings the JS execution itself is bounded
            // by serviceConfiguration.getMaxCPUTimeMS() via GraalVM context interruption.
            // The callback wait must be longer: it covers JS time PLUS any C8Y REST
            // calls (device auto-creation, measurement/alarm creation) that follow.
            int tempPipelineTimeout = 0;
            if (resolvedMappings != null) {
                for (Mapping mapping : resolvedMappings) {
                    if (mapping.isTransformationAsCode()) {
                        tempPipelineTimeout = serviceConfiguration.getEffectivePipelineTimeoutMS();
                        break;
                    }
                }
            }
            pipelineTimeout = tempPipelineTimeout; // Now final
            result.setPipelineTimeoutMS(pipelineTimeout);

        } catch (Exception e) {
            log.warn("{} - Error resolving appropriate map for topic {}. Could NOT be parsed. Ignoring this message!",
                    tenant, topic);
            log.debug("Error resolving appropriate mapping: {}", e.getMessage(), e);
            // Mirrors OutboundMessageDispatcher: a resolution failure belongs to no single
            // mapping, so it is reported on the catch-all status instead of being dropped.
            MappingStatus mappingStatusUnspecified = mappingService.getMappingStatus(tenant,
                    Mapping.UNSPECIFIED_MAPPING);
            if (mappingStatusUnspecified != null) {
                mappingStatusUnspecified.incrementErrors();
            }
            if (testMapping == null) {
                inboundErrorCounter.increment();
            }
            return result;
        }

        // Process via the in-process router, asynchronously
        Future<List<ProcessingContext<Object>>> futureProcessingResult = virtualThreadPool.submit(TenantLogContext.wrap(tenant, () -> {
            // Lets a timed-out callback verify that this thread really left — Future.isDone()
            // cannot tell it that, see ProcessingResultWrapper.workerCompleted.
            result.markWorkerStarted();
            try {
                // Abort early if the MQTT callback already cancelled (timeout fired before
                // we even reached the router - cancel actions were not yet registered).
                if (result.getCancellationRequested().get()) {
                    log.warn("{} - Cancellation already requested before routing started, aborting processing for topic: {}", tenant, topic);
                    return new ArrayList<>();
                }

                List<ProcessingContext<Object>> contexts = inboundMessageRouter.processInboundMessage(
                        connectorMessage, resolvedMappings, testing, serviceConfiguration, result);

                // A 422 means a cached device no longer exists. Only the legs that failed this way are
                // processed again: resending the whole message to every mapping would send the
                // requests of the mappings that already succeeded a second time.
                List<ProcessingContext<Object>> staleDeviceLegs = new ArrayList<>();
                if (contexts != null) {
                    for (ProcessingContext<Object> context : contexts) {
                        if (context == null || !context.hasFailure()) continue;
                        if (evictStaleDevice(tenant, context)
                                && Boolean.TRUE.equals(context.getMapping().getCreateNonExistingDevice())) {
                            staleDeviceLegs.add(context);
                        }
                    }
                }
                if (!staleDeviceLegs.isEmpty()) {
                    if (serviceConfiguration.getLogPayload()) {
                        String payload = dynamic.mapper.processor.util.PayloadClassifier.describeForLog(connectorMessage.getPayload());
                        log.info("{} - Resending message to C8Y due to previous 422 error with payload {}", tenant, payload);
                    } else
                        log.info("{} - Resending message to C8Y due to previous 422 error", tenant);
                    List<Mapping> retryMappings = staleDeviceLegs.stream().map(ProcessingContext::getMapping).toList();
                    List<ProcessingContext<Object>> retried = inboundMessageRouter.processInboundMessage(
                            connectorMessage, retryMappings, testing, serviceConfiguration, result);
                    // The result of the message is the legs that were fine plus the retried ones. The failed
                    // first attempts are replaced, not kept: they would report an error the retry fixed.
                    List<ProcessingContext<Object>> merged = new ArrayList<>(contexts);
                    merged.removeAll(staleDeviceLegs);
                    if (retried != null) {
                        merged.addAll(retried);
                    }
                    contexts = merged;
                    for (ProcessingContext<Object> retryContext : retried != null ? retried : List.<ProcessingContext<Object>>of()) {
                        if (retryContext != null && retryContext.hasFailure()) {
                            log.warn("{} - Retry after 422 also failed for topic {}: {}",
                                    tenant, topic, retryContext.getErrors());
                        }
                    }
                }
                boolean hasError = contexts != null
                        && contexts.stream().anyMatch(c -> c != null && c.hasFailure());
                if (hasError && testMapping == null) {
                    inboundErrorCounter.increment();
                }
                // Stop the timer
                if (testMapping == null) {
                    timer.stop(inboundProcessingTimer);
                }
                return contexts != null ? contexts : new ArrayList<>();

            } catch (Exception e) {
                if (testMapping == null) {
                    inboundErrorCounter.increment();
                }
                log.error("{} - Error processing inbound message: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Inbound processing failed", e);
            } finally {
                result.markWorkerCompleted();
            }
        }));

        result.setProcessingResult((Future) futureProcessingResult);

        return result;
    }

    /**
     * Evicts the external ID of every request of the leg that failed with HTTP 422 from both device
     * caches, so a retry resolves the device again instead of substituting the same stale source ID.
     * The failing request is looked up, not taken as "the last one": a leg can hold several requests
     * and the failed one is not necessarily the newest (and a leg may hold none at all).
     *
     * @return {@code true} if the leg failed with a 422
     */
    private boolean evictStaleDevice(String tenant, ProcessingContext<Object> context) {
        boolean found = false;
        for (DynamicMapperRequest request : context.getRequests()) {
            if (request == null || !request.hasError()
                    || ProcessingResultHelper.httpStatusOf(request.getError()) != 422) {
                continue;
            }
            found = true;
            String externalId = request.getExternalId() != null ? request.getExternalId() : context.getExternalId();
            String externalIdType = request.getExternalIdType() != null ? request.getExternalIdType()
                    : context.getMapping().getExternalIdType();
            evictExternalId(tenant, externalIdType, externalId);
        }
        if (!found) {
            // The 422 was recorded on the context only (for example by a step that is not a request).
            for (Exception error : context.getErrors()) {
                if (ProcessingResultHelper.httpStatusOf(error) == 422) {
                    found = true;
                    evictExternalId(tenant, context.getMapping().getExternalIdType(), context.getExternalId());
                    break;
                }
            }
        }
        return found;
    }

    private void evictExternalId(String tenant, String externalIdType, String externalId) {
        if (externalId == null) {
            return;
        }
        log.info("{} - Removing device from Identity Cache with external ID: {}", tenant, externalId);
        ID identity = new ID(externalIdType, externalId);
        this.serviceRegistry.getCacheManager().removeDeviceFromInboundExternalIdCache(tenant, identity);
        // Also evict the implicit-device-creation cache (IdentityResolutionService.getOrCreateDeviceThreadSafe
        // reads from this one, not InboundExternalIdCache above) - otherwise the resend re-substitutes the same
        // stale, no-longer-existing sourceId and fails identically. See attic/fix/inconsistant-cache/ISSUE.md.
        this.serviceRegistry.getTenantRegistry().removeFromExternalIdCache(tenant, identity);
    }

    @Override
    public ProcessingResultWrapper<?> onTestMessage(ConnectorMessage message, Mapping testMapping) {
        return processMessage(message, testMapping);
    }
}
