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

import org.springframework.stereotype.Component;

import dynamic.mapper.model.Direction;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractExtensibleProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.flow.SimpleDataPreparationContext;
import dynamic.mapper.processor.model.DataPreparationContext;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.Message;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.ExtensionInboundRegistry;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound extensible processor that delegates processing to external Java extensions.
 * Used when mappings are configured with extension-based processing for outbound direction.
 *
 * Only supports EXTENSION_JAVA transformation type:
 * - EXTENSION_JAVA: Calls ProcessorExtensionOutbound.onMessage() to prepare outbound requests (Smart Java Function pattern)
 *
 * Any other transformation type will result in a ProcessingException being thrown.
 * The extension prepares requests which are then sent by SendOutboundProcessor.
 */
@Slf4j
@Component
public class ExtensibleOutboundProcessor extends AbstractExtensibleProcessor {

    public ExtensibleOutboundProcessor(
            MappingService mappingService,
            ExtensionInboundRegistry extensionInboundRegistry) {
        super(mappingService, extensionInboundRegistry);
    }

    /**
     * Override to handle ProcessingContext<Object> for outbound (not byte[])
     */
    @Override
    public void process(org.apache.camel.Exchange exchange) throws Exception {
        ProcessingContext<Object> context = getProcessingContextAsObject(exchange);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.getTesting();

        try {
            processWithExtensionOutbound(context);
        } catch (Exception e) {
            handleProcessingErrorObject(e, context, tenant, mapping, testing);
        }
    }

    private void processWithExtensionOutbound(ProcessingContext<Object> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ExtensionEntry extensionEntry = mapping.getExtension();

        // Validate direction
        validateExtensionDirection(tenant, extensionEntry, Direction.OUTBOUND);

        // Check transformation type - only EXTENSION_JAVA is supported
        TransformationType transformationType = mapping.getTransformationType();

        if (transformationType == TransformationType.EXTENSION_JAVA) {
            // Complete processing with connector access (Smart Java Function pattern)
            processWithExtensionOutboundImpl(context, tenant, extensionEntry);
        } else {
            // Invalid transformation type for extension processing
            String errorMsg = String.format(
                "%s - Invalid transformation type '%s' for extension processing. " +
                "Expected EXTENSION_JAVA for mapping '%s' with extension '%s'. " +
                "Note: EXTENSION_SOURCE (substitution-based pattern) has been removed.",
                tenant, transformationType, mapping.getName(), extensionEntry.getExtensionName());
            log.error(errorMsg);
            throw new ProcessingException(errorMsg);
        }
    }

    @Override
    protected void processWithExtension(ProcessingContext<byte[]> context) throws ProcessingException {
        // Not used for outbound - we override process() instead
        throw new UnsupportedOperationException("Outbound processor uses ProcessingContext<Object>, not byte[]");
    }

    @SuppressWarnings("unchecked")
    private ProcessingContext<Object> getProcessingContextAsObject(org.apache.camel.Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }

    /**
     * Process using ProcessorExtensionOutbound to prepare outbound requests.
     * The actual sending is handled by SendOutboundProcessor.
     * Uses the SMART Java Function pattern (return-value based).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void processWithExtensionOutboundImpl(ProcessingContext<Object> context,
                                             String tenant,
                                             ExtensionEntry extensionEntry)
            throws ProcessingException {
        // Get the extension from registry (same pattern as inbound)
        ProcessorExtensionOutbound extension;
        try {
            extension = getProcessorExtensionOutbound(tenant, extensionEntry);
        } catch (Exception ex) {
            throwExtensionNotFoundException(tenant, extensionEntry);
            return; // Unreachable, but makes null analysis happy
        }

        if (extension == null) {
            log.info("{} - onMessage - extension not found", tenant);
            logExtensions(tenant, extensionInboundRegistry.getExtensions(tenant));
            throwExtensionNotFoundException(tenant, extensionEntry);
            return; // Unreachable, but makes null analysis happy
        }

        try {
            // Process using return-value based pattern
            // 1. Create Message wrapper
            Message<Object> message = Message.from(context);

            // 2. Create DataPreparationContext
            // Note: For outbound, C8YAgent is typically not needed, but we provide it for consistency
            DataPreparationContext prepContext = new SimpleDataPreparationContext(
                context.getFlowContext(),
                null, // c8yAgent not typically needed for outbound
                tenant,
                context.getTesting(),
                context.getMapping(),
                context
            );

            // 3. Call new pattern method
            DeviceMessage[] results = extension.onMessage(message, prepContext);

            // 4. Store results in context for processing by ExtensibleResultOutboundProcessor
            if (results != null && results.length > 0) {
                log.debug("{} - Extension returned {} DeviceMessage(s)", tenant, results.length);
                context.setExtensionResult(results);
            } else {
                log.warn("{} - Extension onMessage() returned null or empty array - no data to publish", tenant);
                context.setIgnoreFurtherProcessing(true);
            }

        } catch (Exception e) {
            String message = String.format(
                    "Tenant %s - Error in outbound extension processing: %s",
                    tenant, e.getMessage());
            log.error(message, e);
            throw new ProcessingException(message, e);
        }
    }

    private void validateExtensionDirection(String tenant, ExtensionEntry extension, Direction expectedDirection)
            throws ProcessingException {
        Direction extensionDirection = extension.getDirection();

        if (extensionDirection != Direction.UNSPECIFIED && extensionDirection != expectedDirection) {
            String message = String.format(
                    "Tenant %s - Extension %s:%s has direction %s but is being used in %s processing",
                    tenant,
                    extension.getExtensionName(),
                    extension.getEventName(),
                    extensionDirection,
                    expectedDirection);
            log.error(message);
            throw new ProcessingException(message);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleProcessingError(
            Exception e,
            ProcessingContext<byte[]> context,
            String tenant,
            Mapping mapping,
            Boolean testing) {
        // Delegate to the Object version (outbound uses ProcessingContext<Object>)
        handleProcessingErrorObject(e, (ProcessingContext<Object>) (ProcessingContext<?>) context, tenant, mapping, testing);
    }

    private void handleProcessingErrorObject(
            Exception e,
            ProcessingContext<Object> context,
            String tenant,
            Mapping mapping,
            Boolean testing) {
        log.error("{} - Error in extensible outbound processor for mapping: {}",
                tenant, context.getMapping().getName(), e);

        if (e instanceof ProcessingException) {
            context.addError((ProcessingException) e);
        } else {
            context.addError(new ProcessingException("Extensible outbound processing failed", e));
        }

        if (!testing) {
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }
    }
}
