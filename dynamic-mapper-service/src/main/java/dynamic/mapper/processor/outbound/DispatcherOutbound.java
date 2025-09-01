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

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.SnoopStatus;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.service.MappingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.notification.websocket.Notification;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AsynchronousDispatcherOutbound
 * 
 * This class implements the <code>NotificationCallback</code> which is then
 * registered as a listener in the <code>C8YNotificationSubscriber </code> when
 * new messages arrive.
 * It processes OUTBOUND messages and works asynchronously.
 * A task <code>AsynchronousDispatcherOutbound.MappingOutboundTask</code> is
 * added the ExecutorService, to not block new arriving messages.
 * The call method in
 * <code>AsynchronousDispatcherOutbound.MappingOutboundTask</code> is the core
 * of the message processing.
 * For all resolved mappings the following steps are performed for new
 * messages:
 * ** deserialize the payload
 * ** extract the content from the payload based on the defined substitution in
 * the mapping and add these to a post processing cache
 * ** substitute in the defined target template of the mapping the extracted
 * content from the cache
 * ** send the resulting target payload to connectorClient, e.g. MQTT broker
 */
@Slf4j
public class DispatcherOutbound implements NotificationCallback {

    @Getter
    protected AConnectorClient connectorClient;

    protected NotificationSubscriber notificationSubscriber;

    protected C8YAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected ExecutorService virtualThreadPool;

    protected MappingService mappingService;

    protected ConfigurationRegistry configurationRegistry;

    protected Map<MappingType, BaseProcessorOutbound<?>> payloadProcessorsOutbound;

