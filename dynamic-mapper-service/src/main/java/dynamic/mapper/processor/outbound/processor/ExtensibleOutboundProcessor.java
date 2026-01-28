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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dynamic.mapper.model.Direction;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.AbstractExtensibleProcessor;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ExtensionResultProcessor;
import dynamic.mapper.processor.extension.OutboundExtension;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.flow.DataPreparationContext;
import dynamic.mapper.processor.flow.DeviceMessage;
import dynamic.mapper.processor.flow.Message;
import dynamic.mapper.processor.flow.SimpleDataPreparationContext;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.ExtensionInboundRegistry;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

/**
 * Outbound extensible processor that delegates processing to external Java extensions.
 * Used when mappings are configured with extension-based processing for outbound direction.
 *
 * Supports two transformation types (validated at runtime):
 * - EXTENSION_SOURCE: Calls OutboundExtension.extractFromSource() to extract substitutions (substitution-based pattern)
 * - EXTENSION_TARGET: Calls ProcessorExtensionOutbound.onMessage() or extractAndPrepare() to prepare outbound requests (Smart Java Function pattern)
 *
 * Any other transformation type will result in a ProcessingException being thrown.
 * The extension prepares requests which are then sent by SendOutboundProcessor.
 */
@Slf4j
@Component
public class ExtensibleOutboundProcessor extends AbstractExtensibleProcessor {

    @Autowired
    private ExtensionResultProcessor resultProcessor;

    public ExtensibleOutboundProcessor(
            MappingService mappingService,
            ExtensionInboundRegistry extensionInboundRegistry) {
        super(mappingService, extensionInboundRegistry);
    }

    @Override
    protected void processWithExtension(ProcessingContext<byte[]> context)
            throws ProcessingException {
        String tenant = context.getTenant();
        Mapping mapping = context.getMapping();
        ExtensionEntry extensionEntry = mapping.getExtension();

        // Validate direction
        validateExtensionDirection(tenant, extensionEntry, Direction.OUTBOUND);

        // Check transformation type to determine which interface to use
        TransformationType transformationType = mapping.getTransformationType();

        if (transformationType == TransformationType.EXTENSION_TARGET) {
            // Complete processing with connector access (Smart Java Function pattern)
            processWithExtensionOutbound(context, tenant, extensionEntry);
        } else if (transformationType == TransformationType.EXTENSION_SOURCE) {
            // Source parsing only (substitution-based pattern)
            processWithExtensionSource(context, tenant, extensionEntry);
        } else {
            // Invalid transformation type for extension processing
            String errorMsg = String.format(
                "%s - Invalid transformation type '%s' for extension processing. " +
                "Expected EXTENSION_SOURCE or EXTENSION_TARGET for mapping '%s' with extension '%s'",
                tenant, transformationType, mapping.getName(), extensionEntry.getExtensionName());
            log.error(errorMsg);
            throw new ProcessingException(errorMsg);
        }
    }

    /**
     * Process using OutboundExtension for substitution-based transformation.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void processWithExtensionSource(ProcessingContext<byte[]> context,
                                           String tenant,
                                           ExtensionEntry extensionEntry)
            throws ProcessingException {
        OutboundExtension extension;
        try {
            Object extensionObj = getProcessorExtensionSource(tenant, extensionEntry);
            if (extensionObj instanceof OutboundExtension) {
                extension = (OutboundExtension) extensionObj;
            } else {
                throwExtensionNotFoundException(tenant, extensionEntry);
                return;
            }
        } catch (Exception ex) {
            throwExtensionNotFoundException(tenant, extensionEntry);
            return; // Unreachable, but makes null analysis happy
        }

        if (extension == null) {
            log.info("{} - extractFromSource - extension not found", tenant);
            logExtensions(tenant, extensionInboundRegistry.getExtensions(tenant));
            throwExtensionNotFoundException(tenant, extensionEntry);
            return; // Unreachable, but makes null analysis happy
        }

        extension.extractFromSource(context);
    }

    /**
     * Process using ProcessorExtensionOutbound to prepare outbound requests.
     * The actual sending is handled by SendOutboundProcessor.
     * Supports both legacy and new patterns.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void processWithExtensionOutbound(ProcessingContext<byte[]> context,
                                             String tenant,
                                             ExtensionEntry extensionEntry)
            throws ProcessingException {
        // Get the extension
        ProcessorExtensionOutbound extension = extensionEntry.getExtensionImplOutbound();

        if (extension == null) {
            String message = String.format(
                    "Tenant %s - Extension %s:%s not loaded or does not implement ProcessorExtensionOutbound",
                    tenant,
                    extensionEntry.getExtensionName(),
                    extensionEntry.getEventName());
            log.error(message);
            throw new ProcessingException(message);
        }

        try {
            // Check which pattern the extension uses
            if (usesNewPattern(extension)) {
                // NEW PATTERN: Return-value based
                processWithNewPattern(extension, context, tenant);
            } else {
                // OLD PATTERN: Side-effect based
                log.debug("{} - Extension {} uses deprecated extractAndPrepare() pattern",
                         tenant, extensionEntry.getExtensionName());
                extension.extractAndPrepare(context);
            }

        } catch (Exception e) {
            String message = String.format(
                    "Tenant %s - Error in outbound extension processing: %s",
                    tenant, e.getMessage());
            log.error(message, e);
            throw new ProcessingException(message, e);
        }
    }

    /**
     * Check if extension uses the new onMessage pattern.
     *
     * <p>Pattern detection works by checking if the extension class has overridden
     * the onMessage method. If it's still the default implementation from the interface,
     * we use the legacy pattern.</p>
     */
    private boolean usesNewPattern(ProcessorExtensionOutbound<?> extension) {
        try {
            Method method = extension.getClass().getMethod("onMessage",
                Message.class, DataPreparationContext.class);
            // Check if the method is declared in the implementation class
            // (not in the interface where the default is defined)
            return !method.getDeclaringClass().equals(ProcessorExtensionOutbound.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Process extension using the new return-value based pattern.
     */
    @SuppressWarnings("unchecked")
    private void processWithNewPattern(ProcessorExtensionOutbound extension,
                                       ProcessingContext<byte[]> context,
                                       String tenant) throws ProcessingException {
        // 1. Create Message wrapper
        Message<byte[]> message = Message.from(context);

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

        // 4. Process results
        if (results != null && results.length > 0) {
            log.debug("{} - Extension returned {} DeviceMessage(s)", tenant, results.length);
            resultProcessor.processOutboundResults(results, context);
        } else {
            log.warn("{} - Extension onMessage() returned null or empty array - no data to publish", tenant);
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
    protected void handleProcessingError(
            Exception e,
            ProcessingContext<byte[]> context,
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
