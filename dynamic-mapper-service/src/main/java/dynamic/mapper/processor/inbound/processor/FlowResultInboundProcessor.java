package dynamic.mapper.processor.inbound.processor;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cumulocity.model.ID;
import com.cumulocity.sdk.client.ProcessingMode;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractFlowResultProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.ExternalIdInfo;
import dynamic.mapper.processor.model.OutputCollector;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingState;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowResultInboundProcessor extends AbstractFlowResultProcessor {

    private final C8YAgent c8yAgent;

    public FlowResultInboundProcessor(
            MappingService mappingService,
            C8YAgent c8yAgent,
            ObjectMapper objectMapper) {
        super(mappingService, objectMapper);
        this.c8yAgent = c8yAgent;
    }

    @Override
    protected void processMessage(
            Object message,
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context) throws ProcessingException {
        String tenant = routing.getTenant();
        Mapping mapping = context.getMapping();

        if (message instanceof CumulocityObject) {
            processCumulocityObject((CumulocityObject) message, routing, state, output, context, tenant, mapping);
        } else {
            log.debug("{} - Message is not a CumulocityObject, skipping: {}", tenant,
                    message.getClass().getSimpleName());
        }
    }

    @Override
    protected void postProcessFlowResults(ProcessingState state, OutputCollector output,
                                         ProcessingContext<?> context) throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        // Check inventory filter condition if specified
        if (mapping.getFilterInventory() != null) {
            boolean filterInventory = evaluateInventoryFilter(tenant, mapping.getFilterInventory(),
                    context.getSourceId(), context.getTesting());
            if (context.getSourceId() == null || !filterInventory) {
                if (mapping.getDebug()) {
                    log.info(
                            "{} - Inbound mapping {}/{} not processed, failing Filter inventory execution: filterResult {}",
                            tenant, mapping.getName(), mapping.getIdentifier(),
                            filterInventory);
                }
                state.setIgnoreFurtherProcessing(true);
            }
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
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

        if (!context.getTesting()) {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }
    }

    /**
     * NEW: Process CumulocityObject using focused contexts.
     */
    private void processCumulocityObject(
            CumulocityObject cumulocityMessage,
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context,
            String tenant,
            Mapping mapping) throws ProcessingException {

        try {
            // Get the API from the cumulocityType using unified API derivation
            API targetAPI = APITopicUtil.deriveAPIFromTopic(cumulocityMessage.getCumulocityType().toString());

            // Set API on context so it's used when creating DynamicMapperRequest
            context.setApi(targetAPI);

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

            // Check if sourceId is explicitly set in CumulocityObject
            String resolvedDeviceId;
            List<ExternalId> externalSources = cumulocityMessage.getExternalSource();
            ExternalIdInfo externalIdInfo = ExternalIdInfo.from(externalSources);

            if (externalIdInfo.isPresent()) {
                context.setExternalId(externalIdInfo.getExternalId());
            }

            if (cumulocityMessage.getSourceId() != null && !cumulocityMessage.getSourceId().isEmpty()) {
                // Use explicitly provided sourceId
                resolvedDeviceId = cumulocityMessage.getSourceId();
                context.setSourceId(resolvedDeviceId);
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                log.debug("{} - Using explicit sourceId from CumulocityObject: {}", tenant, resolvedDeviceId);
            } else if ((resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant)) != null) {
                // Use resolved device ID from externalSource
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
                        // Update externalIdInfo with created device info
                        externalIdInfo = ExternalIdInfo.builder()
                                .externalType(externalSource.getType())
                                .externalId(externalSource.getExternalId())
                                .build();
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

            // Create request without adding to context (will be added via OutputCollector)
            DynamicMapperRequest dynamicMapperRequest = ProcessingResultHelper.createDynamicMapperRequest(
                    context.getDeviceContext(),
                    routing,
                    payloadJson,
                    cumulocityMessage.getAction(),
                    mapping);

            // Set additional properties
            dynamicMapperRequest.setApi(targetAPI);  // Set the derived API for this specific message
            dynamicMapperRequest.setSourceId(resolvedDeviceId);
            dynamicMapperRequest.setExternalId(externalIdInfo.getExternalId());
            dynamicMapperRequest.setExternalIdType(externalIdInfo.getExternalType());

            // Add to output collector (thread-safe), will be synced back to context
            output.addRequest(dynamicMapperRequest);

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId);

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityObject: " + e.getMessage(), e);
        }
    }

}