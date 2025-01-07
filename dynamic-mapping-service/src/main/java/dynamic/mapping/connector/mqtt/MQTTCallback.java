package dynamic.mapping.connector.mqtt;

import java.util.function.Consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;

public class MQTTCallback implements Consumer<Mqtt3Publish> {
    GenericMessageCallback genericMessageCallback;
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);
    String tenant;
    String connectorIdentifier;
    boolean supportsMessageContext;

    MQTTCallback(GenericMessageCallback callback, String tenant, String connectorIdentifier,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdentifier = connectorIdentifier;
        this.supportsMessageContext = supportsMessageContext;
    }

    @Override
    public void accept(Mqtt3Publish mqttMessage) {
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        // if (mqttMessage.getPayload().isPresent()) {
        // ByteBuffer byteBuffer = mqttMessage.getPayload().get();
        // byte[] byteArray = new byte[byteBuffer.remaining()];
        // byteBuffer.get(byteArray);
        // connectorMessage.setPayload(byteArray);
        // }
        byte[] payloadBytes = mqttMessage.getPayload()
                .map(byteBuffer -> {
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    return bytes;
                })
                .orElse(null);
        ConnectorMessage connectorMessage = ConnectorMessage.builder()
                .tenant(tenant)
                .supportsMessageContext(supportsMessageContext)
                .topic(topic)
                .sendPayload(true)
                .connectorIdentifier(connectorIdentifier)
                .payload(payloadBytes)
                .build();

        connectorMessage.setSupportsMessageContext(supportsMessageContext);
        genericMessageCallback.onMessage(connectorMessage);
    }

}