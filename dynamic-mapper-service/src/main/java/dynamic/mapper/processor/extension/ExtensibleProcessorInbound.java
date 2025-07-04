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

package dynamic.mapper.processor.extension;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Extension;
import dynamic.mapper.model.ExtensionEntry;
import dynamic.mapper.model.ExtensionStatus;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.inbound.BaseProcessorInbound;
import dynamic.mapper.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.ConfigurationRegistry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ExtensibleProcessorInbound extends BaseProcessorInbound<byte[]> {

    private Map<String, Extension> extensions = new HashMap<>();

    public ExtensibleProcessorInbound(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public byte[] deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException {
        return message.getPayload();
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        ProcessorExtensionSource extension = null;
        String tenant = context.getTenant();
        try {
            extension = getProcessorExtensionSource(context.getMapping().extension);
            if (extension == null) {
                log.info("{} - extractFromSource ******* {}", tenant, this);
                logExtensions(tenant);
                String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                        context.getMapping().extension.getExtensionName(),
                        context.getMapping().extension.getEventName());
                log.warn(message);
                throw new ProcessingException(message);
            }
        } catch (Exception ex) {
            String message = String.format("Tenant %s - Extension %s:%s could not be found!", tenant,
                    context.getMapping().extension.getExtensionName(),
                    context.getMapping().extension.getEventName());
            log.warn(message);
            throw new ProcessingException(message);
        }
        extension.extractFromSource(context);
    }

    @Override
    public void substituteInTargetAndSend(ProcessingContext<byte[]> context) {
        ProcessorExtensionTarget extension = null;

        extension = getProcessorExtensionTarget(context.getMapping().extension);
        // the extension is only meant to be used on the source side, extracting. From
        // now on we can use the standard substituteInTargetAndSend
        if (extension == null) {
            super.substituteInTargetAndSend(context);
            return;
        } else {
            extension.substituteInTargetAndSend(context, c8yAgent);
        }
        return;
    }

    public ProcessorExtensionSource<?> getProcessorExtensionSource(ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensions.get(extensionName).getExtensionEntries().get(eventName).getExtensionImplSource();
    }

    public ProcessorExtensionTarget<?> getProcessorExtensionTarget(ExtensionEntry extension) {
        String extensionName = extension.getExtensionName();
        String eventName = extension.getEventName();
        return extensions.get(extensionName).getExtensionEntries().get(eventName).getExtensionImplTarget();
    }

    public Extension getExtension(String extensionName) {
        return extensions.get(extensionName);
    }

    public Map<String, Extension> getExtensions() {
        return extensions;
    }

    private void logExtensions(String tenant) {
        log.info("{} - Logging content ...", tenant);
        for (Map.Entry<String, Extension> entryExtension : extensions.entrySet()) {
            String extensionKey = entryExtension.getKey();
            Extension extension = entryExtension.getValue();
            log.info("{} - Extension {}: {} found contains: ", tenant, extensionKey,
                    extension.getName());
            for (Map.Entry<String, ExtensionEntry> entryExtensionEntry : extension.getExtensionEntries().entrySet()) {
                String extensionEntryKey = entryExtensionEntry.getKey();
                ExtensionEntry extensionEntry = entryExtensionEntry.getValue();
                log.info("{} - ExtensionEntry {}: {} found : ", tenant, extensionEntryKey,
                        extensionEntry.getEventName());
            }
        }
    }

    public void addExtensionEntry(String tenant, String extensionName, ExtensionEntry entry) {
        if (!extensions.containsKey(extensionName)) {
            log.warn("{} - Cannot add extension entry. Create first an extension!", tenant);
        } else {
            extensions.get(extensionName).getExtensionEntries().put(entry.getEventName(), entry);
        }
    }

    public void addExtension(String tenant, Extension extension) {
        if (extensions.containsKey(extension.getName())) {
            log.warn("{} - Extension with this name {} already exits, override existing extension!", tenant,
                    extension.getName());
        }
        extensions.put(extension.getName(), extension);
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