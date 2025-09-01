package dynamic.mapper.processor.inbound.processor;

import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.C8YRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.ProcessingMode;

@Slf4j
@Component
public class SubstitutionProcessor extends BaseProcessor {

    @Autowired
    private C8YAgent c8yAgent;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            substituteInTargetAndCreateRequests(context);
        } catch (Exception e) {
            String errorMessage = String.format("Tenant %s - Error in substitution processor for mapping: %s",
                    tenant, mapping.name);
            log.error(errorMessage, e);
            context.addError(new ProcessingException("Substitution failed", e));
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

    }

    /**
     * Perform substitution and create C8Y requests
     */
    private void substituteInTargetAndCreateRequests(ProcessingContext<Object> context) throws Exception {
        Mapping mapping = context.getMapping();

        if (mapping.getTargetTemplate() == null || mapping.getTargetTemplate().trim().isEmpty()) {
            log.warn("No target template defined for mapping: {}", mapping.getName());
            return;
        }

        String targetTemplate = mapping.getTargetTemplate();
        Map<String, List<SubstituteValue>> processingCache = context.getProcessingCache();

        if (processingCache == null || processingCache.isEmpty()) {
            log.debug("Processing cache is empty for mapping: {}", mapping.getName());
            // Create single request with original template
            createC8YRequest(targetTemplate, context, mapping);
            return;
        }

        // Determine cardinality based on expandArray values
        int cardinality = determineCardinality(processingCache);
        log.debug("Determined cardinality: {} for mapping: {}", cardinality, mapping.getName());

        // Create requests based on cardinality
        for (int i = 0; i < cardinality; i++) {
            try {
                DocumentContext payloadTarget = JsonPath.parse(targetTemplate);

                // Apply substitutions for this index
                for (Map.Entry<String, List<SubstituteValue>> entry : processingCache.entrySet()) {
                    String pathTarget = entry.getKey();
                    List<SubstituteValue> substituteValues = entry.getValue();

                    if (substituteValues != null && !substituteValues.isEmpty()) {
                        SubstituteValue substitute = getSubstituteValueForIndex(substituteValues, i);

                        if (substitute != null) {
                            // EXACT copy of prepareAndSubstituteInPayload logic
                            prepareAndSubstituteInPayload(context, payloadTarget, pathTarget, substitute);
                        }
                    }
                }

                // Convert processed payload back to string
                String processedPayload = payloadTarget.jsonString();

                // Create C8Y request
                createC8YRequest(processedPayload, context, mapping);

                log.debug("Created request {} of {} for mapping: {}", i + 1, cardinality, mapping.getName());

            } catch (Exception e) {
                log.error("Failed to create request {} for mapping: {}", i, mapping.getName(), e);
                context.addError(new ProcessingException("Failed to create request " + i, e));

                if (!context.isNeedsRepair()) {
                    throw e;
                }
            }
        }
    }

    /**
     * EXACT copy of BaseProcessorInbound.prepareAndSubstituteInPayload - DO NOT
     * MODIFY!
     * 
     * @throws ProcessingException
     */
    private void prepareAndSubstituteInPayload(ProcessingContext<Object> context, DocumentContext payloadTarget,
            String pathTarget, SubstituteValue substitute) throws ProcessingException {
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        if ((Mapping.TOKEN_IDENTITY + ".externalId").equals(pathTarget)) {
            ID identity = new ID(mapping.getExternalIdType(), substitute.getValue().toString());
            SubstituteValue sourceId = new SubstituteValue(substitute.getValue(),
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            if (!context.getApi().equals(API.INVENTORY)) {
                var resolvedSourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity, context);
                if (resolvedSourceId == null) {
                    if (mapping.getCreateNonExistingDevice()) {
                        sourceId.setValue(createImplicitDevice(identity, context));
                    }
                } else {
                    sourceId.setValue(resolvedSourceId.getManagedObject().getId().getValue());
                }
                SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                        mapping.transformGenericPath2C8YPath(pathTarget));
                context.setSourceId(sourceId.getValue().toString());
                // cache the mapping of device to client ID
                if (context.getClient() != null) {
                    configurationRegistry.addOrUpdateClientRelation(tenant, context.getClient(),
                            sourceId.getValue().toString());
                }
                substitute.setRepairStrategy(RepairStrategy.CREATE_IF_MISSING);
            }
        } else if ((Mapping.TOKEN_IDENTITY + ".c8ySourceId").equals(pathTarget)) {
            SubstituteValue sourceId = new SubstituteValue(substitute.getValue(),
                    TYPE.TEXTUAL, RepairStrategy.CREATE_IF_MISSING, false);
            // in this case the device needs to exists beforehand
            SubstituteValue.substituteValueInPayload(sourceId, payloadTarget,
                    mapping.transformGenericPath2C8YPath(pathTarget));
            context.setSourceId(sourceId.getValue().toString());
            // cache the mapping of device to client ID
            if (context.getClient() != null) {
                configurationRegistry.addOrUpdateClientRelation(tenant, context.getClient(),
                        sourceId.getValue().toString());
            }
            substitute.setRepairStrategy(RepairStrategy.CREATE_IF_MISSING);
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".api").equals(pathTarget)) {
            context.setApi(API.fromString((String) substitute.getValue()));
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Name").equals(pathTarget)) {
            context.getBinaryInfo().setName((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Type").equals(pathTarget)) {
            context.getBinaryInfo().setType((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".attachment_Data").equals(pathTarget)) {
            context.getBinaryInfo().setData((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".processingMode").equals(pathTarget)) {
            context.setProcessingMode(ProcessingMode.parse((String) substitute.getValue()));
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".deviceName").equals(pathTarget)) {
            context.setDeviceName((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA + ".deviceType").equals(pathTarget)) {
            context.setDeviceType((String) substitute.getValue());
        } else if ((Mapping.TOKEN_CONTEXT_DATA).equals(pathTarget)) {
            // Handle the case where substitute.value is a Map containing context data keys
            if (substitute.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> contextDataMap = (Map<String, Object>) substitute.getValue();

                // Process each key in the map
                for (Map.Entry<String, Object> entry : contextDataMap.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();

                    switch (key) {
                        case "api":
                            if (value instanceof String) {
                                context.setApi(API.fromString((String) value));
                            }
                            break;
                        case "attachment_Name":
                            if (value instanceof String) {
                                context.getBinaryInfo().setName((String) value);
                            }
                            break;
                        case "attachment_Type":
                            if (value instanceof String) {
                                context.getBinaryInfo().setType((String) value);
                            }
                            break;
                        case "attachment_Data":
                            if (value instanceof String) {
                                context.getBinaryInfo().setData((String) value);
                            }
                            break;
                        case "processingMode":
                            if (value instanceof String) {
                                context.setProcessingMode(ProcessingMode.parse((String) substitute.getValue()));
                            }
                            break;
                        case "deviceName":
                            if (value instanceof String) {
                                context.setDeviceName((String) value);
                            }
                            break;
                        case "deviceType":
                            if (value instanceof String) {
                                context.setDeviceType((String) value);
                            }
                            break;
                        default:
                            // Handle unknown keys - you might want to log a warning or ignore
                            // Optional: log.warn("Unknown context data key: {}", key);
                            break;
                    }
                }
            }
        } else {
            SubstituteValue.substituteValueInPayload(substitute, payloadTarget, pathTarget);
        }
    }

    /**
     * Create implicit device when needed
     * 
     * @throws ProcessingException
     */
    private String createImplicitDevice(ID identity, ProcessingContext<Object> context) throws ProcessingException {
        try {
            // This would typically create a device in C8Y and return its ID
            // Implementation depends on your C8YAgent
            ManagedObjectRepresentation implMo = c8yAgent.upsertDevice(context.getTenant(), identity, context);
            return implMo.getId().getName();
        } catch (Exception e) {
            log.error("Failed to create implicit device for identity: {}", identity, e);
            throw new ProcessingException("Failed to create implicit device", e);
        }
    }

    /**
     * Get substitute value for specific index (handles array expansion)
     */
    private SubstituteValue getSubstituteValueForIndex(List<SubstituteValue> values, int index) {
        if (values.size() == 1) {
            return values.get(0);
        } else if (index < values.size()) {
            return values.get(index);
        } else {
            return values.get(values.size() - 1); // Use last value if index out of bounds
        }
    }

    /**
     * Determine cardinality based on expandArray values
     */
    private int determineCardinality(Map<String, List<SubstituteValue>> processingCache) {
        int maxCardinality = 1;

        for (List<SubstituteValue> values : processingCache.values()) {
            if (values != null && !values.isEmpty()) {
                SubstituteValue firstValue = values.get(0);
                if (firstValue.isExpandArray() && values.size() > maxCardinality) {
                    maxCardinality = values.size();
                }
            }
        }

        return maxCardinality;
    }

    /**
     * Create C8Y request with correct structure
     */
    private void createC8YRequest(String processedPayload, ProcessingContext<Object> context, Mapping mapping)
            throws Exception {
        API api = context.getApi() != null ? context.getApi() : determineDefaultAPI(mapping);

        C8YRequest request = C8YRequest.builder()
                .api(api)
                .method(RequestMethod.POST) // Default method
                .sourceId(context.getSourceId())
                .externalIdType(mapping.getExternalIdType())
                .request(processedPayload)
                .targetAPI(api)
                .build();

        context.addRequest(request);
        log.debug("Created C8Y request for API: {} with payload: {}", api, processedPayload);
    }

    /**
     * Determine default API from mapping
     */
    private API determineDefaultAPI(Mapping mapping) {
        if (mapping.getTargetAPI() != null) {
            try {
                return mapping.getTargetAPI();
            } catch (Exception e) {
                log.warn("Unknown target API: {}, defaulting to MEASUREMENT", mapping.getTargetAPI());
            }
        }

        return API.MEASUREMENT; // Default
    }

}
