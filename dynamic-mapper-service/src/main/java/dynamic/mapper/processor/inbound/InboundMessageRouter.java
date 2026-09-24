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
package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.processor.DeserializationInboundProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.InternalProtobufProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SendInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionResultInboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.processor.util.MessageRoutingSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * In-process replacement for the former Camel {@code DynamicMapperInboundRoutes}
 * {@code RouteBuilder}. Fans a single inbound {@link ConnectorMessage} out across all
 * valid mappings resolved for its topic, running one mapping's whole pipeline per
 * virtual thread, and collects the resulting {@link ProcessingContext}s.
 */
@Slf4j
@Component
public class InboundMessageRouter extends MessageRoutingSupport {

    private final ExecutorService virtualThreadPool;

    private final ExtensibleInboundProcessor extensibleProcessor;

    private final ExtensibleResultInboundProcessor extensibleResultInboundProcessor;

    private final InternalProtobufProcessor internalProtobufProcessor;

    private final EnrichmentInboundProcessor enrichmentProcessor;

    private final FlowInboundProcessor flowInboundProcessor;

    private final SubstitutionResultInboundProcessor substitutionInboundProcessor;

    private final DeserializationInboundProcessor deserializationInboundProcessor;

    private final JSONataInboundProcessor jsonataExtractionInboundProcessor;

    private final FlowResultInboundProcessor flowResultInboundProcessor;

    private final FilterInboundProcessor filterInboundProcessor;

    private final SendInboundProcessor inboundSendProcessor;

    private final ConsolidationProcessor consolidationProcessor;

    public InboundMessageRouter(
            ConnectorRegistry connectorRegistry,
            @Qualifier("virtualThreadPool") ExecutorService virtualThreadPool,
            ExtensibleInboundProcessor extensibleProcessor,
            ExtensibleResultInboundProcessor extensibleResultInboundProcessor,
            InternalProtobufProcessor internalProtobufProcessor,
            EnrichmentInboundProcessor enrichmentProcessor,
            FlowInboundProcessor flowInboundProcessor,
            SubstitutionResultInboundProcessor substitutionInboundProcessor,
            DeserializationInboundProcessor deserializationInboundProcessor,
            JSONataInboundProcessor jsonataExtractionInboundProcessor,
            FlowResultInboundProcessor flowResultInboundProcessor,
            FilterInboundProcessor filterInboundProcessor,
            SendInboundProcessor inboundSendProcessor,
            ConsolidationProcessor consolidationProcessor) {
        super(connectorRegistry);
        this.virtualThreadPool = virtualThreadPool;
        this.extensibleProcessor = extensibleProcessor;
        this.extensibleResultInboundProcessor = extensibleResultInboundProcessor;
        this.internalProtobufProcessor = internalProtobufProcessor;
        this.enrichmentProcessor = enrichmentProcessor;
        this.flowInboundProcessor = flowInboundProcessor;
        this.substitutionInboundProcessor = substitutionInboundProcessor;
        this.deserializationInboundProcessor = deserializationInboundProcessor;
        this.jsonataExtractionInboundProcessor = jsonataExtractionInboundProcessor;
        this.flowResultInboundProcessor = flowResultInboundProcessor;
        this.filterInboundProcessor = filterInboundProcessor;
        this.inboundSendProcessor = inboundSendProcessor;
        this.consolidationProcessor = consolidationProcessor;
    }

