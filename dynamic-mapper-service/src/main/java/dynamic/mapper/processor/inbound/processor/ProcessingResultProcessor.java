package dynamic.mapper.processor.inbound.processor;

import org.springframework.stereotype.Component;

import dynamic.mapper.processor.model.ProcessingContext;

import org.apache.camel.Exchange;

@Component
public class ProcessingResultProcessor extends BaseProcessor {
    
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        
        // The ProcessingContext itself contains all the processed data
        // No need to extract a separate "processedData" - the context IS the result
        
        exchange.getIn().setHeader("mappingProcessingResult", context);
        exchange.getIn().setBody(context); // For aggregation - pass the context itself
    }
}
