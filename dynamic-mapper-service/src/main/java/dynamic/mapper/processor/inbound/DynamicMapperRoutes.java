package dynamic.mapper.processor.inbound;

import java.util.ArrayList;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.processor.inbound.processor.CodeExtractionProcessor;
import dynamic.mapper.processor.inbound.processor.DeserializationProcessor;
import dynamic.mapper.processor.inbound.processor.EnrichmentProcessor;
import dynamic.mapper.processor.inbound.processor.ExtensibleProcessor;
import dynamic.mapper.processor.inbound.processor.JSONataExtractionProcessor;
import dynamic.mapper.processor.inbound.processor.FilterProcessor;
import dynamic.mapper.processor.inbound.processor.MappingContextProcessor;
import dynamic.mapper.processor.inbound.processor.MappingResolverProcessor;
import dynamic.mapper.processor.inbound.processor.InboundSendProcessor;
import dynamic.mapper.processor.inbound.processor.ProcessingResultProcessor;
import dynamic.mapper.processor.inbound.processor.SubstitutionProcessor;
import dynamic.mapper.processor.inbound.util.ProcessingContextAggregationStrategy;
import dynamic.mapper.processor.inbound.util.ProcessingContextInitializer;
import dynamic.mapper.processor.model.ProcessingContext;
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
        from("direct:processSingleMapping")
            .routeId("single-mapping-processor")
            .process(new MappingContextProcessor())
            .process(new DeserializationProcessor())
            .process(new EnrichmentProcessor())
            // Conditional extension processing
            .choice()
                .when(header("processingContext").method("getMapping").method("getExtension").isNotNull())
                    .process(new ExtensibleProcessor())
            .end()
            // Continue with regular processing
            .choice()
                .when(header("processingContext").method("getMapping").method("isSubstitutionsAsCode"))
                    .process(new CodeExtractionProcessor())
                .otherwise()
                    .process(new JSONataExtractionProcessor())
            .end()
            .process(new SubstitutionProcessor())
            .process(new FilterProcessor())
            .choice()
                .when(header("processingContext").method("isIgnoreFurtherProcessing"))
                    .to("log:filtered-message?level=DEBUG")
                    .stop()
                .otherwise()
                    .process(new InboundSendProcessor())
            .end()
            .process(new ProcessingResultProcessor());

        // Error handling route
        from("direct:errorHandling")
                .routeId("error-handler")
                .to("log:dynamic-mapper-error?level=ERROR&showException=true");
    }
}