    // The Outbound Dispatcher is hardly connected to the Connector otherwise it is
    // not possible to correlate messages received bei Notification API to the
    // correct Connector
    public DispatcherOutbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.mappingService = configurationRegistry.getMappingService();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.connectorClient = connectorClient;
        this.payloadProcessorsOutbound = configurationRegistry.getPayloadProcessorsOutbound(connectorClient.getTenant(),
                connectorClient.getConnectorIdentifier());
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, linked to connector: {}",
                connectorClient.getTenant(), connectorClient.getConnectorName());
        notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 200);
    }

    @Override
    public ProcessingResult<?> onNotification(Notification notification) {
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();

        // We don't care about UPDATES nor DELETES and ignore notifications if connector
        // is not connected
        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
        if (!connectorClient.isConnected())
            log.warn("{} - Notification message received but connector {} is not connected. Ignoring message..",
                    tenant, connectorClient.getConnectorName());
        if (("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))
                && connectorClient.isConnected()) {
            if ("UPDATE".equals(notification.getOperation()) && notification.getApi().equals(API.OPERATION)) {
                log.info("{} - Update Operation message for connector: {} is received, ignoring it",
                        tenant, connectorClient.getConnectorName());
                return result;
            }
            C8YMessage c8yMessage = new C8YMessage();
            Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
            c8yMessage.setParsedPayload(parsedPayload);
            c8yMessage.setApi(notification.getApi());
            c8yMessage.setOperation(notification.getOperation());
            String messageId = String.valueOf(parsedPayload.get("id"));
            c8yMessage.setMessageId(messageId);
            try {
                var expression = jsonata(notification.getApi().identifier);
                Object sourceIdResult = expression.evaluate(parsedPayload);
                String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
                c8yMessage.setSourceId(sourceId);
            } catch (Exception e) {
                log.debug("Could not extract source.id: {}", e.getMessage());

            }
            c8yMessage.setPayload(notification.getMessage());
            c8yMessage.setTenant(tenant);
            c8yMessage.setSendPayload(true);
            // TODO Return a future so it can be blocked for QoS 1 or 2
            return processMessage(c8yMessage);
        }
        return result;
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - We got an exception: ", connectorClient.getTenant(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed", connectorClient.getTenant());
        if (reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(connectorClient.getTenant(), null);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[1];
    }

    public static class MappingOutboundTask<T> implements Callable<List<ProcessingContext<?>>> {
        List<Mapping> resolvedMappings;
        Map<MappingType, BaseProcessorOutbound<T>> payloadProcessorsOutbound;
        C8YMessage c8yMessage;
        MappingService mappingService;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;
        ServiceConfiguration serviceConfiguration;
        AConnectorClient connectorClient;
        Timer outboundProcessingTimer;
        Counter outboundProcessingCounter;
        ConfigurationRegistry configurationRegistry;

        public MappingOutboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
                MappingService mappingService,
                Map<MappingType, BaseProcessorOutbound<T>> payloadProcessorsOutbound,
                C8YMessage c8yMessage, AConnectorClient connectorClient) {
            this.connectorClient = connectorClient;
            this.resolvedMappings = resolvedMappings;
            this.mappingService = mappingService;
            this.c8yAgent = configurationRegistry.getC8yAgent();
            this.outboundProcessingTimer = Timer.builder("dynmapper_outbound_processing_time")
                    .tag("tenant", connectorClient.getTenant())
                    .tag("connector", connectorClient.getConnectorIdentifier())
                    .description("Processing time of outbound messages").register(Metrics.globalRegistry);
            this.outboundProcessingCounter = Counter.builder("dynmapper_outbound_message_total")
                    .tag("tenant", connectorClient.getTenant()).description("Total number of outbound messages")
                    .tag("connector", connectorClient.getConnectorIdentifier()).register(Metrics.globalRegistry);
            this.c8yMessage = c8yMessage;
            this.objectMapper = configurationRegistry.getObjectMapper();
            this.serviceConfiguration = configurationRegistry.getServiceConfiguration(c8yMessage.getTenant());
            this.payloadProcessorsOutbound = payloadProcessorsOutbound;
            this.configurationRegistry = configurationRegistry;
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            Timer.Sample timer = Timer.start(Metrics.globalRegistry);
            String tenant = c8yMessage.getTenant();
            boolean sendPayload = c8yMessage.isSendPayload();

            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingService
                    .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);

            // Parse operation representation if applicable
            OperationRepresentation op = null;
            if (c8yMessage.getApi().equals(API.OPERATION)) {
                try {
                    op = JSONBase.getJSONParser().parse(OperationRepresentation.class, c8yMessage.getPayload());
                } catch (Exception e) {
                    log.error("{} - Failed to parse operation representation: {}", tenant, e.getMessage());
                    throw new OperationParsingException("Failed to parse operation", e);
                }
            }

            // Track if any mapping requested auto-acknowledgment
            boolean shouldAutoAcknowledge = false;
            List<Exception> criticalExceptions = new ArrayList<>();

            // Process each mapping independently
            for (Mapping mapping : resolvedMappings) {
                // Skip inactive mappings or mappings not deployed outbound
                if (!mapping.getActive() ||
                        !connectorClient.isMappingOutboundDeployed(mapping.identifier)) {
                    continue;
                }

                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                Context graalContext = null;

                // Create a basic context that includes identifying information even if
                // processing fails
                ProcessingContext<?> context = createBasicContext(tenant, mapping);

                // Handle auto-acknowledgment for operations
                if (op != null && Boolean.TRUE.equals(mapping.getAutoAckOperation())) {
                    try {
                        c8yAgent.updateOperationStatus(tenant, op, OperationStatus.EXECUTING, null);
                        shouldAutoAcknowledge = true;
                    } catch (Exception e) {
                        log.warn("{} - Failed to update operation status to EXECUTING: {}",
                                tenant, e.getMessage());
                        criticalExceptions.add(e);
                    }
                }

                try {
                    // Get the appropriate processor for this mapping type
                    BaseProcessorOutbound processor = payloadProcessorsOutbound.get(mapping.mappingType);
                    if (processor == null) {
                        handleMissingProcessor(tenant, mapping, context, mappingStatus, mappingStatusUnspecified);
                        continue;
                    }

                    // Get payload and create full context
                    Object payload = c8yMessage.getParsedPayload();
                    context = createFullOutboundContext(tenant, mapping, payload, sendPayload, serviceConfiguration);

                    // Prepare GraalVM context if code exists
                    if (mapping.code != null) {
                        try {
                            graalContext = createGraalContext(
                                    configurationRegistry.getGraalEngine(c8yMessage.getTenant()));
                            context.setGraalContext(graalContext);
                            // context.setSharedSource(configurationRegistry.getGraalsSourceShared(tenant));
                            // context.setSystemSource(configurationRegistry.getGraalsSourceSystem(tenant));
                            // context.setMappingSource(configurationRegistry.getGraalsSourceMapping(tenant,
                            // mapping.id));
                            context.setSharedCode(serviceConfiguration.getCodeTemplates()
                                    .get(TemplateType.SHARED.name()).getCode());
                            context.setSystemCode(serviceConfiguration.getCodeTemplates()
                                    .get(TemplateType.SYSTEM.name()).getCode());
                        } catch (Exception e) {
                            handleGraalVMError(tenant, mapping, e, context, mappingStatus);
                            processingResult.add(context);
                            criticalExceptions.add(e);
                            continue;
                        }
                    }

                    // Log message receipt
                    logOutboundMessageReceived(tenant, mapping, context, serviceConfiguration);
                    mappingStatus.messagesReceived++;

                    // Handle snooping or normal processing
                    if (isSnoopingEnabled(mapping)) {
                        handleSnooping(tenant, mapping, context, mappingService, mappingStatus, objectMapper);
                    } else {
                        // Process and send the message
                        processOutboundMessage(tenant, mapping, context, processor, mappingStatus, c8yMessage);

                        // Collect errors if any occurred
                        if (context.hasError()) {
                            criticalExceptions.addAll(context.getErrors());
                        }
                    }
                } catch (Exception e) {
                    // Handle any uncaught exceptions
                    String errorMessage = String.format("Tenant %s - Error processing outbound mapping %s: %s",
                            tenant, mapping.identifier, e.getMessage());
                    log.error(errorMessage, e);
                    context.addError(new ProcessingException(errorMessage, e));
                    mappingStatus.errors++;
                    criticalExceptions.add(e);
                    mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
                } finally {
                    // Clean up GraalVM context
                    if (graalContext != null) {
                        try {
                            graalContext.close();
                        } catch (Exception e) {
                            log.warn("{} - Error closing GraalVM context: {}", tenant, e.getMessage());
                        }
                    }

                    // Always add the context to results, even if processing failed
                    processingResult.add(context);
                }
            }

            // Handle operation status updates if auto-acknowledge was requested
            if (shouldAutoAcknowledge && op != null) {
                updateOperationStatus(tenant, op, criticalExceptions);
            }

            // Stop the timer
            timer.stop(outboundProcessingTimer);

            return processingResult;
        }

        // -------------------- Helper Methods --------------------

        private ProcessingContext<?> createBasicContext(String tenant, Mapping mapping) {
            return ProcessingContext.builder()
                    .tenant(tenant)
                    .topic(mapping.publishTopic)
                    .mapping(mapping)
                    .mappingType(mapping.mappingType)
                    .build();
        }

        private ProcessingContext<?> createFullOutboundContext(String tenant, Mapping mapping,
                Object payload, boolean sendPayload, ServiceConfiguration serviceConfiguration) {
            return ProcessingContext.builder()
                    .payload(payload)
                    .rawPayload(c8yMessage.getPayload())
                    .topic(mapping.publishTopic)
                    .mappingType(mapping.mappingType)
                    .mapping(mapping)
                    .sendPayload(sendPayload)
                    .tenant(tenant)
                    .supportsMessageContext(mapping.supportsMessageContext)
                    .qos(mapping.qos) // use QoS from mapping
                    .serviceConfiguration(serviceConfiguration)
                    .build();
        }

        private void handleMissingProcessor(String tenant, Mapping mapping, ProcessingContext<?> context,
                MappingStatus mappingStatus, MappingStatus mappingStatusUnspecified) {
            String errorMessage = String.format("Tenant %s - No processor for MessageType: %s registered",
                    tenant, mapping.mappingType);
            log.error(errorMessage);
            mappingStatus.errors++;
            mappingStatusUnspecified.errors++;
            context.addError(new ProcessingException(errorMessage));
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);

        }

        private Context createGraalContext(Engine graalEngine)
                throws Exception {
            Context graalContext = Context.newBuilder("js")
                    .engine(graalEngine)
                    // .option("engine.WarnInterpreterOnly", "false")
                    .allowHostAccess(configurationRegistry.getHostAccess())
                    .allowHostClassLookup(className ->
                    // Allow only the specific SubstitutionContext class
                    className.equals("dynamic.mapper.processor.model.SubstitutionContext")
                            || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                            || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                            || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                            || className.equals("dynamic.mapper.processor.model.RepairStrategy")
                            // Allow base collection classes needed for return values
                            || className.equals("java.util.ArrayList") ||
                            className.equals("java.util.HashMap") ||
                            className.equals("java.util.HashSet"))
                    .build();

            return graalContext;
        }

        private void handleGraalVMError(String tenant, Mapping mapping, Exception e,
                ProcessingContext<?> context, MappingStatus mappingStatus) {
            String errorMessage = String.format("Tenant %s - Failed to set up GraalVM context: %s",
                    tenant, e.getMessage());
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

        private void logOutboundMessageReceived(String tenant, Mapping mapping, ProcessingContext<?> context,
                ServiceConfiguration serviceConfiguration) {
            if (serviceConfiguration.logPayload || mapping.debug) {
                log.info(
                        "{} - Start processing message on topic: [{}] connector: {}, mapping : {}, wrapped message: {}",
                        tenant, context.getTopic(), connectorClient.getConnectorName(), mapping.getName(),
                        context.getPayload().toString());
            } else {
                log.info(
                        "{} - Start processing message on topic: [{}] connector: {}, mapping : {}, sendPayload: {}",
                        tenant, context.getTopic(), connectorClient.getConnectorName(), mapping.getName(),
                        context.isSendPayload());
            }
        }

        private boolean isSnoopingEnabled(Mapping mapping) {
            return mapping.snoopStatus == SnoopStatus.ENABLED || mapping.snoopStatus == SnoopStatus.STARTED;
        }

        private void handleSnooping(String tenant, Mapping mapping, ProcessingContext<?> context,
                MappingService mappingService, MappingStatus mappingStatus, ObjectMapper objectMapper) {
            try {
                String serializedPayload = objectMapper.writeValueAsString(context.getPayload());
                if (serializedPayload != null) {
                    mapping.addSnoopedTemplate(serializedPayload);
                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                    mappingStatus.snoopedTemplatesActive++;

                    log.debug("{} - Adding snoopedTemplate to map: {},{},{}",
                            tenant, mapping.mappingTopic, mapping.snoopedTemplates.size(), mapping.snoopStatus);
                    mappingService.addDirtyMapping(tenant, mapping);
                } else {
                    log.warn("{} - Message could NOT be serialized for snooping, payload is null");
                }
            } catch (Exception e) {
                log.warn("{} - Error during snooping: {}", tenant, e.getMessage());
            }
        }

        private void processOutboundMessage(String tenant, Mapping mapping, ProcessingContext<?> context,
                BaseProcessorOutbound processor, MappingStatus mappingStatus, C8YMessage c8yMessage) {
            try {
                // Processing pipeline
                processor.enrichPayload(context);
                processor.extractFromSource(context);

                if (!context.isIgnoreFurtherProcessing()) {
                    processor.substituteInTargetAndSend(context);

                    // Metrics tracking
                    recordOutboundMetrics(c8yMessage, processor);

                    // Check for errors
                    List<C8YRequest> resultRequests = context.getRequests();
                    if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                        mappingStatus.errors++;
                        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
                    }
                }
            } catch (Exception e) {
                int lineNumber = 0;
                if (e.getStackTrace().length > 0) {
                    lineNumber = e.getStackTrace()[0].getLineNumber();
                }
                String errorMessage = String.format("Tenant %s - Processing error: %s for mapping: %s, line %s",
                        tenant, mapping.name, e.getMessage(), lineNumber);
                log.error(errorMessage, e);
                context.addError(new ProcessingException(errorMessage, e));
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
        }

        private void recordOutboundMetrics(C8YMessage c8yMessage, BaseProcessorOutbound processor) {
            Counter.builder("dynmapper_outbound_message_total")
                    .tag("tenant", c8yMessage.getTenant())
                    .description("Total number of outbound messages")
                    .tag("connector", processor.connectorClient.getConnectorIdentifier())
                    .register(Metrics.globalRegistry)
                    .increment();
        }

        private void updateOperationStatus(String tenant, OperationRepresentation op, List<Exception> errors) {
            try {
                if (!errors.isEmpty()) {
                    // Join error messages for the status update
                    String errorMessage = errors.stream()
                            .map(Exception::getMessage)
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining("; "));

                    c8yAgent.updateOperationStatus(tenant, op, OperationStatus.FAILED, errorMessage);
                    log.info("{} - Operation {} marked as FAILED", tenant, op.getId());
                } else {
                    c8yAgent.updateOperationStatus(tenant, op, OperationStatus.SUCCESSFUL, null);
                    log.info("{} - Operation {} marked as SUCCESSFUL", tenant, op.getId());
                }
            } catch (Exception e) {
                log.error("{} - Failed to update operation status: {}", tenant, e.getMessage());
            }
        }

        // Custom exception class
        public static class OperationParsingException extends Exception {
            public OperationParsingException(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }

    public ProcessingResult<?> processMessage(C8YMessage c8yMessage) {
        String tenant = c8yMessage.getTenant();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);

        log.info("{} - PROCESSING: C8Y message, API: {}, device: {}. connector: {}, message id: {}",
                tenant,
                c8yMessage.getApi(), c8yMessage.getSourceId(),
                connectorClient.getConnectorName(),
                c8yMessage.getMessageId());

        MappingStatus mappingStatusUnspecified = mappingService.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        List<Mapping> resolvedMappings = new ArrayList<>();

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();

        // Handle C8Y Operation Status
        if (c8yMessage.getPayload() != null) {
            try {
                resolvedMappings = mappingService.resolveMappingOutbound(tenant, c8yMessage, serviceConfiguration);
                consolidatedQos = connectorClient.determineMaxQosOutbound(resolvedMappings);
                result.setConsolidatedQos(consolidatedQos);

                // Check if at least one Code based mappings exits, then we nee to timeout the
                // execution
                for (Mapping mapping : resolvedMappings) {
                    if (mapping.isSubstitutionsAsCode()) {
                        result.setMaxCPUTimeMS(serviceConfiguration.getMaxCPUTimeMS());
                    }
                }
            } catch (Exception e) {
                log.warn("{} - Error resolving appropriate map. Could NOT be parsed. Ignoring this message!",
                        tenant, e);
                log.debug(e.getMessage(), e);
                mappingStatusUnspecified.errors++;
                return result;
            }
        } else {
            return result;
        }
        Future futureProcessingResult = virtualThreadPool.submit(
                new MappingOutboundTask(configurationRegistry, resolvedMappings, mappingService,
                        payloadProcessorsOutbound, c8yMessage, connectorClient));
        result.setProcessingResult(futureProcessingResult);

        return result;
    }
}