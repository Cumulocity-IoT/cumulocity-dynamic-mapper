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
package dynamic.mapper.processor.outbound.route;

import dynamic.mapper.processor.util.CamelHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.outbound.processor.ExtensibleResultOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowResultOutboundProcessor;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.processor.outbound.processor.DeserializationOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.ExtensibleOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.JSONataOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.EnrichmentOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SendOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SubstitutionResultOutboundProcessor;
import dynamic.mapper.processor.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.processor.util.DynamicMapperBaseRoutes;

@Component
public class DynamicMapperOutboundRoutes extends DynamicMapperBaseRoutes {

    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Autowired
    private EnrichmentOutboundProcessor enrichmentProcessor;

    @Autowired
    private ExtensibleOutboundProcessor extensibleOutboundProcessor;

    @Autowired
    private ExtensibleResultOutboundProcessor extensibleResultOutboundProcessor;

    @Autowired
    private FlowOutboundProcessor flowOutboundProcessor;

    @Autowired
    private SubstitutionResultOutboundProcessor substitutionOutboundProcessor;

    @Autowired
    private DeserializationOutboundProcessor deserializationOutboundProcessor;

    @Autowired
    private JSONataOutboundProcessor jsonataExtractionOutboundProcessor;

    @Autowired
    private FlowResultOutboundProcessor flowResultOutboundProcessor;

    @Autowired
    private SendOutboundProcessor outboundSendProcessor;

    @Autowired
    private ConsolidationProcessor consolidationProcessor;

    @Autowired
    private ProcessingContextAggregationStrategy processingContextAggregationStrategy;

    @Override
    public void configure() throws Exception {

        // Global error handling for OUTBOUND
        onException(Exception.class)
                .handled(true)
                .process(exchange -> {
                    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    String routeId = exchange.getFromRouteId();

                    // Safe endpoint access
                    String endpoint = "unknown";
                    try {
                        if (exchange.getFromEndpoint() != null) {
                            endpoint = exchange.getFromEndpoint().getEndpointUri();
                        }
                    } catch (Exception e) {
                        // Ignore endpoint access errors
                    }

                    log.error("=== CAMEL OUTBOUND ROUTE ERROR ===");
                    log.error("Route ID: {}", routeId);
                    log.error("Endpoint: {}", endpoint);
                    log.error("Exception Type: {}", cause.getClass().getSimpleName());
                    log.error("Exception Message: {}", cause.getMessage());
                    log.error("Full Stack Trace: ", cause);

                    ProcessingResultWrapper<Object> result = ProcessingResultWrapper.builder()
                            .error(cause)
                            .maxCPUTimeMS(0)
                            .build();

                    exchange.getIn().setHeader(CamelHeaders.PROCESSING_RESULT, result);
                })
                .to("direct:outboundErrorHandling");

        // Main processing entry point (transport agnostic)
        from("direct:processOutboundMessage")
                .routeId("outbound-message-processor")
                .choice()
                .when(header(CamelHeaders.MAPPINGS).isNull())
                .process(exchange -> {
                    // No mappings found - return empty contexts list
                    exchange.getIn().setHeader(CamelHeaders.PROCESSED_CONTEXTS, new ArrayList<ProcessingContext<Object>>());
                })
                .stop()
                .otherwise()
                .to("direct:processWithMappingsOutbound");

        // Process message with found mappings
        from("direct:processWithMappingsOutbound")
                .routeId("single-outbound-mapping-processor")
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
                        log.debug("Filtered {} outbound mappings to {} valid mappings",
                                allMappings.size(), validMappings.size());
                    }
                })
                .split(header(CamelHeaders.MAPPINGS))
                .parallelProcessing(true)
                .executorService(virtualThreadPool)
                .aggregationStrategy(processingContextAggregationStrategy)
                .to("direct:processSingleOutboundMapping")
                .end();

        // Single mapping processing pipeline
        from("direct:processSingleOutboundMapping")
                .routeId("single-filtered-outbound-mapping-processor")
                // 0. Common processing for all
                .process(deserializationOutboundProcessor)
                .process(enrichmentProcessor)


                // Check if further processing should be ignored after enrichment
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-enrichment-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()

                // 1. Branch based on processing type
                .choice()
                // 1b. Extension processing path
                .when(exchange -> isExtension(exchange))
                .to("direct:processOutboundExtension")

                // 1c. Flow function path
                .when(exchange -> isFlowFunction(exchange))
                .to("direct:processOutboundFlowFunction")

                // 1e. JSONata extraction path
                .when(exchange -> isJSONataExtraction(exchange))
                .to("direct:processOutboundJSONataExtraction")

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
                .to("direct:processOutboundJSONataExtraction") // Default to JSONata
                .end();


        // 1b. Extension processing route
        from("direct:processOutboundExtension")
                .routeId("outbound-extension-processor")
                .process(extensibleOutboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-extension-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(extensibleResultOutboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-extension-result-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(outboundSendProcessor)
                .process(consolidationProcessor)
                .end()
                .end();

        // 1c. Flow function processing route
        from("direct:processOutboundFlowFunction")
                .routeId("outbound-flow-function-processor")
                .process(flowOutboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-flow-function-filtered-message?level=DEBUG")
                // Still call SendOutboundProcessor so it can auto-ack the operation as
                // FAILED when a JS error caused processing to be skipped.
                .process(outboundSendProcessor)
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(flowResultOutboundProcessor)
                .process(outboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1e. JSONata extraction processing route
        from("direct:processOutboundJSONataExtraction")
                .routeId("outbound-jsonata-extraction-processor")
                .process(jsonataExtractionOutboundProcessor)
                .process(substitutionOutboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-jsonata-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(outboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // Error handling route
        from("direct:outboundErrorHandling")
                .routeId("outbound-error-handler")
                .to("log:dynamic-mapper-outbound-error?level=ERROR&showException=true");
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