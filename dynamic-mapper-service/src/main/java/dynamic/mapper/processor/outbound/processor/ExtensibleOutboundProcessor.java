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
import dynamic.mapper.processor.extension.OutboundExtension;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.ExtensionInboundRegistry;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbound extensible processor that delegates processing to external Java extensions.
 * Used when mappings are configured with extension-based processing for outbound direction.
 *
 * Supports two transformation types:
 * - EXTENSION_SOURCE: Calls OutboundExtension.extractFromSource() to extract substitutions
 * - EXTENSION_TARGET: Calls ProcessorExtensionOutbound.extractAndPrepare() to prepare outbound requests
 *
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
            // Complete processing with connector access
            processWithExtensionOutbound(context, tenant, extensionEntry);
        } else {
            // Source parsing only (substitutions)
            processWithExtensionSource(context, tenant, extensionEntry);
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
            // Call extension to extract and prepare outbound requests
            // Requests are added to context.getRequests() by the extension
            extension.extractAndPrepare(context);

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
