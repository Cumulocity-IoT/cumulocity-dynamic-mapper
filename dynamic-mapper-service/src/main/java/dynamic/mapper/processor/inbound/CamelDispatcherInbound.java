package dynamic.mapper.processor.inbound;

@Component
public class CamelDispatcherInbound implements GenericMessageCallback {
    
    private final ProducerTemplate producerTemplate;
    private final CamelContext camelContext;
    
    public CamelDispatcherInbound(CamelContext camelContext) {
        this.camelContext = camelContext;
        this.producerTemplate = camelContext.createProducerTemplate();
    }
    
    @Override
    public ProcessingResult<?> onMessage(ConnectorMessage message) {
        try {
            // Send message to Camel route for processing
            Exchange exchange = createExchange(message);
            Exchange result = producerTemplate.send("direct:processInboundMessage", exchange);
            
            return extractProcessingResult(result);
            
        } catch (Exception e) {
            return ProcessingResult.failure("Processing failed: " + e.getMessage(), e);
        }
    }
    
    private Exchange createExchange(ConnectorMessage message) {
        Exchange exchange = new DefaultExchange(camelContext);
        Message camelMessage = exchange.getIn();
        
        camelMessage.setBody(message);
        camelMessage.setHeader("tenant", extractTenant(message));
        camelMessage.setHeader("topic", message.getTopic());
        camelMessage.setHeader("transport", message.getTransport()); // MQTT, Kafka, HTTP, etc.
        
        return exchange;
    }
    
    private ProcessingResult<?> extractProcessingResult(Exchange result) {
        if (result.getException() != null) {
            return ProcessingResult.failure("Processing failed", result.getException());
        }
        
        ProcessingResult<?> processingResult = result.getIn().getHeader("processingResult", ProcessingResult.class);
        return processingResult != null ? processingResult : ProcessingResult.success("Message processed successfully");
    }
    
    private String extractTenant(ConnectorMessage message) {
        // Extract tenant from topic or headers based on transport
        return message.getTenant(); // Assuming ConnectorMessage has this method
    }
}
