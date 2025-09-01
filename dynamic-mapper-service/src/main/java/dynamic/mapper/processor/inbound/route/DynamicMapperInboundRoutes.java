package dynamic.mapper.processor.inbound.route;

import java.util.ArrayList;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.inbound.processor.CodeExtractionInboundProcessor;
import dynamic.mapper.processor.inbound.processor.DeserializationProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentInboundProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleProcessor;
import dynamic.mapper.processor.inbound.processor.FilterInboundProcessor;
import dynamic.mapper.processor.inbound.processor.SendInboundProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataExtractionInboundProcessor;
import dynamic.mapper.processor.inbound.processor.MappingContextProcessor;
import dynamic.mapper.processor.inbound.processor.ProcessingResultProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionProcessor;
import dynamic.mapper.processor.inbound.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.service.MappingService;

@Component
public class DynamicMapperInboundRoutes extends RouteBuilder {

    @Autowired
    private MappingService mappingService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingContextProcessor mappingContextProcessor;

    @Autowired
    private CodeExtractionInboundProcessor codeExtractionInboundProcessor;

    @Autowired
    private SubstitutionProcessor substitutionProcessor;

    @Autowired
    private DeserializationProcessor deserializationProcessor;
    @Autowired
    private EnrichmentInboundProcessor enrichmentInboundProcessor;

    @Autowired
    private JSONataExtractionInboundProcessor jsonataExtractionInboundProcessor;

    @Autowired
    private FilterInboundProcessor filterInboundProcessor;

    @Autowired
    private SendInboundProcessor inboundSendProcessor;

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
                .to("direct:errorHandling");

        // Main processing entry point (transport agnostic)
        from("direct:processInboundMessage")
                .routeId("inbound-message-processor")
                .log("=== ROUTE RECEIVED MESSAGE ===")
                .process(exchange -> {
                    log.info("MappingResolverProcessor - Processing exchange: {}", exchange);
                })
                .choice()
                .when(header("mappings").isNull())
                .process(exchange -> {
                    // No mappings found - return empty contexts list
                    exchange.getIn().setHeader("processedContexts", new ArrayList<ProcessingContext<Object>>());
                })
                .stop()
                .otherwise()
                .to("direct:processWithMappings");

        // Process message with found mappings
        from("direct:processWithMappings")
                .routeId("mapping-processor")
                .split(header("mappings"))
                .parallelProcessing(false)
                .aggregationStrategy(new ProcessingContextAggregationStrategy())
                .to("direct:processSingleMapping")
                .end();

        // Single mapping processing pipeline
        from("direct:processSingleMapping")
                .routeId("single-mapping-processor")
                .process(deserializationProcessor)
                .process(mappingContextProcessor)
                .process(enrichmentInboundProcessor)
                // Conditional extension processing
                .choice()
                .when(exchange -> {
                    ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                            ProcessingContext.class);
                    return context != null &&
                            context.getMapping() != null &&
                            context.getMapping().getExtension() != null;
                })
                .process(new ExtensibleProcessor())
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

                .process(substitutionProcessor)
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

                .process(new ProcessingResultProcessor());

        // Error handling route
        from("direct:errorHandling")
                .routeId("error-handler")
                .to("log:dynamic-mapper-error?level=ERROR&showException=true");
    }
}