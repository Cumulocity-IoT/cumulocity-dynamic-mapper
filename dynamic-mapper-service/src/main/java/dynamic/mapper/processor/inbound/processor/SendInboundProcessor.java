package dynamic.mapper.processor.inbound.processor;

import dynamic.mapper.processor.util.CamelHeaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.IdentityResolutionService;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer;
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
    private IdentityResolutionService identityResolutionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MappingService mappingService;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<Object> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT, ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        Boolean testing = context.getTesting();

        // Check if processing was cancelled due to timeout
        ProcessingResultWrapper<?> wrapper = exchange.getIn().getHeader(CamelHeaders.PROCESSING_RESULT_WRAPPER,
                ProcessingResultWrapper.class);
        if (wrapper != null && wrapper.getCancellationRequested().get()) {
            log.warn("{} - Processing was cancelled (timeout), skipping SendInboundProcessor for mapping: {}",
                    tenant, mapping.getName());
            return;
        }

        try {
            // Check if we have a single request from parallel processing (body contains split request)
            DynamicMapperRequest singleRequest = exchange.getIn().getBody(DynamicMapperRequest.class);

            if (singleRequest != null) {
                // Parallel mode: process single request from body
                processSingleRequest(context, singleRequest, true);
            } else {
                // Sequential mode: collapse multiple measurement requests into one bulk request.
                bulkMeasurementRequestsIfNeeded(context);
                // Sequential mode: process all requests in context
                processAllRequests(context);
            }
            // After all requests are processed, store the SparkPlug B birth fragment if applicable.
            // Deliberately outside the INVENTORY request path so it runs even when the Smart Function
            // emits no INVENTORY object (e.g. emits only a MEASUREMENT, or emits nothing at all).
            storeSparkPlugBBirthMessage(context);
            // Update the sparkPlugB_isActive flag: TRUE for BIRTH/DATA, FALSE for DEATH.
            updateSparkPlugBActiveStatus(context);
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
     * If more than one measurement request exists in the context, merge them into one
     * bulk request with payload shape {"measurements": [...]}.
     */
    private void bulkMeasurementRequestsIfNeeded(ProcessingContext<Object> context) throws ProcessingException {
        List<DynamicMapperRequest> requests = context.getRequests();
        if (requests == null || requests.size() < 2) {
            return;
        }

        List<DynamicMapperRequest> measurementRequests = requests.stream()
                .filter(req -> API.MEASUREMENT.equals(req.getApi()))
                .toList();
        if (measurementRequests.size() <= 1) {
            return;
        }

        final String tenant = context.getTenant();
        final List<Map<String, Object>> combinedMeasurements = new ArrayList<>();
        for (DynamicMapperRequest request : measurementRequests) {
            try {
                Map<String, Object> payloadMap = objectMapper.readValue(
                        request.getRequest(), new TypeReference<Map<String, Object>>() {});
                Object measurements = payloadMap.get("measurements");
                if (measurements instanceof List<?>) {
                    for (Object entry : (List<?>) measurements) {
                        if (entry instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> measurement = (Map<String, Object>) entry;
                            combinedMeasurements.add(measurement);
                        }
                    }
                } else {
                    combinedMeasurements.add(payloadMap);
                }
            } catch (Exception e) {
                throw new ProcessingException("Failed to parse measurement request for bulk processing", e);
            }
        }

        DynamicMapperRequest template = measurementRequests.get(0);
        Map<String, Object> bulkPayload = new HashMap<>();
        bulkPayload.put("measurements", combinedMeasurements);

        DynamicMapperRequest bulkRequest = DynamicMapperRequest.builder()
                .predecessor(template.getPredecessor())
                .method(template.getMethod())
                .api(API.MEASUREMENT)
                .publishTopic(template.getPublishTopic())
                .retain(template.getRetain())
                .sourceId(template.getSourceId())
                .externalId(template.getExternalId())
                .externalIdType(template.getExternalIdType())
                .pathCumulocity(template.getPathCumulocity())
                .build();

        try {
            bulkRequest.setRequest(objectMapper.writeValueAsString(bulkPayload));
        } catch (Exception e) {
            throw new ProcessingException("Failed to serialize bulk measurement payload", e);
        }

        List<DynamicMapperRequest> mergedRequests = new ArrayList<>();
        boolean bulkInserted = false;
        for (DynamicMapperRequest request : requests) {
            if (API.MEASUREMENT.equals(request.getApi())) {
                if (!bulkInserted) {
                    mergedRequests.add(bulkRequest);
                    bulkInserted = true;
                }
                continue;
            }
            mergedRequests.add(request);
        }
        context.setRequests(mergedRequests);

        log.info("{} - Merged {} measurement requests into one bulk request with {} measurements",
                tenant, measurementRequests.size(), combinedMeasurements.size());
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
            // Propagate the resolved device ID to context so storeSparkPlugBBirthMessage can use it.
            context.setSourceId(device.getId().getValue());

        } catch (Exception e) {
            request.setError(e);
            throw e;
        }
    }

    /**
     * Process non-INVENTORY API requests (MEASUREMENT, EVENT, ALARM)
     */
    private void processNonInventoryRequest(ProcessingContext<Object> context, int requestIndex) throws Exception {
        String tenant = context.getTenant();
        DynamicMapperRequest request = context.getRequests().get(requestIndex);
        try {
            // Resolve external ID if needed and add source to payload
            if (request.getExternalId() != null) {
                ID identity = new ID(request.getExternalIdType(), request.getExternalId());
                ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId2GlobalId(tenant, identity,
                        context.getTesting());

                if (sourceId != null) {
                    request.setSourceId(sourceId.getManagedObject().getId().getValue());

                    // Add source field to payload JSON
                    String payloadJson = request.getRequest();
                    Map<String, Object> payloadMap = objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
                    Map<String, Object> source = new HashMap<>();
                    source.put("id", request.getSourceId());
                    payloadMap.put("source", source);
                    request.setRequest(objectMapper.writeValueAsString(payloadMap));

                    // Cache the mapping of device to client ID
                    if (context.getClientId() != null) {
                        configurationRegistry.addOrUpdateClientRelation(tenant, context.getClientId(),
                                request.getSourceId());
                    }
                }
            }

            if (context.getSendPayload()) {
                if (context.getServiceConfiguration().getLogPayload()) {
                    log.info("{} - Sending {} request to C8Y: externalId={}, payload={}",
                            tenant, request.getApi(), request.getExternalId(), request.getRequest());
                }
                AbstractExtensibleRepresentation meaoResult = c8yAgent.createMEAO(context, requestIndex);
                if (meaoResult != null) {
                    request.setResponse(objectMapper.writeValueAsString(meaoResult));
                }
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

    /**
     * For SparkPlug B BIRTH messages, persists the alias→metric-definition map as a named
     * fragment on the managed object so that subsequent DATA messages can resolve aliases.
     * <ul>
     *   <li><b>NBIRTH</b> — stored as {@code sparkPlugB_NBIRTH} on the <b>Edge Node</b> MO
     *       (identified by the external ID {@code [Group ID]_[Edge Node ID]}). The MO was just
     *       created/updated by {@code upsertDevice}, so it is guaranteed to exist.</li>
     *   <li><b>DBIRTH</b> — stored as {@code sparkPlugB_DBIRTH_<sparkplugDeviceId>} on the
     *       <b>Edge Node</b> MO (same NODE MO). No separate Device MO is created for DBIRTH.
     *       This allows all DBIRTH alias maps to live on the NODE device.</li>
     * </ul>
     *
     * @param context the current processing context
     */
    @SuppressWarnings("unchecked")
    private void storeSparkPlugBBirthMessage(ProcessingContext<Object> context) {
        if (!dynamic.mapper.processor.model.MappingType.SPARKPLUGB
                .equals(context.getMapping().getMappingType())) {
            return;
        }

        String topic = context.getTopic();
        if (topic == null) {
            return;
        }
        // Topic format: spBv1.0/{group_id}/{message_type}/{edge_node_id}[/{device_id}]
        String[] parts = topic.split("/");
        if (parts.length < 4) {
            return;
        }
        String messageType = parts[2];
        if (!"NBIRTH".equals(messageType) && !"DBIRTH".equals(messageType)) {
            return;
        }

        String tenant = context.getTenant();
        String externalIdType = context.getMapping().getExternalIdType();
        if (externalIdType == null || externalIdType.isEmpty()) {
            externalIdType = "c8y_Serial";
        }

        // C8Y internal ID of the MO where the fragment will be stored
        final String targetDeviceId;
        final String fragmentKey;

        if ("NBIRTH".equals(messageType)) {
            // NBIRTH: store sparkPlugB_NBIRTH on the NODE MO.
            // context.getSourceId() is set when the Smart Function returned an INVENTORY object.
            String resolvedId = context.getSourceId();
            if (resolvedId == null) {
                String externalIdValue = parts[1] + "_" + parts[3]; // [Group ID]_[Edge Node ID]
                com.cumulocity.model.ID identity = new com.cumulocity.model.ID(externalIdType, externalIdValue);
                com.cumulocity.rest.representation.identity.ExternalIDRepresentation resolved =
                        c8yAgent.resolveExternalId2GlobalId(tenant, identity, context.getTesting());
                if (resolved != null) {
                    resolvedId = resolved.getManagedObject().getId().getValue();
                    log.debug("{} - storeSparkPlugBBirthMessage: resolved {} → C8Y ID {}",
                            tenant, externalIdValue, resolvedId);
                } else if (Boolean.TRUE.equals(context.getMapping().getCreateNonExistingDevice())) {
                    log.info("{} - storeSparkPlugBBirthMessage: NODE MO for {}/{} does not exist, "
                            + "auto-creating (createNonExistingDevice=true)", tenant, externalIdType, externalIdValue);
                    try {
                        resolvedId = identityResolutionService.getOrCreateDeviceThreadSafe(
                                tenant, externalIdType, externalIdValue, identity, context);
                        if (resolvedId == null) {
                            log.error("{} - storeSparkPlugBBirthMessage: Failed to auto-create NODE device for {}/{}",
                                    tenant, externalIdType, externalIdValue);
                            return;
                        }
                    } catch (Exception e) {
                        log.error("{} - storeSparkPlugBBirthMessage: Exception while auto-creating NODE device for {}/{}: {}",
                                tenant, externalIdType, externalIdValue, e.getMessage());
                        return;
                    }
                } else {
                    log.error("{} - storeSparkPlugBBirthMessage: NODE MO for {} '{}' does not exist and no "
                            + "INVENTORY request was produced by the Smart Function. The '{}' fragment cannot "
                            + "be stored. Configure the Smart Function to return a CumulocityObject with "
                            + "cumulocityType=INVENTORY for NBIRTH messages, or enable createNonExistingDevice.",
                            tenant, externalIdType, externalIdValue, messageType);
                    return;
                }
            }
            targetDeviceId = resolvedId;
            fragmentKey = dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer.SPARKPLUGB_NBIRTH_FRAGMENT;

        } else {
            // DBIRTH: store sparkPlugB_DBIRTH_<sparkplugDeviceId> on the NODE MO.
            // No implicit device creation for DBIRTH — all data lives on the NODE device.
            if (parts.length < 5) {
                log.error("{} - storeSparkPlugBBirthMessage: DBIRTH topic has fewer than 5 levels: {}",
                        tenant, topic);
                return;
            }
            String sparkplugDeviceId = parts[4];
            // Always resolve the NODE's external ID: [Group ID]_[Edge Node ID]
            String nodeExternalIdValue = parts[1] + "_" + parts[3];
            com.cumulocity.model.ID nodeIdentity = new com.cumulocity.model.ID(externalIdType, nodeExternalIdValue);
            com.cumulocity.rest.representation.identity.ExternalIDRepresentation resolved =
                    c8yAgent.resolveExternalId2GlobalId(tenant, nodeIdentity, context.getTesting());
            if (resolved == null) {
                log.error("{} - storeSparkPlugBBirthMessage: NODE MO for {} '{}' not found. "
                        + "Cannot store DBIRTH alias map. Ensure the NBIRTH message has been processed first.",
                        tenant, externalIdType, nodeExternalIdValue);
                return;
            }
            targetDeviceId = resolved.getManagedObject().getId().getValue();
            fragmentKey = dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer
                    .getDbBirthFragmentKey(sparkplugDeviceId);
            log.debug("{} - storeSparkPlugBBirthMessage: DBIRTH for sparkplugDeviceId='{}' → "
                    + "fragment '{}' on NODE MO {}", tenant, sparkplugDeviceId, fragmentKey, targetDeviceId);
        }

        // ── Build the alias→metricDefinition map from the decoded payload ──────────────

        // ── Build the alias→metricDefinition map from the decoded payload ──────────────
        Object payload = context.getPayload();
        if (!(payload instanceof java.util.Map)) {
            return;
        }
        java.util.Map<String, Object> payloadMap = (java.util.Map<String, Object>) payload;
        Object metricsObj = payloadMap.get("metrics");
        if (!(metricsObj instanceof java.util.List)) {
            return;
        }

        // Build alias → {name, dataType} map for efficient alias resolution in NDATA/DDATA
        java.util.Map<Long, java.util.Map<String, Object>> aliasMap = new java.util.LinkedHashMap<>();
        for (Object metricObj : (java.util.List<?>) metricsObj) {
            if (!(metricObj instanceof java.util.Map)) {
                continue;
            }
            java.util.Map<String, Object> metric = (java.util.Map<String, Object>) metricObj;
            Object aliasObj = metric.get("alias");
            Object nameObj = metric.get("name");
            Object dataTypeObj = metric.get("dataType");
            if (aliasObj instanceof Long) {
                java.util.Map<String, Object> def = new java.util.LinkedHashMap<>();
                if (nameObj != null) def.put("name", nameObj);
                if (dataTypeObj != null) def.put("dataType", dataTypeObj);
                aliasMap.put((Long) aliasObj, def);
            }
        }

        log.info("{} - Storing '{}' fragment ({} metric definitions) on MO {} ({})",
                tenant, fragmentKey, aliasMap.size(), targetDeviceId, messageType);
        c8yAgent.storeManagedObjectFragment(tenant, targetDeviceId, fragmentKey,
                aliasMap, context.getTesting());
    }

    /**
     * Updates the isActive fragment on the NODE managed object for the SparkPlug B topic:
     * <ul>
     *   <li><b>NBIRTH / NDATA</b> → {@code sparkPlugB_isActive = true} on NODE MO</li>
     *   <li><b>NDEATH</b>         → {@code sparkPlugB_isActive = false} on NODE MO</li>
     *   <li><b>DBIRTH / DDATA</b> → {@code sparkPlugB_isActive_<deviceId> = true} on NODE MO</li>
     *   <li><b>DDEATH</b>         → {@code sparkPlugB_isActive_<deviceId> = false} on NODE MO</li>
     *   <li>NCMD / DCMD / STATE   — no change</li>
     * </ul>
     * Device-level isActive fragments are always stored on the <b>Edge Node MO</b> (same MO that
     * holds the DBIRTH alias maps), not on a separate device MO.
     */
    private void updateSparkPlugBActiveStatus(ProcessingContext<Object> context) {
        if (!dynamic.mapper.processor.model.MappingType.SPARKPLUGB
                .equals(context.getMapping().getMappingType())) {
            return;
        }

        String topic = context.getTopic();
        if (topic == null) {
            return;
        }
        String[] parts = topic.split("/");
        if (parts.length < 4) {
            return;
        }
        String messageType = parts[2];

        boolean isActive;
        if ("NBIRTH".equals(messageType) || "DBIRTH".equals(messageType)
                || "NDATA".equals(messageType) || "DDATA".equals(messageType)) {
            isActive = true;
        } else if ("NDEATH".equals(messageType) || "DDEATH".equals(messageType)) {
            isActive = false;
        } else {
            // NCMD / DCMD / STATE — not relevant for the active flag
            return;
        }

        String tenant = context.getTenant();
        String externalIdType = context.getMapping().getExternalIdType();
        if (externalIdType == null || externalIdType.isEmpty()) {
            externalIdType = "c8y_Serial";
        }

        boolean isDeviceLevel = "DBIRTH".equals(messageType) || "DDATA".equals(messageType)
                || "DDEATH".equals(messageType);

        if (isDeviceLevel) {
            // Device-level: store sparkPlugB_isActive_<deviceId> on the NODE MO
            if (parts.length < 5) {
                log.warn("{} - updateSparkPlugBActiveStatus: {} topic has fewer than 5 levels: {}",
                        tenant, messageType, topic);
                return;
            }
            String sparkplugDeviceId = parts[4];
            // Resolve the NODE MO: [Group ID]_[Edge Node ID]
            String nodeExternalIdValue = parts[1] + "_" + parts[3];
            ID nodeIdentity = new ID(externalIdType, nodeExternalIdValue);
            ExternalIDRepresentation resolved =
                    c8yAgent.resolveExternalId2GlobalId(tenant, nodeIdentity, context.getTesting());
            if (resolved == null) {
                log.debug("{} - updateSparkPlugBActiveStatus: NODE MO not found for externalId={}, skipping",
                        tenant, nodeExternalIdValue);
                return;
            }
            String nodeMoId = resolved.getManagedObject().getId().getValue();
            String fragmentKey = SparkPlugBDeserializer.getIsActiveFragmentKey(sparkplugDeviceId);
            log.info("{} - Setting {}={} on NODE MO {} (messageType={}, sparkplugDeviceId={})",
                    tenant, fragmentKey, isActive, nodeMoId, messageType, sparkplugDeviceId);
            c8yAgent.storeManagedObjectFragment(tenant, nodeMoId, fragmentKey, isActive, context.getTesting());

        } else {
            // Node-level NBIRTH/NDATA/NDEATH: store sparkPlugB_isActive on the NODE MO.
            // context.getSourceId() is set by processInventoryRequest when the Smart Function
            // returns an INVENTORY object for the NODE — reuse it to avoid an extra lookup.
            String nodeMoId = context.getSourceId();

            if (nodeMoId == null) {
                // Derive NODE external ID from topic: [Group ID]_[Edge Node ID]
                String externalIdValue = parts[1] + "_" + parts[3];
                ID identity = new ID(externalIdType, externalIdValue);
                ExternalIDRepresentation resolved =
                        c8yAgent.resolveExternalId2GlobalId(tenant, identity, context.getTesting());
                if (resolved == null) {
                    log.debug("{} - updateSparkPlugBActiveStatus: no NODE MO found for externalId={}, skipping",
                            tenant, externalIdValue);
                    return;
                }
                nodeMoId = resolved.getManagedObject().getId().getValue();
            }

            log.info("{} - Setting {}={} on NODE MO {} (messageType={})",
                    tenant, SparkPlugBDeserializer.SPARKPLUGB_IS_ACTIVE_FRAGMENT,
                    isActive, nodeMoId, messageType);
            c8yAgent.storeManagedObjectFragment(tenant, nodeMoId,
                    SparkPlugBDeserializer.SPARKPLUGB_IS_ACTIVE_FRAGMENT,
                    isActive, context.getTesting());
        }
    }

}