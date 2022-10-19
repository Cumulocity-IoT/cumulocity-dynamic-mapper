package mqtt.mapping.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingNode;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.SnoopStatus;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.processor.handler.SysHandler;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public abstract class PayloadProcessor implements MqttCallback {

    @Autowired
    protected C8yAgent c8yAgent;

    @Autowired
    protected MQTTClient mqttClient;

    @Autowired
    SysHandler sysHandler;

    public static String SOURCE_ID = "source.id";
    public static String TOKEN_DEVICE_TOPIC = "_DEVICE_IDENT_";
    public static String TOKEN_DEVICE_TOPIC_BACKQUOTE = "`_DEVICE_IDENT_`";
    public static String TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
    public static String TOKEN_TOPIC_LEVEL_BACKQUOTE = "`_TOPIC_LEVEL_`";

    public static final String TIME = "time";

    public abstract String deserializePayload(MqttMessage mqttMessage);

    public abstract ArrayList<TreeNode> resolveMapping(String topic, String payloadMessage) throws ResolveException;

    public abstract void transformPayload(ProcessingContext ctx, String payloadMessage, boolean send) throws ProcessingException;

    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadMessage = deserializePayload(mqttMessage);
                processPayload(topic, payloadMessage, true);
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    public ArrayList<ProcessingContext> processPayload(String topic, String payloadMessage, boolean sendPayload)  {
        ArrayList<TreeNode> nodes = new ArrayList<TreeNode>();
        ArrayList<ProcessingContext> processingResult = new ArrayList<ProcessingContext>();

        try {
            nodes = resolveMapping(topic, payloadMessage);
        } catch (Exception e) {
            log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message.");
            e.printStackTrace();
            MappingStatus ms = mqttClient.getMappingStatus(null, true);
            ms.errors++;
        }

        for (TreeNode node : nodes) {
            ProcessingContext ctx = new ProcessingContext();
            if (node instanceof MappingNode) {
                ctx.setMapping(((MappingNode) node).getMapping());
                Mapping map = ctx.getMapping();
                ArrayList<String> topicLevels = TreeNode.splitTopic(topic);
                if (map.indexDeviceIdentifierInTemplateTopic >= 0) {
                    String deviceIdentifier = topicLevels
                            .get((int) (map.indexDeviceIdentifierInTemplateTopic));
                    log.info("Resolving deviceIdentifier: {}, {} to {}", topic,
                            map.indexDeviceIdentifierInTemplateTopic, deviceIdentifier);
                    ctx.setDeviceIdentifier(deviceIdentifier);
                }
                MappingStatus ms = mqttClient.getMappingStatus(map, false);
                try {
                    ms.messagesReceived++;
                    if (map.snoopStatus == SnoopStatus.ENABLED
                            || map.snoopStatus == SnoopStatus.STARTED) {
                        ms.snoopedTemplatesActive++;
                        ms.snoopedTemplatesTotal = map.snoopedTemplates.size();
                        map.addSnoopedTemplate(payloadMessage);

                        log.info("Adding snoopedTemplate to map: {},{},{}", map.subscriptionTopic,
                                map.snoopedTemplates.size(),
                                map.snoopStatus);
                        mqttClient.setMappingDirty(map);
                    } else {
                        transformPayload(ctx, payloadMessage, sendPayload);
                        if ( ctx.hasError() || ctx.getRequests().stream().anyMatch(r -> r.hasError())) {
                            ms.errors++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Message could NOT be parsed, ignoring this message.");
                    e.printStackTrace();
                    ms.errors++;
                }
            } else {
                ctx.setError(new ResolveException("Could not find appropriate mapping for topic: " + topic));
            }
            processingResult.add(ctx);
        }
        return processingResult;
    }

    // public void sendC8YRequests(ProcessingContext ctx) {
    //     // send target payload to c8y
    //     for (C8YRequest request : ctx.getRequests()) {
    //         try {
    //             if (request.getTargetAPI().equals(API.INVENTORY) && !request.isAlreadySubmitted()) {
    //                 c8yAgent.upsertDevice(request.getPayload(), request.getSource(), request.getExternalIdType());
    //             } else if (!request.getTargetAPI().equals(API.INVENTORY) && !request.isAlreadySubmitted()) {
    //                 c8yAgent.createMEA(request.getTargetAPI(), request.getPayload());
    //             }
    //         } catch (ProcessingException error) {
    //             request.setError(error);
    //         }
    //     }
    // }

    public String resolveExternalId(String externalId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getExternalId(externalId, externalIdType);
        String id = null;
        if (extId != null) {
            id = extId.getManagedObject().getId().getValue();
        }
        log.info("Found id {} for external id: {}", id, externalId);
        return id;
    }

    public JSONObject substituteValue(SubstituteValue sub, JSONObject jsonObject, String key) throws JSONException {
        String[] pt = key.split(Pattern.quote("."));
        if (pt == null) {
            pt = new String[] { key };
        }
        return substituteValue(sub, jsonObject, pt);
    }

    public JSONObject substituteValue(SubstituteValue sub, JSONObject jsonObject, String[] keys) throws JSONException {
        String currentKey = keys[0];

        if (keys.length == 1) {
            return jsonObject.put(currentKey, sub.typedValue());
        } else if (!jsonObject.has(currentKey)) {
            throw new JSONException(currentKey + "is not a valid key.");
        }

        JSONObject nestedJsonObjectVal = jsonObject.getJSONObject(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        JSONObject updatedNestedValue = substituteValue(sub, nestedJsonObjectVal, remainingKeys);
        return jsonObject.put(currentKey, updatedNestedValue);
    }

    @Override
    public void connectionLost(Throwable throwable) {
        log.error("Connection Lost to MQTT broker: ", throwable);
        c8yAgent.createEvent("Connection lost to MQTT broker", "mqtt_status_event", DateTime.now(), null);
        mqttClient.submitConnect();
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
    }

}
