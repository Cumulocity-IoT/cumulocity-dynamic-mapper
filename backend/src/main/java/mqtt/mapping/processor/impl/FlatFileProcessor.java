package mqtt.mapping.processor.impl;

import java.nio.charset.Charset;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Service
public class FlatFileProcessor<I,O> extends JSONProcessor<String,String> {


    public FlatFileProcessor( ObjectMapper objectMapper, MQTTClient mqttClient, C8yAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<String> deserializePayload(ProcessingContext<String> context, MqttMessage mqttMessage) {
        String payloadMessage = null;
        if (mqttMessage.getPayload() != null) {
            payloadMessage = (mqttMessage.getPayload() != null
                    ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                    : "");
            JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(payloadMessage));
            payloadMessage = payloadJsonNode.toString();
        }
        context.setPayload(payloadMessage);
        return context;
    }

}