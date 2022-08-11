package mqttagent.callbacks;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callbacks.handler.SysHandler;
import mqttagent.configuration.MQTTMapping;
import mqttagent.configuration.MQTTMappingSubstitution;
import mqttagent.services.C8yAgent;
import mqttagent.services.MQTTClient;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

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

    static String TOKEN_DEVICE_TOPIC = "$.TOPIC";

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadMessage = mqttMessage.getPayload() != null
                        ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                        : "";
                String wildcardTopic = topic.replaceFirst("([^///]+$)", "#");
                String deviceIdentifier = topic.replaceFirst("^(.*[\\/])", "");
                log.info("Message received on topic {},{},{} with message {}", topic, deviceIdentifier, wildcardTopic, payloadMessage);

                Map<String, MQTTMapping> mappings = mqttClient.getMappingsPerTenant(c8yAgent.tenant);
                MQTTMapping map1 = mappings.get(topic);
                log.info("Looking for exact matching of topics: {},{},{}", c8yAgent.tenant, topic, map1);
                if (map1 != null) {
                    subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                        var payloadTarget = map1.target;
                        for ( MQTTMappingSubstitution sub:  map1.substitutions) {
                            var substitute = "";
                            try {
                                if (sub.pathSource.equals(TOKEN_DEVICE_TOPIC)
                                    && deviceIdentifier != null 
                                    && !deviceIdentifier.equals("")) {
                                    substitute = deviceIdentifier;
                                } else {
                                    substitute = (String) JsonPath.parse(payloadMessage).read(sub.pathTarget);
                                }
                            } catch (PathNotFoundException p){
                                log.error("No substitution for: {}, {}, {}", sub.pathSource, payloadTarget, payloadMessage);                   
                            }
                            //TODO use dot notation
                            //payloadTarget = payloadTarget.replaceAll(Pattern.quote(sub.name), substitute);
                        }
                        log.info("Posting payload: {}", payloadTarget);
                        c8yAgent.createC8Y_MEA(map1.targetAPI, payloadTarget);
                    });
                } else {
                    // exact topic not found, look for topic without device identifier
                    // e.g. /temperature/9090 -> /temperature/#
                    MQTTMapping map2 = mappings.get(wildcardTopic);
                    log.info("Looking for wildcard matching of topics: {},{},{}", c8yAgent.tenant, wildcardTopic, map2);
                    if (map2 != null) {
                        subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                            var payloadTarget = map2.target;
                            for ( MQTTMappingSubstitution sub:  map2.substitutions) {
                                var substitute = "";
                                try {
                                    if (sub.pathSource.equals(TOKEN_DEVICE_TOPIC)
                                        && map2.topic.endsWith("#")
                                        && deviceIdentifier != null 
                                        && !deviceIdentifier.equals("")) {
                                        substitute = deviceIdentifier;
                                    } else {
                                        substitute = (String) JsonPath.parse(payloadMessage).read(sub.pathSource);
                                    }
                                } catch (PathNotFoundException p){
                                  log.error("No substitution for: {}, {}, {}", sub.pathSource, payloadTarget, payloadMessage);
                                }
                                //TODO use dot notation
                                //payloadTarget = payloadTarget.replaceAll(Pattern.quote(sub.pathSource), substitute);
                            }
                            log.info("Posting payload: {}", payloadTarget);
                            c8yAgent.createC8Y_MEA(map2.targetAPI, payloadTarget);
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
