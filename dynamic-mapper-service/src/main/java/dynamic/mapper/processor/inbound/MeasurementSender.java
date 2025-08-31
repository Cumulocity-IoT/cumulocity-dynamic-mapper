package dynamic.mapper.processor.inbound;

import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.API;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MeasurementSender extends BaseMessageSender<Object> {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    protected C8YRequest createRequest(String processedTarget, ProcessingContext<Object> context) {
        try {
            // Parse the processed target as JSON
            JsonNode targetJson = objectMapper.readTree(processedTarget);
            
            // Create measurement request
            C8YRequest request = C8YRequest.builder()
                .api(API.MEASUREMENT)
                .method(RequestMethod.POST)
                .request(processedTarget)
                .build();
            
            
            // Update context
            context.setApi(API.MEASUREMENT);
            
            log.debug("Created measurement request with body: {}", processedTarget);
            return request;
            
        } catch (Exception e) {
            log.error("Failed to create measurement request: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create measurement request", e);
        }
    }
    
    @Override
    protected API getAPI() {
        return API.MEASUREMENT;
    }
}