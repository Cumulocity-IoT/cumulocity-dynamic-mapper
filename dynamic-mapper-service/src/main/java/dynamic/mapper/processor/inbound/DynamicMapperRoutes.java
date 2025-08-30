package dynamic.mapper.processor.inbound;

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
                ProcessingResult<?> result = ProcessingResult.failure("Processing failed: " + cause.getMessage(), cause);
                exchange.getIn().setHeader("processingResult", result);
            })
            .to("direct:errorHandling");
            
        // Main processing entry point (transport agnostic)
        from("direct:processInboundMessage")
            .routeId("inbound-message-processor")
            .process(new MappingResolverProcessor(mappingService))
            .choice()
                .when(header("mappings").isNull())
                    .process(exchange -> {
                        String topic = exchange.getIn().getHeader("topic", String.class);
                        ProcessingResult<?> result = ProcessingResult.success("No mappings found for topic: " + topic);
                        exchange.getIn().setHeader("processingResult", result);
                    })
                    .stop()
                .otherwise()
                    .to("direct:processWithMappings");
                    
        // Process message with found mappings
        from("direct:processWithMappings")
            .routeId("mapping-processor")
            .process(new ProcessingContextInitializer())
            .split(header("mappings"))
                .parallelProcessing(false) // Can be made configurable
                .aggregationStrategy(new ProcessingResultAggregationStrategy())
                .to("direct:processSingleMapping")
            .end()
            .process(exchange -> {
                // Final processing result
                if (!exchange.getIn().getHeaders().containsKey("processingResult")) {
                    exchange.getIn().setHeader("processingResult", ProcessingResult.success("All mappings processed successfully"));
                }
            });
                
        // Single mapping processing pipeline
        from("direct:processSingleMapping")
            .routeId("single-mapping-processor")
            .process(new MappingContextProcessor())
            .process(new DeserializationProcessor())
            .process(new EnrichmentProcessor())
            .process(new ExtractionProcessor())
            .process(new FilterProcessor())
            .process(new ProcessAndSendProcessor())
            .process(new ProcessingResultProcessor());
            
        // Error handling route
        from("direct:errorHandling")
            .routeId("error-handler")
            .to("log:dynamic-mapper-error?level=ERROR&showException=true");
    }
}
