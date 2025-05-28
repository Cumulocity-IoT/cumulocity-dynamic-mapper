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

package dynamic.mapping.processor.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapping.configuration.TemplateType;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
import dynamic.mapping.model.Qos;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.MappingComponent;
import dynamic.mapping.model.SnoopStatus;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.ProcessingResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * AsynchronousDispatcherInbound
 * 
 * This class implements the <code>GenericMessageCallback</code> which is then
 * registered as a listener when new messages arrive.
 * It processes INBOUND messages and works asynchronously.
 * A task <code>GenericMessageCallback.MappingInboundTask</code> is added the
 * ExecutorService, to not block new arriving messages.
 * The call method in
 * <code>AsynchronousDispatcherInbound.MappingInboundTask</code> is the core of
 * the message processing.
 * For all resolved mappings the following steps are performed for new
 * messages:
 * ** deserialize the payload
 * ** extract the content from the payload based on the defined substitution in
 * the mapping and add these to a post processing cache
 * ** substitute in the defined target template of the mapping the extracted
 * content from the cache
 * ** send the resulting target payload to Cumulocity
 */

@Slf4j
public class DispatcherInbound implements GenericMessageCallback {

    private AConnectorClient connectorClient;

    private ExecutorService virtualThreadPool;

    private MappingComponent mappingComponent;

    private ConfigurationRegistry configurationRegistry;

