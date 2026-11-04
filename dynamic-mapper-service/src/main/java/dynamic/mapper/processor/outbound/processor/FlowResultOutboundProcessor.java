/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */
package dynamic.mapper.processor.outbound.processor;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.mutable.MutableInt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.API;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.status.MappingStatus;
import dynamic.mapper.processor.AbstractFlowResultProcessor;
import dynamic.mapper.model.MappingType;
import dynamic.mapper.processor.outbound.serializer.SparkPlugBSerializer;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.CumulocityType;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.model.DynamicMapperRequest;
import dynamic.mapper.processor.model.ExternalIdInfo;
import dynamic.mapper.processor.runtime.ProcessingContext;
import dynamic.mapper.processor.runtime.RoutingContext;
import dynamic.mapper.processor.util.APITopicUtil;
import dynamic.mapper.processor.util.OutboundRequestFinalizer;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.mapping.MappingService;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Processes the result of a Smart Function (JavaScript) outbound mapping.
 * Converts each {@link DeviceMessage} or {@link CumulocityObject} returned by
 * the JS function into a {@link DynamicMapperRequest} that is later dispatched
 * by the connector.
 *
 * <h2>ID transformation rules</h2>
 *
 * Two distinct identifiers travel through this processor:
 *
 * <ul>
 *   <li><b>internalSourceId</b> — the Cumulocity internal managed object ID
 *       (e.g. {@code "326993962"}). Set by {@code EnrichmentOutboundProcessor}
 *       from the triggering C8Y event/alarm/measurement {@code source.id}.
 *       This value is used for the {@code source.id} / {@code id} field in every
 *       Cumulocity REST API payload and must always be a numeric string.
 *       It is never overwritten on the shared {@code ProcessingContext} because
 *       one JS call can return a batch of messages (e.g. MEASUREMENT + INVENTORY)
 *       that all share the same context instance.</li>
 *
 *   <li><b>brokerRoutingId</b> — the external device identifier (e.g. LoRa EUI
 *       {@code "0080E11505710591"}). Resolved by
 *       {@code resolveGlobalId2ExternalId()} from the internal ID via the C8Y
 *       Identity Service. Used exclusively to replace the {@code _externalId_}
 *       token in broker publish topics. Falls back to {@code internalSourceId}
 *       when no external ID type is configured or resolution fails.</li>
 * </ul>
 *
 * <h2>Connector-specific behavior</h2>
 *
 * <table border="1" cellpadding="4">
 *   <tr>
 *     <th>Connector</th>
 *     <th>source.id / id in payload</th>
 *     <th>Publish path / topic</th>
 *     <th>brokerRoutingId used?</th>
 *   </tr>
 *   <tr>
 *     <td><b>WebHook (cumulocityInternal=true)</b><br>
 *         Internal Connector — calls C8Y REST API directly</td>
 *     <td>{@code internalSourceId} injected by {@code populateSourceIdentifier()}
 *         into {@code source.id} (EVENT/ALARM/MEASUREMENT) or {@code id}
 *         (INVENTORY/OPERATION). Required by the C8Y REST API.</td>
 *     <td>{@code request.pathCumulocity} = {@code api.path} for POST,
 *         {@code api.path}/{@code internalSourceId} for PUT/PATCH/DELETE.
 *         Derived by {@code APITopicUtil.deriveAPIFromTopic()} from the JS
 *         return topic (e.g. {@code measurement/measurements/326993962}).</td>
 *     <td>Only if the JS topic contains the {@code _externalId_} token.</td>
 *   </tr>
 *   <tr>
 *     <td><b>WebHook (cumulocityInternal=false)</b><br>
 *         External HTTP endpoint</td>
 *     <td>{@code internalSourceId} is still injected, but the external system
 *         may ignore this field.</td>
 *     <td>JS {@code topic} (or mapping {@code publishTopic}) appended to the
 *         connector's {@code baseUrl}. {@code _externalId_} replaced by
 *         {@code brokerRoutingId} when present.</td>
 *     <td>Yes — replaces {@code _externalId_} in the HTTP path.</td>
 *   </tr>
 *   <tr>
 *     <td><b>MQTT / Kafka / Pulsar / RabbitMQ</b><br>
 *         External message brokers</td>
 *     <td>{@code internalSourceId} injected into the JSON payload, but broker
 *         consumers typically ignore C8Y-specific fields.</td>
 *     <td>JS {@code topic} (or mapping {@code publishTopic}) used as the
 *         broker topic/partition key. {@code _externalId_} replaced by
 *         {@code brokerRoutingId}.</td>
 *     <td>Yes — the primary purpose for external brokers.</td>
 *   </tr>
 * </table>
 *
 * <h2>CumulocityObject vs DeviceMessage</h2>
 *
 * The JS function may return either type:
 * <ul>
 *   <li>{@link DeviceMessage} — generic message with a free-form topic and
 *       payload; the API is derived from the topic prefix
 *       ({@code measurement/measurements/...} → {@code API.MEASUREMENT}).</li>
 *   <li>{@link CumulocityObject} — typed message with an explicit
 *       {@code cumulocityType}; device resolution uses {@code externalSource}
 *       rather than internal-to-external ID lookup.</li>
 * </ul>
 *
 * <h2>Cross-device routing (sourceId override)</h2>
 *
 * By default the triggering device's internal ID (read from
 * {@code context.getSourceId()}) is used as the C8Y managed object target for
 * every message in the batch. A JS function can override this for an individual
 * message by setting the optional {@code sourceId} property on the returned
 * {@link DeviceMessage} or {@link CumulocityObject}:
 *
 * <pre>{@code
 * return {
 *   cumulocityType: "measurement",
 *   sourceId: "987654321",   // child device internal ID
 *   payload: { ... },
 *   topic: "measurements/987654321"
 * };
 * }</pre>
 *
 * When {@code sourceId} is set:
 * <ul>
 *   <li>{@code internalSourceId} is updated to the provided value and used for
 *       {@code source.id} / {@code id} in the Cumulocity REST payload.</li>
 *   <li>{@code brokerRoutingId} is set to the same value; the
 *       {@code externalSource} lookup via the Identity Service is skipped.</li>
 * </ul>
 *
 * <b>Important:</b> The value must be a Cumulocity <em>internal</em> numeric
 * managed object ID, not an external identifier such as a serial number or
 * LoRa EUI. Passing an external ID here will cause a
 * {@code 422 UNPROCESSABLE_ENTITY} from the Cumulocity REST API.
 */
