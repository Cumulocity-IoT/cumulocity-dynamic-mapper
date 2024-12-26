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
import dynamic.mapping.model.Mapping;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.inbound.BasePayloadProcessorInbound;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.ConfigurationRegistry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ExtensibleProcessor extends BasePayloadProcessorInbound<byte[]> {

    private Map<String, Extension> extensions = new HashMap<>();

    public ExtensibleProcessor(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public ProcessingContext<byte[]> deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException {
        ProcessingContext<byte[]> context = new ProcessingContext<byte[]>();
        context.setPayload(message.getPayload());
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        ProcessorExtensionSource extension = null;
        String tenant = context.getTenant();
        try {
            extension = getProcessorExtensionSource(context.getMapping().extension);
            if (extension == null) {
                log.info("Tenant {} - extractFromSource ******* {}", tenant, this);
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

    @Override
    public void applyFilter(ProcessingContext<byte[]> context) {
        // do nothing
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
                        extensionEntry.getEventName());
            }
        }
    }

    public void addExtensionEntry(String tenant, String extensionName, ExtensionEntry entry) {
        if (!extensions.containsKey(extensionName)) {
            log.warn("Tenant {} - Cannot add extension entry. Create first an extension!", tenant);
        } else {
            extensions.get(extensionName).getExtensionEntries().put(entry.getEventName(), entry);
        }
    }

    public void addExtension(String tenant, Extension extension) {
        if (extensions.containsKey(extension.getName())) {
            log.warn("Tenant {} - Extension with this name {} already exits, override existing extension!", tenant,
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