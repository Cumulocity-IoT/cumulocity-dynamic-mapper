package mqttagent.callbacks;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callbacks.handler.SysHandler;
import mqttagent.model.MQTTMapping;
import mqttagent.model.MQTTMappingSubstitution;
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
import java.util.Arrays;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.json.JSONException;
import org.json.JSONObject;

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
                        var payloadTarget = new JSONObject (map1.target);
                        for ( MQTTMappingSubstitution sub:  map1.substitutions) {
                            var substitute = "";
                            try {
                                if (("$." + sub.pathSource).equals(TOKEN_DEVICE_TOPIC)
                                    && deviceIdentifier != null 
                                    && !deviceIdentifier.equals("")) {
                                    substitute = deviceIdentifier;
                                } else {
                                    substitute = (String) JsonPath.parse(payloadMessage).read("$." + sub.pathSource);
                                }
                            } catch (PathNotFoundException p){
                                log.error("No substitution for: {}, {}, {}", "$." + sub.pathSource, payloadTarget, payloadMessage);                   
                            }

                            String[] pathTarget = sub.pathTarget.split(Pattern.quote("."));
                            if (pathTarget == null) {
                                pathTarget = new String[] {sub.pathTarget};
                            }
                            if (sub.pathTarget.equals("source.id") && map1.mapDeviceIdentifier){
                                var deviceId = resolveExternalId(substitute, map1.externalIdType);
                                if (deviceId == null) {
                                    throw new RuntimeException ("External id " + deviceId + " for type " + map1.externalIdType + " not found!" );
                                }
                                substitute = deviceId;
                            }
                            addValue(substitute, payloadTarget, pathTarget);
                        }
                        log.info("Posting payload: {}", payloadTarget);
                        c8yAgent.createC8Y_MEA(map1.targetAPI, payloadTarget.toString());
                    });
                } else {
                    // exact topic not found, look for topic without device identifier
                    // e.g. /temperature/9090 -> /temperature/#
                    MQTTMapping map2 = mappings.get(wildcardTopic);
                    log.info("Looking for wildcard matching of topics: {},{},{}", c8yAgent.tenant, wildcardTopic, map2);
                    if (map2 != null) {
                        subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
                            var payloadTarget = new JSONObject (map2.target);
                            for ( MQTTMappingSubstitution sub:  map2.substitutions) {
                                var substitute = "";
                                try {
                                    if (("$." + sub.pathSource).equals(TOKEN_DEVICE_TOPIC)
                                        && map2.topic.endsWith("#")
                                        && deviceIdentifier != null 
                                        && !deviceIdentifier.equals("")) {
                                        substitute = deviceIdentifier;
                                    } else {
                                        substitute = (String) JsonPath.parse(payloadMessage).read("$." + sub.pathSource);
                                    }
                                } catch (PathNotFoundException p){
                                  log.error("No substitution for: {}, {}, {}", "$." + sub.pathSource, payloadTarget, payloadMessage);
                                }
                                String[] pathTarget = sub.pathTarget.split(Pattern.quote("."));
                                if (pathTarget == null) {
                                    pathTarget = new String[] {sub.pathTarget};
                                }
                                if (sub.pathTarget.equals("source.id") && map2.mapDeviceIdentifier){
                                    var deviceId = resolveExternalId(substitute, map2.externalIdType);
                                    if (deviceId == null) {
                                        throw new RuntimeException ("External id " + deviceId + " for type " + map2.externalIdType + " not found!" );
                                    }
                                    substitute = deviceId;
                                }
                                addValue(substitute, payloadTarget, pathTarget);
                            }
                            log.info("Posting payload: {}", payloadTarget);
                            c8yAgent.createC8Y_MEA(map2.targetAPI, payloadTarget.toString());
                        });

                    }
                }
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    private String resolveExternalId(String externalId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getExternalId(externalId, externalIdType);
        String id = null;
        if ( extId != null){
            id = c8yAgent.getExternalId(externalId, externalIdType).getExternalId();
        }
        log.info("Found id {} for external id: {}, {}", id, externalId);
        return id;
    }

    public JSONObject addValue(String value, JSONObject jsonObject, String[] keys) throws JSONException {
        String currentKey = keys[0];
        
        if (keys.length == 1) {
          return jsonObject.put(currentKey, value);
        } else if (!jsonObject.has(currentKey)) {
          throw new JSONException(currentKey + "is not a valid key.");
        }
    
        JSONObject nestedJsonObjectVal = jsonObject.getJSONObject(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        JSONObject updatedNestedValue = addValue(value, nestedJsonObjectVal, remainingKeys);
        return jsonObject.put(currentKey, updatedNestedValue);
      }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
