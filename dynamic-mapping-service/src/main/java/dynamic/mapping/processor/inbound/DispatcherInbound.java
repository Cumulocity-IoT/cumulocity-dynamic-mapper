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

import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingStatus;
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
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Handler;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.bridge.SLF4JBridgeHandler;

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

    private static final Handler GRAALJS_LOG_HANDLER = new SLF4JBridgeHandler();

    private AConnectorClient connectorClient;

    private ExecutorService virtThreadPool;

    private MappingComponent mappingComponent;

    private ConfigurationRegistry configurationRegistry;

    private Counter inboundMessageCounter;

    public DispatcherInbound(ConfigurationRegistry configurationRegistry,
            AConnectorClient connectorClient) {
        this.connectorClient = connectorClient;
        this.virtThreadPool = configurationRegistry.getVirtThreadPool();
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
        Engine graalsEngine;
        ExecutorService virtThreadPool;

        public MappingInboundTask(ConfigurationRegistry configurationRegistry, List<Mapping> resolvedMappings,
                ConnectorMessage message, AConnectorClient connectorClient) {
            this.connectorClient = connectorClient;
            this.resolvedMappings = resolvedMappings;
            this.mappingComponent = configurationRegistry.getMappingComponent();
            this.c8yAgent = configurationRegistry.getC8yAgent();
            this.payloadProcessorsInbound = configurationRegistry.getPayloadProcessorsInbound()
                    .get(message.getTenant());
            this.connectorMessage = message;
            this.objectMapper = configurationRegistry.getObjectMapper();
            this.serviceConfiguration = configurationRegistry.getServiceConfigurations().get(message.getTenant());
            this.inboundProcessingTimer = Timer.builder("dynmapper_inbound_processing_time")
                    .tag("tenant", connectorMessage.getTenant())
                    .tag("connector", connectorMessage.getConnectorIdentifier())
                    .description("Processing time of inbound messages").register(Metrics.globalRegistry);
            this.inboundProcessingCounter = Counter.builder("dynmapper_inbound_message_total")
                    .tag("tenant", connectorMessage.getTenant()).description("Total number of inbound messages")
                    .tag("connector", connectorMessage.getConnectorIdentifier()).register(Metrics.globalRegistry);
            this.graalsEngine = configurationRegistry.getGraalsEngine();
            this.virtThreadPool = configurationRegistry.getVirtThreadPool();

        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            // long startTime = System.nanoTime();
            Timer.Sample timer = Timer.start(Metrics.globalRegistry);
            String tenant = connectorMessage.getTenant();
            String topic = connectorMessage.getTopic();
            boolean sendPayload = connectorMessage.isSendPayload();

            List<ProcessingContext<?>> processingResult = new ArrayList<>();
            MappingStatus mappingStatusUnspecified = mappingComponent
                    .getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
            resolvedMappings.forEach(mapping -> {
                // only process active mappings
                if (mapping.getActive()
                        && connectorClient.getMappingsDeployedInbound().containsKey(mapping.identifier)) {
                    MappingStatus mappingStatus = mappingComponent.getMappingStatus(tenant, mapping);
                    // identify the correct processor based on the mapping type
                    BaseProcessorInbound processor = payloadProcessorsInbound.get(mapping.mappingType);
                    try {
                        if (processor != null) {

                            // // prepare graals func if required
                            // Value extractFromSourceFunc = null;
                            // if (mapping.code != null) {
                            //     try (Context context = Context.newBuilder("js")
                            //             .engine(graalsEngine)
                            //             .allowAllAccess(true)
                            //             .option("js.strict", "true")
                            //             .build()) {
                            //         String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.identifier;
                            //         extractFromSourceFunc = context.getBindings("js").getMember(identifier);
                            //         if (extractFromSourceFunc == null) {
                            //             byte[] decodedBytes = Base64.getDecoder().decode(mapping.code);
                            //             String decodedCode = new String(decodedBytes);
                            //             String decodedCodeAdapted = decodedCode.replaceFirst(
                            //                     Mapping.EXTRACT_FROM_SOURCE,
                            //                     identifier);
                            //             Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                            //                     .buildLiteral();

                            //             // // make the engine evaluate the javascript script
                            //             context.eval(source);
                            //             extractFromSourceFunc = context.getBindings("js")
                            //                     .getMember(identifier);

                            //         }
                            //     }

                            // }
                            inboundProcessingCounter.increment();
                            Object payload = processor.deserializePayload(mapping, connectorMessage);
                            ProcessingContext<?> context = ProcessingContext.builder().payload(payload).topic(topic)
                                    .mappingType(mapping.mappingType).mapping(mapping).sendPayload(sendPayload)
                                    .tenant(tenant).supportsMessageContext(connectorMessage.isSupportsMessageContext()
                                            && mapping.supportsMessageContext)
                                    .key(connectorMessage.getKey()).serviceConfiguration(serviceConfiguration)
                                    // .graalsContext(mapping.code != null ? this.graalsContext : null)
                                    // .graalsContext(this.graalsContext)
                                    .graalsEngine(this.graalsEngine)
                                    .build();
                            if (serviceConfiguration.logPayload || mapping.debug) {
                                log.info("Tenant {} - New message on topic: {}, on connector: {}, wrapped message: {}",
                                        tenant,
                                        context.getTopic(),
                                        connectorClient.getConnectorIdentifier(),
                                        context.getPayload().toString());
                            } else {
                                log.info("Tenant {} - New message on topic: {}, on connector: {}", tenant,
                                        context.getTopic(), connectorClient.getConnectorIdentifier());
                            }
                            mappingStatus.messagesReceived++;
                            if (mapping.snoopStatus == SnoopStatus.ENABLED
                                    || mapping.snoopStatus == SnoopStatus.STARTED) {
                                String serializedPayload = objectMapper.writeValueAsString(context.getPayload());
                                if (serializedPayload != null) {
                                    mapping.addSnoopedTemplate(serializedPayload);
                                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                    mappingStatus.snoopedTemplatesActive++;

                                    log.debug("Tenant {} - Adding snoopedTemplate to map: {},{},{}", tenant,
                                            mapping.mappingTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mappingComponent.addDirtyMapping(tenant, mapping);

                                } else {
                                    log.warn(
                                            "Tenant {} - Message could NOT be parsed, ignoring this message, as class is not valid: {} {}",
                                            tenant,
                                            context.getPayload().getClass());
                                }
                            } else {
                                processor.enrichPayload(context);
                                processor.extractFromSource(context);
                                processor.validateProcessingCache(context);
                                processor.applyFilter(context);
                                if (!context.isIgnoreFurtherProcessing()) {
                                    processor.substituteInTargetAndSend(context);
                                    List<C8YRequest> resultRequests = context.getRequests();
                                    if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                        mappingStatus.errors++;
                                    }
                                }
                            }
                            processingResult.add(context);
                        } else {
                            mappingStatusUnspecified.errors++;
                            log.error("Tenant {} - No processor for MessageType: {} registered, ignoring this message!",
                                    tenant, mapping.mappingType);
                        }
                    } catch (Exception e) {
                        log.warn("Tenant {} - Message could NOT be parsed, ignoring this message: {}", tenant,
                                e.getMessage());
                        log.warn("Tenant {} - Message Stacktrace: ", tenant, e);
                        mappingStatus.errors++;
                    }
                }
            });
            timer.stop(inboundProcessingTimer);

            return processingResult;
        }
    }

    public Future<List<ProcessingContext<?>>> processMessage(ConnectorMessage message) {
        String topic = message.getTopic();
        String tenant = message.getTenant();

        MappingStatus mappingStatusUnspecified = mappingComponent.getMappingStatus(tenant, Mapping.UNSPECIFIED_MAPPING);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        List<Mapping> resolvedMappings = new ArrayList<>();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (message.getPayload() != null) {
                try {
                    resolvedMappings = mappingComponent.resolveMappingInbound(tenant, topic);
                } catch (Exception e) {
                    log.warn(
                            "Tenant {} - Error resolving appropriate map for topic {}. Could NOT be parsed. Ignoring this message!",
                            tenant, topic);
                    log.debug(e.getMessage(), e);
                    mappingStatusUnspecified.errors++;
                }
            } else {
                return futureProcessingResult;
            }
        } else {
            return futureProcessingResult;
        }

        futureProcessingResult = virtThreadPool.submit(
                new MappingInboundTask(configurationRegistry, resolvedMappings,
                        message, connectorClient));

        return futureProcessingResult;

    }

    @Override
    public void onClose(String closeMessage, Throwable closeException) {
    }

    @Override
    public void onMessage(ConnectorMessage message) {
        processMessage(message);
    }

    @Override
    public void onError(Throwable errorException) {
    }
}