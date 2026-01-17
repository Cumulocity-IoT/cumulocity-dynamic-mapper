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

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.dashjoin.jsonata.Functions;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstitutionContext;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for Code extraction processors that provides common functionality
 * for extracting and processing substitutions using GraalVM JavaScript code execution.
 *
 * Uses the Template Method pattern to define the overall extraction flow while
 * allowing subclasses to customize specific steps.
 */
@Slf4j
public abstract class AbstractCodeExtractionProcessor extends CommonProcessor {

    protected final MappingService mappingService;

    protected AbstractCodeExtractionProcessor(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    /**
     * Template method that defines the overall processing flow.
     * Handles GraalVM context lifecycle management.
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            extractFromSource(context);
        } catch (Exception e) {
            handleProcessingError(e, context, tenant, mapping);
        } finally {
            // Close the GraalVM Context completely
            cleanupGraalContext(context, tenant);
        }
    }

    /**
     * Extract and process substitutions from the source payload using JavaScript code.
     * Common logic for both inbound and outbound processing.
     *
     * Made public to allow for unit testing of extraction logic independently.
     *
     * @param context The processing context containing payload and mapping information
     * @throws ProcessingException if extraction or processing fails
     */
    public void extractFromSource(ProcessingContext<?> context) throws ProcessingException {
        Value result = null;
        Value sourceValue = null;
        Value bindings = null;

        try {
            Mapping mapping = context.getMapping();
            String tenant = context.getTenant();
            ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

            Object payloadObject = context.getPayload();

            // Log payload if configured
            if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
                String payload = toPrettyJsonString(payloadObject);
                log.info("{} - Incoming payload (patched) in extractFromSource(): {} {} {} {}", tenant,
                        payload,
                        serviceConfiguration.getLogPayload(), mapping.getDebug(),
                        serviceConfiguration.getLogPayload() || mapping.getDebug());
            }

            if (mapping.getCode() != null) {
                Context graalContext = context.getGraalContext();

                String identifier = Mapping.EXTRACT_FROM_SOURCE + "_" + mapping.getIdentifier();
                bindings = graalContext.getBindings("js");

                // Load main code
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst(
                        Mapping.EXTRACT_FROM_SOURCE,
                        identifier);
                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();
                graalContext.eval(source);

                sourceValue = bindings.getMember(identifier);

                // Load shared code if available
                if (context.getSharedCode() != null) {
                    byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
                    String decodedSharedCode = new String(decodedSharedCodeBytes);
                    Source sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                            .buildLiteral();
                    graalContext.eval(sharedSource);
                }

                // Load system code if available
                if (context.getSystemCode() != null) {
                    byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
                    String decodedSystemCode = new String(decodedSystemCodeBytes);
                    Source systemSource = Source.newBuilder("js", decodedSystemCode, "systemCode.js")
                            .buildLiteral();
                    graalContext.eval(systemSource);
                }

                // Prepare payload - subclass-specific
                preparePayload(context, payloadObject);

                String payloadAsString = Functions.string(payloadObject, false);

                // Execute JavaScript function
                result = sourceValue.execute(
                        new SubstitutionContext(
                                context.getMapping().getGenericDeviceIdentifier(),
                                payloadAsString,
                                context.getTopic()));

                // CRITICAL: Convert with Context still open
                SubstitutionResult javaResult = deepConvertSubstitutionResultWithContext(
                        result,
                        graalContext,
                        tenant);

                // Process the fully converted Java objects - subclass-specific
                processSubstitutionResult(javaResult, context, payloadObject, mapping, tenant);
            }

        } catch (Exception e) {
            throw new ProcessingException("Extraction failed: " + e.getMessage(), e);
        } finally {
            // Explicitly null out GraalVM Value references
            result = null;
            sourceValue = null;
            bindings = null;
        }
    }

