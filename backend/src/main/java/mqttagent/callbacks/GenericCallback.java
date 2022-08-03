package mqttagent.callbacks;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callbacks.handler.SysHandler;
import mqttagent.services.C8yAgent;
import mqttagent.services.MQTTClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;

@Slf4j
@Service
public class GenericCallback implements MqttCallback {

    @Autowired
    C8yAgent c8yAgent;

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    SysHandler sysHandler;

    @Override
    public void connectionLost(Throwable throwable) {
        log.error("Connection Lost to MQTT Broker: ", throwable);
        subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
            c8yAgent.createEvent("Connection lost to MQTT Broker", "mqtt_status_event", DateTime.now(), null);
        });
        mqttClient.reconnect();
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadString = mqttMessage.getPayload() != null ? new String(mqttMessage.getPayload(), Charset.defaultCharset()) : "";

                // find appropriate mapping
                // subscriptionsService.runForEachTenant( (tenant) -> {
                //     c8yAgent.createEvent(payloadString, topic, DateTime.now(), null);
                // });
                // byte[] payload = Base64.getEncoder().encode(mqttMessage.getPayload());
                log.info("Message received on topic {} with message {}", topic, payloadString);
                subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                    c8yAgent.createEvent(payloadString, topic, DateTime.now(), null);
                });
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
