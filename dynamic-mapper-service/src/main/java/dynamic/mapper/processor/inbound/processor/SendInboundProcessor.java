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

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
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
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        try {
            // Process all C8Y requests that were created by SubstitutionProcessor
            processAndPrepareRequests(context);

            // Create alarms for any processing issues
            createProcessingAlarms(context);

        } catch (Exception e) {
            log.error("Error in inbound send processor for mapping: {}",
                    context.getMapping().getName(), e);
            context.addError(new ProcessingException("Send processing failed", e));
            return;
        }

    }

    /**
     * Process and send all C8Y requests created during substitution
     */
    private void processAndPrepareRequests(ProcessingContext<Object> context) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        // Process each C8Y request with index
        for (int index = 0; index < context.getRequests().size(); index++) {
            DynamicMapperRequest request = context.getRequests().get(index);
            try {
                if (API.INVENTORY.equals(request.getApi())) {
                    processInventoryRequest(context, index);
                } else {
                    processNonInventoryRequest(context, index);
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
    private void processInventoryRequest(ProcessingContext<Object> context, int requestIndex) throws Exception {
        String tenant = context.getTenant();
        DynamicMapperRequest request = context.getRequests().get(requestIndex);

        try {
            ID identity = null;
            // Resolve external ID if needed
            if (request.getExternalId() != null) {
                identity = new ID(request.getExternalIdType(), request.getExternalId());
                ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity, context);

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
            if (context.isSendPayload()) {
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