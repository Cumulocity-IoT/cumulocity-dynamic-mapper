package dynamic.mapper.processor.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import dynamic.mapper.processor.model.ProcessingContext;

@Component
public class ProcessingContextAggregationStrategy implements AggregationStrategy {
    
    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        ProcessingContext<?> newContext = newExchange.getIn().getBody(ProcessingContext.class);
        
        if (oldExchange == null) {
            // First result
            List<ProcessingContext<Object>> contexts = new ArrayList<>();
            contexts.add((ProcessingContext<Object>) newContext);
            newExchange.getIn().setHeader("processedContexts", contexts);
            return newExchange;
        }
        
        // Aggregate contexts
        @SuppressWarnings("unchecked")
        List<ProcessingContext<Object>> existingContexts = oldExchange.getIn().getHeader("processedContexts", List.class);
        existingContexts.add((ProcessingContext<Object>) newContext);
        
        oldExchange.getIn().setHeader("processedContexts", existingContexts);
        return oldExchange;
    }
}
