package mqtt.mapping.processor.extension;

import java.io.IOException;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.ProcessorExtension;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class ExtensionPayloadProcessor<T> extends PayloadProcessor<byte[]> {

        public ExtensionPayloadProcessor(ObjectMapper objectMapper, MQTTClient mqttClient, C8yAgent c8yAgent) {
                super(objectMapper, mqttClient, c8yAgent);
        }

        @Override
        public ProcessingContext<byte[]> deserializePayload(ProcessingContext<byte[]> context, MqttMessage mqttMessage)
                        throws IOException {
                context.setPayload(mqttMessage.getPayload());
                return context;
        }
        public void extractFromSource(ProcessingContext<byte[]> context) throws ProcessingException {
                // bot used
        }

        public void extractFromSource(ProcessingContext<byte[]> context, ProcessorExtension extension) throws ProcessingException {
                extension.extractFromSource(context);
        }
}