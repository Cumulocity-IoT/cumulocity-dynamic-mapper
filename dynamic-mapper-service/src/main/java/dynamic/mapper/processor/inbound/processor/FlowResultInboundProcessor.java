package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cumulocity.model.ID;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.flow.CumulocityMessage;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.notification.websocket.Notification;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowResultInboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.isTesting();

        try {
            processFlowResults(context);

            // Check inventory filter condition if specified
            if (mapping.getFilterInventory() != null && !mapping.getCreateNonExistingDevice()) {
                boolean filterInventory = evaluateInventoryFilter(tenant, mapping.getFilterInventory(),
                        context.getSourceId(), context.isTesting());
                if (context.getSourceId() == null
                        || !filterInventory) {
                    if (mapping.getDebug()) {
                        log.info(
                                "{} - Inbound mapping {}/{} not processed, failing Filter inventory execution: filterResult {}",
                                tenant, mapping.getName(), mapping.getIdentifier(),
                                filterInventory);
                    }
                    context.setIgnoreFurtherProcessing(true);
                }
            }
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "s - Error in FlowSubstitutionInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);
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

    private void processFlowResults(ProcessingContext<?> context) throws ProcessingException {
        Object flowResult = context.getFlowResult();
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        if (flowResult == null) {
            log.debug("{} - No flow result available, skipping Flow substitution", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> messagesToProcess = new ArrayList<>();

        // Handle both single objects and lists
        if (flowResult instanceof List) {
            messagesToProcess.addAll((List<?>) flowResult);
        } else {
            messagesToProcess.add(flowResult);
        }

        if (messagesToProcess.isEmpty()) {
            log.info("{} - Flow result is empty, skipping processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        // Process each message
        for (Object message : messagesToProcess) {
            if (message instanceof CumulocityMessage) {
                processCumulocityMessage((CumulocityMessage) message, context, tenant, mapping);
            } else {
                log.debug("{} - Message is not a CumulocityMessage, skipping: {}", tenant,
                        message.getClass().getSimpleName());
            }
        }

        if (context.getRequests().isEmpty()) {
            log.info("{} - No C8Y requests generated from flow result", tenant);
            context.setIgnoreFurtherProcessing(true);
        } else {
            log.info("{} - Generated {} C8Y requests from flow result", tenant, context.getRequests().size());
        }

    }

    private void processCumulocityMessage(CumulocityMessage cumulocityMessage, ProcessingContext<?> context,
            String tenant, Mapping mapping) throws ProcessingException {

        try {
            // Get the API from the cumulocityType
            API targetAPI = Notification.convertResourceToAPI(cumulocityMessage.getCumulocityType());

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // Resolve device ID and set it hierarchically in the payload
            String resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant);
            List<ExternalSource> externalSources = ProcessingResultHelper.convertToExternalSourceList(cumulocityMessage.getExternalSource());
            String externalId = null;
            String externalType = null;

            if (externalSources != null && !externalSources.isEmpty()) {
                ExternalSource externalSource = externalSources.get(0);
                externalId = externalSource.getExternalId();
                externalType = externalSource.getType();
                context.setExternalId(externalId);
            }

            if (resolvedDeviceId != null) {
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                context.setSourceId(resolvedDeviceId);
            } else if (externalSources != null && !externalSources.isEmpty()) {
                // create implicitDevice if enabled
                if (mapping.getCreateNonExistingDevice()) {
                    ExternalSource externalSource = externalSources.get(0);
                    if (externalSource != null && externalSource.getType() != null
                            && externalSource.getExternalId() != null) {
                        ID identity = new ID(externalSource.getType(),
                                externalSource.getExternalId());
                        String sourceId = ProcessingResultHelper.createImplicitDevice(identity, context, log,
                                c8yAgent,
                                objectMapper);
                        context.setSourceId(sourceId);
                        resolvedDeviceId = sourceId; // Set this so it's used below
                        externalType = externalSource.getType();
                        externalId = externalSource.getExternalId();
                        context.setExternalId(externalSource.getExternalId());
                        ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, sourceId);
                    }
                } else {
                    // No device ID and not creating implicit devices - skip this message
                    log.warn(
                            "{} - Cannot process message: no device ID resolved and createNonExistingDevice is false for mapping {}",
                            tenant, mapping.getIdentifier());
                    return; // Don't create a request
                }
            } else {
                log.warn("{} - Cannot process message: no external source provided for mapping {}",
                        tenant, mapping.getIdentifier());
                return; // Don't create a request
            }

            // Only create request if we have a resolved device ID
            if (resolvedDeviceId == null) {
                log.warn("{} - Skipping request creation: no device ID available for API {} in mapping {}",
                        tenant, targetAPI.name, mapping.getIdentifier());
                return;
            }

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            DynamicMapperRequest dynamicMapperRequest = ProcessingResultHelper.createAndAddDynamicMapperRequest(context, payloadJson,
                    cumulocityMessage.getAction(), mapping);
            dynamicMapperRequest.setApi(targetAPI);
            dynamicMapperRequest.setSourceId(resolvedDeviceId);
            dynamicMapperRequest.setExternalId(externalId);
            dynamicMapperRequest.setExternalIdType(externalType);

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId);

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityMessage: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> clonePayload(Object payload) throws ProcessingException {
        try {
            if (payload instanceof Map) {
                return new HashMap<>((Map<String, Object>) payload);
            } else {
                // Convert object to map using Jackson
                return objectMapper.convertValue(payload, Map.class);
            }
        } catch (Exception e) {
            throw new ProcessingException("Failed to clone payload: " + e.getMessage(), e);
        }
    }

}