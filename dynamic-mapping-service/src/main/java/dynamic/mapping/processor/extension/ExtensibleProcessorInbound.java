/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.processor.extension;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.model.Extension;
import dynamic.mapping.model.ExtensionEntry;
import dynamic.mapping.model.ExtensionStatus;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.inbound.BasePayloadProcessorInbound;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.ConfigurationRegistry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ExtensibleProcessorInbound extends BasePayloadProcessorInbound<byte[]> {

    private Map<String, Extension> extensions = new HashMap<>();

    public ExtensibleProcessorInbound(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public ProcessingContext<byte[]> deserializePayload(ProcessingContext<byte[]> context, ConnectorMessage message)
            throws IOException {
        context.setPayload(message.getPayload());
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        ProcessorExtensionInbound extension = null;
        try {
            extension = getProcessorExtension(context.getMapping().extension);
            if (extension == null) {
                log.info("Tenant {} - extractFromSource ******* {}", tenant, this);
                logExtensions();
                String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                        context.getMapping().extension.getName(),
                        context.getMapping().extension.getEvent());
                log.warn("Tenant {} - Extension {}:{} could not be found!", tenant,
                        context.getMapping().extension.getName(),
                        context.getMapping().extension.getEvent());
                throw new ProcessingException(message);
            }
        } catch (Exception ex) {
            String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                    context.getMapping().extension.getName(),
                    context.getMapping().extension.getEvent());
            log.warn("Tenant {} - Extension {}:{} could not be found!", tenant,
                    context.getMapping().extension.getName(),
                    context.getMapping().extension.getEvent());
            throw new ProcessingException(message);
        }
        extension.extractFromSource(context);
    }

    public ProcessorExtensionInbound<?> getProcessorExtension(ExtensionEntry extension) {
        String name = extension.getName();
        String event = extension.getEvent();
        return extensions.get(name).getExtensionEntries().get(event).getExtensionImplementation();
    }

    public Extension getExtension(String extensionName) {
        return extensions.get(extensionName);
    }

    public Map<String, Extension> getExtensions() {
        return extensions;
    }

    public void logExtensions() {
        log.info("Tenant {} - Logging content ...", tenant);
        for (Map.Entry<String, Extension> entryExtension : extensions.entrySet()) {
            String extensionKey = entryExtension.getKey();
            Extension extension = entryExtension.getValue();
            log.info("Tenant {} - Extension {}:{} found contains: ", tenant, extensionKey,
                    extension.getName());
            for (Map.Entry<String, ExtensionEntry> entryExtensionEntry : extension.getExtensionEntries().entrySet()) {
                String extensionEntryKey = entryExtensionEntry.getKey();
                ExtensionEntry extensionEntry = entryExtensionEntry.getValue();
                log.info("Tenant {} - ExtensionEntry {}:{} found : ", tenant, extensionEntryKey,
                        extensionEntry.getEvent());
            }
        }
    }

    public void addExtensionEntry(String extensionName, ExtensionEntry entry) {
        Extension ext = extensions.get(extensionName);
        if (ext == null) {
            log.warn("Tenant {} - Create new extension first!", tenant);
        } else {
            ext.getExtensionEntries().put(entry.getEvent(), entry);
        }
    }

    public void addExtension(String id, String extensionName, boolean external) {
        Extension ext = extensions.get(extensionName);
        if (ext != null) {
            log.warn("Tenant {} - Extension with this name {} already exits, override existing extension!", tenant,
                    extensionName);
        } else {
            ext = new Extension(id, extensionName, external);
            extensions.put(extensionName, ext);
        }
    }

    public Extension deleteExtension(String extensionName) {
        Extension result = extensions.remove(extensionName);
        return result;
    }

    public void deleteExtensions() {
        extensions = new HashMap<>();
    }

    public void updateStatusExtension(String extName) {
        Extension ext = extensions.get(extName);
        ext.setLoaded(ExtensionStatus.COMPLETE);
        long countDefined = ext.getExtensionEntries().size();
        long countLoaded = ext.getExtensionEntries().entrySet().stream()
                .map(entry -> entry.getValue().isLoaded())
                .filter(entry -> entry).count();
        if (countLoaded == 0) {
            ext.setLoaded(ExtensionStatus.NOT_LOADED);
        } else if (countLoaded < countDefined) {
            ext.setLoaded(ExtensionStatus.PARTIALLY);
        }
    }

}