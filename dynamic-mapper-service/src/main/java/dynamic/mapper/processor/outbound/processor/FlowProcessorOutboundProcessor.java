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

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractFlowProcessorProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.JavaScriptInteropHelper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound FlowProcessor that executes JavaScript smart functions
 * to transform Cumulocity operations into device messages.
 */
@Slf4j
@Component
public class FlowProcessorOutboundProcessor extends AbstractFlowProcessorProcessor {

    @Autowired
    private C8YAgent c8yAgent;

    public FlowProcessorOutboundProcessor(MappingService mappingService) {
        super(mappingService);
    }

    @Override
    protected String getProcessorName() {
        return "FlowProcessorOutboundProcessor";
    }

    @Override
    protected Value createInputMessage(Context graalContext, ProcessingContext<?> context) {
        // Create a DeviceMessage from the current context using builder pattern
        DeviceMessage deviceMessage = DeviceMessage.create()
            .payload(context.getPayload())
            .topic(context.getTopic())
            .build();

        // Convert to JavaScript object
        return graalContext.asValue(deviceMessage);
    }

    @Override
    protected void processResult(Value result, ProcessingContext<?> context, String tenant)
            throws ProcessingException {
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

        if (context.getMapping().getDebug() || context.getServiceConfiguration().getLogPayload()) {
            log.info("{} - onMessage function returned {} complete message(s)", tenant, outputMessages.size());
        }

        // Create alarms for messages reported during processing
        createAlarmsForProcessing(context, tenant);

        // IMPORTANT: Don't store the Value object itself, only extracted data
        // This ensures no GraalVM Value references leak
    }

    @Override
    protected void handleProcessingError(Exception e, String errorMessage,
            ProcessingContext<?> context, String tenant, Mapping mapping) {
        MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
        context.addError(new ProcessingException(errorMessage, e));
        mappingStatus.errors++;
        mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
    }

    /**
     * Check if the result value is empty.
     * Handles null, undefined, empty arrays, and empty objects.
     */
    private Boolean isEmptyResult(Value result) {
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
     * Handles DeviceMessage types for outbound processing.
     */
    private void processMessageElement(Value element, List<Object> outputMessages, String tenant) {
        if (element == null || element.isNull()) {
            log.debug("{} - Skipping null element", tenant);
            return;
        }

        try {
            // always use DeviceMessage for outbound
            DeviceMessage deviceMsg = JavaScriptInteropHelper.convertToDeviceMessage(element);
            outputMessages.add(deviceMsg);
            log.debug("{} - Processed DeviceMessage: topic={}", tenant, deviceMsg.getTopic());
        } catch (Exception e) {
            log.error("{} - Error processing message element: {}", tenant, e.getMessage(), e);
        }
    }

    /**
     * Create alarms for any errors or warnings that occurred during processing.
     */
    private void createAlarmsForProcessing(ProcessingContext<?> context, String tenant) {
        if (context.getSourceId() != null && !context.getAlarms().isEmpty()) {
            ManagedObjectRepresentation sourceMor = new ManagedObjectRepresentation();
            sourceMor.setId(new GId(context.getSourceId()));
            context.getAlarms()
                    .forEach(alarm -> c8yAgent.createAlarm("WARNING", alarm, Utils.MAPPER_PROCESSING_ALARM,
                            new DateTime(), sourceMor, tenant));
        }
    }
}
