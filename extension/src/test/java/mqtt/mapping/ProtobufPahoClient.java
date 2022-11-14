package mqtt.mapping;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import mqtt.mapping.processor.protobuf.CustomMeasurementOuter;
import mqtt.mapping.processor.protobuf.CustomMeasurementOuter.CustomMeasurement;
import mqtt.mapping.processor.protobuf.CustomEventOuter;
import mqtt.mapping.processor.protobuf.CustomEventOuter.CustomEvent;

public class ProtobufPahoClient {

    static MemoryPersistence persistence = new MemoryPersistence();

    public static void main(String[] args) {

        ProtobufPahoClient client = new ProtobufPahoClient();
        client.testSendMeasurement();
        client.testSendEvent();
    }

    private void testSendMeasurement() {
        int qos = 0;
        String broker = System.getenv("broker");
        String client_id = System.getenv("client_id");
        String broker_username = System.getenv("broker_username");
        String broker_password = System.getenv("broker_password");
        try {
            String topic1 = "protobuf/measurement";
            MqttClient sampleClient = new MqttClient(broker, client_id, persistence);
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setUserName(broker_username);
            connOpts.setPassword(broker_password.toCharArray());
            connOpts.setCleanSession(true);

            System.out.println("Connecting to broker: " + broker);

            sampleClient.connect(connOpts);

            System.out.println("Publishing message: :::");

            CustomMeasurementOuter.CustomMeasurement proto = CustomMeasurement.newBuilder()
                    .setExternalIdType("c8y_Serial")
                    .setExternalId("berlin_01")
                    .setUnit("C")
                    .setMeasurementType("c8y_GenericMeasurement")
                    .setValue(99.7F)
                    .build();

            MqttMessage message = new MqttMessage(proto.toByteArray());
            message.setQos(qos);
            sampleClient.publish(topic1, message);

            System.out.println("Message published");
            sampleClient.disconnect();
            System.out.println("Disconnected");
            //System.exit(0);

        } catch (MqttException me) {
            System.out.println("Exception:" + me.getMessage());
            me.printStackTrace();
        }
    }

    private void testSendEvent() {
        int qos = 0;
        String broker = System.getenv("broker");
        String client_id = System.getenv("client_id");
        String broker_username = System.getenv("broker_username");
        String broker_password = System.getenv("broker_password");
        String topic2 = "protobuf/event";

        try {
            MqttClient sampleClient = new MqttClient(broker, client_id, persistence);
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setUserName(broker_username);
            connOpts.setPassword(broker_password.toCharArray());
            connOpts.setCleanSession(true);

            System.out.println("Connecting to broker: " + broker);

            sampleClient.connect(connOpts);

            System.out.println("Publishing message: :::");

            CustomEventOuter.CustomEvent proto = CustomEvent.newBuilder()
                    .setExternalIdType("c8y_Serial")
                    .setExternalId("berlin_01")
                    .setTxt("Dummy Text")
                    .build();

            MqttMessage message = new MqttMessage(proto.toByteArray());
            message.setQos(qos);
            sampleClient.publish(topic2, message);

            System.out.println("Message published");
            sampleClient.disconnect();
            System.out.println("Disconnected");
            //System.exit(0);

        } catch (MqttException me) {
            System.out.println("Exception:" + me.getMessage());
            me.printStackTrace();
        }
    }
}
