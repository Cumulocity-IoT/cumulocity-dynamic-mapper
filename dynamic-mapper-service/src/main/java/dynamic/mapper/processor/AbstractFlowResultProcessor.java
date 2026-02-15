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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.OutputCollector;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingState;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for flow result processors that provides common functionality
 * for processing flow results in both inbound and outbound directions.
 *
 * Uses the Template Method pattern to define the overall process flow while
 * allowing subclasses to customize specific steps.
 */
@Slf4j
public abstract class AbstractFlowResultProcessor extends CommonProcessor {

    protected final MappingService mappingService;
    protected final ObjectMapper objectMapper;

    protected AbstractFlowResultProcessor(MappingService mappingService, ObjectMapper objectMapper) {
        this.mappingService = mappingService;
        this.objectMapper = objectMapper;
    }

    /**
     * Template method that defines the overall processing flow.
     * Subclasses should not override this method.
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        // Extract focused contexts at entry point
        RoutingContext routing = context.getRoutingContext();
        ProcessingState state = context.getProcessingState();
        OutputCollector output = new OutputCollector();

        String tenant = routing.getTenant();
        Mapping mapping = context.getMapping();

        try {
            processFlowResults(routing, state, output, context);
            postProcessFlowResults(state, output, context);

            // Sync back to context for backward compatibility
            syncOutputToContext(output, context);
        } catch (Exception e) {
            handleProcessingError(e, context, tenant, mapping);
        }
    }

    /**
     * Sync OutputCollector contents back to ProcessingContext for backward compatibility.
     * Can be removed once all callers migrate to reading from OutputCollector directly.
     */
    private void syncOutputToContext(OutputCollector output, ProcessingContext<?> context) {
        if (!output.getRequests().isEmpty()) {
            context.getRequests().addAll(output.getRequests());
        }
    }

    /**
     * Process flow results - common logic for both inbound and outbound.
     * Normalizes flow result to a list and processes each message.
     * NEW: Uses focused contexts internally.
     */
    private void processFlowResults(
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context) throws ProcessingException {
        Object flowResult = context.getFlowResult();
        String tenant = routing.getTenant();

        if (flowResult == null) {
            log.debug("{} - No flow result available, skipping flow result processing", tenant);
            state.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> messagesToProcess = normalizeFlowResult(flowResult);

        if (messagesToProcess.isEmpty()) {
            log.info("{} - Flow result is empty, skipping processing", tenant);
            state.setIgnoreFurtherProcessing(true);
            return;
        }

        // Process each message using focused contexts
        for (Object message : messagesToProcess) {
            processMessage(message, routing, state, output, context);
        }

        handleEmptyRequests(output, state, tenant);
    }

    /**
     * Normalize flow result to a list of messages.
     * Handles both single objects and lists.
     */
    private List<Object> normalizeFlowResult(Object flowResult) {
        List<Object> messagesToProcess = new ArrayList<>();
        if (flowResult instanceof List) {
            messagesToProcess.addAll((List<?>) flowResult);
        } else {
            messagesToProcess.add(flowResult);
        }
        return messagesToProcess;
    }

    /**
     * Handle case where no requests were generated from flow results.
     * NEW: Uses focused contexts.
     */
    private void handleEmptyRequests(OutputCollector output, ProcessingState state, String tenant) {
        if (output.getRequests().isEmpty()) {
            log.info("{} - No requests generated from flow result", tenant);
            state.setIgnoreFurtherProcessing(true);
        } else {
            log.info("{} - Generated {} requests from flow result", tenant, output.getRequests().size());
        }
    }

    /**
     * Clone a payload object to a Map for modification.
     * Handles both Map instances and arbitrary objects (converted via Jackson).
     *
     * @param payload The payload to clone
     * @return A mutable Map representation of the payload
     * @throws ProcessingException if cloning fails
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> clonePayload(Object payload) throws ProcessingException {
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

    /**
     * NEW: Process a single message using focused contexts.
     * Subclasses must implement this to handle their specific message types.
     *
     * @param message The message to process
     * @param routing Immutable routing information
     * @param state Thread-safe mutable state
     * @param output Thread-safe output collector
     * @param context Legacy context for any remaining needs
     * @throws ProcessingException if processing fails
     */
    protected abstract void processMessage(
            Object message,
            RoutingContext routing,
            ProcessingState state,
            OutputCollector output,
            ProcessingContext<?> context) throws ProcessingException;

    /**
     * NEW: Hook for subclass-specific post-processing using focused contexts.
     * Default implementation does nothing.
     *
     * @param state Thread-safe mutable state
     * @param output Thread-safe output collector
     * @param context Legacy context for any remaining needs
     * @throws ProcessingException if post-processing fails
     */
    protected void postProcessFlowResults(ProcessingState state, OutputCollector output,
                                         ProcessingContext<?> context) throws ProcessingException {
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
