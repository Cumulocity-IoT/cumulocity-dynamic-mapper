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

package mqtt.mapping.processor.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.connector.IConnectorClient;
import mqtt.mapping.connector.callback.ConnectorMessage;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.Extension;
import mqtt.mapping.model.ExtensionEntry;
import mqtt.mapping.model.ExtensionStatus;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.connector.client.mqtt.MQTTClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ExtensibleProcessorInbound extends BasePayloadProcessor<byte[]> {

    private Map<String, Extension> extensions = new HashMap<>();

    public ExtensibleProcessorInbound(ObjectMapper objectMapper, IConnectorClient connectorClient, C8YAgent c8yAgent, String tenant) {
        super(objectMapper, connectorClient, c8yAgent, tenant);
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
                String message = String.format("Extension %s:%s could not be found!",
                        context.getMapping().extension.getName(),
                        context.getMapping().extension.getEvent());
                log.warn("Extension {}:{} could not be found!",
                        context.getMapping().extension.getName(),
                        context.getMapping().extension.getEvent());
                throw new ProcessingException(message);
            }
        } catch (Exception ex) {
            String message = String.format("Extension %s:%s could not be found!",
                    context.getMapping().extension.getName(),
                    context.getMapping().extension.getEvent());
            log.warn("Extension {}:{} could not be found!", context.getMapping().extension.getName(),
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

    public void addExtensionEntry(String extensionName, ExtensionEntry entry) {
        Extension ext = extensions.get(extensionName);
        if (ext == null) {
            log.warn("Create new extension first!");
        } else {
            ext.getExtensionEntries().put(entry.getEvent(), entry);
        }
    }

    public void addExtension(String id, String extensionName, boolean external) {
        Extension ext = extensions.get(extensionName);
        if (ext != null) {
            log.warn("Extension with this name {} already exits, override existing extension!",
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