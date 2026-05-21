package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.HashMap;
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
import dynamic.mapper.processor.model.CumulocityType;
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
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Component
public class FlowResultInboundProcessor extends AbstractFlowResultProcessor {

    private final C8YAgent c8yAgent;
    private final ConfigurationRegistry configurationRegistry;

    @Autowired
    public FlowResultInboundProcessor(
            MappingService mappingService,
            C8YAgent c8yAgent,
            ConfigurationRegistry configurationRegistry,
            ObjectMapper objectMapper) {
        super(mappingService, objectMapper);
        this.c8yAgent = c8yAgent;
        this.configurationRegistry = configurationRegistry;
    }

    /**
     * Moves CumulocityObject items that carry device-creation contextData to the front of the list.
     * This guarantees that the implicit device (with its deviceName, deviceType, deviceFragments,
     * deviceGroups) is always created before any other requests (e.g. an inventory update) that
     * target the same device — regardless of the order returned by onMessage().
     */
    @Override
    protected List<Object> reorderMessages(List<Object> messages) {
        List<Object> withContextData = new ArrayList<>();
        List<Object> withoutContextData = new ArrayList<>();
        for (Object msg : messages) {
            if (msg instanceof CumulocityObject && hasDeviceCreationContextData((CumulocityObject) msg)) {
                withContextData.add(msg);
            } else {
                withoutContextData.add(msg);
            }
        }
        List<Object> result = new ArrayList<>(withContextData);
        result.addAll(withoutContextData);
        return result;
    }

