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

package dynamic.mapping.processor.outbound;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import com.cumulocity.model.JSONBase;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.dashjoin.jsonata.json.Json;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.TemplateType;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.model.Qos;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.notification.websocket.NotificationCallback;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.ProcessingResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.model.API;
import dynamic.mapping.notification.C8YNotificationSubscriber;
import dynamic.mapping.notification.websocket.Notification;
import dynamic.mapping.processor.C8YMessage;
import dynamic.mapping.processor.ProcessingException;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
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

    protected C8YNotificationSubscriber notificationSubscriber;

    protected C8YAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected ExecutorService virtualThreadPool;

    protected MappingComponent mappingComponent;

    protected ConfigurationRegistry configurationRegistry;

    protected Map<MappingType, BaseProcessorOutbound<?>> payloadProcessorsOutbound;

    // The Outbound Dispatcher is hardly connected to the Connector otherwise it is
    // not possible to correlate messages received bei Notification API to the
    // correct Connector
    public DispatcherOutbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.connectorClient = connectorClient;
        // log.info("Tenant {} - HIER I {} {}", connectorClient.getTenant(),
        // configurationRegistry.getPayloadProcessorsOutbound());
        // log.info("Tenant {} - HIER II {} {}", connectorClient.getTenant(),
        // configurationRegistry.getPayloadProcessorsOutbound().get(connectorClient.getTenant()));
        this.payloadProcessorsOutbound = configurationRegistry.getPayloadProcessorsOutbound(connectorClient.getTenant(),
                connectorClient.getConnectorIdentifier());
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();

    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("Tenant {} - Phase IV: Notification 2.0 connected over WebSocket, linked to connector: {}",
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
            log.warn("Tenant {} - Notification message received but connector {} is not connected. Ignoring message..",
                    tenant, connectorClient.getConnectorName());
        // log.info("Tenant {} - Notification message received {}",
        // tenant, operation);
        if (("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))
                && connectorClient.isConnected()) {
            // log.info("Tenant {} - Notification received: <{}>, <{}>, <{}>, <{}>", tenant,
            // notification.getMessage(),
            // notification.getNotificationHeaders(),
            // connectorClient.connectorConfiguration.name,
            // connectorClient.isConnected());
            if ("UPDATE".equals(notification.getOperation()) && notification.getApi().equals(API.OPERATION)) {
                log.info("Tenant {} - Update Operation message for connector {} is received, ignoring it",
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
        log.error("Tenant {} - We got an exception: ", connectorClient.getTenant(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("Tenant {} - WebSocket connection closed", connectorClient.getTenant());
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
        MappingComponent mappingComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;
        ServiceConfiguration serviceConfiguration;
        AConnectorClient connectorClient;
        Engine graalsEngine;
        Timer outboundProcessingTimer;
        Counter outboundProcessingCounter;

        public MappingOutboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
                MappingComponent mappingComponent,
                Map<MappingType, BaseProcessorOutbound<T>> payloadProcessorsOutbound,
                C8YMessage c8yMessage, AConnectorClient connectorClient) {
            this.connectorClient = connectorClient;
            this.resolvedMappings = resolvedMappings;
            this.mappingComponent = mappingComponent;
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
            this.graalsEngine = configurationRegistry.getGraalsEngine(c8yMessage.getTenant());
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            Timer.Sample timer = Timer.start(Metrics.globalRegistry);
            String tenant = c8yMessage.getTenant();
            boolean sendPayload = c8yMessage.isSendPayload();

            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingComponent
                    .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);

            // Parse operation representation if applicable
            OperationRepresentation op = null;
            if (c8yMessage.getApi().equals(API.OPERATION)) {
                try {
                    op = JSONBase.getJSONParser().parse(OperationRepresentation.class, c8yMessage.getPayload());
                } catch (Exception e) {
                    log.error("Tenant {} - Failed to parse operation representation: {}", tenant, e.getMessage());
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
                        !connectorClient.getMappingsDeployedOutbound().containsKey(mapping.identifier)) {
                    continue;
                }

                MappingStatus mappingStatus = mappingComponent.getMappingStatus(tenant, mapping);
                Context graalsContext = null;

                // Create a basic context that includes identifying information even if
                // processing fails
                ProcessingContext<?> context = createBasicContext(tenant, mapping);

                // Handle auto-acknowledgment for operations
                if (op != null && Boolean.TRUE.equals(mapping.getAutoAckOperation())) {
                    try {
                        c8yAgent.updateOperationStatus(tenant, op, OperationStatus.EXECUTING, null);
                        shouldAutoAcknowledge = true;
                    } catch (Exception e) {
                        log.warn("Tenant {} - Failed to update operation status to EXECUTING: {}",
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
                            graalsContext = setupGraalVMContext(mapping, serviceConfiguration);
                            context.setGraalsContext(graalsContext);
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
                        handleSnooping(tenant, mapping, context, mappingComponent, mappingStatus, objectMapper);
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
                    mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
                } finally {
                    // Clean up GraalVM context
                    if (graalsContext != null) {
                        try {
                            graalsContext.close();
                        } catch (Exception e) {
                            log.warn("Tenant {} - Error closing GraalVM context: {}", tenant, e.getMessage());
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
            mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);

        }

        private Context setupGraalVMContext(Mapping mapping, ServiceConfiguration serviceConfiguration)
                throws Exception {
            HostAccess customHostAccess = HostAccess.newBuilder()
                    // Allow access to public members of accessible classes
                    .allowPublicAccess(true)
                    // Allow array access for basic functionality
                    .allowArrayAccess(true)
                    // Allow List operations
                    .allowListAccess(true)
                    // Allow Map operations
                    .allowMapAccess(true)
                    .build();
            Context graalsContext = Context.newBuilder("js")
                    .option("engine.WarnInterpreterOnly", "false")
                    .allowHostAccess(customHostAccess)
                    .allowHostClassLookup(className ->
                    // Allow only the specific SubstitutionContext class
                    className.equals("dynamic.mapping.processor.model.SubstitutionContext")
                            || className.equals("dynamic.mapping.processor.model.SubstitutionResult")
                            || className.equals("dynamic.mapping.processor.model.SubstituteValue")
                            || className.equals("dynamic.mapping.processor.model.SubstituteValue$TYPE")
                            || className.equals("dynamic.mapping.processor.model.RepairStrategy")
                            // Allow base collection classes needed for return values
                            || className.equals("java.util.ArrayList") ||
                            className.equals("java.util.HashMap"))
                    .build();

            String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
            Value extractFromSourceFunc = graalsContext.getBindings("js").getMember(identifier);

            if (extractFromSourceFunc == null) {
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst(
                        Mapping.EXTRACT_FROM_SOURCE,
                        identifier);
                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();

                graalsContext.eval(source);
            }

            return graalsContext;
        }

        private void handleGraalVMError(String tenant, Mapping mapping, Exception e,
                ProcessingContext<?> context, MappingStatus mappingStatus) {
            String errorMessage = String.format("Tenant %s - Failed to set up GraalVM context: %s",
                    tenant, e.getMessage());
            log.error(errorMessage, e);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

        private void logOutboundMessageReceived(String tenant, Mapping mapping, ProcessingContext<?> context,
                ServiceConfiguration serviceConfiguration) {
            if (serviceConfiguration.logPayload || mapping.debug) {
                log.info(
                        "Tenant {} - Start processing message on topic: [{}]  connector: {}, mapping : {}, wrapped message: {}",
                        tenant, context.getTopic(), connectorClient.getConnectorName(), mapping.getName(),
                        context.getPayload().toString());
            } else {
                log.info(
                        "Tenant {} - Start processing message on topic: [{}]  connector: {}, mapping : {}, sendPayload: {}",
                        tenant, context.getTopic(), connectorClient.getConnectorName(), mapping.getName(),
                        context.isSendPayload());
            }
        }

        private boolean isSnoopingEnabled(Mapping mapping) {
            return mapping.snoopStatus == SnoopStatus.ENABLED || mapping.snoopStatus == SnoopStatus.STARTED;
        }

        private void handleSnooping(String tenant, Mapping mapping, ProcessingContext<?> context,
                MappingComponent mappingComponent, MappingStatus mappingStatus, ObjectMapper objectMapper) {
            try {
                String serializedPayload = objectMapper.writeValueAsString(context.getPayload());
                if (serializedPayload != null) {
                    mapping.addSnoopedTemplate(serializedPayload);
                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                    mappingStatus.snoopedTemplatesActive++;

                    log.debug("Tenant {} - Adding snoopedTemplate to map: {},{},{}",
                            tenant, mapping.mappingTopic, mapping.snoopedTemplates.size(), mapping.snoopStatus);
                    mappingComponent.addDirtyMapping(tenant, mapping);
                } else {
                    log.warn("Tenant {} - Message could NOT be serialized for snooping, payload is null");
                }
            } catch (Exception e) {
                log.warn("Tenant {} - Error during snooping: {}", tenant, e.getMessage());
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
                        mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
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
                mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
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
                    log.info("Tenant {} - Operation {} marked as FAILED", tenant, op.getId());
                } else {
                    c8yAgent.updateOperationStatus(tenant, op, OperationStatus.SUCCESSFUL, null);
                    log.info("Tenant {} - Operation {} marked as SUCCESSFUL", tenant, op.getId());
                }
            } catch (Exception e) {
                log.error("Tenant {} - Failed to update operation status: {}", tenant, e.getMessage());
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

        log.info("Tenant {} - PROCESSING: C8Y message, API: {}, device: {}. connector: {}, payload: {}",
                tenant,
                c8yMessage.getApi(), c8yMessage.getSourceId(),
                connectorClient.getConnectorName(),
                c8yMessage.getMessageId());

        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        List<Mapping> resolvedMappings = new ArrayList<>();

        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();

        // Handle C8Y Operation Status
        if (c8yMessage.getPayload() != null) {
            try {
                resolvedMappings = mappingComponent.resolveMappingOutbound(tenant, c8yMessage);
                consolidatedQos = connectorClient.determineMaxQosOutbound(resolvedMappings);
                result.setConsolidatedQos(consolidatedQos);

                // Check if at least one Code based mappings exits, then we nee to timeout the
                // execution
                for (Mapping mapping : resolvedMappings) {
                    if (MappingType.CODE_BASED.equals(mapping.mappingType)) {
                        result.setMaxCPUTimeMS(serviceConfiguration.getMaxCPUTimeMS());
                    }
                }
            } catch (Exception e) {
                log.warn("Tenant {} - Error resolving appropriate map. Could NOT be parsed. Ignoring this message!",
                        tenant, e);
                log.debug(e.getMessage(), tenant);
                mappingStatusUnspecified.errors++;
            }
        } else {
            return result;
        }
        Future futureProcessingResult = virtualThreadPool.submit(
                new MappingOutboundTask(configurationRegistry, resolvedMappings, mappingComponent,
                        payloadProcessorsOutbound, c8yMessage, connectorClient));
        result.setProcessingResult(futureProcessingResult);

        return result;
    }
}