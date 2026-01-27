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

import java.util.Map;

import org.apache.camel.Exchange;

import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.service.ExtensionInboundRegistry;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for Extensible processors that provides common functionality
 * for processing using external Java extensions.
 *
 * Uses the Template Method pattern to define the overall extension processing flow
 * while allowing subclasses to customize specific steps.
 */
@Slf4j
public abstract class AbstractExtensibleProcessor extends CommonProcessor {

    protected final MappingService mappingService;
    protected final ExtensionInboundRegistry extensionInboundRegistry;

    protected AbstractExtensibleProcessor(
            MappingService mappingService,
            ExtensionInboundRegistry extensionInboundRegistry) {
        this.mappingService = mappingService;
        this.extensionInboundRegistry = extensionInboundRegistry;
    }

    /**
     * Template method that defines the overall processing flow.
     * Handles exception management and error reporting.
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<byte[]> context = getProcessingContextAsByteArray(exchange);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();
        Boolean testing = context.getTesting();

        try {
            processWithExtension(context);
        } catch (Exception e) {
            handleProcessingError(e, context, tenant, mapping, testing);
        }
    }

    /**
     * Process using extension. Subclasses implement specific logic.
     *
     * @param context The processing context
     * @throws ProcessingException if processing fails
     */
    protected abstract void processWithExtension(ProcessingContext<byte[]> context)
            throws ProcessingException;

    /**
     * Get extension source implementation for extraction.
     * Returns either InboundExtension or OutboundExtension depending on direction.
     *
     * @param tenant The tenant identifier
     * @param extension The extension entry configuration
     * @return The extension source implementation (InboundExtension or OutboundExtension)
     */
    protected Object getProcessorExtensionSource(String tenant, ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensionInboundRegistry.getExtension(tenant, extensionName)
                .getExtensionEntries()
                .get(eventName)
                .getExtensionImplSource();
    }

    /**
     * Get extension inbound implementation for transformation.
     *
     * @param tenant The tenant identifier
     * @param extension The extension entry configuration
     * @return The extension inbound implementation
     */
    protected ProcessorExtensionInbound<?> getProcessorExtensionInbound(String tenant, ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensionInboundRegistry.getExtension(tenant, extensionName)
                .getExtensionEntries()
                .get(eventName)
                .getExtensionImplInbound();
    }

    /**
     * Throw extension not found exception with formatted message.
     *
     * @param tenant The tenant identifier
     * @param extension The extension entry that was not found
     * @throws ProcessingException always
     */
    protected void throwExtensionNotFoundException(String tenant, ExtensionEntry extension)
            throws ProcessingException {
        String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                extension.getExtensionName(),
                extension.getEventName());
        log.warn(message);
        throw new ProcessingException(message);
    }

    /**
     * Log all available extensions for debugging.
     *
     * @param tenant The tenant identifier
     * @param extensions The map of extensions
     */
    protected void logExtensions(String tenant, Map<String, Extension> extensions) {
        log.info("{} - Logging available extensions...", tenant);
        for (Map.Entry<String, Extension> entryExtension : extensions.entrySet()) {
            String extensionKey = entryExtension.getKey();
            Extension extension = entryExtension.getValue();
            log.info("{} - Extension {}: {} found contains:", tenant, extensionKey,
                    extension.getName());
            for (Map.Entry<String, ExtensionEntry> entryExtensionEntry : extension.getExtensionEntries()
                    .entrySet()) {
                String extensionEntryKey = entryExtensionEntry.getKey();
                ExtensionEntry extensionEntry = entryExtensionEntry.getValue();
                log.info("{} - ExtensionEntry {}: {} found", tenant, extensionEntryKey,
                        extensionEntry.getEventName());
            }
        }
    }

    /**
     * Handle processing errors in a subclass-specific way.
     *
     * @param e The exception that occurred
     * @param context The processing context
     * @param tenant The tenant identifier
     * @param mapping The mapping being processed
     * @param testing Whether this is a test execution
     */
    protected abstract void handleProcessingError(
            Exception e,
            ProcessingContext<byte[]> context,
            String tenant,
            Mapping mapping,
            Boolean testing);

    /**
     * Extract processing context from exchange header.
     *
     * @param exchange The Camel exchange
     * @return The processing context
     */
    @SuppressWarnings("unchecked")
    protected ProcessingContext<byte[]> getProcessingContextAsByteArray(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }
}
