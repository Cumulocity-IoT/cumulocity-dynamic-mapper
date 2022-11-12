package mqtt.mapping;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import mqtt.mapping.processor.protobuf.CustomMeasurementOuter;
import mqtt.mapping.processor.protobuf.CustomMeasurementOuter.CustomMeasurement;

public class ProtobufPahoClient {
    //static String topic = "protobuf/measurement";
    static String topic = "protobuf/measurement";

    //static String content = "Message from MqttPublishSample";
    static int qos = 0;
    static String broker = "ssl://daehpresal58554.hycloud.softwareag.com:8883";
    //static String broker = "tcp://test.mosquitto.org:1883";
    static String clientId = "protobuf-client";
    static String username = "test";
    static String password = "test123#";
    static MemoryPersistence persistence = new MemoryPersistence();

    public static void main(String[] args) {
        try {
            MqttClient sampleClient = new MqttClient(broker, clientId, persistence);
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setUserName(username);
            connOpts.setPassword(password.toCharArray());
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
            sampleClient.publish(topic, message);

            System.out.println("Message published");
            sampleClient.disconnect();

            System.out.println("Disconnected");

            System.exit(0);

        } catch ( MqttException me ) {
            System.out.println("reason " + me.getReasonCode());
            System.out.println("msg " + me.getMessage());
            System.out.println("loc " + me.getLocalizedMessage());
            System.out.println("cause " + me.getCause());
            System.out.println("excep " + me);
            me.printStackTrace();
        }
    }
}
