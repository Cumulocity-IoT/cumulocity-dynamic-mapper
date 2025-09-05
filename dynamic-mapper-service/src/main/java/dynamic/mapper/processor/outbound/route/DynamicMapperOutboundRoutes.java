package dynamic.mapper.processor.outbound.route;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.outbound.processor.FlowProcessorOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowResultOutboundProcessor;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.processor.outbound.processor.CodeExtractionOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.DeserializationOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.EnrichmentOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.JSONataExtractionOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.MappingContextOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SendOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SnoopingOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SubstitutionOutboundProcessor;
import dynamic.mapper.processor.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.util.ResultProcessor;

@Component
public class DynamicMapperOutboundRoutes extends RouteBuilder {

    @Autowired
    private ConnectorRegistry connectorRegistry;

    @Autowired
    private MappingContextOutboundProcessor mappingContextProcessor;

    @Autowired
    private CodeExtractionOutboundProcessor codeExtractionOutboundProcessor;

    @Autowired
    private FlowProcessorOutboundProcessor flowProcessorOutboundProcessor;

    @Autowired
    private SubstitutionOutboundProcessor substitutionInboundProcessor;

    @Autowired
    private SnoopingOutboundProcessor snoopingOutboundProcessor;

    @Autowired
    private DeserializationOutboundProcessor deserializationOutboundProcessor;

    @Autowired
    private EnrichmentOutboundProcessor enrichmentOutboundProcessor;

    @Autowired
    private JSONataExtractionOutboundProcessor jsonataExtractionOutboundProcessor;

    @Autowired
    private FlowResultOutboundProcessor flowResultOutboundProcessor;

    @Autowired
    private SendOutboundProcessor outboundSendProcessor;

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

                    ProcessingResult<Object> result = ProcessingResult.builder()
                            .error(cause)
                            .maxCPUTimeMS(0)
                            .build();

                    exchange.getIn().setHeader("processingResult", result);
                })
                .to("direct:outboundErrorHandling");

        // Main processing entry point (transport agnostic)
        from("direct:processOutboundMessage")
                .routeId("outbound-message-processor")
                .process(exchange -> {
                    log.info("Outbound MappingResolverProcessor - Processing exchange: {}", exchange);
                })
                .choice()
                .when(header("mappings").isNull())
                .process(exchange -> {
                    // No mappings found - return empty contexts list
                    exchange.getIn().setHeader("processedContexts", new ArrayList<ProcessingContext<Object>>());
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
                    List<Mapping> allMappings = exchange.getIn().getHeader("mappings", List.class);
                    String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);
                    String tenant = exchange.getIn().getHeader("tenant", String.class);

                    if (allMappings != null) {
                        List<Mapping> validMappings = allMappings.stream()
                                .filter(mapping -> isValidMapping(tenant, mapping, connectorIdentifier))
                                .collect(java.util.stream.Collectors.toList());

                        exchange.getIn().setHeader("mappings", validMappings);
                        log.debug("Filtered {} outbound mappings to {} valid mappings",
                                allMappings.size(), validMappings.size());
                    }
                })
                .split(header("mappings"))
                .parallelProcessing(false)
                .aggregationStrategy(processingContextAggregationStrategy)
                .to("direct:processSingleOutboundMapping")
                .end();

        // Single mapping processing pipeline
        from("direct:processSingleOutboundMapping")
                .routeId("single-filtered-outbound-mapping-processor")
                .process(deserializationOutboundProcessor)
                .process(mappingContextProcessor)
                .process(enrichmentOutboundProcessor)
                // Check for snooping BEFORE other processing
                .process(snoopingOutboundProcessor)
                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null && context.isIgnoreFurtherProcessing();
                })
                .to("log:outbound-snooping-message?level=DEBUG&showBody=false")
                .stop() // Stop here for snooping - no further processing
                .end()

                // Check transformation type for processing flow
                .choice()
                .when(exchange -> isFlowFunction(exchange))
                .to("direct:processOutboundFlowFunction")
                .otherwise()
                .to("direct:processTraditionalOutboundMapping")
                .end()

                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null && context.isIgnoreFurtherProcessing();
                })
                .to("log:outbound-filtered-message?level=DEBUG")
                .stop()
                .otherwise()
                .process(outboundSendProcessor)
                .end()

                .process(new ResultProcessor());

        // Flow function processing route for OUTBOUND
        from("direct:processOutboundFlowFunction")
                .routeId("outbound-flow-function-processor")
                .to("log:outbound-flow-function-processing?level=DEBUG&showHeaders=false&showBody=false")
                .process(flowProcessorOutboundProcessor)
                .process(flowResultOutboundProcessor);

        // Traditional mapping processing route for OUTBOUND
        from("direct:processTraditionalOutboundMapping")
                .routeId("traditional-outbound-mapping-processor")
                .to("log:traditional-outbound-mapping-processing?level=DEBUG&showHeaders=false&showBody=false")
                // Regular extraction processing
                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null &&
                            context.getMapping() != null &&
                            context.getMapping().isSubstitutionsAsCode();
                })
                .process(codeExtractionOutboundProcessor)
                .otherwise()
                .process(jsonataExtractionOutboundProcessor)
                .end()
                .process(substitutionInboundProcessor);

        // Error handling route
        from("direct:outboundErrorHandling")
                .routeId("outbound-error-handler")
                .to("log:dynamic-mapper-outbound-error?level=ERROR&showException=true");
    }

    /**
     * Check if the outbound mapping uses flow function transformation
     */
    private boolean isFlowFunction(Exchange exchange) {
        try {
            ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
            if (context != null && context.getMapping() != null) {
                TransformationType transformationType = context.getMapping().getTransformationType();
                boolean isFlow = TransformationType.FLOW_FUNCTION.equals(transformationType);
                
                log.debug("Checking outbound transformation type for mapping {}: {} (isFlow: {})", 
                         context.getMapping().getName(), 
                         transformationType != null ? transformationType.toString() : "null",
                         isFlow);
                
                return isFlow;
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking outbound transformation type: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Custom predicate to validate if outbound mapping should be processed
     */
    private boolean isValidMapping(String tenant, Mapping mapping, String connectorIdentifier) {
        try {
            if (mapping == null) {
                log.debug("Outbound mapping is null, skipping");
                return false;
            }

            // Check if mapping is active
            if (!mapping.getActive()) {
                log.debug("Outbound mapping {} is inactive, skipping", mapping.getName());
                return false;
            }

            // Check if mapping is deployed
            if (connectorIdentifier != null && !isMappingDeployed(tenant, mapping, connectorIdentifier)) {
                log.debug("Outbound mapping {} not deployed for connector {}, skipping",
                        mapping.getName(), connectorIdentifier);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("Error validating outbound mapping: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if outbound mapping is deployed for the connector
     */
    private boolean isMappingDeployed(String tenant, Mapping mapping, String connectorIdentifier) {
        try {
            AConnectorClient connector = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);
            return connector != null && connector.isMappingOutboundDeployed(mapping.getIdentifier());

        } catch (Exception e) {
            log.warn("Error checking outbound mapping deployment status: {}", e.getMessage());
            return true; // Default to allowing processing
        }
    }
}