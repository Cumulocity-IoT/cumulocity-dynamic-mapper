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
package dynamic.mapper.processor.inbound.route;

import dynamic.mapper.processor.util.CamelHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.processor.DeserializationInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.InternalProtobufProcessor;
import dynamic.mapper.processor.inbound.processor.SendInboundProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataInboundProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionResultInboundProcessor;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.util.RequestAggregationStrategy;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.processor.util.DynamicMapperBaseRoutes;

@Component
public class DynamicMapperInboundRoutes extends DynamicMapperBaseRoutes {

    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Autowired
    private ExtensibleInboundProcessor extensibleProcessor;

    @Autowired
    private ExtensibleResultInboundProcessor extensibleResultInboundProcessor;

    @Autowired
    private InternalProtobufProcessor internalProtobufProcessor;

    @Autowired
    private EnrichmentInboundProcessor enrichmentProcessor;

    @Autowired
    private FlowInboundProcessor flowInboundProcessor;

    @Autowired
    private SubstitutionResultInboundProcessor substitutionInboundProcessor;

    @Autowired
    private DeserializationInboundProcessor deserializationInboundProcessor;

    @Autowired
    private JSONataInboundProcessor jsonataExtractionInboundProcessor;

    @Autowired
    private FlowResultInboundProcessor flowResultInboundProcessor;

    @Autowired
    private FilterInboundProcessor filterInboundProcessor;

    @Autowired
    private SendInboundProcessor inboundSendProcessor;

    @Autowired
    private ConsolidationProcessor consolidationProcessor;

    @Autowired
    private ProcessingContextAggregationStrategy processingContextAggregationStrategy;

    @Autowired
    private RequestAggregationStrategy requestAggregationStrategy;