@Slf4j
@Component
public class FlowResultOutboundProcessor extends AbstractFlowResultProcessor {

    protected static final String EXTERNAL_ID_TOKEN = "_externalId_";

    private final SparkPlugBSerializer sparkPlugBSerializer;

    public FlowResultOutboundProcessor(
            MappingService mappingService,
            ObjectMapper objectMapper,
            SparkPlugBSerializer sparkPlugBSerializer) {
        super(mappingService, objectMapper);
        this.sparkPlugBSerializer = sparkPlugBSerializer;
    }

    @Override
    protected void processMessage(
            Object message,
            RoutingContext routing,
            ProcessingContext<?> context) throws ProcessingException {
        String tenant = routing.getTenant();
        Mapping mapping = context.getMapping();

        if (message instanceof DeviceMessage) {
            processDeviceMessage((DeviceMessage) message, routing, context, tenant, mapping);
        } else if (message instanceof CumulocityObject) {
            processCumulocityObject((CumulocityObject) message, routing, context, tenant, mapping);
        } else {
            log.debug("{} - Message is not a recognized type, skipping: {}", tenant,
                    message.getClass().getSimpleName());
        }
    }

    @Override
    protected void handleProcessingError(Exception e, ProcessingContext<?> context, String tenant, Mapping mapping) {
        String location = describeJsLocation(e);
        String errorMessage = String.format(
                "Tenant %s - Error in FlowResultOutboundProcessor for mapping %s: %s%s",
                tenant, mapping.getName(), e.getMessage(), location);
        log.error(errorMessage, e);

        context.addError(new ProcessingException(errorMessage, e));
        if (context.affectsMappingStatus()) {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            mappingStatus.incrementErrors();
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }
    }

