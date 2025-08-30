package dynamic.mapper.processor.inbound;

@Component
public class ProcessingResultProcessor implements Processor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        
        // Create success result for this mapping
        ProcessingResult<?> result = ProcessingResult.success("Mapping processed successfully", context.getProcessedData());
        exchange.getIn().setHeader("mappingProcessingResult", result);
        exchange.getIn().setBody(result); // For aggregation
    }
}
