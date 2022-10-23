package mqtt.mapping.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    public abstract List<TreeNode> resolveMapping(ProcessingContext ctx) throws ResolveException;

    public abstract void transformPayload(ProcessingContext ctx, boolean send) throws ProcessingException;

    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                ProcessingContext context = new ProcessingContext();
                context.setPayload(deserializePayload(mqttMessage));
                context.setTopic(topic);
                processPayload(context, true);
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    public List<ProcessingContext> processPayload(ProcessingContext context, boolean sendPayload)  {
        List<TreeNode> nodes = new ArrayList<TreeNode>();
        List<ProcessingContext> processingResult = new ArrayList<ProcessingContext>();

        try {
            nodes = resolveMapping(context);
        } catch (Exception e) {
            log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message: {}", e);
            e.printStackTrace();
            MappingStatus mappingStatus = mqttClient.getMappingStatus(null, true);
            mappingStatus.errors++;
        }

        for (TreeNode node : nodes) {
            if (node instanceof MappingNode) {
                context.setMapping(((MappingNode) node).getMapping());
                Mapping mapping = context.getMapping();
                String payload = context.getPayload();
                MappingStatus mappingStatus = mqttClient.getMappingStatus(mapping, false);
                try {
                    mappingStatus.messagesReceived++;
                    if (mapping.snoopStatus == SnoopStatus.ENABLED
                            || mapping.snoopStatus == SnoopStatus.STARTED) {
                        mappingStatus.snoopedTemplatesActive++;
                        mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                        mapping.addSnoopedTemplate(payload);

                        log.info("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                                mapping.snoopedTemplates.size(),
                                mapping.snoopStatus);
                        mqttClient.setMappingDirty(mapping);
                    } else {
                        transformPayload(context, sendPayload);
                        if ( context.hasError() || context.getRequests().stream().anyMatch(r -> r.hasError())) {
                            mappingStatus.errors++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Message could NOT be parsed, ignoring this message.");
                    e.printStackTrace();
                    mappingStatus.errors++;
                }
            } else {
                context.setError(new ResolveException("Could not find appropriate mapping for topic: " + context.getTopic()));
            }
            processingResult.add(context);
        }
        return processingResult;
    }

    public String resolveExternalId(String externalId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getExternalId(externalId, externalIdType);
        String id = null;
        if (extId != null) {
            id = extId.getManagedObject().getId().getValue();
        }
        log.info("Found id {} for external id: {}", id, externalId);
        return id;
    }

    public JSONObject substituteValue(SubstituteValue sub, JSONObject jsonObject, String keys) throws JSONException {
        String[] splitKeys = keys.split(Pattern.quote("."));
        if (splitKeys == null) {
            splitKeys = new String[] { keys };
        }
        return substituteValue(sub, jsonObject, splitKeys);
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
