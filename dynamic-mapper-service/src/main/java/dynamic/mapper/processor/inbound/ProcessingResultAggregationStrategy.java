package dynamic.mapper.processor.inbound;

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
        ProcessingResult<?> combinedResult = hasFailures 
            ? ProcessingResult.failure("Some mappings failed", existingResults)
            : ProcessingResult.success("All mappings processed", existingResults);
            
        oldExchange.getIn().setHeader("processingResult", combinedResult);
        return oldExchange;
    }
}
