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

import static com.dashjoin.jsonata.Jsonata.jsonata;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.TemplateType;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SimpleFlowContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for Enrichment processors that provides common
 * functionality
 * for setting up GraalVM contexts and enriching payloads with metadata.
 *
 * Handles both SUBSTITUTION_AS_CODE and SMART_FUNCTION transformation types.
 */
@Slf4j
public abstract class AbstractEnrichmentProcessor extends CommonProcessor {

    protected final ConfigurationRegistry configurationRegistry;
    protected final MappingService mappingService;

    private Context.Builder graalContextBuilder;

    protected AbstractEnrichmentProcessor(
            ConfigurationRegistry configurationRegistry,
            MappingService mappingService) {
        this.configurationRegistry = configurationRegistry;
        this.mappingService = mappingService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext",
                ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);

        // Extract additional info from headers if available
        String connectorIdentifier = exchange.getIn().getHeader("connectorIdentifier", String.class);

        // Hook for subclass-specific setup (e.g., QoS determination)
        performPreEnrichmentSetup(context, connectorIdentifier);

        // Prepare GraalVM context if code exists
        if (mapping.getCode() != null
                && mapping.isSubstitutionAsCode()) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine);
                context.setGraalContext(graalContext);

                // Set cached Source objects for performance
                context.setSharedSource(configurationRegistry.getGraalsSourceShared(tenant));
                context.setSystemSource(configurationRegistry.getGraalsSourceSystem(tenant));

                // Keep Base64 strings for backward compatibility if needed
                context.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                context.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());
            } catch (Exception e) {
                handleGraalVMError(tenant, mapping, e, context);
                return;
            }
        } else if (mapping.getCode() != null &&
                TransformationType.SMART_FUNCTION.equals(mapping.getTransformationType())) {
            try {
                var graalEngine = configurationRegistry.getGraalEngine(tenant);
                var graalContext = createGraalContext(graalEngine);

                // Set cached Source objects for performance
                context.setSharedSource(configurationRegistry.getGraalsSourceShared(tenant));
                context.setSystemSource(configurationRegistry.getGraalsSourceSystem(tenant));

                // Keep Base64 strings for backward compatibility if needed
                context.setSharedCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SHARED.name()).getCode());
                context.setSystemCode(serviceConfiguration.getCodeTemplates()
                        .get(TemplateType.SYSTEM.name()).getCode());

                context.setGraalContext(graalContext);
                context.setFlowState(new HashMap<String, Object>());
                context.setFlowContext(new SimpleFlowContext(graalContext, tenant,
                        (InventoryEnrichmentClient) configurationRegistry.getC8yAgent(),
                        context.getTesting()));
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
     */
    protected Context createGraalContext(Engine graalEngine) throws Exception {
        if (graalContextBuilder == null) {
            graalContextBuilder = Context.newBuilder("js");
        }

        Context graalContext = graalContextBuilder
                .engine(graalEngine)
                .allowHostAccess(configurationRegistry.getHostAccess())
                .allowHostClassLookup(className ->
                // Allow only the specific SubstitutionContext class
                className.equals("dynamic.mapper.processor.model.SubstitutionContext")
                        || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                        || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                        || className.equals("dynamic.mapper.processor.model.RepairStrategy")
                        // Allow base collection classes needed for return values
                        || className.equals("java.util.ArrayList") ||
                        className.equals("java.util.HashMap") ||
                        className.equals("java.util.HashSet"))
                .build();
        return graalContext;
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
            log.info(
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
     * Helper method to safely add values to DataPrepContext.
     */
    protected void addToFlowContext(DataPrepContext flowContext, ProcessingContext<?> context, String key,
            Object value) {
        try {
            if (context.getGraalContext() != null && value != null) {
                Value graalValue = context.getGraalContext().asValue(value);
                flowContext.setState(key, graalValue);
            }
        } catch (Exception e) {
            log.warn("{} - Failed to add '{}' to DataPrepContext: {}", context.getTenant(), key, e.getMessage());
        }
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
    protected abstract void enrichPayload(ProcessingContext<?> context);

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