    private boolean hasDeviceCreationContextData(CumulocityObject obj) {
        Map<String, Object> cd = obj.getContextData();
        return cd != null && (cd.containsKey("deviceName") || cd.containsKey("deviceType")
                || cd.containsKey("deviceGroups") || cd.containsKey("deviceFragments"));
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
        int lineNumber = extractJsLineNumber(e);
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
            // Custom routing: bypass device resolution, call tenant-local microservice directly
            if (CumulocityType.CUSTOM.equals(cumulocityMessage.getCumulocityType())) {
                String targetPath = cumulocityMessage.getTargetPath();
                if (targetPath == null || !targetPath.startsWith("/service/")) {
                    throw new ProcessingException(
                            "Custom routing targetPath must start with /service/, got: " + targetPath);
                }
                DynamicMapperRequest customRequest = DynamicMapperRequest.builder()
                        .predecessor(-1)
                        .method(ProcessingResultHelper.mapActionToRequestMethod(cumulocityMessage.getAction()))
                        .api(API.CUSTOM)
                        .pathCumulocity(targetPath)
                        .request(objectMapper.writeValueAsString(cumulocityMessage.getPayload()))
                        .build();
                output.addRequest(customRequest);
                log.debug("{} - Created CUSTOM route request: path={}, method={}",
                        tenant, targetPath, customRequest.getMethod());
                return;
            }

            // Get the API from the cumulocityType using unified API derivation
            if(cumulocityMessage.getCumulocityType() == null){
                String warnMsg = String.format(
                        "CumulocityObject missing cumulocityType, cannot derive API for mapping '%s', skipping message", mapping.getIdentifier());
                log.warn("{} - {}", tenant, warnMsg);
                output.addWarning(warnMsg);
                return;
            }
            API targetAPI = APITopicUtil.deriveAPIFromTopic(cumulocityMessage.getCumulocityType().toString());
            if (targetAPI == null) {
                String warnMsgType = String.format(
                        "CumulocityObject has unrecognized cumulocityType '%s' for mapping '%s', skipping message",
                        cumulocityMessage.getCumulocityType(), mapping.getIdentifier());
                log.warn("{} - {}", tenant, warnMsgType);
                output.addWarning(warnMsgType);
                return;
            }

            // Set API on context so it's used when creating DynamicMapperRequest
            context.setApi(targetAPI);

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // contextData for generating device with defined name/type
            Map<String, Object> contextData = cumulocityMessage.getContextData();
            if (contextData != null) {
                if (contextData.get("deviceName") != null) {
                    context.setDeviceName((String) contextData.get("deviceName"));
                }
                if (contextData.get("deviceType") != null) {
                    context.setDeviceType((String) contextData.get("deviceType"));
                }
                if (contextData.get("processingMode") != null) {
                    context.setProcessingMode(ProcessingMode.parse((String) contextData.get("processingMode")));
                }
                if (contextData.get("attachmentName") != null) {
                    context.getBinaryInfo().setName((String) contextData.get("attachmentName"));
                }
                if (contextData.get("attachmentType") != null) {
                    context.getBinaryInfo().setType((String) contextData.get("attachmentType"));
                }
                if (contextData.get("attachmentData") != null) {
                    context.getBinaryInfo().setData((String) contextData.get("attachmentData"));
                }
                if (contextData.get("deviceFragments") != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> deviceFragments = (Map<String, Object>) contextData.get("deviceFragments");
                    context.setDeviceFragments(deviceFragments);
                }
                if (contextData.get("deviceGroups") != null) {
                    @SuppressWarnings("unchecked")
                    List<String> deviceGroups = (List<String>) contextData.get("deviceGroups");
                    context.setDeviceGroups(deviceGroups);
                }
            }

            // Merge device identity into MANAGED_OBJECT patch payload so that name/type/fragments
            // are always written to inventory — even when:
            // (a) contextData is on a sibling CumulocityObject in the same batch (processed first
            //     by reorderMessages, populating context.getDeviceName/Type/Fragments), or
            // (b) the device was created by a concurrent thread without contextData (race condition).
            if (CumulocityType.MANAGED_OBJECT.equals(cumulocityMessage.getCumulocityType())) {
                if (context.getDeviceName() != null) {
                    payload.put("name", context.getDeviceName());
                }
                if (context.getDeviceType() != null) {
                    payload.put("type", context.getDeviceType());
                }
                if (context.getDeviceFragments() != null) {
                    payload.putAll(context.getDeviceFragments());
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
                    ExternalId externalId = externalSources.get(0);
                    if (externalId != null && externalId.getType() != null
                            && externalId.getExternalId() != null) {
                        ID identity = new ID(externalId.getType(),
                                externalId.getExternalId());
                        // Use thread-safe method to prevent race condition
                        String sourceId = configurationRegistry.getOrCreateDeviceThreadSafe(
                                tenant, externalId.getType(), externalId.getExternalId(), identity, context);
                        if (sourceId != null) {
                            context.setSourceId(sourceId);
                            resolvedDeviceId = sourceId; // Set this so it's used below
                            // Update externalIdInfo with created device info
                            externalIdInfo = ExternalIdInfo.builder()
                                    .externalType(externalId.getType())
                                    .externalId(externalId.getExternalId())
                                    .build();
                            context.setExternalId(externalId.getExternalId());
                            ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, sourceId);
                        } else {
                            String warnMsg = String.format(
                                    "Failed to create implicit device for externalId '%s' (type '%s') for mapping '%s'.",
                                    externalId.getExternalId(), externalId.getType(), mapping.getIdentifier());
                            log.warn("{} - {}", tenant, warnMsg);
                            output.addWarning(warnMsg);
                            return; // Don't create a request
                        }
                    }
                } else {
                    // No device ID and not creating implicit devices - skip this message
                    ExternalId externalId = externalSources.get(0);
                    String warnMsg = String.format(
                            "Device with externalId '%s' (type '%s') not found in inventory and createNonExistingDevice is disabled - no request created for mapping '%s'. Enable createNonExistingDevice or use an existing externalId.",
                            externalId != null ? externalId.getExternalId() : "unknown",
                            externalId != null ? externalId.getType() : "unknown",
                            mapping.getIdentifier());
                    log.warn("{} - {}", tenant, warnMsg);
                    output.addWarning(warnMsg);
                    return; // Don't create a request
                }
            } else {
                String warnMsg = String.format(
                        "Cannot process message: no externalSource provided for mapping '%s'. Set externalId in the returned CumulocityObject.",
                        mapping.getIdentifier());
                log.warn("{} - {}", tenant, warnMsg);
                output.addWarning(warnMsg);
                return; // Don't create a request
            }

            // Only create request if we have a resolved device ID
            if (resolvedDeviceId == null) {
                log.warn("{} - Skipping request creation: no device ID available for API {} in mapping {}",
                        tenant, targetAPI.name, mapping.getIdentifier());
                return;
            }