    private void processDeviceMessage(
            DeviceMessage deviceMessage,
            RoutingContext routing,
            ProcessingContext<?> context,
            String tenant,
            Mapping mapping) throws ProcessingException {

        try {

            // Custom routing: bypass device resolution, call tenant-local microservice directly
            if (CumulocityType.CUSTOM.equals(deviceMessage.getCumulocityType())) {
                String servicePath = deviceMessage.getTopic();
                if (servicePath == null || !servicePath.startsWith("/service/")) {
                    throw new ProcessingException(
                            "Custom routing DeviceMessage.topic must start with /service/, got: " + servicePath);
                }
                DynamicMapperRequest customRequest = DynamicMapperRequest.builder()
                        .predecessor(-1)
                        .method(ProcessingResultHelper.mapActionToRequestMethod(deviceMessage.getAction()))
                        .api(API.CUSTOM)
                        .pathCumulocity(servicePath)
                        .request(objectMapper.writeValueAsString(deviceMessage.getPayload()))
                        .build();
                context.addRequest(customRequest);
                log.debug("{} - Created CUSTOM route request from DeviceMessage: path={}, method={}",
                        tenant, servicePath, customRequest.getMethod());
                return;
            }

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(deviceMessage.getPayload());

            // internalSourceId: the internal C8Y managed object ID — must NOT be overwritten.
            // The same context is reused for every DeviceMessage in the batch
            // (one JS call can return [MEASUREMENT, INVENTORY, ...]), so writing back to
            // context.sourceId would corrupt the value for every subsequent message.
            //
            // brokerRoutingId: used only for _externalId_ token replacement in broker topics.
            // Defaults to internalSourceId; overridden by resolveGlobalId2ExternalId() when
            // the mapping needs the external EUI/serial instead of the numeric internal ID.
            String internalSourceId = context.getSourceId();
            String brokerRoutingId = internalSourceId;

            // Retrieve topic early: the external-ID REST call is only needed when the topic
            // uses the _externalId_ token — skip it for all other messages to avoid unnecessary
            // C8Y Identity Service calls and spurious WARN logs.
            String publishTopic = deviceMessage.getTopic();

            if (deviceMessage.getSourceId() != null && !deviceMessage.getSourceId().isEmpty()) {
                // Cross-device routing: the JS function explicitly set sourceId on the returned
                // DeviceMessage to target a different device than the one that triggered the
                // mapping (e.g. a gateway receives an event but the resulting measurement should
                // be stored under a child device).
                //
                // The provided value MUST be a Cumulocity internal numeric managed object ID.
                // Passing an external ID (e.g. LoRa EUI, serial number) here will cause a
                // 422 UNPROCESSABLE_ENTITY from the Cumulocity REST API.
                //
                // externalSource lookup is skipped — brokerRoutingId is set to the same value.
                internalSourceId = deviceMessage.getSourceId();
                brokerRoutingId = internalSourceId;
                log.debug("{} - Using explicit sourceId from DeviceMessage: {}", tenant, internalSourceId);
            } else if (publishTopic != null && publishTopic.contains(EXTERNAL_ID_TOKEN)) {
                // Only resolve the external ID when the topic actually uses the _externalId_ token.
                // Falls back to internalSourceId if resolution fails.
                try {
                    brokerRoutingId = resolveGlobalId2ExternalId(deviceMessage, context, tenant);
                    log.debug("{} - Resolved external ID for broker routing: {}", tenant, brokerRoutingId);
                } catch (ProcessingException e) {
                    log.warn("{} - Could not resolve external ID for device '{}' with externalIdType '{}': {}",
                            tenant, internalSourceId, context.getMapping().getExternalIdType(), e.getMessage());
                }
                log.debug("{} - internalSourceId (C8Y payload): {}, brokerRoutingId (broker topic): {}",
                        tenant, internalSourceId, brokerRoutingId);
            }

            // Set resolved publish topic (from substituteInTargetAndSend logic)
            setResolvedPublishTopic(context, payload);

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Create the request without adding to context (will be added via OutputCollector, matching inbound pattern)
            DynamicMapperRequest request = ProcessingResultHelper.createDynamicMapperRequest(
                    context.getDeviceContext(), context.getRoutingContext(), payloadJson,
                    deviceMessage.getAction(), mapping);

            // For SparkPlug B outbound, serialize payload to proto binary bytes
            if (MappingType.SPARKPLUGB.equals(mapping.getMappingType())) {
                try {
                    byte[] protoBytes = sparkPlugBSerializer.serialize(payload);
                    request.setBinaryPayload(protoBytes);
                    log.debug("{} - SparkPlugB outbound: serialized {} bytes for topic {}",
                            tenant, protoBytes.length, deviceMessage.getTopic());
                } catch (Exception serEx) {
                    throw new ProcessingException(
                            "Failed to serialize SparkPlugB payload: " + serEx.getMessage(), serEx);
                }
            }
            context.addRequest(request);

            // Override resolvedPublishTopic if DeviceMessage provides a topic
            if (publishTopic != null && !publishTopic.isEmpty()) {
                // Handle EXTERNAL_ID_TOKEN replacement in the topic
                if (publishTopic.contains(EXTERNAL_ID_TOKEN)) {
                    if (brokerRoutingId != null) {
                        publishTopic = publishTopic.replace(EXTERNAL_ID_TOKEN, brokerRoutingId);
                    } else {
                        log.warn("{} - Publish topic contains {} token but external ID could not be resolved",
                                tenant, EXTERNAL_ID_TOKEN);
                        // Optionally: skip processing or use a default value
                        // return; // Uncomment to skip processing if external ID is required
                    }
                }
                // Override the resolved publish topic with the one from DeviceMessage
                context.setResolvedPublishTopic(publishTopic);
            }
            // If publishTopic is null or empty, keep the resolved topic from mapping (set at line 121)

            // Derive API: cumulocityType from the DeviceMessage first, then the resolved publish topic.
            // Without a match the request keeps the API it was created with (the mapping's target API).
            API derivedAPI = OutboundRequestFinalizer.deriveApi(deviceMessage.getCumulocityType(),
                    context.getResolvedPublishTopic());
            if (derivedAPI != null) {
                request.setApi(derivedAPI);
            }

            // Set publishTopic on request from resolved topic
            request.setPublishTopic(context.getResolvedPublishTopic());

            // internalSourceId → source.id in the C8Y payload (must be the numeric managed object ID).
            // brokerRoutingId (e.g. LoRa EUI) is only used for broker topic-token replacement above.
            OutboundRequestFinalizer.finalizeRequest(request, internalSourceId, payloadJson, tenant, objectMapper);

            // Broker message key and retain flag
            OutboundRequestFinalizer.applyTransportFields(context, deviceMessage);

            log.debug("{} - Created outbound request: deviceId={}, topic={}",
                    tenant, brokerRoutingId != null ? brokerRoutingId : "unresolved",
                    context.getResolvedPublishTopic());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process DeviceMessage: " + e.getMessage(), e);
        }
    }


