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

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.flow.JavaScriptConsole;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for FlowProcessor processors that provides common functionality
 * for executing JavaScript smart functions using GraalVM.
 *
 * Handles JavaScript code loading, execution, result processing, and GraalVM resource cleanup.
 */
@Slf4j
public abstract class AbstractFlowProcessorProcessor extends CommonProcessor {

    protected final MappingService mappingService;

    protected AbstractFlowProcessorProcessor(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);

        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();

        try {
            processSmartMapping(context);
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "%s - Error in %s: %s for mapping: %s, line %s",
                    tenant, getProcessorName(), mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);

            handleProcessingError(e, errorMessage, context, tenant, mapping);
        } finally {
            // Close the Context completely
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    log.warn("{} - Error closing context in finally block: {}", tenant, e.getMessage());
                }
            }
        }
    }

    /**
     * Process smart mapping by executing JavaScript function.
     */
    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        if (serviceConfiguration.getLogPayload() || mapping.getDebug()) {
            String payload = toPrettyJsonString(payloadObject);
            log.info("{} - Incoming payload (patched) in onMessage(): {} {} {} {}", tenant,
                    payload,
                    serviceConfiguration.getLogPayload(), mapping.getDebug(),
                    serviceConfiguration.getLogPayload() || mapping.getDebug());
        }

        if (mapping.getCode() != null) {
            Context graalContext = context.getGraalContext();

            // Use try-finally to ensure cleanup
            Value bindings = null;
            Value onMessageFunction = null;
            Value inputMessage = null;
            Value result = null;

            try {
                // Task 1: Invoking JavaScript function
                String identifier = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
                bindings = graalContext.getBindings("js");

                // Always provide console for JavaScript code
                if (context.getFlowContext() != null) {
                    JavaScriptConsole console = new JavaScriptConsole(context.getFlowContext(), tenant, mapping);
                    bindings.putMember("console", console);
                }

                // Load and execute the JavaScript code
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst("onMessage", identifier);

                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();
                graalContext.eval(source);

                // Load shared code if available
                loadSharedCode(graalContext, context);

                onMessageFunction = bindings.getMember(identifier);
                inputMessage = createInputMessage(graalContext, context);

                // Execute the JavaScript function
                result = onMessageFunction.execute(inputMessage, context.getFlowContext());

                // Task 2: Extracting the result
                processResult(result, context, tenant);

            } finally {
                // Explicitly null out GraalVM Value references
                onMessageFunction = null;
                inputMessage = null;
                result = null;
                bindings = null;
            }
        }
    }

    /**
     * Load shared and system code into GraalVM context using cached Sources - OPTIMIZED!
     */
    protected void loadSharedCode(Context graalContext, ProcessingContext<?> context) {
        // Use pre-cached Source if available - no decoding or parsing needed
        if (context.getSharedSource() != null) {
            graalContext.eval(context.getSharedSource());
        }

        // Also load system code if available
        if (context.getSystemSource() != null) {
            graalContext.eval(context.getSystemSource());
        }
    }

    /**
     * Extract warnings from the flow context.
     */
    protected void extractWarnings(ProcessingContext<?> context, String tenant) {
        Value warnings = null;
        try {
            warnings = context.getFlowContext().getState(DataPrepContext.WARNINGS);
            if (warnings != null && warnings.hasArrayElements()) {
                List<String> warningList = new ArrayList<>();
                long size = warnings.getArraySize();

                for (long i = 0; i < size; i++) {
                    Value warningElement = null;
                    try {
                        warningElement = warnings.getArrayElement(i);
                        if (warningElement != null && warningElement.isString()) {
                            warningList.add(warningElement.asString());
                        }
                    } finally {
                        warningElement = null;
                    }
                }

                context.setWarnings(warningList);
                log.debug("{} - Collected {} warning(s) from flow execution", tenant, warningList.size());
            }
        } finally {
            warnings = null;
        }
    }

    /**
     * Extract logs from the flow context.
     */
    protected void extractLogs(ProcessingContext<?> context, String tenant) {
        Value logs = null;
        try {
            logs = context.getFlowContext().getState(DataPrepContext.LOGS);
            if (logs != null && logs.hasArrayElements()) {
                List<String> logList = new ArrayList<>();
                long size = logs.getArraySize();

                for (long i = 0; i < size; i++) {
                    Value logElement = null;
                    try {
                        logElement = logs.getArrayElement(i);
                        if (logElement != null && logElement.isString()) {
                            logList.add(logElement.asString());
                        }
                    } finally {
                        logElement = null;
                    }
                }

                context.setLogs(logList);
                log.debug("{} - Collected {} logs from flow execution", tenant, logList.size());
            }
        } finally {
            logs = null;
        }
    }

    /**
     * Get processor name for error messages.
     * Subclasses should return their class name.
     */
    protected abstract String getProcessorName();

    /**
     * Create input message for JavaScript function.
     * Subclasses implement to create appropriate message type (DeviceMessage or CumulocityObject).
     */
    protected abstract Value createInputMessage(Context graalContext, ProcessingContext<?> context);

    /**
     * Process the result from JavaScript function execution.
     * Subclasses implement to handle their specific message types.
     */
    protected abstract void processResult(Value result, ProcessingContext<?> context, String tenant)
            throws ProcessingException;

    /**
     * Handle processing errors.
     * Subclasses can customize error handling (e.g., checking testing mode).
     */
    protected abstract void handleProcessingError(Exception e, String errorMessage,
            ProcessingContext<?> context, String tenant, Mapping mapping);
}
