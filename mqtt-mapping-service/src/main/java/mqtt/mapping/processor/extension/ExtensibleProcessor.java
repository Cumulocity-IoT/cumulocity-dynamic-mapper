package mqtt.mapping.processor.extension;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.ExtensionEntry;
import mqtt.mapping.model.ExtensionStatus;
import mqtt.mapping.model.Extension;
import mqtt.mapping.processor.BasePayloadProcessor;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class ExtensibleProcessor<T> extends BasePayloadProcessor<byte[]> {

    private Map<String, Extension> extensions = new HashMap<>();

    public ExtensibleProcessor(ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent) {
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<byte[]> deserializePayload(ProcessingContext<byte[]> context, MqttMessage mqttMessage)
            throws IOException {
        context.setPayload(mqttMessage.getPayload());
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        ProcessorExtension extension = null;
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

    public ProcessorExtension<?> getProcessorExtension(ExtensionEntry extension) {
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

    public void addExtension(String id, String extensionName) {
        Extension ext = extensions.get(extensionName);
        if (ext != null) {
            log.warn("Extension with this name {} already exits, override existing extension!",
                    extensionName);
        } else {
            ext = new Extension(id, extensionName);
            extensions.put(extensionName, ext);
        }
    }

    public String deleteExtension(String extensionName) {
        Extension ext = extensions.remove(extensionName);
        String result = null;
        if (ext != null) {
            result = ext.getName();
        }
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