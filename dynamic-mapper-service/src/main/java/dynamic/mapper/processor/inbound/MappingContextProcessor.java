package dynamic.mapper.processor.inbound;

@Component
public class MappingContextProcessor implements Processor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ConnectorMessage message = exchange.getIn().getHeader("originalMessage", ConnectorMessage.class);
        Mapping currentMapping = exchange.getIn().getBody(Mapping.class);
        
        // Create processing context for this specific mapping
        ProcessingContext<Object> context = new ProcessingContext<>(message, currentMapping);
        exchange.getIn().setHeader("processingContext", context);
    }
}
