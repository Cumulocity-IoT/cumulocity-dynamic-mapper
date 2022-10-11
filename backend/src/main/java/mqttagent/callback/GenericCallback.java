package mqttagent.callback;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callback.GenericCallback.SubstituteValue.TYPE;
import mqttagent.callback.handler.SysHandler;
import mqttagent.core.C8yAgent;
import mqttagent.model.API;
import mqttagent.model.Mapping;
import mqttagent.model.MappingNode;
import mqttagent.model.MappingStatus;
import mqttagent.model.MappingSubstitution;
import mqttagent.model.MappingsRepresentation;
import mqttagent.model.ProcessingContext;
import mqttagent.model.ResolveException;
import mqttagent.model.SnoopStatus;
import mqttagent.model.TreeNode;
import mqttagent.service.MQTTClient;

@Slf4j
@Service
public class GenericCallback implements MqttCallback {

    static class SubstituteValue {
        static enum TYPE  {
            NUMBER,
            TEXTUAL
        }
        public String value;
        public TYPE type;
        public SubstituteValue(String value, TYPE type) {
            this.type = type;
            this.value = value;
        }
        public Object typedValue() {
            if (type.equals(TYPE.TEXTUAL)) {
                return value;
            } else {
                //check if int
                try{
                    return Integer.parseInt(value );
                } catch(NumberFormatException e1){
                    //not int
                    try{
                        Float.parseFloat(value);
                    }catch(NumberFormatException e2){
                        return null;
                    }
                }
            }
            return null;
        }
    }

    private static final String TIME = "time";

    @Autowired
    C8yAgent c8yAgent;

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    SysHandler sysHandler;

    @Autowired
    ObjectMapper objectMapper;

    @Override
    public void connectionLost(Throwable throwable) {
        log.error("Connection Lost to MQTT Broker: ", throwable);
        c8yAgent.createEvent("Connection lost to MQTT Broker", "mqtt_status_event", DateTime.now(), null);
        mqttClient.reconnect();
    }

    static SimpleDateFormat sdf;
    static {
        sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        sdf.setTimeZone(TimeZone.getTimeZone("CET"));
    }

    static String TOKEN_DEVICE_TOPIC = "_DEVICE_IDENT_";
    static String TOKEN_DEVICE_TOPIC_BACKQUOTE = "`_DEVICE_IDENT_`";
    static int SNOOP_TEMPLATES_MAX = 5;
    static String SOURCE_ID = "source.id";

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadMessage = (mqttMessage.getPayload() != null
                        ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                        : "");
                ProcessingContext ctx = null;
                try {
                    ctx = resolveMap(topic, payloadMessage);
                } catch (Exception e) {
                    log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message.");
                    e.printStackTrace();
                    MappingStatus st = mqttClient.getMonitoring().get(MQTTClient.KEY_MONITORING_UNSPECIFIED);
                    st.errors++;
                    mqttClient.getMonitoring().put(MQTTClient.KEY_MONITORING_UNSPECIFIED, st);
                }

                if (ctx != null) {
                    Mapping map = ctx.getMapping();
                    MappingStatus st = mqttClient.getMonitoring().get(map.id);
                    try {
                        handleNewPayload(ctx, payloadMessage);
                        st.messagesReceived++;
                        if (map.snoopTemplates == SnoopStatus.ENABLED || map.snoopTemplates == SnoopStatus.STARTED) {
                            st.snoopedTemplatesTotal++;
                            st.snoopedTemplatesActive = map.snoopedTemplates.size();
                        }
                        mqttClient.getMonitoring().put(map.id, st);
                    } catch (Exception e) {
                        log.warn("Message could NOT be parsed, ignoring this message.");
                        e.printStackTrace();
                        st.errors++;
                        mqttClient.getMonitoring().put(MQTTClient.KEY_MONITORING_UNSPECIFIED, st);
                    }
                }
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    private ProcessingContext resolveMap(String topic, String payloadMessage) throws ResolveException {
        ProcessingContext context = new ProcessingContext();
        log.info("Message received on topic '{}'  with message {}", topic,
                payloadMessage);
        ArrayList<String> levels = new ArrayList<String>(Arrays.asList(topic.split(TreeNode.SPLIT_TOPIC_REGEXP)));
        TreeNode node = mqttClient.getActiveMappings().resolveTopicPath(levels);
        if (node instanceof MappingNode) {
            context.setMapping(((MappingNode) node).getMapping());
            // if (!context.getMapping().targetAPI.equals(API.INVENTORY)) {
            ArrayList<String> topicLevels = new ArrayList<String>(
                    Arrays.asList(topic.split(TreeNode.SPLIT_TOPIC_REGEXP)));
            if (context.getMapping().indexDeviceIdentifierInTemplateTopic >= 0) {
                String deviceIdentifier = topicLevels
                        .get((int) (context.getMapping().indexDeviceIdentifierInTemplateTopic));
                log.info("Resolving deviceIdentifier: {}, {} to {}", topic,
                        context.getMapping().indexDeviceIdentifierInTemplateTopic, deviceIdentifier);
                context.setDeviceIdentifier(deviceIdentifier);
            }
            // }
        } else {
            throw new ResolveException("Could not find appropriate mapping for topic: " + topic);
        }
        return context;
    }

