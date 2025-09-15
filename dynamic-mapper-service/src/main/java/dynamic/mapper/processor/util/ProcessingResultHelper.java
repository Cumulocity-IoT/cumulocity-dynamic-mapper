package dynamic.mapper.processor.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.API;
import dynamic.mapper.model.MappingRepresentation;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.DynamicMapperRequest;
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

        /**
     * Create implicit device when needed - extracted from original createImplicitDevice
     */
    public static String createImplicitDevice(ID identity, ProcessingContext<Object> context, Logger log, C8YAgent c8yAgent, ObjectMapper objectMapper) {
        Map<String, Object> request = new HashMap<>();
        
        // Set device name
        if (context.getDeviceName() != null) {
            request.put("name", context.getDeviceName());
        } else {
            request.put("name", "device_" + identity.getType() + "_" + identity.getValue());
        }
        
        // Set device type
        if (context.getDeviceType() != null) {
            request.put("type", context.getDeviceType());
        } else {
            request.put("type", "c8y_GeneratedDeviceType");
        }
        
        // Set device properties
        request.put(MappingRepresentation.MAPPING_GENERATED_TEST_DEVICE, null);
        request.put("c8y_IsDevice", null);
        request.put("com_cumulocity_model_Agent", null);
        
        try {
            int predecessor = context.getRequests().size();
            String requestString = objectMapper.writeValueAsString(request);
            
            // Create C8Y request for device creation
            DynamicMapperRequest deviceRequest = DynamicMapperRequest.builder()
                .predecessor(predecessor)
                .method(context.getMapping().getUpdateExistingDevice() ? RequestMethod.POST : RequestMethod.PATCH)
                .api(API.INVENTORY)
                .sourceId(null)
                .externalIdType(context.getMapping().getExternalIdType())
                .externalId(context.getExternalId())
                .request(requestString)
                .targetAPI(API.INVENTORY)
                .build();
                
            var index = context.addRequest(deviceRequest);
            
            // Create the device
            ManagedObjectRepresentation adHocDevice = c8yAgent.upsertDevice(context.getTenant(), identity, context, index);
            
            // Update request with response
            String response = objectMapper.writeValueAsString(adHocDevice);
            context.getCurrentRequest().setResponse(response);
            context.getCurrentRequest().setSourceId(adHocDevice.getId().getValue());
            
            return adHocDevice.getId().getValue();
            
        } catch (Exception e) {
            context.getCurrentRequest().setError(e);
            log.error("Failed to create implicit device: {}", e.getMessage(), e);
            return null;
        }
    }
}
