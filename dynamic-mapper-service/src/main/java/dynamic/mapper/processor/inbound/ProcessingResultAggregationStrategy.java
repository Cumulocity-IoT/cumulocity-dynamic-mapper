package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

import dynamic.mapper.processor.model.ProcessingContext;

public class ProcessingResultAggregationStrategy implements AggregationStrategy {
    
    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        ProcessingContext<?> newContext = newExchange.getIn().getBody(ProcessingContext.class);
        
        if (oldExchange == null) {
            // First result - create initial list
            List<ProcessingContext<Object>> contexts = new ArrayList<>();
            contexts.add((ProcessingContext<Object>) newContext);
            newExchange.getIn().setHeader("processedContexts", contexts);
            return newExchange;
        }
        
        // Aggregate contexts
        @SuppressWarnings("unchecked")
        List<ProcessingContext<Object>> existingContexts = oldExchange.getIn().getHeader("processedContexts", List.class);
        
        if (existingContexts == null) {
            existingContexts = new ArrayList<>();
        }
        
        existingContexts.add((ProcessingContext<Object>) newContext);
        oldExchange.getIn().setHeader("processedContexts", existingContexts);
        
        return oldExchange;
    }
}
