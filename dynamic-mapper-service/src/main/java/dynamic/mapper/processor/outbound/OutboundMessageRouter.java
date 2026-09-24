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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.outbound.processor.DeserializationOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.EnrichmentOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.ExtensibleOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.ExtensibleResultOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowResultOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.JSONataOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SendOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SubstitutionResultOutboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.processor.util.MessageRoutingSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * In-process replacement for the former Camel {@code DynamicMapperOutboundRoutes}
 * {@code RouteBuilder}. Fans a single outbound {@link C8YMessage} out across all valid
 * mappings resolved for it, running one mapping's whole pipeline per virtual thread, and
 * collects the resulting {@link ProcessingContext}s.
 */
@Slf4j
@Component
public class OutboundMessageRouter extends MessageRoutingSupport {

    private final ExecutorService virtualThreadPool;

    private final EnrichmentOutboundProcessor enrichmentProcessor;

    private final ExtensibleOutboundProcessor extensibleOutboundProcessor;

    private final ExtensibleResultOutboundProcessor extensibleResultOutboundProcessor;

    private final FlowOutboundProcessor flowOutboundProcessor;

    private final SubstitutionResultOutboundProcessor substitutionOutboundProcessor;

    private final DeserializationOutboundProcessor deserializationOutboundProcessor;

    private final JSONataOutboundProcessor jsonataExtractionOutboundProcessor;

    private final FlowResultOutboundProcessor flowResultOutboundProcessor;

    private final SendOutboundProcessor outboundSendProcessor;

    private final ConsolidationProcessor consolidationProcessor;

    public OutboundMessageRouter(
            ConnectorRegistry connectorRegistry,
            @Qualifier("virtualThreadPool") ExecutorService virtualThreadPool,
            EnrichmentOutboundProcessor enrichmentProcessor,
            ExtensibleOutboundProcessor extensibleOutboundProcessor,
            ExtensibleResultOutboundProcessor extensibleResultOutboundProcessor,
            FlowOutboundProcessor flowOutboundProcessor,
            SubstitutionResultOutboundProcessor substitutionOutboundProcessor,
            DeserializationOutboundProcessor deserializationOutboundProcessor,
            JSONataOutboundProcessor jsonataExtractionOutboundProcessor,
            FlowResultOutboundProcessor flowResultOutboundProcessor,
            SendOutboundProcessor outboundSendProcessor,
            ConsolidationProcessor consolidationProcessor) {
        super(connectorRegistry);
        this.virtualThreadPool = virtualThreadPool;
        this.enrichmentProcessor = enrichmentProcessor;
        this.extensibleOutboundProcessor = extensibleOutboundProcessor;
        this.extensibleResultOutboundProcessor = extensibleResultOutboundProcessor;
        this.flowOutboundProcessor = flowOutboundProcessor;
        this.substitutionOutboundProcessor = substitutionOutboundProcessor;
        this.deserializationOutboundProcessor = deserializationOutboundProcessor;
        this.jsonataExtractionOutboundProcessor = jsonataExtractionOutboundProcessor;
        this.flowResultOutboundProcessor = flowResultOutboundProcessor;
        this.outboundSendProcessor = outboundSendProcessor;
        this.consolidationProcessor = consolidationProcessor;
    }

