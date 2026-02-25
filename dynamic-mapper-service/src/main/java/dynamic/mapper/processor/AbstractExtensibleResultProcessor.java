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

import org.apache.camel.Exchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.CumulocityObject;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.OutputCollector;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.ProcessingState;
import dynamic.mapper.processor.model.RoutingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for extension result processors that provides common functionality
 * for processing extension results in both inbound and outbound directions.
 *
 * <p>This processor handles results returned by Java Extensions (ProcessorExtensionInbound/Outbound)
 * and converts them to DynamicMapperRequest objects for execution by SendInboundProcessor/SendOutboundProcessor.</p>
 *
 * <p>Uses the Template Method pattern to define the overall process flow while
 * allowing subclasses to customize specific steps.</p>
 *
 * <h3>Architecture:</h3>
 * <ul>
 *   <li><b>Inbound:</b> CumulocityObject[] → DynamicMapperRequest[] (for C8Y API)</li>
 *   <li><b>Outbound:</b> DeviceMessage[] → DynamicMapperRequest[] (for broker publishing)</li>
 * </ul>
 *
 * @see CumulocityObject
 * @see DeviceMessage
 * @see dynamic.mapper.processor.model.DynamicMapperRequest
 */
@Slf4j
public abstract class AbstractExtensibleResultProcessor extends CommonProcessor {

    protected final MappingService mappingService;
    protected final ObjectMapper objectMapper;
    protected final C8YAgent c8yAgent;

    protected AbstractExtensibleResultProcessor(
            MappingService mappingService,
            ObjectMapper objectMapper,
            C8YAgent c8yAgent) {
        this.mappingService = mappingService;
        this.objectMapper = objectMapper;
        this.c8yAgent = c8yAgent;
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
            processExtensionResults(routing, state, output, context);
            postProcessExtensionResults(state, output, context);

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
        if (!output.getWarnings().isEmpty()) {
            context.getWarnings().addAll(output.getWarnings());
        }
    }

    /**
     * NEW: Process extension results using focused contexts.
     * Subclasses must implement this to handle their specific result types.
     *
     * @param routing Immutable routing information
     * @param state Thread-safe mutable state
     * @param output Thread-safe output collector
     * @param context Legacy context for any remaining needs
     * @throws ProcessingException if processing fails
     */
    protected abstract void processExtensionResults(
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
    protected void postProcessExtensionResults(ProcessingState state, OutputCollector output,
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
    protected abstract void handleProcessingError(
            Exception e,
            ProcessingContext<?> context,
            String tenant,
            Mapping mapping);
}
