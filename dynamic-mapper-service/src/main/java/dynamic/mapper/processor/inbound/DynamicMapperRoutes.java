package dynamic.mapper.processor.inbound;

import java.util.ArrayList;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;
import dynamic.mapper.service.MappingService;

@Component
public class DynamicMapperRoutes extends RouteBuilder {

    @Autowired
    private MappingService mappingService;

    @Override
    public void configure() throws Exception {

        // Global error handling
        onException(Exception.class)
                .handled(true)
                .process(exchange -> {
                    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                    exchange.getIn().setHeader("processingError", cause);
                    exchange.getIn().setHeader("processedContexts", new ArrayList<>());
                })
                .to("direct:errorHandling");

        // Main processing entry point
        from("direct:processInboundMessage")
                .routeId("inbound-message-processor")
                .process(new MappingResolverProcessor(mappingService))
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
                .process(new ProcessingContextInitializer())
                .split(header("mappings"))
                .parallelProcessing(false)
                .aggregationStrategy(new ProcessingContextAggregationStrategy())
                .to("direct:processSingleMapping")
                .end();

        // Single mapping processing pipeline
        // Single mapping processing pipeline
        from("direct:processSingleMapping")
                .routeId("single-mapping-processor")
                .process(new MappingContextProcessor())
                .process(new DeserializationProcessor())
                .process(new EnrichmentProcessor())
                .process(new ExtractionProcessor())
                .process(new SubstitutionProcessor())
                .process(new FilterProcessor())
                .choice()
                .when(header("processingContext").method("isIgnoreFurtherProcessing"))
                .to("log:filtered-message?level=DEBUG")
                .stop() // Stop processing if filtered out
                .otherwise()
                .process(new ProcessAndSendProcessor())
                .end()
                .process(new ProcessingResultProcessor());

        // Error handling route
        from("direct:errorHandling")
                .routeId("error-handler")
                .to("log:dynamic-mapper-error?level=ERROR&showException=true");
    }
}