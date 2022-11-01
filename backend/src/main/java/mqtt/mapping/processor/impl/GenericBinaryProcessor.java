package mqtt.mapping.processor.impl;

import java.io.IOException;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Service
public class GenericBinaryProcessor<O> extends JSONProcessor<JsonNode> {

    public GenericBinaryProcessor ( ObjectMapper objectMapper, MQTTClient mqttClient, C8yAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context, MqttMessage mqttMessage) throws IOException{
        JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(Hex.encodeHexString(mqttMessage.getPayload())));
        context.setPayload(payloadJsonNode);
        return context;
    }
}