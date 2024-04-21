package dynamic.mapping.connector.mqtt;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;

public class MQTTCallback implements Consumer<Mqtt3Publish> {
    GenericMessageCallback genericMessageCallback;
    static String TOPIC_LEVEL_SEPARATOR = String.valueOf(MqttTopic.TOPIC_LEVEL_SEPARATOR);
    String tenant;
    String connectorIdent;
    boolean supportsMessageContext;

    MQTTCallback(GenericMessageCallback callback, String tenant, String connectorIdent,
            boolean supportsMessageContext) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdent = connectorIdent;
        this.supportsMessageContext = supportsMessageContext;
    }

    @Override
    public void accept(Mqtt3Publish mqttMessage) {
        ConnectorMessage connectorMessage = new ConnectorMessage();
        if (mqttMessage.getPayload().isPresent()) {
            ByteBuffer byteBuffer = mqttMessage.getPayload().get();
            byte[] byteArray = new byte[byteBuffer.remaining()];
            byteBuffer.get(byteArray);
            connectorMessage.setPayload(byteArray);
        }
        connectorMessage.setTenant(tenant);
        connectorMessage.setSendPayload(true);
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        connectorMessage.setTopic(topic);
        connectorMessage.setConnectorIdent(connectorIdent);
        connectorMessage.setSupportsMessageContext(supportsMessageContext);
        genericMessageCallback.onMessage(connectorMessage);
    }

}