    /**
     * CRITICAL: Deep convert SubstitutionResult WITH Context still open.
     * Common to both inbound and outbound processors.
     */
    protected SubstitutionResult deepConvertSubstitutionResultWithContext(
            Value result,
            Context graalContext,
            String tenant) {

        if (result == null || result.isNull()) {
            return new SubstitutionResult();
        }

        try {
            if (result.isHostObject()) {
                Object hostObj = result.asHostObject();

                if (hostObj instanceof SubstitutionResult) {
                    SubstitutionResult originalResult = (SubstitutionResult) hostObj;

                    log.debug("{} - Converting SubstitutionResult with {} substitutions (context is open)",
                            tenant, originalResult.substitutions.size());

                    SubstitutionResult cleanResult = new SubstitutionResult();

                    for (Map.Entry<String, List<SubstituteValue>> entry : originalResult.substitutions.entrySet()) {
                        String key = entry.getKey();
                        List<SubstituteValue> originalValues = entry.getValue();
                        List<SubstituteValue> cleanValues = new ArrayList<>();

                        for (SubstituteValue subValue : originalValues) {
                            // Convert with context available
                            SubstituteValue cleanSubValue = convertSubstituteValueWithContext(
                                    subValue,
                                    graalContext,
                                    tenant);
                            cleanValues.add(cleanSubValue);
                        }

                        cleanResult.substitutions.put(key, cleanValues);
                    }

                    if (originalResult.alarms != null) {
                        cleanResult.alarms.addAll(originalResult.alarms);
                    }

                    log.debug("{} - Successfully converted SubstitutionResult with {} substitutions",
                            tenant, cleanResult.substitutions.size());

                    return cleanResult;
                }
            }

            log.warn("{} - Result is not a SubstitutionResult host object", tenant);
            return new SubstitutionResult();

        } catch (Exception e) {
            log.error("{} - Error converting SubstitutionResult: {}", tenant, e.getMessage(), e);
            throw new RuntimeException("Failed to convert substitution result", e);
        }
    }

    /**
     * Convert SubstituteValue WITH Context available.
     * Common to both inbound and outbound processors.
     */
    protected SubstituteValue convertSubstituteValueWithContext(
            SubstituteValue original,
            Context graalContext,
            String tenant) {

        Object cleanValue = original.value;

        if (cleanValue != null) {
            String className = cleanValue.getClass().getName();

            if (className.contains("org.graalvm.polyglot") ||
                    className.contains("com.oracle.truffle.polyglot")) {

                log.debug("{} - Converting GraalVM object: {}", tenant, className);

                try {
                    // Wrap it as a Value and convert
                    Value valueWrapper = graalContext.asValue(cleanValue);
                    cleanValue = JavaScriptInteropHelper.convertValueToJavaObject(valueWrapper);

                    log.debug("{} - Converted to: {} (type: {})",
                            tenant,
                            cleanValue,
                            cleanValue != null ? cleanValue.getClass().getName() : "null");

                } catch (Exception e) {
                    log.error("{} - Failed to convert GraalVM object: {}", tenant, e.getMessage());

                    // Try direct cast to Value
                    if (cleanValue instanceof Value) {
                        Value val = (Value) cleanValue;
                        cleanValue = JavaScriptInteropHelper.convertValueToJavaObject(val);
                        log.debug("{} - Converted via direct cast", tenant);
                    }
                }
            }
        }

        // Final validation
        if (cleanValue != null) {
            String finalClassName = cleanValue.getClass().getName();
            if (finalClassName.contains("org.graalvm") || finalClassName.contains("com.oracle.truffle")) {
                log.error("{} - CONVERSION FAILED! Still has GraalVM object: {}", tenant, finalClassName);
                // Force to string as absolute last resort
                cleanValue = cleanValue.toString();
            }
        }

        return new SubstituteValue(
                cleanValue,
                original.type,
                original.repairStrategy,
                original.expandArray);
    }

    /**
     * Clean up GraalVM context resources.
     */
    private void cleanupGraalContext(ProcessingContext<?> context, String tenant) {
        if (context != null && context.getGraalContext() != null) {
            try {
                Context graalContext = context.getGraalContext();
                graalContext.close();
                context.setGraalContext(null);
                log.debug("{} - GraalVM Context closed successfully", tenant);
            } catch (Exception e) {
                log.warn("{} - Error closing GraalVM context: {}", tenant, e.getMessage());
            }
        }
    }

    /**
     * Prepare the payload before JavaScript execution.
     * Subclasses can add metadata, topic levels, etc.
     *
     * @param context The processing context
     * @param payloadObject The payload object to prepare
     */
    protected void preparePayload(ProcessingContext<?> context, Object payloadObject) {
        // Default: no preparation
    }

    /**
     * Process the SubstitutionResult after conversion.
     * Subclasses must implement their specific processing logic.
     *
     * @param result The converted substitution result
     * @param context The processing context
     * @param payloadObject The original payload object
     * @param mapping The mapping configuration
     * @param tenant The tenant identifier
     * @throws ProcessingException if processing fails
     */
    protected abstract void processSubstitutionResult(
            SubstitutionResult result,
            ProcessingContext<?> context,
            Object payloadObject,
            Mapping mapping,
            String tenant) throws ProcessingException;

    /**
     * Handle processing errors in a subclass-specific way.
     * Subclasses must implement this to provide their error handling strategy.
     *
     * @param e The exception that occurred
     * @param context The processing context
     * @param tenant The tenant identifier
     * @param mapping The mapping being processed
     */
    protected abstract void handleProcessingError(Exception e, ProcessingContext<?> context,
                                                 String tenant, Mapping mapping);
}
