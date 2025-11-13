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

import static dynamic.mapper.model.Substitution.toPrettyJsonString;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.camel.Exchange;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.flow.CumulocityMessage;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.FlowContext;
import dynamic.mapper.processor.flow.JavaScriptInteropHelper;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowProcessorOutboundProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;

    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<?> context = exchange.getIn().getHeader("processingContext", ProcessingContext.class);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            processSmartMapping(context);
        } catch (Exception e) {
            int lineNumber = 0;
            if (e.getStackTrace().length > 0) {
                lineNumber = e.getStackTrace()[0].getLineNumber();
            }
            String errorMessage = String.format(
                    "Tenant %s - Error in FlowProcessorOutboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);

            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
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

    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        if (serviceConfiguration.isLogPayload() || mapping.getDebug()) {
            String payload = toPrettyJsonString(payloadObject); // is this and this required?
            log.info("{} - Incoming payload (patched) in onMessage(): {} {} {} {}", tenant,
                    payload,
                    serviceConfiguration.isLogPayload(), mapping.getDebug(),
                    serviceConfiguration.isLogPayload() || mapping.getDebug());
        }

        if (mapping.getCode() != null) {
            Context graalContext = context.getGraalContext();

            // Use try-finally to ensure cleanup
            Value onMessageFunction = null;
            Value inputMessage = null;
            Value result = null;

            try {
                // Task 1: Invoking JavaScript function
                String identifier = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
                Value bindings = graalContext.getBindings("js");

                // Load and execute the JavaScript code
                byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
                String decodedCode = new String(decodedBytes);
                String decodedCodeAdapted = decodedCode.replaceFirst("onMessage", identifier);

                Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                        .buildLiteral();
                graalContext.eval(source);

                // Load shared and system code if available
                loadSharedCode(graalContext, context);
                loadSystemCode(graalContext, context);

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

                // Clear bindings if needed (depends on your Context lifecycle)
                // bindings = null;
            }
        }
    }

    private void processResult(Value result, ProcessingContext<?> context, String tenant) throws ProcessingException {
        extractWarnings(context, tenant);
        extractLogs(context, tenant);

        // Always initialize an empty list
        List<Object> outputMessages = new ArrayList<>();

        if (isEmptyResult(result)) {
            log.warn("{} - onMessage function did not return any transformation result", tenant);
            context.getWarnings().add("onMessage function did not return any transformation result");
            context.setFlowResult(outputMessages); // Set empty list
            context.setIgnoreFurtherProcessing(true);
            ProcessingResultHelper.createAndAddDynamicMapperRequest(context,
                    context.getMapping().getTargetTemplate(), null, context.getMapping());
            return;
        }

        try {
            outputMessages = extractOutputMessages(result, tenant);
        } catch (Exception e) {
            log.error("{} - Error extracting output messages: {}", tenant, e.getMessage(), e);
            context.getWarnings().add("Error extracting output messages: " + e.getMessage());
            outputMessages = new ArrayList<>(); // Ensure it's empty
        }

        // Always set flow result (even if empty)
        context.setFlowResult(outputMessages);

        if (outputMessages.isEmpty()) {
            log.info("{} - No valid messages produced from onMessage function", tenant);
            context.getWarnings().add("No valid messages produced from onMessage function");
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        if (context.getMapping().getDebug() || context.getServiceConfiguration().isLogPayload()) {
            log.info("{} - onMessage function returned {} complete message(s)", tenant, outputMessages.size());
        }

        // IMPORTANT: Don't store the Value object itself, only extracted data
        // This ensures no GraalVM Value references leak
    }

    private void loadSharedCode(Context graalContext, ProcessingContext<?> context) {
        if (context.getSharedCode() != null) {
            byte[] decodedSharedCodeBytes = Base64.getDecoder().decode(context.getSharedCode());
            String decodedSharedCode = new String(decodedSharedCodeBytes);
            Source sharedSource = Source.newBuilder("js", decodedSharedCode, "sharedCode.js")
                    .buildLiteral();
            graalContext.eval(sharedSource);
        }
    }

    private void loadSystemCode(Context graalContext, ProcessingContext<?> context) {
        if (context.getSystemCode() != null) {
            byte[] decodedSystemCodeBytes = Base64.getDecoder().decode(context.getSystemCode());
            String decodedSystemCode = new String(decodedSystemCodeBytes);
            Source systemSource = Source.newBuilder("js", decodedSystemCode, "systemCode.js")
                    .buildLiteral();
            graalContext.eval(systemSource);
        }
    }

    /**
     * Create input message for outbound processing.
     * For outbound, the input is a CumulocityMessage containing the C8Y object.
     */
    private Value createInputMessage(Context graalContext, ProcessingContext<?> context) {
        // Create a DeviceMessage from the current context
        DeviceMessage deviceMessage = new DeviceMessage();

        // Set payload - convert to proper Java object first
        deviceMessage.setPayload(context.getPayload());

        // Set topic
        deviceMessage.setTopic(context.getTopic());

        // Convert to JavaScript object
        return graalContext.asValue(deviceMessage);
    }

    /**
     * Extract warnings from the flow context.
     */
    private void extractWarnings(ProcessingContext<?> context, String tenant) {

        Value warnings = context.getFlowContext().getState(FlowContext.WARNINGS);
        if (warnings != null && warnings.hasArrayElements()) {
            List<String> warningList = new ArrayList<>();
            long size = warnings.getArraySize();
            for (long i = 0; i < size; i++) {
                Value warningElement = warnings.getArrayElement(i);
                if (warningElement != null && warningElement.isString()) {
                    warningList.add(warningElement.asString());
                }
            }
            context.setWarnings(warningList);
            log.debug("{} - Collected {} warning(s) from flow execution", tenant, warningList.size());
        }
    }

    /**
     * Extract warnings from the flow context.
     */
    private void extractLogs(ProcessingContext<?> context, String tenant) {

        Value logs = context.getFlowContext().getState(FlowContext.LOGS);
        if (logs != null && logs.hasArrayElements()) {
            List<String> logList = new ArrayList<>();
            long size = logs.getArraySize();
            for (long i = 0; i < size; i++) {
                Value logElement = logs.getArrayElement(i);
                if (logElement != null && logElement.isString()) {
                    logList.add(logElement.asString());
                }
            }
            context.setLogs(logList);
            log.debug("{} - Collected {} logs from flow execution", tenant, logList.size());
        }
    }

    /**
     * Check if the result value is empty.
     * Handles null, undefined, empty arrays, and empty objects.
     */
    private boolean isEmptyResult(Value result) {
        // Null check
        if (result == null || result.isNull()) {
            return true;
        }

        // Check for JavaScript undefined
        if (result.toString().equals("undefined")) {
            return true;
        }

        // Empty array check
        if (result.hasArrayElements()) {
            if (result.getArraySize() == 0) {
                return true;
            } else {
                return false;
            }
        }

        // Empty object check (if applicable)
        if (result.hasMembers() && result.getMemberKeys().isEmpty()) {
            return true;
        }

        return false;
    }

    /**
     * Extract output messages from the result.
     * Handles both arrays and single items.
     */
    private List<Object> extractOutputMessages(Value result, String tenant) {
        List<Object> outputMessages = new ArrayList<>();

        // Check if result is an array
        if (result.hasArrayElements()) {
            long arraySize = result.getArraySize();
            log.debug("{} - Processing array result with {} element(s)", tenant, arraySize);

            for (long i = 0; i < arraySize; i++) {
                Value element = result.getArrayElement(i);
                processMessageElement(element, outputMessages, tenant);
            }
        } else {
            // Single item - process directly
            log.debug("{} - Processing single item result", tenant);
            processMessageElement(result, outputMessages, tenant);
        }

        return outputMessages;
    }

    /**
     * Process a single message element and add it to the output list.
     * Handles both DeviceMessage and CumulocityMessage types.
     */
    private void processMessageElement(Value element, List<Object> outputMessages, String tenant) {
        if (element == null || element.isNull()) {
            log.debug("{} - Skipping null element", tenant);
            return;
        }

        try {
            if (JavaScriptInteropHelper.isDeviceMessage(element)) {
                DeviceMessage deviceMsg = JavaScriptInteropHelper.convertToDeviceMessage(element);
                outputMessages.add(deviceMsg);
                log.debug("{} - Processed DeviceMessage: topic={}", tenant, deviceMsg.getTopic());

            } else if (JavaScriptInteropHelper.isCumulocityMessage(element)) {
                CumulocityMessage cumulocityMsg = JavaScriptInteropHelper.convertToCumulocityMessage(element);
                outputMessages.add(cumulocityMsg);
                log.debug("{} - Processed CumulocityMessage: type={}, action={}",
                        tenant, cumulocityMsg.getCumulocityType(), cumulocityMsg.getAction());

            } else {
                log.warn("{} - Unknown message type returned from onMessage function: {}",
                        tenant, element.getClass().getName());
            }
        } catch (Exception e) {
            log.error("{} - Error processing message element: {}", tenant, e.getMessage(), e);
        }
    }
}