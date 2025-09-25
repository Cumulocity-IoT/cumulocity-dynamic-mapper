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
import dynamic.mapper.processor.flow.JavaScriptInteropHelper;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FlowProcessorOutboundProcessor extends BaseProcessor  {

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
                    "Tenant %s - Error in FlowProcessorInboundProcessor: %s for mapping: %s, line %s",
                    tenant, mapping.getName(), e.getMessage(), lineNumber);
            log.error(errorMessage, e);

            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException(errorMessage, e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
            return;
        } 
    }

    public void processSmartMapping(ProcessingContext<?> context) throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ServiceConfiguration serviceConfiguration = context.getServiceConfiguration();

        Object payloadObject = context.getPayload();

        String payload = toPrettyJsonString(payloadObject);
        if (serviceConfiguration.logPayload || mapping.getDebug()) {
            log.info("{} - Processing payload: {}", tenant, payload);
        }

        if (mapping.getCode() != null) {
            Context graalContext = context.getGraalContext();

            // Task 1: Invoking JavaScript function
            String identifier = Mapping.SMART_FUNCTION_NAME + "_" + mapping.getIdentifier();
            Value bindings = graalContext.getBindings("js");

            // Load and execute the JavaScript code
            byte[] decodedBytes = Base64.getDecoder().decode(mapping.getCode());
            String decodedCode = new String(decodedBytes);
            String decodedCodeAdapted = decodedCode.replaceFirst(
                    "onMessage",
                    identifier);

            Source source = Source.newBuilder("js", decodedCodeAdapted, identifier + ".js")
                    .buildLiteral();
            graalContext.eval(source);

            // Load shared and system code if available
            loadSharedCode(graalContext, context);
            loadSystemCode(graalContext, context);

            Value onMessageFunction = bindings.getMember(identifier);

            // Create input message (DeviceMessage or CumulocityMessage)
            Value inputMessage = createInputMessage(graalContext, context);

            // Execute the JavaScript function
            final Value result = onMessageFunction.execute(inputMessage, context.getFlowContext());

            // Task 2: Extracting the result
            processResult(result, context, tenant);
        }
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

    private void processResult(Value result, ProcessingContext<?> context, String tenant) {
        if (!result.hasArrayElements()) {
            log.warn("{} - onMessage function did not return an array", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        long arraySize = result.getArraySize();
        if (arraySize == 0) {
            log.info("{} - onMessage function returned empty array", tenant);
            context.setIgnoreFurtherProcessing(true);
            return;
        }

        List<Object> outputMessages = new ArrayList<>();

        for (int i = 0; i < arraySize; i++) {
            Value element = result.getArrayElement(i);

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
                log.warn("{} - Unknown message type returned from onMessage function", tenant);
            }
        }

        context.setFlowResult(outputMessages);

        if (context.getMapping().getDebug() || context.getServiceConfiguration().logPayload) {
            log.info("{} - onMessage function returned {} complete messages", tenant, outputMessages.size());
        }
    }

}