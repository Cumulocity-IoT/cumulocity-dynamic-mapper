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

package dynamic.mapper.processor;

import dynamic.mapper.processor.util.CamelHeaders;

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import dynamic.mapper.processor.model.TransformationType;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.io.IOAccess;

import dynamic.mapper.configuration.CodeTemplate;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.processor.model.SmartFunctionContext;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.cache.FlowStateStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for Enrichment processors that provides common
 * functionality for setting up GraalVM contexts and enriching payloads with
 * metadata. Handles SMART_FUNCTION and EXTENSION_JAVA transformation types.
 */
@Slf4j
public abstract class AbstractEnrichmentProcessor extends CommonProcessor {

    protected final ConfigurationRegistry configurationRegistry;
    protected final MappingService mappingService;
    protected final FlowStateStore flowStateStore;

    protected AbstractEnrichmentProcessor(
            ConfigurationRegistry configurationRegistry,
            MappingService mappingService,
            FlowStateStore flowStateStore) {
        this.configurationRegistry = configurationRegistry;
        this.mappingService = mappingService;
        this.flowStateStore = flowStateStore;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader(CamelHeaders.PROCESSING_CONTEXT,
                ProcessingContext.class);

        if (context == null) {
            log.warn("processingContext header is null - deserialization likely failed upstream, skipping enrichment");
            return;
        }

        // Extract focused contexts
        RoutingContext routing = context.getRoutingContext();

        String tenant = routing.getTenant();
        Mapping mapping = context.getMapping();

        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader(CamelHeaders.CONNECTOR_IDENTIFIER, String.class);

        // Hook for subclass-specific setup (e.g., QoS determination)
        performPreEnrichmentSetup(context, connectorIdentifier);

        // Prepare GraalVM context if code exists (SMART_FUNCTION only)
        boolean supportESM = Boolean.TRUE.equals(serviceConfiguration.getSupportESM());
        if (mapping.getCode() != null
                && TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine, supportESM);

                // Set cached Source objects for performance
                context.setSharedSource(configurationRegistry.getGraalsSourceShared(tenant));
                context.setSystemSource(configurationRegistry.getGraalsSourceSystem(tenant));

                // Keep Base64 strings for backward compatibility if needed
                CodeTemplate sharedTemplate = serviceConfiguration.getCodeTemplates().get(TemplateType.SHARED.name());
                CodeTemplate systemTemplate = serviceConfiguration.getCodeTemplates().get(TemplateType.SYSTEM.name());
                if (sharedTemplate == null || systemTemplate == null) {
                    log.error(
                            "{} - SHARED or SYSTEM code template missing for mapping [{}] — re-initialize code templates",
                            tenant, mapping.getIdentifier());
                    handleGraalVMError(tenant, mapping,
                            new IllegalStateException("SHARED or SYSTEM code template not found"), context);
                    return;
                }
                context.setSharedCode(sharedTemplate.getCode());
                context.setSystemCode(systemTemplate.getCode());

                context.setGraalContext(graalContext);
                context.setFlowState(new HashMap<String, Object>());
                Map<String, Object> initialState = flowStateStore.loadState(tenant, mapping.getIdentifier());
                context.setFlowContext(new SmartFunctionContext(graalContext, tenant,
                        (InventoryEnrichmentClient) configurationRegistry.getC8yAgent(),
                        context.getTesting(), flowStateStore, mapping.getIdentifier(), initialState));
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, context);
                return;
            }
        }

        mappingStatus.messagesReceived++;
        logMessageReceived(tenant, mapping, connectorIdentifier, context, serviceConfiguration);

        // Now call the enrichment logic
        try {
            enrichPayload(context);
        } catch (Exception e) {
            handleEnrichmentError(tenant, mapping, e, context, mappingStatus);
        }
    }

    /**
     * Create GraalVM context with appropriate security settings.
     *
     * @param graalEngine the shared GraalVM engine for the tenant
     * @param supportESM  when {@code true}, enables ESM module evaluation
     *                    ({@code js.esm-eval-returns-exports}) and full IO access
     *                    so that mapping code may use {@code export function}
     *                    syntax
     */
    protected Context createGraalContext(Engine graalEngine, boolean supportESM) throws Exception {
        Context.Builder builder = Context.newBuilder("js")
                .engine(graalEngine)
                .option("js.text-encoding", "true")
                .allowHostAccess(configurationRegistry.getHostAccess())
                .allowHostClassLookup(className ->
                // Allow only the specific SubstitutionContext class
                className.equals("dynamic.mapper.processor.model.SubstitutionContext")
                        || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                        || className.equals("dynamic.mapper.processor.model.RepairStrategy")
                        || className.equals("java.nio.charset.StandardCharsets")
                        || className.equals("java.lang.String")
                        || className.equals("java.util.Base64")
                        // Allow base collection classes needed for return values
                        || className.equals("java.util.ArrayList")
                        || className.equals("java.util.Arrays")
                        || className.equals("java.util.HashMap")
                        || className.equals("java.util.HashSet"));

        if (supportESM) {
            builder.allowIO(IOAccess.ALL)
                    .allowExperimentalOptions(true)
                    .option("js.esm-eval-returns-exports", "true");
        }

        return builder.build();
    }

    /**
     * Log message received with appropriate detail level based on configuration.
     */
    protected void logMessageReceived(String tenant, Mapping mapping, String connectorIdentifier,
            ProcessingContext<?> context,
            ServiceConfiguration serviceConfiguration) {
        if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
            Object pp = context.getPayload();
            String ppLog = null;

            if (pp instanceof byte[]) {
                ppLog = new String((byte[]) pp, StandardCharsets.UTF_8);
            } else if (pp != null) {
                ppLog = pp.toString();
            }
            log.info(
                    "{} - PROCESSING message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}, wrapped message: {}",
                    tenant, context.getTopic(), connectorIdentifier, mapping.getName(),
                    mapping.getQos().ordinal(), ppLog);
        } else {
            log.debug(
                    "{} - PROCESSING message on topic: [{}], on  connector: {}, for Mapping {} with QoS: {}",
                    tenant, context.getTopic(), connectorIdentifier, mapping.getName(),
                    mapping.getQos().ordinal());
        }
    }

    /**
     * Handle GraalVM setup errors.
     */
    protected void handleGraalVMError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context) {
        MappingStatus mappingStatus = mappingService
                .getMappingStatus(tenant, mapping);
        String errorMessage = String.format("Tenant %s - Failed to set up GraalVM context: %s",
                tenant, e.getMessage());
        log.error(errorMessage, e);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    /**
     * Normalizes an outbound test payload to look like a real C8Y notification.
     * The UI injects _IDENTITY_.c8ySourceId but the enrichment processor expects
     * the API-specific identifier field (e.g. source.id for
     * EVENT/ALARM/MEASUREMENT,
     * id for INVENTORY, deviceId for OPERATION). Mutates the map in-place.
     */
    @SuppressWarnings("unchecked")
    protected void normalizeTestPayload(String tenant, Map<String, Object> payloadMap, String identifier) {
        Object identityToken = payloadMap.get(Mapping.TOKEN_IDENTITY);
        if (!(identityToken instanceof Map)) {
            return;
        }
        Object c8ySourceId = ((Map<String, Object>) identityToken).get("c8ySourceId");
        if (c8ySourceId == null) {
            return;
        }
        if (identifier.contains(".")) {
            String[] parts = identifier.split("\\.", 2);
            Map<String, Object> nested = new HashMap<>();
            nested.put(parts[1], c8ySourceId.toString());
            payloadMap.put(parts[0], nested);
        } else {
            payloadMap.put(identifier, c8ySourceId.toString());
        }
        log.debug("{} - Normalized test payload: injected '{}' = '{}'", tenant, identifier, c8ySourceId);
    }

    /**
     * Extract content from payload using JSONata expression.
     * Used by outbound enrichment to extract device identifiers.
     */
    protected Object extractContent(ProcessingContext<?> context, Object payloadJsonNode,
            String payloadAsString, String pathExpression) {
        Object extractedSourceContent = null;
        try {
            var expr = jsonata(pathExpression);
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        } catch (Exception e) {
            log.error("{} - EvaluateRuntimeException for: {}, {}: ", context.getTenant(),
                    pathExpression, payloadAsString, e);
        }
        return extractedSourceContent;
    }

    /**
     * Builds the base config map shared by inbound and outbound Smart Function
     * contexts: {@code tenant}, {@code topic}, {@code clientId},
     * {@code mappingName}, {@code mappingId}, {@code targetAPI}, {@code debug}.
     * Subclasses add their own keys on top.
     */
    protected Map<String, Object> buildBaseSmartFunctionConfig(ProcessingContext<?> context) {
        Mapping mapping = context.getMapping();
        Map<String, Object> config = new HashMap<>();
        config.put("tenant", context.getTenant());
        config.put("topic", context.getTopic());
        config.put("clientId", context.getClientId());
        config.put("mappingName", mapping.getName());
        config.put("mappingId", mapping.getId());
        config.put("targetAPI", mapping.getTargetAPI().toString());
        config.put(ProcessingContext.DEBUG, mapping.getDebug());
        return config;
    }

    /**
     * Hook for subclass-specific pre-enrichment setup.
     * Default implementation does nothing.
     *
     * @param context             The processing context
     * @param connectorIdentifier The connector identifier
     */
    protected void performPreEnrichmentSetup(ProcessingContext<?> context, String connectorIdentifier) {
        // Default: no-op, subclasses can override
    }

    /**
     * Enrich the payload with metadata and context information.
     * Subclasses must implement their specific enrichment logic.
     *
     * @param context The processing context containing payload and mapping
     *                information
     */
    protected abstract void enrichPayload(ProcessingContext<?> context) throws ProcessingException;

    /**
     * Handle errors during enrichment phase.
     * Subclasses must implement their specific error handling strategy.
     *
     * @param tenant        The tenant identifier
     * @param mapping       The mapping being processed
     * @param e             The exception that occurred
     * @param context       The processing context
     * @param mappingStatus The mapping status for error tracking
     */
    protected abstract void handleEnrichmentError(String tenant, Mapping mapping, Exception e,
            ProcessingContext<?> context, MappingStatus mappingStatus);
}
