package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cumulocity.model.ID;
import com.cumulocity.sdk.client.ProcessingMode;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.ExternalId;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.notification.websocket.Notification;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowResultInboundProcessor extends BaseProcessor {

    private final MappingService mappingService;
    private final C8YAgent c8yAgent;
    private final ObjectMapper objectMapper;

    public FlowResultInboundProcessor(
            MappingService mappingService,
            C8YAgent c8yAgent,
            ObjectMapper objectMapper) {
        this.mappingService = mappingService;
        this.c8yAgent = c8yAgent;
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        Boolean testing = context.getTesting();

        try {
            processFlowResults(context);

            // Check inventory filter condition if specified
            // if (mapping.getFilterInventory() != null &&
            // !mapping.getCreateNonExistingDevice()) {
            if (mapping.getFilterInventory() != null) {
                boolean filterInventory = evaluateInventoryFilter(tenant, mapping.getFilterInventory(),
                        context.getSourceId(), context.getTesting());
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
                    "%s - Error in FlowResultInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);
            if (e instanceof ProcessingException) {
                context.addError((ProcessingException) e);
            } else {
                context.addError(new ProcessingException(errorMessage, e));
            }

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
            if (message instanceof CumulocityObject) {
                processCumulocityObject((CumulocityObject) message, context, tenant, mapping);
            } else {
                log.debug("{} - Message is not a CumulocityObject, skipping: {}", tenant,
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

    private void processCumulocityObject(CumulocityObject cumulocityMessage, ProcessingContext<?> context,
            String tenant, Mapping mapping) throws ProcessingException {

        try {
            // Get the API from the cumulocityType
            API targetAPI = Notification.convertResourceToAPI(cumulocityMessage.getCumulocityType().name());

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // contextData for generating device with defined name/type
            Map<String, String> contextData = cumulocityMessage.getContextData();
            if (contextData != null) {
                if (contextData.get("deviceName") != null) {
                    context.setDeviceName(contextData.get("deviceName"));
                }
                if (contextData.get("deviceType") != null) {
                    context.setDeviceType(contextData.get("deviceType"));
                }
                if (contextData.get("processingMode") != null) {
                    context.setProcessingMode(ProcessingMode.parse(contextData.get("processingMode")));
                }
                if (contextData.get("attachmentName") != null) {
                    context.getBinaryInfo().setName((String) (contextData.get("attachmentName")));
                }
                if (contextData.get("attachmentType") != null) {
                    context.getBinaryInfo().setType((String) (contextData.get("attachmentType")));
                }
                if (contextData.get("attachmentData") != null) {
                    context.getBinaryInfo().setData((String) (contextData.get("attachmentData")));
                }
            }

            // Resolve device ID and set it hierarchically in the payload
            String resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant);
            List<ExternalId> externalSources = cumulocityMessage.getExternalSource();
            String externalId = null;
            String externalType = null;

            if (externalSources != null && !externalSources.isEmpty()) {
                ExternalId externalSource = externalSources.get(0);
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
                    ExternalId externalSource = externalSources.get(0);
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

            DynamicMapperRequest dynamicMapperRequest = ProcessingResultHelper.createAndAddDynamicMapperRequest(context,
                    payloadJson,
                    cumulocityMessage.getAction(), mapping);
            dynamicMapperRequest.setApi(targetAPI);
            dynamicMapperRequest.setSourceId(resolvedDeviceId);
            dynamicMapperRequest.setExternalId(externalId);
            dynamicMapperRequest.setExternalIdType(externalType);

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId);

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityObject: " + e.getMessage(), e);
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