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
            .reduce((q1, q2) -> getHigherQos(q1, q2))
            .orElse(Qos.AT_LEAST_ONCE);
        
        // Calculate processing time based on contexts
        int processingTime = calculateProcessingTime(contexts);
        
        return ProcessingResult.<T>builder()
            .processingResult(future)
            .consolidatedQos(consolidatedQos)
            .maxCPUTimeMS(processingTime)
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
    
    public static <T> ProcessingResult<T> failure(Exception error, int maxCPUTimeMS) {
        return ProcessingResult.<T>builder()
            .error(error)
            .maxCPUTimeMS(maxCPUTimeMS)
            .build();
    }
    
    public static <T> ProcessingResult<T> empty() {
        CompletableFuture<List<ProcessingContext<T>>> emptyFuture = 
            CompletableFuture.completedFuture(new ArrayList<>());
            
        return ProcessingResult.<T>builder()
            .processingResult(emptyFuture)
            .consolidatedQos(Qos.AT_MOST_ONCE)
            .maxCPUTimeMS(0)
            .build();
    }
    
    private static Qos getHigherQos(Qos q1, Qos q2) {
        // Assuming QoS priority: EXACTLY_ONCE > AT_LEAST_ONCE > AT_MOST_ONCE
        if (q1 == Qos.EXACTLY_ONCE || q2 == Qos.EXACTLY_ONCE) {
            return Qos.EXACTLY_ONCE;
        }
        if (q1 == Qos.AT_LEAST_ONCE || q2 == Qos.AT_LEAST_ONCE) {
            return Qos.AT_LEAST_ONCE;
        }
        return Qos.AT_MOST_ONCE;
    }
    
    private static <T> int calculateProcessingTime(List<ProcessingContext<T>> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 100;
        }
        
        // Calculate based on complexity - number of requests, errors, etc.
        int totalTime = contexts.stream()
            .mapToInt(ctx -> {
                int contextTime = 200; // Base time per context
                contextTime += ctx.getRequests().size() * 50; // Time per request
                if (ctx.hasError()) {
                    contextTime += 100; // Additional time for error handling
                }
                return contextTime;
            })
            .sum();
            
        return Math.max(100, totalTime); // Minimum 100ms
    }
}
