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
import java.util.Collection;
import java.util.List;

import org.apache.camel.Exchange;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Substitution;
import dynamic.mapper.processor.model.PayloadContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingState;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstitutionEvaluation;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for JSONata extraction processors that provides common functionality
 * for extracting and processing substitutions from payloads.
 *
 * Uses the Template Method pattern to define the overall extraction flow while
 * allowing subclasses to customize specific steps.
 */
@Slf4j
public abstract class AbstractJSONataExtractionProcessor extends CommonProcessor {

    protected final MappingService mappingService;

    protected AbstractJSONataExtractionProcessor(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    /**
     * Template method that defines the overall processing flow.
     * Subclasses should not override this method.
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
        }
    }

    /**
     * Extract and process substitutions from the source payload.
     * Common logic for both inbound and outbound processing.
     *
     * Made public to allow for unit testing of extraction logic independently.
     *
     * @param context The processing context containing payload and mapping information
     * @throws ProcessingException if extraction or processing fails
     */
    public void extractFromSource(ProcessingContext<?> context) throws ProcessingException {
        // Extract focused contexts at entry for cleaner internal API
        RoutingContext routing = context.getRoutingContext();
        PayloadContext<?> payload = context.getPayloadContext();
        ProcessingState state = context.getProcessingState();

        extractFromSource(routing, payload, state, context);

        // Sync state modifications back to context for downstream processors
        context.syncFromState(state);
    }

    /**
     * NEW: Extract using focused contexts - cleaner internal implementation.
     */
    private void extractFromSource(
            RoutingContext routing,
            PayloadContext<?> payload,
            ProcessingState state,
            ProcessingContext<?> context) throws ProcessingException {
        try {
            Mapping mapping = context.getMapping();
            String tenant = routing.getTenant();
            ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

            Object payloadObject = payload.getDeserializedPayload();
            String payloadAsString = toPrettyJsonString(payloadObject);

            // Log payload if configured
            if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
                log.info("{} - Incoming payload (patched): {} {} {} {}", tenant,
                        payloadAsString,
                        serviceConfiguration.getLogPayload(), mapping.getDebug(),
                        serviceConfiguration.getLogPayload() || mapping.getDebug());
            }

            // Process all substitutions using focused contexts
            for (Substitution substitution : mapping.getSubstitutions()) {
                processSubstitution(routing, state, substitution, payloadObject, payloadAsString, mapping, serviceConfiguration, context);
            }

            // Hook for subclass-specific post-processing
            postProcessSubstitutions(state, context);

        } catch (Exception e) {
            throw new ProcessingException(e.getMessage() != null ? e.getMessage() : e.getClass().getName(), e);
        }
    }

    /**
     * Process a single substitution: extract content and add to cache.
     * NEW: Using focused contexts internally.
     */
    private void processSubstitution(
            RoutingContext routing,
            ProcessingState state,
            Substitution substitution,
            Object payloadObject,
            String payloadAsString,
            Mapping mapping,
            ServiceConfiguration serviceConfiguration,
            ProcessingContext<?> context) {

        String tenant = routing.getTenant();

        // Step 1: Extract content from payload
        Object extractedSourceContent = extractContentFromPayload(context, substitution, payloadObject, payloadAsString);

        // Step 2: Analyze and process extracted content
        // Get existing substitutions and create a mutable copy
        List<SubstituteValue> existingValues = state.getSubstitutions(substitution.getPathTarget());
        List<SubstituteValue> processingCacheEntry = existingValues != null && !existingValues.isEmpty()
            ? new ArrayList<>(existingValues)
            : new ArrayList<>();

        if (extractedSourceContent != null && SubstitutionEvaluation.isArray(extractedSourceContent)
                && substitution.getExpandArray()) {
            // Extracted result is an array, iterate over elements
            var extractedSourceContentCollection = (Collection<?>) extractedSourceContent;
            for (Object element : extractedSourceContentCollection) {
                SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, element,
                        substitution, mapping);
            }
        } else {
            // Single value or array not to be expanded
            SubstitutionEvaluation.processSubstitute(tenant, processingCacheEntry, extractedSourceContent,
                    substitution, mapping);
        }

        state.putSubstitutions(substitution.getPathTarget(), processingCacheEntry);

        // Log substitution if configured
        if (serviceConfiguration.getLogSubstitution() || mapping.getDebug()) {
            String contentAsString = extractedSourceContent != null ? extractedSourceContent.toString() : "null";
            log.debug("{} - Evaluated substitution (pathSource:substitute)/({}: {}), (pathTarget)/({})",
                    tenant, substitution.getPathSource(), contentAsString, substitution.getPathTarget());
        }
    }

    /**
     * Extract content from payload for a specific substitution.
     * Subclasses must implement this to provide their extraction strategy.
     *
     * @param context The processing context
     * @param substitution The substitution containing the path to extract
     * @param payloadObject The payload object
     * @param payloadAsString The payload as a string (for error logging)
     * @return The extracted content, or null if extraction fails
     */
    protected abstract Object extractContentFromPayload(ProcessingContext<?> context,
                                                       Substitution substitution,
                                                       Object payloadObject,
                                                       String payloadAsString);

    /**
     * NEW: Hook for subclass-specific post-processing using focused contexts.
     * Default implementation does nothing.
     *
     * @param state Thread-safe mutable state with processing cache
     * @param context Legacy context for any remaining needs
     * @throws ProcessingException if post-processing fails
     */
    protected void postProcessSubstitutions(ProcessingState state, ProcessingContext<?> context)
            throws ProcessingException {
        // Default: no post-processing
    }

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
