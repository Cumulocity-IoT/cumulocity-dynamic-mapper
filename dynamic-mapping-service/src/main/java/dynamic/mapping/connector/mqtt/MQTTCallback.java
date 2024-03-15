package dynamic.mapping.connector.mqtt;

import java.nio.ByteBuffer;
import java.util.Optional;
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

    MQTTCallback(GenericMessageCallback callback, String tenant, String connectorIdent) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdent = connectorIdent;
    }

    // @Override
    // public void connectionLost(Throwable throwable) {
    // genericMessageCallback.onClose(null, throwable);
    // }

    @Override
    public void accept(Mqtt3Publish mqttMessage) {
        ConnectorMessage connectorMessage = new ConnectorMessage();
        Optional<ByteBuffer> ob = mqttMessage.getPayload();
        if (ob.isPresent()) {
            ByteBuffer bb = ob.get();
            byte[] arr = new byte[bb.remaining()];
            bb.get(arr);
            connectorMessage.setPayload(arr);
        }
        connectorMessage.setTenant(tenant);
        connectorMessage.setSendPayload(true);
        String topic = String.join(TOPIC_LEVEL_SEPARATOR, mqttMessage.getTopic().getLevels());
        connectorMessage.setTopic(topic);
        connectorMessage.setConnectorIdent(connectorIdent);
        genericMessageCallback.onMessage(connectorMessage);
    }

}