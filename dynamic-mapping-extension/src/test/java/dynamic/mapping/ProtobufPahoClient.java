/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import dynamic.mapping.processor.extension.external.CustomEventOuter;
import dynamic.mapping.processor.extension.external.CustomEventOuter.CustomEvent;


public class ProtobufPahoClient {

    static MemoryPersistence persistence = new MemoryPersistence();

    public static void main(String[] args) {

        ProtobufPahoClient client = new ProtobufPahoClient();
        client.testSendEvent();
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
                    .setEventType("c8y_ProtobufEventType")
                    .setTimestamp(System.currentTimeMillis())
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