    /**
     * Main processing entry point (transport agnostic). Mirrors the former
     * {@code direct:processOutboundMessage} → {@code direct:processWithMappingsOutbound} → split
     * chain: filters {@code mappings} down to active/deployed ones, then processes each valid
     * mapping's pipeline on its own virtual thread and joins the results.
     */
    public List<ProcessingContext<Object>> processOutboundMessage(C8YMessage c8yMessage, String connectorIdentifier,
            List<Mapping> mappings, boolean testing, ServiceConfiguration serviceConfiguration,
            ProcessingResultWrapper<?> resultWrapper) {
        if (mappings == null) {
            return new ArrayList<>();
        }

        String tenant = c8yMessage.getTenant();

        List<Mapping> validMappings = mappings.stream()
                .filter(mapping -> isValidMapping(tenant, mapping, connectorIdentifier))
                .toList();

        if (validMappings.isEmpty()) {
            log.info("{} - All {} candidate mapping(s) filtered out for connector {} — no processing will occur",
                    tenant, mappings.size(), connectorIdentifier);
            return new ArrayList<>();
        }
        log.debug("{} - Filtered {} candidate mapping(s) to {} valid mapping(s) for connector {}",
                tenant, mappings.size(), validMappings.size(), connectorIdentifier);

        List<CompletableFuture<ProcessingContext<Object>>> futures = validMappings.stream()
                .map(mapping -> CompletableFuture.supplyAsync(
                        () -> processSingleOutboundMapping(mapping, c8yMessage, connectorIdentifier, testing,
                                serviceConfiguration, resultWrapper),
                        virtualThreadPool))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Single mapping processing pipeline. Mirrors the former
     * {@code direct:processSingleOutboundMapping} route.
     */
    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> processSingleOutboundMapping(Mapping mapping, C8YMessage c8yMessage,
            String connectorIdentifier, boolean testing, ServiceConfiguration serviceConfiguration,
            ProcessingResultWrapper<?> resultWrapper) {
        ProcessingContext<?> context = null;
        try {
            // 0. Common processing for all
            context = deserializationOutboundProcessor.process(c8yMessage.getTenant(), mapping, c8yMessage,
                    serviceConfiguration, testing);
            if (resultWrapper != null) {
                context.setProcessingResultWrapper(resultWrapper);
            }

            enrichmentProcessor.process(context, connectorIdentifier);
            // Note: outbound has no topic/payload filter step (FilterInboundProcessor is
            // inbound-only). Outbound filtering is entirely handled by the connector's
            // subscription topic, not by a post-enrichment predicate.

            // Check if further processing should be ignored after enrichment
            if (shouldIgnoreFurtherProcessing(context)) {
                log.debug("{} - outbound message filtered after enrichment", context.getTenant());
                consolidationProcessor.process(context);
                return (ProcessingContext<Object>) context;
            }

            // 1. Branch based on processing type
            if (isExtension(context)) {
                processOutboundExtension((ProcessingContext<Object>) context, connectorIdentifier);
            } else if (isFlowFunction(context)) {
                processOutboundFlowFunction(context, connectorIdentifier);
            } else if (isJSONataExtraction(context)) {
                processOutboundJSONataExtraction(context, connectorIdentifier);
            } else {
                // Default fallback — unknown/unmatched TransformationType
                log.warn("{} - No matching transformation type for mapping '{}' (type={}), falling back to JSONata",
                        context.getTenant(),
                        context.getMapping() != null ? context.getMapping().getName() : "unknown",
                        context.getMapping() != null ? context.getMapping().getTransformationType() : "null");
                processOutboundJSONataExtraction(context, connectorIdentifier);
            }
            return (ProcessingContext<Object>) context;
        } catch (Exception e) {
            handleRouteException(e, "OUTBOUND");
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

    /** 1b. Extension processing route. */
    private void processOutboundExtension(ProcessingContext<Object> context, String connectorIdentifier)
            throws Exception {
        extensibleOutboundProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - outbound message filtered after extension processing", context.getTenant());
            // Still call SendOutboundProcessor so it can auto-ack the operation as
            // FAILED when the extension caused processing to be skipped.
            outboundSendProcessor.process(context, connectorIdentifier);
            consolidationProcessor.process(context);
            return;
        }
        extensibleResultOutboundProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - outbound message filtered after extension result processing", context.getTenant());
            outboundSendProcessor.process(context, connectorIdentifier);
            consolidationProcessor.process(context);
            return;
        }
        outboundSendProcessor.process(context, connectorIdentifier);
        consolidationProcessor.process(context);
    }

    /** 1c. Flow function processing route. */
    private void processOutboundFlowFunction(ProcessingContext<?> context, String connectorIdentifier)
            throws Exception {
        flowOutboundProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - outbound message filtered after flow function processing", context.getTenant());
            // Still call SendOutboundProcessor so it can auto-ack the operation as
            // FAILED when a JS error caused processing to be skipped.
            outboundSendProcessor.process((ProcessingContext<Object>) context, connectorIdentifier);
            consolidationProcessor.process(context);
            return;
        }
        flowResultOutboundProcessor.process(context);
        outboundSendProcessor.process((ProcessingContext<Object>) context, connectorIdentifier);
        consolidationProcessor.process(context);
    }

    /** 1e. JSONata extraction processing route. */
    private void processOutboundJSONataExtraction(ProcessingContext<?> context, String connectorIdentifier)
            throws Exception {
        jsonataExtractionOutboundProcessor.process(context);
        substitutionOutboundProcessor.process(context);
        if (shouldIgnoreFurtherProcessing(context)) {
            log.debug("{} - outbound message filtered after JSONata extraction", context.getTenant());
            // Still call SendOutboundProcessor so it can auto-ack the operation as
            // FAILED when substitution/extraction caused processing to be skipped.
            outboundSendProcessor.process((ProcessingContext<Object>) context, connectorIdentifier);
            consolidationProcessor.process(context);
            return;
        }
        outboundSendProcessor.process((ProcessingContext<Object>) context, connectorIdentifier);
        consolidationProcessor.process(context);
    }

    /**
     * Override for outbound-specific mapping deployment check
     */
    @Override
    public boolean isMappingDeployed(String tenant, Mapping mapping, String connectorIdentifier) {
        try {
            AConnectorClient connector = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            return connector != null && connector.isMappingOutboundDeployed(mapping.getIdentifier());

        } catch (Exception e) {
            log.warn("Error checking outbound mapping deployment status: {}", e.getMessage());
            return true; // Default to allowing processing
        }
    }
}
