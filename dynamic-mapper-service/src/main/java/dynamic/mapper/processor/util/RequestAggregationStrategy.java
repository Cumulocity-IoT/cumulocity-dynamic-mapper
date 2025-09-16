package dynamic.mapper.processor.util;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RequestAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // For parallel processing, we just need to collect the results
        // The actual requests are already processed and updated in the context
        if (oldExchange == null) {
            return newExchange;
        }
        
        // You can add any specific aggregation logic here if needed
        // For now, just return the new exchange
        return newExchange;
    }
}