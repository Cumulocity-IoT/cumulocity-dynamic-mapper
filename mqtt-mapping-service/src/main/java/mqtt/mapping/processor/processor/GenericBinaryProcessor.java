package mqtt.mapping.processor.processor;

import java.io.IOException;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.processor.model.PayloadWrapper;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Service
public class GenericBinaryProcessor<O> extends JSONProcessor<JsonNode> {

    public GenericBinaryProcessor ( ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context, MqttMessage mqttMessage) throws IOException{
        JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(Hex.encodeHexString(mqttMessage.getPayload())));
        context.setPayload(payloadJsonNode);
        return context;
    }
}