    public DispatcherInbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.connectorClient = connectorClient;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.configurationRegistry = configurationRegistry;
    }

    public static class MappingInboundTask<T> implements Callable<List<ProcessingContext<?>>> {
        List<Mapping> resolvedMappings;
        Map<MappingType, BaseProcessorInbound<?>> payloadProcessorsInbound;
        ConnectorMessage connectorMessage;
        MappingComponent mappingComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;
        ServiceConfiguration serviceConfiguration;
        Timer inboundProcessingTimer;
        Counter inboundProcessingCounter;
        AConnectorClient connectorClient;
        ExecutorService virtualThreadPool;
        Engine graalsEngine;
        ConfigurationRegistry configurationRegistry;

        public MappingInboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
                ConnectorMessage message, AConnectorClient connectorClient) {
            this.connectorClient = connectorClient;
            this.resolvedMappings = resolvedMappings;
            this.mappingComponent = configurationRegistry.getMappingComponent();
            this.c8yAgent = configurationRegistry.getC8yAgent();
            this.payloadProcessorsInbound = configurationRegistry.getPayloadProcessorsInbound(message.getTenant());
            this.connectorMessage = message;
            this.objectMapper = configurationRegistry.getObjectMapper();
            this.serviceConfiguration = configurationRegistry.getServiceConfiguration(message.getTenant());
            this.inboundProcessingTimer = Timer.builder("dynmapper_inbound_processing_time")
                    .tag("tenant", connectorMessage.getTenant())
                    .tag("connector", connectorMessage.getConnectorIdentifier())
                    .description("Processing time of inbound messages").register(Metrics.globalRegistry);
            this.inboundProcessingCounter = Counter.builder("dynmapper_inbound_message_total")
                    .tag("tenant", connectorMessage.getTenant()).description("Total number of inbound messages")
                    .tag("connector", connectorMessage.getConnectorIdentifier()).register(Metrics.globalRegistry);
            this.graalsEngine = configurationRegistry.getGraalsEngine(message.getTenant());
            this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
            this.configurationRegistry = configurationRegistry;
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            Timer.Sample timer = Timer.start(Metrics.globalRegistry);
            String tenant = connectorMessage.getTenant();
            String topic = connectorMessage.getTopic();
            boolean sendPayload = connectorMessage.isSendPayload();

            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingComponent
                    .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);

            // Track if any critical exceptions occurred that should be propagated
            List<Exception> criticalExceptions = new ArrayList<>();

            // Process each mapping independently
            for (Mapping mapping : resolvedMappings) {
                // Skip inactive mappings or mappings not deployed inbound
                if (!mapping.getActive() ||
                        !connectorClient.isMappingInboundDeployedInbound(mapping.identifier)) {
                    continue;
                }

                MappingStatus mappingStatus = mappingComponent.getMappingStatus(tenant, mapping);
                Context graalsContext = null;

                // Create a basic context that includes identifying information even if
                // processing fails
                ProcessingContext<?> context = createBasicContext(tenant, topic, mapping);

                try {
                    // Get the appropriate processor for this mapping type
                    BaseProcessorInbound processor = payloadProcessorsInbound.get(mapping.mappingType);
                    if (processor == null) {
                        handleMissingProcessor(tenant, mapping, context, mappingStatus, mappingStatusUnspecified);
                        processingResult.add(context);
                        continue;
                    }

                    // Deserialize the payload - critical first step
                    Object payload = null;
                    try {
                        payload = processor.deserializePayload(mapping, connectorMessage);
                    } catch (IOException e) {
                        handleDeserializationError(tenant, mapping, e, context, mappingStatus);
                        processingResult.add(context);
                        continue;
                    }

                    // Now create the full context with the payload
                    context = createFullContext(tenant, topic, mapping, payload, sendPayload, connectorMessage);

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
                            continue;
                        }
                    }

                    // Log message and increment counter
                    inboundProcessingCounter.increment();
                    logInboundMessageReceived(tenant, mapping, context, serviceConfiguration);
                    mappingStatus.messagesReceived++;

                    // Handle snooping or normal processing
                    if (isSnoopingEnabled(mapping)) {
                        handleSnooping(tenant, mapping, context, mappingComponent, mappingStatus, objectMapper);
                    } else {
                        processMessage(tenant, mapping, context, processor, mappingStatus);
                    }
                } catch (Exception e) {
                    // Handle any uncaught exceptions
                    String errorMessage = String.format("Tenant %s - Unexpected error processing mapping %s: %s",
                            tenant, mapping.identifier, e.getMessage());
                    log.error(errorMessage, e);
                    context.addError(new ProcessingException(errorMessage, e));
                    mappingStatus.errors++;
                    mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);

                    // Determine if this is a critical exception that should be propagated
                    criticalExceptions.add(e);
                } finally {
                    // Clean up GraalVM context
                    if (graalsContext != null) {
                        try {
                            graalsContext.close();
                            graalsContext = null;
                        } catch (Exception e) {
                            log.warn("{} - Error closing GraalVM context: {}", tenant, e.getMessage());
                        }
                    }

                    // Always add the context to results, even if processing failed
                    processingResult.add(context);
                }
            }

            // Stop the timer
            timer.stop(inboundProcessingTimer);

            // Optionally propagate critical exceptions
            if (!criticalExceptions.isEmpty()) {
                Exception firstException = criticalExceptions.get(0);
                if (criticalExceptions.size() > 1) {
                    log.error("{} - Multiple critical exceptions occurred. First: {}",
                            tenant, firstException.getMessage());
                }
                throw new MappingProcessingException("Failed to process mappings", firstException);
            }

            return processingResult;
        }

        // Helper methods for cleaner code organization

        private ProcessingContext<?> createBasicContext(String tenant, String topic, Mapping mapping) {
            return ProcessingContext.builder()
                    .tenant(tenant)
                    .topic(topic)
                    .mapping(mapping)
                    .mappingType(mapping.mappingType)
                    .build();
        }

        private ProcessingContext<?> createFullContext(String tenant, String topic, Mapping mapping,
                Object payload, boolean sendPayload, ConnectorMessage connectorMessage) {
            return ProcessingContext.builder()
                    .payload(payload)
                    .rawPayload(connectorMessage.getPayload())
                    .topic(topic)
                    .mappingType(mapping.mappingType)
                    .mapping(mapping)
                    .sendPayload(sendPayload)
                    .tenant(tenant)
                    .supportsMessageContext(
                            connectorMessage.isSupportsMessageContext() && mapping.supportsMessageContext)
                    .key(connectorMessage.getKey())
                    .serviceConfiguration(serviceConfiguration)
                    .api(mapping.targetAPI)
                    .build();
        }

        private void handleMissingProcessor(String tenant, Mapping mapping, ProcessingContext<?> context,
                MappingStatus mappingStatus, MappingStatus mappingStatusUnspecified) {
            String errorMessage = String.format("Tenant %s - No processor for MessageType: %s registered",
                    tenant, mapping.mappingType);
            log.error(errorMessage);
            context.addError(new ProcessingException(errorMessage));
            mappingStatus.errors++;
            mappingStatusUnspecified.errors++;
            mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

        private void handleDeserializationError(String tenant, Mapping mapping, Exception e,
                ProcessingContext<?> context, MappingStatus mappingStatus) {
            String errorMessage = String.format("Tenant %s - Failed to deserialize payload: %s",
                    tenant, e.getMessage());
            log.warn(errorMessage);
            log.debug("{} - Deserialization error details:", tenant, e);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingComponent.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

        private Context setupGraalVMContext(Mapping mapping, ServiceConfiguration serviceConfiguration)
                throws Exception {
            Context graalsContext = Context.newBuilder("js")
                    .engine(graalsEngine)
                    //.option("engine.WarnInterpreterOnly", "false")
                    .allowHostAccess(configurationRegistry.getHostAccess())
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

        private void logInboundMessageReceived(String tenant, Mapping mapping, ProcessingContext<?> context,
                ServiceConfiguration serviceConfiguration) {
            if (serviceConfiguration.logPayload || mapping.debug) {
                Object pp = context.getPayload();
                String ppLog = null;

                if (pp instanceof byte[]) {
                    ppLog = new String((byte[]) pp, StandardCharsets.UTF_8);
                } else if (pp != null) {
                    ppLog = pp.toString();
                }
                log.info(
                        "{} - Start processing message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}, wrapped message: {}",
                        tenant, context.getTopic(), connectorClient.getConnectorIdentifier(), mapping.getName(),
                        mapping.getQos().ordinal(), ppLog);
            } else {
                log.info(
                        "{} - Start processing message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}",
                        tenant, context.getTopic(), connectorClient.getConnectorIdentifier(), mapping.getName(),
                        mapping.getQos().ordinal());
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

                    log.debug("{} - Adding snoopedTemplate to map: {},{},{}",
                            tenant, mapping.mappingTopic, mapping.snoopedTemplates.size(), mapping.snoopStatus);
                    mappingComponent.addDirtyMapping(tenant, mapping);
                } else {
                    log.warn("{} - Message could NOT be serialized for snooping", tenant);
                }
            } catch (Exception e) {
                log.warn("{} - Error during snooping: {}", tenant, e.getMessage());
                log.debug("{} - Snooping error details:", tenant, e);
            }
        }

        private void processMessage(String tenant, Mapping mapping, ProcessingContext<?> context,
                BaseProcessorInbound processor, MappingStatus mappingStatus) {
            try {
                // Processing pipeline
                processor.enrichPayload(context);
                processor.extractFromSource(context);

                // Check if we should continue processing
                if (!context.isIgnoreFurtherProcessing()) {
                    processor.validateProcessingCache(context);
                    processor.applyFilter(context);
                }

                // Final processing and sending
                if (!context.isIgnoreFurtherProcessing()) {
                    processor.substituteInTargetAndSend(context);
                    List<C8YRequest> resultRequests = context.getRequests();
                    if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                        mappingStatus.errors++;
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

        // Custom exception to propagate critical errors
        public static class MappingProcessingException extends Exception {
            public MappingProcessingException(String message, Throwable cause) {
                super(message, cause);
            }
        }
    }

    public ProcessingResult<?> processMessage(ConnectorMessage connectorMessage) {
        String topic = connectorMessage.getTopic();
        String tenant = connectorMessage.getTenant();
        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        if (serviceConfiguration.logPayload) {
            if (connectorMessage.getPayload() != null) {
                String payload = new String(connectorMessage.getPayload(), StandardCharsets.UTF_8);
                log.info("{} - PROCESSING: message on topic: [{}], payload: {}", tenant, topic, payload);
            }
        }

        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);

        List<Mapping> resolvedMappings = new ArrayList<>();
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult result = ProcessingResult.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (connectorMessage.getPayload() != null) {
                try {
                    resolvedMappings = mappingComponent.resolveMappingInbound(tenant, topic);
                    consolidatedQos = connectorClient.determineMaxQosInbound(resolvedMappings);
                    result.setConsolidatedQos(consolidatedQos);

                    // Check if at least one Code based mappings exists, then we need to timeout the
                    // execution
                    for (Mapping mapping : resolvedMappings) {
                        if (MappingType.CODE_BASED.equals(mapping.mappingType)) {
                            result.setMaxCPUTimeMS(serviceConfiguration.getMaxCPUTimeMS());
                        }
                    }

                } catch (Exception e) {
                    log.warn(
                            "{} - Error resolving appropriate map for topic {}. Could NOT be parsed. Ignoring this message!",
                            tenant, topic);
                    log.debug(e.getMessage(), e);
                    mappingStatusUnspecified.errors++;
                }
            } else {
                return result;
            }
        } else {
            return result;
        }

        Future<List<ProcessingContext<?>>> futureProcessingResult = virtualThreadPool.submit(
                new MappingInboundTask(configurationRegistry, resolvedMappings,
                        connectorMessage, connectorClient));
        result.setProcessingResult(futureProcessingResult);
        return result;
    }

    @Override
    public void onClose(String closeMessage, Throwable closeException) {
    }

    @Override
    public ProcessingResult<?> onMessage(ConnectorMessage message) {
        // TODO Return a future so it can be blocked for QoS 1 or 2
        return processMessage(message);
    }

    @Override
    public void onError(Throwable errorException) {
    }
}