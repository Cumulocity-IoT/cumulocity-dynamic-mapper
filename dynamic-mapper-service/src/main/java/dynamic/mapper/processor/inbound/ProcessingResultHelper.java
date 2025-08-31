package dynamic.mapper.processor.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.springframework.stereotype.Component;

import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResult;

@Component
public class ProcessingResultHelper {
    
    public static <T> ProcessingResult<T> success(List<ProcessingContext<T>> contexts) {
        CompletableFuture<List<ProcessingContext<T>>> future = CompletableFuture.completedFuture(contexts);
        
        // Determine consolidated QoS from contexts
        Qos consolidatedQos = contexts.stream()
            .map(ProcessingContext::getQos)
            .filter(qos -> qos != null)
            .max((q1, q2) -> Integer.compare(q1.ordinal(), q2.ordinal())) // Assuming enum with ordinal priority
            .orElse(Qos.AT_LEAST_ONCE);
        
        return ProcessingResult.<T>builder()
            .processingResult(future)
            .consolidatedQos(consolidatedQos)
            .maxCPUTimeMS(calculateMaxCPUTime(contexts))
            .build();
    }
    
    public static <T> ProcessingResult<T> successAsync(Future<List<ProcessingContext<T>>> future, Qos qos, int maxCPUTimeMS) {
        return ProcessingResult.<T>builder()
            .processingResult(future)
            .consolidatedQos(qos)
            .maxCPUTimeMS(maxCPUTimeMS)
            .build();
    }
    
    public static <T> ProcessingResult<T> failure(Exception error) {
        return ProcessingResult.<T>builder()
            .error(error)
            .maxCPUTimeMS(0)
            .build();
    }
    
    public static <T> ProcessingResult<T> empty() {
        return ProcessingResult.<T>builder()
            .processingResult(CompletableFuture.completedFuture(new ArrayList<>()))
            .consolidatedQos(Qos.AT_MOST_ONCE)
            .maxCPUTimeMS(0)
            .build();
    }
    
    private static <T> int calculateMaxCPUTime(List<ProcessingContext<T>> contexts) {
        // Calculate based on number of contexts and complexity
        return Math.max(1000, contexts.size() * 500); // Base 1s + 500ms per context
    }
}