    private void handleNewPayload(ProcessingContext ctx, String payloadMessage) throws ProcessingException {
        Mapping mapping = ctx.getMapping();
        String deviceIdentifier = ctx.getDeviceIdentifier();
        if (mapping.snoopTemplates.equals(SnoopStatus.ENABLED) || mapping.snoopTemplates.equals(SnoopStatus.STARTED)) {
            mapping.snoopedTemplates.add(payloadMessage);
            if (mapping.snoopedTemplates.size() >= SNOOP_TEMPLATES_MAX) {
                // remove oldest payload
                mapping.snoopedTemplates.remove(0);
            } else {
                mapping.snoopTemplates = SnoopStatus.STARTED;
            }
            log.info("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                    mapping.snoopedTemplates.size(),
                    mapping.snoopTemplates);
            mqttClient.setMappingDirty(mapping);
        } else {

            /*
             * step 0 patch payload with dummy property _DEVICE_IDENT_ in case of a wildcard
             * in the template topic
             */
            JsonNode payloadJsonNode;
            try {
                payloadJsonNode = objectMapper.readTree(payloadMessage);
                boolean containsWildcardTemplateTopic = MappingsRepresentation
                        .isWildcardTopic(mapping.getTemplateTopic());
                if (containsWildcardTemplateTopic && payloadJsonNode instanceof ObjectNode) {
                    ((ObjectNode) payloadJsonNode).put(TOKEN_DEVICE_TOPIC, deviceIdentifier);
                }
                payloadMessage = payloadJsonNode.toPrettyString();
                log.info("Patched payload:{}, {}", containsWildcardTemplateTopic, payloadMessage);
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException parsing: {}, {}", payloadMessage, e);
                throw new ProcessingException("JsonProcessingException parsing: " + payloadMessage + " exception:" + e);
            }

            var payloadTarget = new JSONObject(mapping.target);
            Map<String, ArrayList<SubstituteValue>> postProcessingCache = new HashMap<String, ArrayList<SubstituteValue>>();
            boolean substitutionTimeExists = false;
            for (MappingSubstitution sub : mapping.substitutions) {
                JsonNode extractedSourceContent = null;
                /*
                 * step 1 extract content from incoming payload
                 */
                try {
                    // escape _DEVICE_IDENT_ with BACKQUOTE "`"
                    var p = sub.pathSource.replace(TOKEN_DEVICE_TOPIC, TOKEN_DEVICE_TOPIC_BACKQUOTE);
                    log.info("Patched sub.pathSource: {}, {}", sub.pathSource, p);
                    Expressions expr = Expressions.parse(p);
                    extractedSourceContent = expr.evaluate(payloadJsonNode);
                } catch (ParseException | IOException | EvaluateException e) {
                    log.error("Exception for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                            payloadMessage, e);
                } catch (EvaluateRuntimeException e) {
                    log.error("EvaluateRuntimeException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                            payloadMessage, e);
                }

                /*
                 * step 2 analyse exctracted content: textual, array
                 */
                var substitute = "";
                if (extractedSourceContent == null) {
                    log.error("No substitution for: {}, {}, {}", sub.pathSource, payloadTarget,
                            payloadMessage);
                } else {
                    var key = (sub.pathTarget.equals(TOKEN_DEVICE_TOPIC) ? SOURCE_ID : sub.pathTarget);
                    ArrayList<SubstituteValue> pl = postProcessingCache.getOrDefault(key,
                            new ArrayList<SubstituteValue>());
                    if (extractedSourceContent.isArray()) {
                        // extracted result from sourcPayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (JsonNode jn : extractedSourceContent) {
                            if (jn.isTextual()){
                                pl.add(new SubstituteValue(jn.textValue(), TYPE.TEXTUAL));
                            } else if (jn.isNumber()){
                                pl.add(new SubstituteValue(jn.numberValue().toString(), TYPE.NUMBER));
                            } else {
                                log.warn("Since result is not textual or number it is ignored: {}, {}, {}, {}", jn.asText());
                            }
                        }
                        postProcessingCache.put(key, pl);
                    } else if (extractedSourceContent.isTextual()) {
                        // extracted result from sourcPayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        pl.add(new SubstituteValue(extractedSourceContent.textValue(), TYPE.TEXTUAL));
                        postProcessingCache.put(key, pl);
                    } else if (extractedSourceContent.isNumber()) {
                        // extracted result from sourcPayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        pl.add(new SubstituteValue(extractedSourceContent.numberValue().toString(), TYPE.NUMBER));
                        postProcessingCache.put(key, pl);
                    } else {
                        log.warn("Ignoring this substitution, sone no objects are allowed for: {}, {}, {}, {}",
                                sub.pathSource, substitute);
                    }
                    log.info("Evaluated substitution (pathSource, substitute): ({},{}), pathTarget: {}, {}, {}, {}",
                            sub.pathSource, substitute, sub.pathTarget, payloadTarget,
                            payloadMessage, mapping.targetAPI.equals(API.INVENTORY));
                }

                if (sub.pathTarget.equals(TIME)) {
                    substitutionTimeExists = true;
                }
            }

            // no substitution fot the time property exists, then use the system time
            if (!substitutionTimeExists) {
                ArrayList<SubstituteValue> pl = postProcessingCache.getOrDefault(TIME,
                        new ArrayList<SubstituteValue>());
                pl.add(new SubstituteValue(new DateTime().toString(), TYPE.TEXTUAL));
                postProcessingCache.put(TIME, pl);
            }

            ArrayList<SubstituteValue> listIdentifier = postProcessingCache.get(SOURCE_ID);
            Set<String> pathTargets = postProcessingCache.keySet();
            if (listIdentifier == null) {
                throw new RuntimeException("Identified mapping has no substitution for source.id defined!");
            }
            int i = 0;
            for (SubstituteValue device : listIdentifier) {
                /*
                 * step 3 replace target with extract content from incoming payload
                 */
                for (String pathTarget : pathTargets) {
                    SubstituteValue substitute = new SubstituteValue("NOT_DEFINED", TYPE.TEXTUAL);
                    if (i < postProcessingCache.get(pathTarget).size()) {
                        substitute = postProcessingCache.get(pathTarget).get(i);
                    } else if (postProcessingCache.get(pathTarget).size() == 1) {
                        // this is an indication that the substitution is the same for all
                        // events/alarms/measurements/inventory
                        substitute = postProcessingCache.get(pathTarget).get(0);
                    }
                    String[] pt = pathTarget.split(Pattern.quote("."));
                    if (pt == null) {
                        pt = new String[] { pathTarget };
                    }
                    if (!mapping.targetAPI.equals(API.INVENTORY)) {
                        if (pathTarget.equals(SOURCE_ID)) {
                            var sourceId = resolveExternalId(substitute.value, mapping.externalIdType);
                            if (sourceId == null && mapping.createNonExistingDevice) {

                                var d = c8yAgent.upsertDevice("device_" + mapping.externalIdType + "_" + substitute.value,
                                        "c8y_MQTTMapping_generated_type", substitute.value, mapping.externalIdType);
                                substitute.value = d.getId().getValue();
                            } else if (sourceId == null) {
                                throw new RuntimeException("External id " + substitute + " for type "
                                        + mapping.externalIdType + " not found!");
                            } else {
                                substitute.value = sourceId;
                            }
                        }
                        substituteValue(substitute, payloadTarget, pt);
                    } else if (!pathTarget.equals(SOURCE_ID)) {
                        substituteValue(substitute, payloadTarget, pt);
                    }
                }
                /*
                 * step 4 send target payload to c8y
                 */
                if (mapping.targetAPI.equals(API.INVENTORY)) {
                    String[] errors = { "" };
                    try {
                        c8yAgent.upsertDevice(payloadTarget.toString(), device.value, mapping.externalIdType);
                    } catch (ProcessingException e) {
                        errors[0] = e.getMessage();
                    }
                    if (!errors[0].equals("")) {
                        throw new ProcessingException(errors[0]);
                    }
                } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    c8yAgent.createMEA(mapping.targetAPI, payloadTarget.toString());
                } else {
                    log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI.equals(API.INVENTORY),
                            postProcessingCache.size());
                }
                log.info("Posted payload: {}, {}, {}", payloadTarget, mapping.targetAPI.equals(API.INVENTORY),
                        listIdentifier.size());
                i++;
            }
        }
    }

    private String resolveExternalId(String externalId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getExternalId(externalId, externalIdType);
        String id = null;
        GId gid = null;
        if (extId != null) {
            gid = extId.getManagedObject().getId();
            id = gid.getValue();
        }
        log.info("Found id {} for external id: {}, {},  {}", id, gid, externalId);
        return id;
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
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