    private void processCumulocityObject(
            CumulocityObject cumulocityMessage,
            RoutingContext routing,
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
                context.addRequest(customRequest);
                log.debug("{} - Created CUSTOM route request: path={}, method={}",
                        tenant, targetPath, customRequest.getMethod());
                return;
            }

            // Get the API from the cumulocityType using unified API derivation
            if (cumulocityMessage.getCumulocityType() == null) {
                String warnMsg = String.format(
                        "CumulocityObject missing cumulocityType, cannot derive API for mapping '%s', skipping message",
                        mapping.getIdentifier());
                log.warn("{} - {}", tenant, warnMsg);
                context.getWarnings().add(warnMsg);
                return;
            }
            API targetAPI = APITopicUtil.deriveAPIFromTopic(cumulocityMessage.getCumulocityType().toString());
            if (targetAPI == null) {
                String warnMsg = String.format(
                        "CumulocityObject has unrecognized cumulocityType '%s' for mapping '%s', skipping message",
                        cumulocityMessage.getCumulocityType(), mapping.getIdentifier());
                log.warn("{} - {}", tenant, warnMsg);
                context.getWarnings().add(warnMsg);
                return;
            }

            // Clone the payload to modify it
            Map<String, Object> payload = clonePayload(cumulocityMessage.getPayload());

            // Check if sourceId is explicitly set in CumulocityObject
            String resolvedDeviceId;
            ExternalIdInfo externalIdInfo = ExternalIdInfo.from(cumulocityMessage.getExternalSource());

            if (externalIdInfo.isPresent()) {
                context.setExternalId(externalIdInfo.getExternalId());
            }

            if (cumulocityMessage.getSourceId() != null && !cumulocityMessage.getSourceId().isEmpty()) {
                // Use explicitly provided sourceId — do NOT write back to context (shared across batch items)
                resolvedDeviceId = cumulocityMessage.getSourceId();
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
                log.debug("{} - Using explicit sourceId from CumulocityObject: {}", tenant, resolvedDeviceId);
            } else if ((resolvedDeviceId = resolveDeviceIdentifier(cumulocityMessage, context, tenant)) != null) {
                // Use resolved device ID from externalSource — do NOT write back to context
                ProcessingResultHelper.setHierarchicalValue(payload, targetAPI.identifier, resolvedDeviceId);
            } else if (externalIdInfo.isPresent()) {
                // The device of the message's externalSource could not be resolved - skip this message.
                // (createNonExistingDevice is an inbound setting and plays no role for outbound mappings.)
                log.warn(
                        "{} - Cannot process message: the device for externalSource '{}' could not be resolved for mapping {}",
                        tenant, externalIdInfo.getExternalId(), mapping.getIdentifier());
                return; // Don't create a request
            } else {
                log.warn("{} - Cannot process message: no external source provided for mapping {}",
                        tenant, mapping.getIdentifier());
                return; // Don't create a request
            }

