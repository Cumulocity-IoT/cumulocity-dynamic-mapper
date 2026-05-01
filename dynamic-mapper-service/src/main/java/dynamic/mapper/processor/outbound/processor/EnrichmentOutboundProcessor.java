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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractEnrichmentProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.JavaExtensionContextImpl;
import dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.SmartFunctionContext;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.cache.FlowStateStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound Enrichment processor that enriches Cumulocity operation payloads
 * with identity information and metadata before transformation.
 */
@Component
@Slf4j
public class EnrichmentOutboundProcessor extends AbstractEnrichmentProcessor {

    /** Container for SparkPlug B MO state loaded in a single fetch. */
    private record SparkPlugBContext(Map<String, String> aliasMap, boolean isActive) {}

    private final C8YAgent c8yAgent;

    public EnrichmentOutboundProcessor(
            ConfigurationRegistry configurationRegistry,
            MappingService mappingService,
            C8YAgent c8yAgent,
            FlowStateStore flowStateStore) {
        super(configurationRegistry, mappingService, flowStateStore);
        this.c8yAgent = c8yAgent;
    }

    @Override
    protected void enrichPayload(ProcessingContext<?> context) throws ProcessingException {
        /*
         * Enrich payload with _IDENTITY_ property containing source device information
         */
        String tenant = context.getTenant();
        Object payloadObject = context.getPayload();
        Mapping mapping = context.getMapping();
        boolean isSmartFunction = TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())
                || TransformationType.EXTENSION_JAVA.equals(mapping.getTransformationType());

        String identifier = context.getApi().identifier;
        if (context.getTesting() && payloadObject instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = (Map<String, Object>) payloadObject;
            normalizeTestPayload(tenant, payloadMap, identifier);
        }
        String payloadAsString = toPrettyJsonString(payloadObject);
        Object sourceId = extractContent(context, payloadObject, payloadAsString, identifier);
        if (sourceId == null) {
            if (context.getTesting()) {
                // Test payload may not contain the source identifier (e.g. no source.id on a
                // MEASUREMENT). Fall back to a mock ID so the test can proceed without error.
                sourceId = "MOCK_DEVICE_ID";
                log.warn("{} - Test mode: payload has no '{}' field, falling back to mock source id '{}'",
                        tenant, identifier, sourceId);
            } else {
                throw new ProcessingException(
                        String.format("Could not extract source ID from payload using path '%s'", identifier));
            }
        }
        context.setSourceId(sourceId.toString());

        Map<String, String> identityFragment = new HashMap<>();
        identityFragment.put("c8ySourceId", sourceId.toString());
        identityFragment.put("externalIdType", mapping.getExternalIdType());

        // For SMART_FUNCTION/EXTENSION_JAVA: populate read-only config — never expand the payload Map
        // _IDENTITY_ and _TOPIC_LEVEL_ are template-substitution tokens not relevant here;
        // the function/extension reads device identity directly from the C8Y payload.
        DataPrepContext flowContext = context.getFlowContext();

        // Declared here so externalId can be added after resolution below
        Map<String, Object> config = null;
        SmartFunctionContext sfContext = null;
        JavaExtensionContextImpl javaExtContext = null;

