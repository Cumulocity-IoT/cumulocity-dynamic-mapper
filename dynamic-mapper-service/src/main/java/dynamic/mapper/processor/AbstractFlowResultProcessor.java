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
import dynamic.mapper.processor.model.ProcessingContext;
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

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            processFlowResults(context);
            postProcessFlowResults(context);
        } catch (Exception e) {
            handleProcessingError(e, context, tenant, mapping);
        }
    }

    /**
     * Process flow results - common logic for both inbound and outbound.
     * Normalizes flow result to a list and processes each message.
     */
    private void processFlowResults(ProcessingContext<?> context) throws ProcessingException {
        Object flowResult = context.getFlowResult();
        String tenant = context.getTenant();

        if (flowResult == null) {
            log.debug("{} - No flow result available, skipping flow result processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> messagesToProcess = normalizeFlowResult(flowResult);

        if (messagesToProcess.isEmpty()) {
            log.info("{} - Flow result is empty, skipping processing", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        // Process each message
        for (Object message : messagesToProcess) {
            processMessage(message, context);
        }

        handleEmptyRequests(context, tenant);
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
     */
    private void handleEmptyRequests(ProcessingContext<?> context, String tenant) {
        if (context.getRequests().isEmpty()) {
            log.info("{} - No requests generated from flow result", tenant);
            context.setIgnoreFurtherProcessing(true);
        } else {
            log.info("{} - Generated {} requests from flow result", tenant, context.getRequests().size());
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
     * Process a single message from the flow result.
     * Subclasses must implement this to handle their specific message types.
     *
     * @param message The message to process
     * @param context The processing context
     * @throws ProcessingException if processing fails
     */
    protected abstract void processMessage(Object message, ProcessingContext<?> context)
            throws ProcessingException;

    /**
     * Hook for subclass-specific post-processing after all flow results are processed.
     * Default implementation does nothing.
     *
     * @param context The processing context
     * @throws ProcessingException if post-processing fails
     */
    protected void postProcessFlowResults(ProcessingContext<?> context) throws ProcessingException {
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