            // Set resolved publish topic (from substituteInTargetAndSend logic)
            setResolvedPublishTopic(context, payload);

            // Convert payload to JSON string for the request
            String payloadJson = objectMapper.writeValueAsString(payload);

            DynamicMapperRequest c8yRequest = ProcessingResultHelper.createDynamicMapperRequest(
                    context.getDeviceContext(), context.getRoutingContext(), payloadJson,
                    cumulocityMessage.getAction(), mapping);
            context.addRequest(c8yRequest);
            c8yRequest.setApi(targetAPI);
            c8yRequest.setExternalIdType(externalIdInfo.getExternalType());
            c8yRequest.setExternalId(externalIdInfo.getExternalId());

            // Set the publishTopic on the request object
            if (context.getResolvedPublishTopic() != null) {
                c8yRequest.setPublishTopic(context.getResolvedPublishTopic());
            }

            OutboundRequestFinalizer.finalizeRequest(c8yRequest, resolvedDeviceId, payloadJson, tenant, objectMapper);

            context.setRetain(Boolean.TRUE.equals(c8yRequest.getRetain()));

            log.debug("{} - Created C8Y request: API={}, action={}, deviceId={}, topic={}",
                    tenant, targetAPI.name, cumulocityMessage.getAction(), resolvedDeviceId,
                    context.getResolvedPublishTopic());

        } catch (Exception e) {
            throw new ProcessingException("Failed to process CumulocityObject: " + e.getMessage(), e);
        }
    }

    /**
     * Set resolved publish topic based on topic levels in payload
     * Logic taken from BaseProcessorOutbound.substituteInTargetAndSend
     */
    private void setResolvedPublishTopic(ProcessingContext<?> context, Map<String, Object> payload) {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        // Check if payload contains topic levels (similar to substituteInTargetAndSend)
        @SuppressWarnings("unchecked")
        List<String> topicLevels = (List<String>) payload.get(Mapping.TOKEN_TOPIC_LEVEL);

        if (topicLevels != null && !topicLevels.isEmpty()) {
            // Merge the replaced topic levels (logic from substituteInTargetAndSend)
            MutableInt c = new MutableInt(0);
            String[] splitTopicInAsList = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());
            String[] splitTopicInAsListOriginal = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());

            topicLevels.forEach(tl -> {
                while (c.intValue() < splitTopicInAsList.length
                        && ("/".equals(splitTopicInAsList[c.intValue()]) && c.intValue() > 0)) {
                    c.increment();
                }
                splitTopicInAsList[c.intValue()] = tl;
                c.increment();
            });

            if (mapping.getDebug() || context.getServiceConfiguration().getLogPayload()) {
                log.debug("{} - Resolved topic from {} to {}",
                        tenant, splitTopicInAsListOriginal, splitTopicInAsList);
            }

            StringBuilder resolvedPublishTopic = new StringBuilder();
            for (String topicPart : splitTopicInAsList) {
                resolvedPublishTopic.append(topicPart);
            }
            context.setResolvedPublishTopic(resolvedPublishTopic.toString());

            // Remove TOPIC_LEVEL from payload (as done in substituteInTargetAndSend)
            payload.remove(Mapping.TOKEN_TOPIC_LEVEL);
        } else {
            // Use the mapping's publish topic as fallback
            context.setResolvedPublishTopic(mapping.getPublishTopic());
        }

        // Handle context data for message context support
        @SuppressWarnings("unchecked")
        Map<String, String> contextData = (Map<String, String>) payload.get(Mapping.TOKEN_CONTEXT_DATA);

        if (contextData != null) {
            // Extract key for message context
            String key = contextData.get(Mapping.CONTEXT_DATA_KEY_NAME);
            if (key != null && !Mapping.CONTEXT_DATA_KEY_PLACEHOLDER.equals(key)) {
                context.setKey(key);
            }

            // Extract publish topic override — only set resolvedPublishTopic, never context.topic
            String publishTopic = contextData.get("publishTopic");
            if (publishTopic != null && !publishTopic.equals("")) {
                context.setResolvedPublishTopic(publishTopic);
            }

            // Remove TOKEN_CONTEXT_DATA from payload
            payload.remove(Mapping.TOKEN_CONTEXT_DATA);
        }
    }

}