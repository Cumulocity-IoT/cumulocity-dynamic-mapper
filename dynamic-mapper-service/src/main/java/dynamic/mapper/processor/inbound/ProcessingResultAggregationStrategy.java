package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

import dynamic.mapper.processor.model.ProcessingResult;

public class ProcessingResultAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        if (oldExchange == null) {
            // First result
            List<ProcessingResult<?>> results = new ArrayList<>();
            results.add(newExchange.getIn().getBody(ProcessingResult.class));
            newExchange.getIn().setHeader("allProcessingResults", results);
            return newExchange;
        }

        // Aggregate results
        @SuppressWarnings("unchecked")
        List<ProcessingResult<?>> existingResults = oldExchange.getIn().getHeader("allProcessingResults", List.class);
        ProcessingResult<?> newResult = newExchange.getIn().getBody(ProcessingResult.class);

        existingResults.add(newResult);

        // Create combined result
        boolean hasFailures = existingResults.stream().anyMatch(r -> !r.isSuccess());

        ProcessingResult<List<ProcessingResult<?>>> combinedResult = new ProcessingResult<>();
        combinedResult.setData(existingResults);

        if (hasFailures) {
            combinedResult.setSuccess(false);
            combinedResult.setMessage("Some mappings failed");
            // You might want to aggregate exception messages here
        } else {
            combinedResult.setSuccess(true);
            combinedResult.setMessage("All mappings processed successfully");
        }

        oldExchange.getIn().setHeader("processingResult", combinedResult);
        return oldExchange;
    }
}
