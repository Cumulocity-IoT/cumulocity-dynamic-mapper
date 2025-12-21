package dynamic.mapper.processor.inbound.processor;

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
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
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

    @Autowired
    private MappingService mappingService;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        Boolean testing = context.getTesting();

        try {
            // Check if we have a single request from parallel processing (body contains split request)
            DynamicMapperRequest singleRequest = exchange.getIn().getBody(DynamicMapperRequest.class);

            if (singleRequest != null) {
                // Parallel mode: process single request from body
                processSingleRequest(context, singleRequest, true);
            } else {
                // Sequential mode: process all requests in context
                processAllRequests(context);
            }
        } catch (Exception e) {
            String errorMessage = String.format(
                    "%s - Error in SendInboundProcessor: %s for mapping: %s",
                    tenant, mapping.getName(), e.getMessage());
            log.error(errorMessage, e);
            //Don't double wrap ProcessingExceptions
            if(e instanceof ProcessingException)
                context.addError((ProcessingException) e);
            else
                context.addError(new ProcessingException(errorMessage, e));

            if (!testing) {
                MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
                mappingStatus.errors++;
                mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            }
            return;
        }
    }

    /**
     * Process all requests sequentially
     */
    private void processAllRequests(ProcessingContext<Object> context) throws Exception {
        try {
            // Process each C8Y request
            for (DynamicMapperRequest request : context.getRequests()) {
                processSingleRequest(context, request, false);
            }

            // Create alarms for any processing issues (after all requests are processed)
            createProcessingAlarms(context);

        } catch (Exception e) {
            log.error("{} - Error in inbound send processor for mapping: '{}'",
                     context.getTenant(), context.getMapping().getName(), e);
            throw e;
        }
    }

    /**
     * Process a single request - common logic for both sequential and parallel modes
     *
     * @param context The processing context
     * @param request The request to process
     * @param isParallelMode True if processing in parallel mode, false for sequential
     */
    private void processSingleRequest(ProcessingContext<Object> context, DynamicMapperRequest request, boolean isParallelMode) throws Exception {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            // Find the index of this request in the context
            int requestIndex = context.getRequests().indexOf(request);
            if (requestIndex == -1) {
                log.warn("{} - Request not found in context", tenant);
                return;
            }

            // Process request based on API type
            if (API.INVENTORY.equals(request.getApi())) {
                processInventoryRequest(context, requestIndex);
            } else {
                processNonInventoryRequest(context, requestIndex);
            }

            // Log if debug is enabled
            if (mapping.getDebug() || context.getServiceConfiguration().getLogPayload()) {
                log.info("{} - Transformed message sent: API: {}, message: {}",
                        tenant, request.getApi(), request.getRequest());
            }

            // In parallel mode, create alarms for this specific request immediately
            // In sequential mode, alarms are created after all requests in processAllRequests
            if (isParallelMode) {
                createProcessingAlarmsForRequest(context, request);
            }

        } catch (Exception e) {
            log.error("{} - Failed to process request: {}", tenant, e.getMessage(), e);
            request.setError(e);
            throw e;
        }
    }

    /**
     * Process INVENTORY API requests
     */
    private void processInventoryRequest(ProcessingContext<Object> context, int requestIndex) throws Exception {
        String tenant = context.getTenant();
        DynamicMapperRequest request = context.getRequests().get(requestIndex);

        try {
            ID identity = null;
            // Resolve external ID if needed
            if (request.getExternalId() != null) {
                identity = new ID(request.getExternalIdType(), request.getExternalId());
                ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity,
                        context.getTesting());

                if (sourceId != null) {
                    request.setSourceId(sourceId.getManagedObject().getId().getValue());

                    // Cache the mapping of device to client ID
                    if (context.getClientId() != null) {
                        configurationRegistry.addOrUpdateClientRelation(tenant, context.getClientId(),
                                request.getSourceId());
                    }
                }
            }

            // Create or update device
            ManagedObjectRepresentation device = c8yAgent.upsertDevice(tenant, identity, context, requestIndex);

            // Set response and update request
            String response = objectMapper.writeValueAsString(device);
            request.setResponse(response);
            request.setSourceId(device.getId().getValue());

        } catch (Exception e) {
            request.setError(e);
            throw e;
        }
    }

    /**
     * Process non-INVENTORY API requests (MEASUREMENT, EVENT, ALARM)
     */
    private void processNonInventoryRequest(ProcessingContext<Object> context, int requestIndex) throws Exception {
        DynamicMapperRequest request = context.getRequests().get(requestIndex);
        try {
            if (context.getSendPayload()) {
                // Send the request to C8Y

                c8yAgent.createMEAO(context, requestIndex);

                // Note: adHocRequest would be returned from createMEAO if needed
                // For now, just set a placeholder response
                request.setResponse("{}"); // This should be the actual response from C8Y
            }

        } catch (Exception e) {
            context.getCurrentRequest().setError(e);
            request.setError(e);
            throw e;
        }
    }

    /**
     * Create alarms for a specific request (used in parallel mode)
     */
    private void createProcessingAlarmsForRequest(ProcessingContext<Object> context, DynamicMapperRequest request) {
        String tenant = context.getTenant();

        if (request.getSourceId() != null && !context.getAlarms().isEmpty()) {
            ManagedObjectRepresentation sourceMor = new ManagedObjectRepresentation();
            sourceMor.setId(new GId(request.getSourceId()));

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

}