    /**
     * Main processing entry point (transport agnostic). Mirrors the former
     * {@code direct:processInboundMessage} → {@code direct:processWithMappingsInbound} → split
     * chain: filters {@code mappings} down to active/deployed ones, then processes each valid
     * mapping's pipeline on its own virtual thread and joins the results.
     */
    public List<ProcessingContext<Object>> processInboundMessage(ConnectorMessage connectorMessage,
            List<Mapping> mappings, boolean testing, ServiceConfiguration serviceConfiguration,
            ProcessingResultWrapper<?> resultWrapper) {
        if (mappings == null) {
            return new ArrayList<>();
        }

        String tenant = connectorMessage.getTenant();
        String connectorIdentifier = connectorMessage.getConnectorIdentifier();

        List<Mapping> validMappings = mappings.stream()
                .filter(mapping -> isValidMapping(tenant, mapping, connectorIdentifier))
                .toList();

        if (validMappings.isEmpty()) {
            log.info("{} - All {} candidate mapping(s) filtered out for connector {} — no processing will occur",
                    tenant, mappings.size(), connectorIdentifier);
            return new ArrayList<>();
        }
        log.info("{} - Filtered {} candidate mapping(s) to {} valid mapping(s) for connector {}",
                tenant, mappings.size(), validMappings.size(), connectorIdentifier);

        List<CompletableFuture<ProcessingContext<Object>>> futures = validMappings.stream()
                .map(mapping -> CompletableFuture.supplyAsync(
                        () -> processSingleInboundMapping(mapping, connectorMessage, testing, serviceConfiguration,
                                resultWrapper),
                        virtualThreadPool))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Single mapping processing pipeline. Mirrors the former
     * {@code direct:processSingleInboundMapping} route.
     */
    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> processSingleInboundMapping(Mapping mapping, ConnectorMessage connectorMessage,
            boolean testing, ServiceConfiguration serviceConfiguration, ProcessingResultWrapper<?> resultWrapper) {
        ProcessingContext<?> context = null;
        try {
            // 0. Common processing for all
            context = deserializationInboundProcessor.process(connectorMessage.getTenant(), mapping,
                    connectorMessage, serviceConfiguration, testing);
            if (resultWrapper != null) {
                context.setProcessingResultWrapper(resultWrapper);
            }

            enrichmentProcessor.process(context, connectorMessage.getConnectorIdentifier());
            filterInboundProcessor.process((ProcessingContext<Object>) context);

            // Check if further processing should be ignored after enrichment
            if (shouldIgnoreFurtherProcessing(context)) {
                log.debug("{} - inbound message filtered after enrichment", context.getTenant());
                consolidationProcessor.process(context);
                return (ProcessingContext<Object>) context;
            }

            // 1. Branch based on processing type
            if (isInternalProtobuf(context)) {
                processInternalProtobuf((ProcessingContext<byte[]>) context);
            } else if (isExtension(context)) {
                processExtension((ProcessingContext<byte[]>) context);
            } else if (isJSONataExtraction(context)) {
                processJSONataExtraction(context);
            } else if (isFlowFunction(context)) {
                processFlowFunction(context);
            } else {
                // Default fallback — unknown/unmatched TransformationType
                log.warn("{} - No matching transformation type for mapping '{}' (type={}), falling back to JSONata",
                        context.getTenant(),
                        context.getMapping() != null ? context.getMapping().getName() : "unknown",
                        context.getMapping() != null ? context.getMapping().getTransformationType() : "null");
                processJSONataExtraction(context);
            }
            return (ProcessingContext<Object>) context;
        } catch (Exception e) {
            handleRouteException(e, "INBOUND");
            if (context != null) {
                context.addError(new dynamic.mapper.processor.ProcessingException(e.getMessage(), e));
                try {
                    consolidationProcessor.process(context);
                } catch (Exception closeError) {
                    log.warn("{} - Error consolidating context after route exception: {}",
                            context.getTenant(), closeError.getMessage());
                }
            }
            return (ProcessingContext<Object>) context;
        }
    }

    /** 1b. JSONata extraction processing route. */
    private void processJSONataExtraction(ProcessingContext<?> context) throws Exception {
        jsonataExtractionInboundProcessor.process(context);
        substitutionInboundProcessor.process((ProcessingContext<Object>) context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - inbound message filtered after JSONata extraction", context.getTenant());
            consolidationProcessor.process(context);
            return;
        }
        inboundSendProcessor.process((ProcessingContext<Object>) context);
        consolidationProcessor.process(context);
    }

    /** 1d. Extension processing route. */
    private void processExtension(ProcessingContext<byte[]> context) throws Exception {
        extensibleProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - inbound message filtered after extension processing", context.getTenant());
            consolidationProcessor.process(context);
            return;
        }
        extensibleResultInboundProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - inbound message filtered after extension result processing", context.getTenant());
            consolidationProcessor.process(context);
            return;
        }
        inboundSendProcessor.process((ProcessingContext<Object>) (ProcessingContext<?>) context);
        consolidationProcessor.process(context);
    }

    /** 1f. Internal protobuf processing route. */
    private void processInternalProtobuf(ProcessingContext<byte[]> context) throws Exception {
        internalProtobufProcessor.process(context);
        substitutionInboundProcessor.process((ProcessingContext<Object>) (ProcessingContext<?>) context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - inbound message filtered after internal-protobuf extraction", context.getTenant());
            consolidationProcessor.process(context);
            return;
        }
        inboundSendProcessor.process((ProcessingContext<Object>) (ProcessingContext<?>) context);
        consolidationProcessor.process(context);
    }

    /** 1e. Flow function processing route. */
    private void processFlowFunction(ProcessingContext<?> context) throws Exception {
        flowInboundProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - inbound message filtered after flow function processing", context.getTenant());
            consolidationProcessor.process(context);
            return;
        }
        flowResultInboundProcessor.process(context);
        inboundSendProcessor.process((ProcessingContext<Object>) context);
        consolidationProcessor.process(context);
    }

    /**
     * Check if mapping is deployed for the connector
     */
    @Override
    public boolean isMappingDeployed(String tenant, Mapping mapping, String connectorIdentifier) {
        try {
            AConnectorClient connector = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            return connector != null && connector.isMappingInboundDeployed(mapping.getIdentifier());

        } catch (Exception e) {
            log.warn("Error checking mapping deployment status: {}", e.getMessage());
            return true; // Default to allowing processing
        }
    }

}
