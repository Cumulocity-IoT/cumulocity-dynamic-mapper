package mqtt.mapping.processor.processor;

import java.io.IOException;
import java.nio.charset.Charset;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.processor.model.PayloadWrapper;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;

@Service
public class FlatFileProcessor<O> extends JSONProcessor<JsonNode> {


    public FlatFileProcessor( ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context, MqttMessage mqttMessage) throws IOException {
        String payloadMessage  = (mqttMessage.getPayload() != null
                    ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                    : "");
        JsonNode payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(payloadMessage));
        context.setPayload(payloadJsonNode);
        return context;
    }

}