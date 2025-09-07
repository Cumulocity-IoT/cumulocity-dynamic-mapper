package dynamic.mapper.processor.inbound.route;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.inbound.processor.CodeExtractionInboundProcessor;
import dynamic.mapper.processor.inbound.processor.DeserializationInboundProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleProcessor;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowProcessorInboundProcessor;
import dynamic.mapper.processor.inbound.processor.FlowResultInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SendInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SnoopingInboundProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataExtractionInboundProcessor;
import dynamic.mapper.processor.inbound.processor.MappingContextInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionInboundProcessor;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.processor.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.processor.util.DynamicMapperBaseRoutes;

@Component
public class DynamicMapperInboundRoutes extends DynamicMapperBaseRoutes {

    @Autowired
    private ExtensibleProcessor extensibleProcessor;

    @Autowired
    private MappingContextInboundProcessor mappingContextProcessor;

    @Autowired
    private CodeExtractionInboundProcessor codeExtractionInboundProcessor;

    @Autowired
    private FlowProcessorInboundProcessor flowProcessorInboundProcessor;

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
    private FlowResultInboundProcessor flowResultInboundProcessor;

    @Autowired
    private FilterInboundProcessor filterInboundProcessor;

    @Autowired
    private SendInboundProcessor inboundSendProcessor;

    @Autowired
    private ConsolidationProcessor consolidationProcessor;

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
                .parallelProcessing(true)
                .aggregationStrategy(processingContextAggregationStrategy)
                .to("direct:processSingleInboundMapping")
                .end();

        // Single mapping processing pipeline
        from("direct:processSingleInboundMapping")
                .routeId("single-filtered-inbound-mapping-processor")
                // 0. Common processing for all
                .process(deserializationInboundProcessor)
                .process(mappingContextProcessor)
                .process(enrichmentInboundProcessor)
                .process(filterInboundProcessor)

                // 1. Branch based on processing type
                .choice()
                // 1a. Snooping path
                .when(exchange -> isSnooping(exchange))
                .to("direct:processSnooping")

                // 1b. JSONata extraction path
                .when(exchange -> isJSONataExtraction(exchange))
                .to("direct:processJSONataExtraction")

                // 1c. SubstitutionAsCode extraction path
                .when(exchange -> isSubstitutionAsCode(exchange))
                .to("direct:processSubstitutionAsCodeExtraction")

                // 1d. Extension processing path
                .when(exchange -> isExtension(exchange))
                .to("direct:processExtension")

                // 1e. Flow function path
                .when(exchange -> isFlowFunction(exchange))
                .to("direct:processFlowFunction")

                // Default fallback (should not happen with proper mapping validation)
                .otherwise()
                .to("direct:processJSONataExtraction") // Default to JSONata
                .end();

        // 1a. Snooping processing route
        from("direct:processSnooping")
                .routeId("snooping-processor")
                .process(snoopingInboundProcessor)
                .to("log:snooping-message?level=DEBUG&showBody=false")
                .process(consolidationProcessor);

        // 1b. JSONata extraction processing route
        from("direct:processJSONataExtraction")
                .routeId("jsonata-extraction-processor")
                .process(jsonataExtractionInboundProcessor)
                .process(substitutionInboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(inboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1c. SubstitutionAsCode extraction processing route
        from("direct:processSubstitutionAsCodeExtraction")
                .routeId("substitution-as-code-extraction-processor")
                .process(codeExtractionInboundProcessor)
                .process(substitutionInboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
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
                .process(substitutionInboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:extension-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(inboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1e. Flow function processing route
        from("direct:processFlowFunction")
                .routeId("flow-function-processor")
                .process(flowProcessorInboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:flow-function-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(flowResultInboundProcessor)
                .process(inboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // Error handling route
        from("direct:inboundErrorHandling")
                .routeId("inbound-error-handler")
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