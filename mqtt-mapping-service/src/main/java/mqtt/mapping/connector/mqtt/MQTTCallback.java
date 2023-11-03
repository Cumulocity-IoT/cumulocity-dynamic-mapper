package mqtt.mapping.connector.mqtt;

import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.connector.core.callback.GenericMessageCallback;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MQTTCallback implements MqttCallback {
    GenericMessageCallback genericMessageCallback;
    String tenant;
    String connectorId;

    MQTTCallback(GenericMessageCallback callback, String tenant, String connectorId) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorId = connectorId;
    }
    @Override
    public void connectionLost(Throwable throwable) {
        genericMessageCallback.onClose(null,throwable);
    }

    @Override
    public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        ConnectorMessage connectorMessage = new ConnectorMessage();
        connectorMessage.setPayload(mqttMessage.getPayload());
        genericMessageCallback.onMessage(s,connectorMessage);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