        if (isSmartFunction) {
            if (flowContext instanceof SmartFunctionContext) {
                sfContext = (SmartFunctionContext) flowContext;

                config = buildBaseSmartFunctionConfig(context);
                config.put(ProcessingContext.RETAIN, false);
                // externalId is added below after resolution

                // For SparkPlug B outbound: expose the alias map (metric name → alias) so the
                // JS function can include the correct alias in the NCMD/DCMD metric payload.
                // The alias map is stored on the device/edge-node MO as sparkPlugB_NBIRTH or
                // sparkPlugB_DBIRTH during inbound BIRTH processing.
                if (MappingType.SPARKPLUGB.equals(mapping.getMappingType())) {
                    SparkPlugBContext spbCtx = loadSparkPlugBContext(tenant, sourceId.toString(), context.getTesting());
                    config.put("aliasMap", spbCtx.aliasMap());
                    config.put("isActive", spbCtx.isActive());
                    log.debug("{} - SparkPlugB context: aliasMap={} entries, isActive={} for sourceId={}",
                            tenant, spbCtx.aliasMap().size(), spbCtx.isActive(), sourceId);
                }
            } else if (flowContext instanceof JavaExtensionContextImpl) {
                javaExtContext = (JavaExtensionContextImpl) flowContext;
                // externalId is set directly on javaExtContext below after resolution
            } else if (TransformationType.EXTENSION_JAVA.equals(mapping.getTransformationType())
                    && context.getTesting()
                    && payloadObject instanceof Map) {
                // Test mode: flowContext is null for EXTENSION_JAVA (created later in ExtensibleOutboundProcessor).
                // Java extensions typically read source.id directly from the payload. Inject a mock source
                // so the extension can build a meaningful topic instead of "measurements/null".
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) payloadObject;
                if (!payloadMap.containsKey("source")) {
                    Map<String, Object> mockSource = new HashMap<>();
                    mockSource.put("id", sourceId.toString());
                    payloadMap.put("source", mockSource);
                    log.debug("{} - Test mode: injected mock source.id '{}' into payload for Java extension",
                            tenant, sourceId);
                }
            }
        } else {
            if (payloadObject instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = (Map<String, Object>) payloadObject;
                payloadMap.put(Mapping.TOKEN_IDENTITY, identityFragment);
                payloadMap.put(ProcessingContext.RETAIN, false);
                List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic(), false);
                payloadMap.put(Mapping.TOKEN_TOPIC_LEVEL, splitTopicExAsList);
            } else {
                log.warn("{} - Parsing this message as JSONArray, no elements from the topic level can be used!",
                        tenant);
            }
        }

        if (mapping.getUseExternalId() && !mapping.getExternalIdType().isEmpty()) {
            ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(context.getTenant(),
                    new GId(sourceId.toString()), mapping.getExternalIdType(),
                    context.getTesting());
            if (externalId == null) {
                if (!context.getTesting()) {
                    // Production: a missing external ID is a hard error — the broker topic cannot be resolved.
                    throw new RuntimeException(String.format("External id %s for type %s not found!",
                            sourceId.toString(), mapping.getExternalIdType()));
                }
                // Test mode: source is synthetic, so use sourceId as fallback to keep topic templates resolvable.
                String fallbackExternalId = sourceId.toString();
                log.warn("{} - External id for device '{}' with type '{}' not found — using fallback '{}' in JS context",
                        tenant, sourceId, mapping.getExternalIdType(), fallbackExternalId);
                identityFragment.put("externalId", fallbackExternalId);
                if (config != null) {
                    config.put("externalId", fallbackExternalId);
                }
                if (javaExtContext != null) {
                    javaExtContext.setExternalId(fallbackExternalId);
                }
            } else {
                identityFragment.put("externalId", externalId.getExternalId());
                if (config != null) {
                    config.put("externalId", externalId.getExternalId());
                }
                if (javaExtContext != null) {
                    javaExtContext.setExternalId(externalId.getExternalId());
                }
            }
        }

        // Set config after all values (including externalId) are populated
        if (sfContext != null && config != null) {
            sfContext.setConfig(config);
        }
    }

    @Override
    protected void handleEnrichmentError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context, MappingStatus mappingStatus) {
        String errorMessage = String.format("%s - Error in enrichment phase for mapping: %s: %s", tenant,
                mapping.getName(), e.getMessage());
        log.error(errorMessage, e);
        context.addError(new ProcessingException(errorMessage, e));
        context.setIgnoreFurtherProcessing(true);
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    /**
     * Loads SparkPlug B state from the managed object in a <em>single</em> REST call.
     * Returns both the inverted alias map ({@code name → alias}) and the
     * {@code sparkPlugB_isActive} flag so the caller avoids two separate fetches.
     *
     * <p>The NBIRTH fragment is checked first (edge-node NCMD); if absent, DBIRTH
     * is used (device DCMD).
     *
     * @param tenant   the tenant identifier
     * @param sourceId internal C8Y managed object ID
     * @param testing  testing flag passed through to C8YAgent
     * @return a {@link SparkPlugBContext} with the alias map and isActive flag
     */
    private SparkPlugBContext loadSparkPlugBContext(String tenant, String sourceId, Boolean testing) {
        Map<String, String> nameToAlias = new LinkedHashMap<>();
        boolean isActive = true; // default: assume active until a DEATH message is received
        try {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, sourceId, testing);
            if (mor == null) {
                return new SparkPlugBContext(nameToAlias, isActive);
            }

            // ── isActive flag ──────────────────────────────────────────────────
            Object activeVal = mor.get(SparkPlugBDeserializer.SPARKPLUGB_IS_ACTIVE_FRAGMENT);
            if (activeVal instanceof Boolean) {
                isActive = (Boolean) activeVal;
            }

            // ── alias map ─────────────────────────────────────────────────────
            // Try NBIRTH first (edge-node NCMD).
            // For DCMD, fall back to scanning all sparkPlugB_DBIRTH_<deviceId> fragments on the
            // NODE MO (DBIRTH alias maps are stored per-device with the prefix, never under the
            // old flat "sparkPlugB_DBIRTH" key).  Merge all device maps — aliases are unique
            // across devices on the same node so a merged map is safe for alias→name lookups.
            Object fragment = mor.get(SparkPlugBDeserializer.SPARKPLUGB_NBIRTH_FRAGMENT);
            if (!(fragment instanceof Map)) {
                // No NBIRTH — scan attrs for any sparkPlugB_DBIRTH_<deviceId> entry
                Map<String, Object> attrs = mor.getAttrs();
                if (attrs != null) {
                    Map<Object, Object> merged = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                        if (entry.getKey().startsWith(SparkPlugBDeserializer.SPARKPLUGB_DBIRTH_FRAGMENT_PREFIX)
                                && entry.getValue() instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> dbirth = (Map<Object, Object>) entry.getValue();
                            merged.putAll(dbirth);
                        }
                    }
                    if (!merged.isEmpty()) {
                        fragment = merged;
                    }
                }
            }
            if (fragment instanceof Map) {
                @SuppressWarnings("rawtypes")
                Map rawMap = (Map) fragment;
                for (Object key : rawMap.keySet()) {
                    Object val = rawMap.get(key);
                    if (!(val instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> def = (Map<String, Object>) val;
                    Object name = def.get("name");
                    if (name != null && !name.toString().isEmpty()) {
                        nameToAlias.put(name.toString(), key.toString());
                    }
                }
            } else {
                log.debug("{} - No SparkPlugB birth fragment found on MO {} for alias resolution", tenant, sourceId);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to load SparkPlugB context for sourceId={}: {}", tenant, sourceId, e.getMessage());
        }
        return new SparkPlugBContext(nameToAlias, isActive);
    }

}