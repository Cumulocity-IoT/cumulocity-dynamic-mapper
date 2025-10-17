package dynamic.mapper.processor.inbound.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

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
import dynamic.mapper.processor.flow.CumulocitySource;
import dynamic.mapper.processor.flow.ExternalSource;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.service.MappingService;
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
                    "Tenant %s - Error in FlowSubstitutionInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);
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
            API targetAPI = getAPIFromCumulocityType(cumulocityMessage.getCumulocityType());

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // Resolve device ID and set it hierarchically in the payload
            String resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant);
            List<ExternalSource> externalSources = convertToExternalSourceList(cumulocityMessage.getExternalSource());
            String externalId = null;
            String externalType = null;
            if(externalSources != null && externalSources.size() > 0) {
                ExternalSource externalSource = externalSources.get(0);
                externalId = externalSource.getExternalId();
                externalType = externalSource.getType();
                context.setExternalId(externalId);
            }

            if (resolvedDeviceId != null) {
                setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                context.setSourceId(resolvedDeviceId);
            } else if (cumulocityMessage.getExternalSource() != null) {
                // create implicitDevice
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
                        externalType = externalSource.getType();
                        externalId = externalSource.getExternalId();
                        context.setExternalId(externalSource.getExternalId());
                        setHierarchicalValue(payload, targetAPI.identifier, sourceId);
                    }

                }
            }

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Create the C8Y request using the correct constructor and methods
            DynamicMapperRequest c8yRequest = createDynamicMapperRequest(payloadJson, targetAPI,
                    cumulocityMessage.getAction(), resolvedDeviceId);
            c8yRequest.setExternalId(externalId);
            c8yRequest.setExternalIdType(externalType);

            // Add the request to context
            context.addRequest(c8yRequest);

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId);

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityMessage: " + e.getMessage(), e);
        }
    }

    /**
     * Sets a value hierarchically in a map using dot notation
     * E.g., "source.id" will create nested maps: {"source": {"id": value}}
     */
    private void setHierarchicalValue(Map<String, Object> map, String path, Object value) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = map;

        // Navigate/create the hierarchy up to the last key
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            if (!current.containsKey(key) || !(current.get(key) instanceof Map)) {
                current.put(key, new HashMap<String, Object>());
            }
            current = (Map<String, Object>) current.get(key);
        }

        // Set the value at the final key
        current.put(keys[keys.length - 1], value);
    }

    private API getAPIFromCumulocityType(String cumulocityType) throws ProcessingException {
        switch (cumulocityType.toLowerCase()) {
            case "measurement":
                return API.MEASUREMENT;
            case "alarm":
                return API.ALARM;
            case "event":
                return API.EVENT;
            case "inventory":
            case "managedobject":
                return API.INVENTORY;
            case "operation":
                return API.OPERATION;
            default:
                throw new ProcessingException("Unknown cumulocity type: " + cumulocityType);
        }
    }

    private String resolveDeviceIdentifier(CumulocityMessage cumulocityMessage, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        // First try externalSource
        if (cumulocityMessage.getExternalSource() != null) {
            return resolveFromExternalSource(cumulocityMessage.getExternalSource(), context, tenant);
        }

        // Then try internalSource
        if (cumulocityMessage.getInternalSource() != null) {
            return resolveFromInternalSource(cumulocityMessage.getInternalSource());
        }

        // Fallback to mapping's generic device identifier
        return context.getMapping().getGenericDeviceIdentifier();
    }

    private String resolveFromExternalSource(Object externalSourceObj, ProcessingContext<?> context,
            String tenant) throws ProcessingException {

        List<ExternalSource> externalSources = convertToExternalSourceList(externalSourceObj);

        if (externalSources.isEmpty()) {
            throw new ProcessingException("External source is empty");
        }

        // Use the first external source for resolution
        ExternalSource externalSource = externalSources.get(0);


        try {
            // Use C8YAgent to resolve external ID to global ID
            var globalId = c8yAgent.resolveExternalId2GlobalId(tenant,
                    new ID(externalSource.getType(), externalSource.getExternalId()),
                    context.isTesting());
            context.setExternalId(externalSource.getExternalId());
            if (globalId != null) {
                return globalId.getManagedObject().getId().getValue();
            } else {
                log.warn("{} - Could not resolve external ID: {}", tenant, externalSource.getExternalId());
                return null;
            }

        } catch (Exception e) {
            throw new ProcessingException("Failed to resolve external ID: " + externalSource.getExternalId(), e);
        }
    }

    private String resolveFromInternalSource(Object internalSourceObj) throws ProcessingException {
        List<CumulocitySource> internalSources = convertToInternalSourceList(internalSourceObj);

        if (internalSources.isEmpty()) {
            throw new ProcessingException("Internal source is empty");
        }

        // Use the first internal source directly
        return internalSources.get(0).getInternalId();
    }

    @SuppressWarnings("unchecked")
    private List<ExternalSource> convertToExternalSourceList(Object obj) {
        List<ExternalSource> result = new ArrayList<>();

        if (obj == null) {
            return result;
        }

        if (obj instanceof ExternalSource) {
            result.add((ExternalSource) obj);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof ExternalSource) {
                    result.add((ExternalSource) item);
                } else if (item instanceof Map) {
                    // Convert Map to ExternalSource
                    ExternalSource externalSource = convertMapToExternalSource((Map<String, Object>) item);
                    if (externalSource != null) {
                        result.add(externalSource);
                    }
                }
            }
        } else if (obj instanceof Map) {
            ExternalSource externalSource = convertMapToExternalSource((Map<String, Object>) obj);
            if (externalSource != null) {
                result.add(externalSource);
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<CumulocitySource> convertToInternalSourceList(Object obj) {
        List<CumulocitySource> result = new ArrayList<>();

        if (obj == null) {
            return result;
        }

        if (obj instanceof CumulocitySource) {
            result.add((CumulocitySource) obj);
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                if (item instanceof CumulocitySource) {
                    result.add((CumulocitySource) item);
                } else if (item instanceof Map) {
                    // Convert Map to CumulocitySource
                    CumulocitySource cumulocitySource = convertMapToCumulocitySource((Map<String, Object>) item);
                    if (cumulocitySource != null) {
                        result.add(cumulocitySource);
                    }
                }
            }
        } else if (obj instanceof Map) {
            CumulocitySource cumulocitySource = convertMapToCumulocitySource((Map<String, Object>) obj);
            if (cumulocitySource != null) {
                result.add(cumulocitySource);
            }
        }

        return result;
    }

    private ExternalSource convertMapToExternalSource(Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        ExternalSource externalSource = new ExternalSource();

        if (map.containsKey("externalId")) {
            externalSource.setExternalId(String.valueOf(map.get("externalId")));
        }
        if (map.containsKey("type")) {
            externalSource.setType(String.valueOf(map.get("type")));
        }
        if (map.containsKey("autoCreateDeviceMO")) {
            externalSource.setAutoCreateDeviceMO((Boolean) map.get("autoCreateDeviceMO"));
        }
        if (map.containsKey("parentId")) {
            externalSource.setParentId(String.valueOf(map.get("parentId")));
        }
        if (map.containsKey("childReference")) {
            externalSource.setChildReference(String.valueOf(map.get("childReference")));
        }
        if (map.containsKey("clientId")) {
            externalSource.setClientId(String.valueOf(map.get("clientId")));
        }

        // Only return if we have the required fields
        if (externalSource.getExternalId() != null && externalSource.getType() != null) {
            return externalSource;
        }

        return null;
    }

    private CumulocitySource convertMapToCumulocitySource(Map<String, Object> map) {
        if (map == null || !map.containsKey("internalId")) {
            return null;
        }

        return new CumulocitySource(String.valueOf(map.get("internalId")));
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

    private DynamicMapperRequest createDynamicMapperRequest(String payloadJson, API targetAPI, String action,
            String sourceId) throws ProcessingException {
        try {
            // Determine the request method based on action
            RequestMethod method = "create".equals(action) ? RequestMethod.POST : RequestMethod.PUT;

            // Create the C8Y request using the builder pattern
            DynamicMapperRequest c8yRequest = DynamicMapperRequest.builder()
                    .method(method)
                    .api(targetAPI)
                    .sourceId(sourceId)
                    .request(payloadJson)
                    .build();

            return c8yRequest;

        } catch (Exception e) {
            throw new ProcessingException("Failed to create C8Y request: " + e.getMessage(), e);
        }
    }
}