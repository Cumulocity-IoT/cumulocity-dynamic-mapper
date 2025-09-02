package dynamic.mapper.processor.inbound.route;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.processor.CodeExtractionInboundProcessor;
import dynamic.mapper.processor.inbound.processor.DeserializationInboundProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleProcessor;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SendInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SnoopingInboundProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataExtractionInboundProcessor;
import dynamic.mapper.processor.inbound.processor.MappingContextInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionInboundProcessor;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.processor.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.util.ResultProcessor;

@Component
public class DynamicMapperInboundRoutes extends RouteBuilder {

    @Autowired
    private ConnectorRegistry connectorRegistry;

    @Autowired
    private ExtensibleProcessor extensibleProcessor;

    @Autowired
    private MappingContextInboundProcessor mappingContextProcessor;

    @Autowired
    private CodeExtractionInboundProcessor codeExtractionInboundProcessor;

    @Autowired
    private SubstitutionInboundProcessor substitutionInboundProcessor;

    @Autowired
    private SnoopingInboundProcessor snoopingInboundProcessor;

    @Autowired
    private DeserializationInboundProcessor deserializationInboundProcessor;

    @Autowired
    private EnrichmentInboundProcessor enrichmentInboundProcessor;

    @Autowired
    private JSONataExtractionInboundProcessor jsonataExtractionInboundProcessor;

    @Autowired
    private FilterInboundProcessor filterInboundProcessor;

    @Autowired
    private SendInboundProcessor inboundSendProcessor;

    @Autowired
    private ResultProcessor resultProcessor;

    @Autowired
    private ProcessingContextAggregationStrategy processingContextAggregationStrategy;

    @Override
    public void configure() throws Exception {

        // Global error handling
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

                    log.error("=== CAMEL ROUTE ERROR ===");
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
                .to("direct:inboundErrorHandling");

        // Main processing entry point (transport agnostic)
        from("direct:processInboundMessage")
                .routeId("inbound-message-processor")
                .choice()
                .when(header("mappings").isNull())
                .process(exchange -> {
                    // No mappings found - return empty contexts list
                    exchange.getIn().setHeader("processedContexts", new ArrayList<ProcessingContext<Object>>());
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
                    List<Mapping> allMappings = exchange.getIn().getHeader("mappings", List.class);
                    String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);
                    String tenant = exchange.getIn().getHeader("tenant", String.class);

                    if (allMappings != null) {
                        List<Mapping> validMappings = allMappings.stream()
                                .filter(mapping -> isValidMapping(tenant, mapping, connectorIdentifier))
                                .collect(java.util.stream.Collectors.toList());

                        exchange.getIn().setHeader("mappings", validMappings);
                        log.debug("Filtered {} mappings to {} valid mappings",
                                allMappings.size(), validMappings.size());
                    }
                })
                .split(header("mappings"))
                .parallelProcessing(false)
                .aggregationStrategy(processingContextAggregationStrategy)
                .to("direct:processSingleInboundMapping")
                .end();

        // Single mapping processing pipeline
        from("direct:processSingleInboundMapping")
                .routeId("single-filtered-inbound-mapping-processor")
                .process(deserializationInboundProcessor)
                .process(mappingContextProcessor)
                .process(enrichmentInboundProcessor)
                // ADD: Check for snooping BEFORE other processing
                .process(snoopingInboundProcessor)
                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null && context.isIgnoreFurtherProcessing();
                })
                .to("log:snooping-message?level=DEBUG&showBody=false")
                .stop() // Stop here for snooping - no further processing
                .end()
                // Conditional extension processing
                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null &&
                            context.getMapping() != null &&
                            context.getMapping().getExtension() != null;
                })
                .process(extensibleProcessor)
                .stop() // Extensions handle their own processing
                .end()

                // Regular extraction processing
                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null &&
                            context.getMapping() != null &&
                            context.getMapping().isSubstitutionsAsCode();
                })
                .process(codeExtractionInboundProcessor)
                .otherwise()
                .process(jsonataExtractionInboundProcessor)
                .end()

                .process(substitutionInboundProcessor)
                .process(filterInboundProcessor)

                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null && context.isIgnoreFurtherProcessing();
                })
                .to("log:filtered-message?level=DEBUG")
                .stop()
                .otherwise()
                .process(inboundSendProcessor)
                .end()

                .process(resultProcessor);

        // Error handling route
        from("direct:inboundErrorHandling")
                .routeId("inbound-error-handler")
                .to("log:dynamic-mapper-error?level=ERROR&showException=true");
    }

    /**
     * Custom predicate to validate if mapping should be processed
     */
    private boolean isValidMapping(String tenant, Mapping mapping, String connectorIdentifier) {
        try {

            if (mapping == null) {
                log.debug("Mapping is null, skipping");
                return false;
            }

            // Check if mapping is active
            if (!mapping.getActive()) {
                log.debug("Mapping {} is inactive, skipping", mapping.getName());
                return false;
            }

            // Check if mapping is deployed (you'll need to get connector info)
            if (connectorIdentifier != null && !isMappingDeployed(tenant, mapping, connectorIdentifier)) {
                log.debug("Mapping {} not deployed for connector {}, skipping",
                        mapping.getName(), connectorIdentifier);
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("Error validating mapping: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if mapping is deployed for the connector
     */
    private boolean isMappingDeployed(String tenant, Mapping mapping, String connectorIdentifier) {
        try {
            AConnectorClient connector = connectorRegistry.getClientForTenant(tenant, connectorIdentifier);

            log.debug("Cannot check deployment status for mapping {}, assuming deployed", mapping.getName());
            return connector != null && connector.isMappingInboundDeployed(mapping.getIdentifier());

        } catch (Exception e) {
            log.warn("Error checking mapping deployment status: {}", e.getMessage());
            return true; // Default to allowing processing
        }
    }
}