            // For MEASUREMENT: always produce a { measurements: [...] } collection payload.
            // If the Smart Function returns { measurements: [...] }, source.id is injected into each entry.
            // If it returns a single measurement, it is wrapped in a one-element list.
            // C8YAgent always calls createBulk regardless — no user-facing type distinction needed.
            if (API.MEASUREMENT.equals(targetAPI)) {
                final Map<String, Object> collectionPayload = new HashMap<>();
                Object measurementsObj = payload.get("measurements");
                if (measurementsObj instanceof List) {
                    // Multi-measurement: inject source.id into each entry, strip outer source.
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> measurements = (List<Map<String, Object>>) measurementsObj;
                    Map<String, Object> sourceMap = new HashMap<>();
                    sourceMap.put("id", resolvedDeviceId);
                    for (Map<String, Object> m : measurements) {
                        m.put("source", sourceMap);
                    }
                    collectionPayload.put("measurements", measurements);
                    log.debug("{} - Created measurement collection request: {} measurements for device {}",
                            tenant, measurements.size(), resolvedDeviceId);
                } else {
                    // Single measurement: source.id already injected by setHierarchicalValue above.
                    collectionPayload.put("measurements", List.of(payload));
                    log.debug("{} - Created single measurement request for device {}", tenant, resolvedDeviceId);
                }
                String payloadJson = objectMapper.writeValueAsString(collectionPayload);
                DynamicMapperRequest dynamicMapperRequest = ProcessingResultHelper.createDynamicMapperRequest(
                        context.getDeviceContext(), routing, payloadJson, cumulocityMessage.getAction(), mapping);
                dynamicMapperRequest.setApi(targetAPI);
                dynamicMapperRequest.setSourceId(resolvedDeviceId);
                dynamicMapperRequest.setExternalId(externalIdInfo.getExternalId());
                dynamicMapperRequest.setExternalIdType(externalIdInfo.getExternalType());
                output.addRequest(dynamicMapperRequest);
                return;
            }

            // For EVENT / ALARM: if payload contains an "events" / "alarms" array, fan out to
            // N individual creation calls. Otherwise fall through to single-object processing.
            if (API.EVENT.equals(targetAPI) || API.EVENT_WITH_CHILDREN.equals(targetAPI)) {
                Object eventsObj = payload.get("events");
                if (eventsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> events = (List<Map<String, Object>>) eventsObj;
                    Map<String, Object> sourceMap = new HashMap<>();
                    sourceMap.put("id", resolvedDeviceId);
                    for (Map<String, Object> event : events) {
                        event.put("source", sourceMap);
                        String eventJson = objectMapper.writeValueAsString(event);
                        DynamicMapperRequest req = ProcessingResultHelper.createDynamicMapperRequest(
                                context.getDeviceContext(), routing, eventJson, cumulocityMessage.getAction(), mapping);
                        req.setApi(API.EVENT);
                        req.setSourceId(resolvedDeviceId);
                        req.setExternalId(externalIdInfo.getExternalId());
                        req.setExternalIdType(externalIdInfo.getExternalType());
                        output.addRequest(req);
                    }
                    log.debug("{} - Fanned out {} event request(s) for device {}", tenant, events.size(), resolvedDeviceId);
                    return;
                }
            }

            if (API.ALARM.equals(targetAPI) || API.ALARM_WITH_CHILDREN.equals(targetAPI)) {
                Object alarmsObj = payload.get("alarms");
                if (alarmsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> alarms = (List<Map<String, Object>>) alarmsObj;
                    Map<String, Object> sourceMap = new HashMap<>();
                    sourceMap.put("id", resolvedDeviceId);
                    for (Map<String, Object> alarm : alarms) {
                        alarm.put("source", sourceMap);
                        String alarmJson = objectMapper.writeValueAsString(alarm);
                        DynamicMapperRequest req = ProcessingResultHelper.createDynamicMapperRequest(
                                context.getDeviceContext(), routing, alarmJson, cumulocityMessage.getAction(), mapping);
                        req.setApi(API.ALARM);
                        req.setSourceId(resolvedDeviceId);
                        req.setExternalId(externalIdInfo.getExternalId());
                        req.setExternalIdType(externalIdInfo.getExternalType());
                        output.addRequest(req);
                    }
                    log.debug("{} - Fanned out {} alarm request(s) for device {}", tenant, alarms.size(), resolvedDeviceId);
                    return;
                }
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