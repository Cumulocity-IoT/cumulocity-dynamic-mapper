package dynamic.mapper.processor.inbound.processor;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingRepresentation;

import org.springframework.web.bind.annotation.RequestMethod;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;

import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SendInboundProcessor extends BaseProcessor {

    @Autowired
    private C8YAgent c8yAgent;
    
    @Autowired
    private ConfigurationRegistry configurationRegistry;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContextAsObject", ProcessingContext.class);
        
        try {
            // Process all C8Y requests that were created by SubstitutionProcessor
            processAndPrepareRequests(context);
            
            // Create alarms for any processing issues
            createProcessingAlarms(context);
            
        } catch (Exception e) {
            log.error("Error in inbound send processor for mapping: {}", 
                context.getMapping().getName(), e);
            context.addError(new ProcessingException("Send processing failed", e));
        }
        
        exchange.getIn().setHeader("processingContext", context);
    }
    
    /**
     * Process and send all C8Y requests created during substitution
     */
    private void processAndPrepareRequests(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        
        // Process each C8Y request
        for (C8YRequest request : context.getRequests()) {
            try {
                if (API.INVENTORY.equals(request.getApi())) {
                    processInventoryRequest(request, context);
                } else {
                    processNonInventoryRequest(request, context);
                }
                
                // Log if debug is enabled
                if (mapping.getDebug() || context.getServiceConfiguration().isLogPayload()) {
                    log.info("{} - Transformed message sent: API: {}, message: {}", 
                        tenant, request.getApi(), request.getRequest());
                }
                
            } catch (Exception e) {
                log.error("{} - Failed to process request: {}", tenant, e.getMessage(), e);
                request.setError(e);
            }
        }
    }
    
    /**
     * Process INVENTORY API requests
     */
    private void processInventoryRequest(C8YRequest request, ProcessingContext<Object> context) throws Exception {
        String tenant = context.getTenant();
        
        try {
            // Resolve external ID if needed
            if (request.getSourceId() != null) {
                ID identity = new ID(request.getExternalIdType(), request.getSourceId());
                ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity, context);
                
                if (sourceId != null) {
                    context.setSourceId(sourceId.getManagedObject().getId().getValue());
                    request.setSourceId(sourceId.getManagedObject().getId().getValue());
                    
                    // Cache the mapping of device to client ID
                    if (context.getClient() != null) {
                        configurationRegistry.addOrUpdateClientRelation(tenant, context.getClient(),
                                context.getSourceId());
                    }
                }
            }
            
            // Create or update device
            ID identity = new ID(request.getExternalIdType(), request.getSourceId());
            ManagedObjectRepresentation adHocDevice = c8yAgent.upsertDevice(tenant, identity, context);
            
            // Set response and update request
            String response = objectMapper.writeValueAsString(adHocDevice);
            request.setResponse(response);
            request.setSourceId(adHocDevice.getId().getValue());
            
        } catch (Exception e) {
            request.setError(e);
            throw e;
        }
    }
    
    /**
     * Process non-INVENTORY API requests (MEASUREMENT, EVENT, ALARM)
     */
    private void processNonInventoryRequest(C8YRequest request, ProcessingContext<Object> context) throws Exception {
        try {
            if (context.isSendPayload()) {
                // Send the request to C8Y
                c8yAgent.createMEAO(context);
                
                // Note: adHocRequest would be returned from createMEAO if needed
                // For now, just set a placeholder response
                request.setResponse("{}"); // This should be the actual response from C8Y
            }
            
        } catch (Exception e) {
            request.setError(e);
            throw e;
        }
    }
    
    /**
     * Create alarms for any processing issues
     */
    private void createProcessingAlarms(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        
        if (context.getSourceId() != null && !context.getAlarms().isEmpty()) {
            ManagedObjectRepresentation sourceMor = new ManagedObjectRepresentation();
            sourceMor.setId(new GId(context.getSourceId()));
            
            context.getAlarms().forEach(alarm -> {
                try {
                    c8yAgent.createAlarm("WARNING", alarm, Utils.MAPPER_PROCESSING_ALARM, 
                        new DateTime(), sourceMor, tenant);
                } catch (Exception e) {
                    log.warn("{} - Failed to create processing alarm: {}", tenant, e.getMessage());
                }
            });
        }
    }
    
    /**
     * Create implicit device when needed - extracted from original createImplicitDevice
     */
    public String createImplicitDevice(ID identity, ProcessingContext<Object> context) {
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
            C8YRequest deviceRequest = C8YRequest.builder()
                .predecessor(predecessor)
                .method(context.getMapping().getUpdateExistingDevice() ? RequestMethod.POST : RequestMethod.PATCH)
                .api(API.INVENTORY)
                .sourceId(null)
                .externalIdType(context.getMapping().getExternalIdType())
                .request(requestString)
                .targetAPI(API.INVENTORY)
                .build();
                
            context.addRequest(deviceRequest);
            
            // Create the device
            ManagedObjectRepresentation adHocDevice = c8yAgent.upsertDevice(context.getTenant(), identity, context);
            
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