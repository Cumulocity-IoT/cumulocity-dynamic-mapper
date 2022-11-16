package mqtt.mapping.processor.extension;

import java.io.IOException;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.processor.BasePayloadProcessor;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class ExtensibleProcessor<T> extends BasePayloadProcessor<byte[]> {

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
        public void extractFromSource(ProcessingContext<byte[]> context, ProcessorExtension<byte[]> extension) throws ProcessingException {
                extension.extractFromSource(context);
        }
}