    @Override
    public void configure() throws Exception {

        // Global error handling
        onException(Exception.class)
                .handled(true)
                .process(exchange -> handleRouteException(exchange, "INBOUND"))
                .to("direct:inboundErrorHandling");

        // Main processing entry point (transport agnostic)
        from("direct:processInboundMessage")
                .routeId("inbound-message-processor")
                .choice()
                .when(header(CamelHeaders.MAPPINGS).isNull())
                .process(exchange -> {
                    // No mappings found - return empty contexts list
                    exchange.getIn().setHeader(CamelHeaders.PROCESSED_CONTEXTS, new ArrayList<ProcessingContext<Object>>());
                })
                .stop()
                .otherwise()
                .to("direct:processWithMappingsInbound");

        // Process message with found mappings
        from("direct:processWithMappingsInbound")
                .routeId("single-inbound-mapping-processor")
                .process(exchange -> {
                    // Filter mappings before splitting: active and deployed
                    @SuppressWarnings("unchecked")
                    List<Mapping> allMappings = exchange.getIn().getHeader(CamelHeaders.MAPPINGS, List.class);
                    String connectorIdentifier = exchange.getIn().getHeader(CamelHeaders.CONNECTOR_IDENTIFIER, String.class);
                    String tenant = exchange.getIn().getHeader(CamelHeaders.TENANT, String.class);

                    if (allMappings != null) {
                        List<Mapping> validMappings = allMappings.stream()
                                .filter(mapping -> isValidMapping(tenant, mapping, connectorIdentifier))
                                .collect(java.util.stream.Collectors.toList());

                        exchange.getIn().setHeader(CamelHeaders.MAPPINGS, validMappings);
                        if (validMappings.isEmpty()) {
                            log.info("{} - All {} candidate mapping(s) filtered out for connector {} — no processing will occur",
                                    tenant, allMappings.size(), connectorIdentifier);
                        } else {
                            log.info("{} - Filtered {} candidate mapping(s) to {} valid mapping(s) for connector {}",
                                    tenant, allMappings.size(), validMappings.size(), connectorIdentifier);
                        }
                    }
                })
                .split(header(CamelHeaders.MAPPINGS))
                .parallelProcessing(true)
                .executorService(virtualThreadPool)
                .aggregationStrategy(processingContextAggregationStrategy)
                .to("direct:processSingleInboundMapping")
                .end();

        // Single mapping processing pipeline
        from("direct:processSingleInboundMapping")
                .routeId("single-filtered-inbound-mapping-processor")
                // 0. Common processing for all
                .process(deserializationInboundProcessor)
                .process(enrichmentProcessor)
                .process(filterInboundProcessor)

                // Check if further processing should be ignored after enrichment
                .choice()
                .when(this::shouldIgnoreFurtherProcessing)
                .to("log:inbound-enrichment-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()

                // 1. Branch based on processing type
                .choice()
                // 1f. Extension processing path
                .when(this::isInternalProtobuf)
                .to("direct:processInternalProtobuf")

                // 1d. Extension processing path
                .when(this::isExtension)
                .to("direct:processExtension")

                // 1b. JSONata extraction path
                .when(this::isJSONataExtraction)
                .to("direct:processJSONataExtraction")

                // 1e. Flow function path
                .when(this::isFlowFunction)
                .to("direct:processFlowFunction")

                // Default fallback — unknown/unmatched TransformationType
                .otherwise()
                .process(exchange -> {
                    ProcessingContext<?> ctx = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT,
                            ProcessingContext.class);
                    log.warn("{} - No matching transformation type for mapping '{}' (type={}), falling back to JSONata",
                            ctx != null ? ctx.getTenant() : "unknown",
                            ctx != null && ctx.getMapping() != null ? ctx.getMapping().getName() : "unknown",
                            ctx != null && ctx.getMapping() != null ? ctx.getMapping().getTransformationType() : "null");
                })
                .to("direct:processJSONataExtraction")
                .end();

        // 1b. JSONata extraction processing route
        from("direct:processJSONataExtraction")
                .routeId("jsonata-extraction-processor")
                .process(jsonataExtractionInboundProcessor)
                .process(substitutionInboundProcessor)
                .choice()
                .when(this::shouldIgnoreFurtherProcessing)
                .to("log:filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(inboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1d. Extension processing route
        from("direct:processExtension")
                .routeId("extension-processor")
                .process(extensibleProcessor)
                .choice()
                .when(this::shouldIgnoreFurtherProcessing)
                .to("log:extension-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(extensibleResultInboundProcessor)
                .choice()
                .when(this::shouldIgnoreFurtherProcessing)
                .to("log:extension-result-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                // Check if parallel processing is enabled
                .choice()
                .when(header(CamelHeaders.PARALLEL_PROCESSING).isEqualTo(true))
                .to("direct:processRequestsInParallel")
                .otherwise()
                .process(inboundSendProcessor)
                .end()
                .process(consolidationProcessor)
                .end()
                .end();

        // 1f. Extension processing route
        from("direct:processInternalProtobuf")
                .routeId("internal-protobuf-processor")
                .process(internalProtobufProcessor)
                .process(substitutionInboundProcessor)
                .choice()
                .when(this::shouldIgnoreFurtherProcessing)
                .to("log:internal-protobuf-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(inboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1e. Flow function processing route
        from("direct:processFlowFunction")
                .routeId("flow-function-processor")
                .process(flowInboundProcessor)
                .choice()
                .when(this::shouldIgnoreFurtherProcessing)
                .to("log:flow-function-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(flowResultInboundProcessor)
                .process(inboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // Add new route for parallel request processing
        from("direct:processRequestsInParallel")
                .routeId("parallel-requests-processor")
                .process(exchange -> {
                    ProcessingContext<Object> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT,
                            ProcessingContext.class);
                    log.debug("Starting parallel processing of {} requests for mapping: {}",
                            context.getRequests().size(), context.getMapping().getName());
                })
                .split(simple("${header.processingContext.requests}"))
                .parallelProcessing(true)
                .executorService(virtualThreadPool)
                .streaming(false) // Process all in parallel, don't stream
                .aggregationStrategy(requestAggregationStrategy)
                .process(exchange -> {
                    // The body now contains a single DynamicMapperRequest
                    DynamicMapperRequest request = exchange.getIn().getBody(DynamicMapperRequest.class);
                    log.debug("Processing request in parallel: API={}, sourceId={}",
                            request.getApi(), request.getSourceId());
                })
                .process(inboundSendProcessor)
                .end()
                .process(exchange -> {
                    log.debug("Completed parallel processing of all requests");
                });

        // Error handling route — logs the exception and ensures PROCESSED_CONTEXTS is set to an
        // empty list so the dispatcher's header read never returns null after an exception.
        from("direct:inboundErrorHandling")
                .routeId("inbound-error-handler")
                .process(exchange -> {
                    if (exchange.getIn().getHeader(CamelHeaders.PROCESSED_CONTEXTS) == null) {
                        exchange.getIn().setHeader(CamelHeaders.PROCESSED_CONTEXTS,
                                new ArrayList<ProcessingContext<Object>>());
                    }
                })
                .to("log:dynamic-mapper-error?level=ERROR&showException=true");
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