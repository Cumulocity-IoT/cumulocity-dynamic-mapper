package mqttagent.callbacks;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callbacks.handler.SysHandler;
import mqttagent.services.C8yAgent;
import mqttagent.services.MQTTClient;
import mqttagent.services.MQTTMapping;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

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

    static SimpleDateFormat sdf;
    static {
        sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        sdf.setTimeZone(TimeZone.getTimeZone("CET"));
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadString = mqttMessage.getPayload() != null
                        ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                        : "";
                String normalizedTopic = topic.replaceFirst("([^///]+$)", "#");
                String deviceIdentifier = topic.replaceFirst("^(.*[\\/])", "");
                log.info("Message received on topic {},{} with message {}", topic, normalizedTopic, payloadString);

                // find appropriate mapping
                // subscriptionsService.runForEachTenant( (tenant) -> {
                // c8yAgent.createEvent(payloadString, topic, DateTime.now(), null);
                // });
                // byte[] payload = Base64.getEncoder().encode(mqttMessage.getPayload());

                Map<String, MQTTMapping> mappings = mqttClient.getMappingsPerTenant(c8yAgent.tenant);
                
                MQTTMapping map1 = mappings.get(topic);
                log.info("Looking for appropriate mappings I: {},{},{}", c8yAgent.tenant, topic, map1);
                if (map1 != null) {
                    subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                        String payload = "{\"source\": {    \"id\":\"490229\" }, \"type\": \"c8y_LoraBellEvent\",  \"text\": \"Elevator was called\",  \"time\": \"current_time\"}";
                        String date = sdf.format(new Date());
                        payload = payload.replaceFirst("current_time", date);
                        log.info("Posting payload: {}", payload);
                        c8yAgent.createC8Y_MEA(map1.targetAPI, payload);
                    });
                } else {
                    // exact topic not found, look for topic without device identifier
                    // e.g. /temperature/9090 -> /temperature/#
                    MQTTMapping map2 = mappings.get(normalizedTopic);
                    log.info("Looking for appropriate mappings II: {},{},{}", c8yAgent.tenant, normalizedTopic, map2);
                    if (map2 != null) {
                        subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                            String payload = "{\"source\": {    \"id\":\"490229\" }, \"type\": \"c8y_LoraBellEvent\",  \"text\": \"Elevator was called\",  \"time\": \"current_time\"}";
                            String date = sdf.format(new Date());
                            payload = payload.replaceFirst("current_time", date);
                            c8yAgent.createC8Y_MEA(map2.targetAPI, payload);
                        });

                    }
                }
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
