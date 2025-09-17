package dynamic.mapper.processor.outbound.route;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import dynamic.mapper.connector.core.client.AConnectorClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.outbound.processor.FlowProcessorOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.FlowResultOutboundProcessor;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.processor.outbound.processor.CodeExtractionOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.DeserializationOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.EnrichmentOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.JSONataExtractionOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.MappingContextOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SendOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SnoopingOutboundProcessor;
import dynamic.mapper.processor.outbound.processor.SubstitutionOutboundProcessor;
import dynamic.mapper.processor.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.util.ConsolidationProcessor;
import dynamic.mapper.processor.util.DynamicMapperBaseRoutes;

@Component
public class DynamicMapperOutboundRoutes extends DynamicMapperBaseRoutes {
    
    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Autowired
    private MappingContextOutboundProcessor mappingContextProcessor;

    @Autowired
    private CodeExtractionOutboundProcessor codeExtractionOutboundProcessor;

    @Autowired
    private FlowProcessorOutboundProcessor flowProcessorOutboundProcessor;

    @Autowired
    private SubstitutionOutboundProcessor substitutionOutboundProcessor;

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
                .process(mappingContextProcessor)
                .process(enrichmentOutboundProcessor)

                // 1. Branch based on processing type
                .choice()
                // 1a. Snooping path
                .when(exchange -> isSnooping(exchange))
                .to("direct:processOutboundSnooping")

                // 1b. Flow function path
                .when(exchange -> isFlowFunction(exchange))
                .to("direct:processOutboundFlowFunction")

                // 1c. SubstitutionAsCode extraction path
                .when(exchange -> isSubstitutionAsCode(exchange))
                .to("direct:processOutboundSubstitutionAsCodeExtraction")

                // 1d. JSONata extraction path
                .when(exchange -> isJSONataExtraction(exchange))
                .to("direct:processOutboundJSONataExtraction")

                // Default fallback
                .otherwise()
                .to("direct:processOutboundJSONataExtraction") // Default to JSONata
                .end();

        // 1a. Snooping processing route
        from("direct:processOutboundSnooping")
                .routeId("outbound-snooping-processor")
                .process(snoopingOutboundProcessor)
                .to("log:outbound-snooping-message?level=DEBUG&showBody=false")
                .process(consolidationProcessor);

        // 1b. Flow function processing route
        from("direct:processOutboundFlowFunction")
                .routeId("outbound-flow-function-processor")
                .process(flowProcessorOutboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-flow-function-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(flowResultOutboundProcessor)
                .process(outboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1c. SubstitutionAsCode extraction processing route
        from("direct:processOutboundSubstitutionAsCodeExtraction")
                .routeId("outbound-substitution-as-code-extraction-processor")
                .process(codeExtractionOutboundProcessor)
                .process(substitutionOutboundProcessor)
                .choice()
                .when(exchange -> shouldIgnoreFurtherProcessing(exchange))
                .to("log:outbound-substitution-as-code-filtered-message?level=DEBUG")
                .process(consolidationProcessor)
                .stop()
                .otherwise()
                .process(outboundSendProcessor)
                .process(consolidationProcessor)
                .end();

        // 1d. JSONata extraction processing route
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