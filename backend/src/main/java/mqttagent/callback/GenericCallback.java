package mqttagent.callback;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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
import mqttagent.callback.handler.SysHandler;
import mqttagent.core.C8yAgent;
import mqttagent.model.API;
import mqttagent.model.Mapping;
import mqttagent.model.MappingNode;
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

                try {
                    ProcessingContext ctx = resolveMap(topic, payloadMessage);
                    handleNewPayload(ctx, payloadMessage);

                } catch (Exception e) {
                    log.warn("Message could NOT be parsed, ignoring this message");
                    e.printStackTrace();

                }
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    private ProcessingContext resolveMap(String topic, String payloadMessage) throws ResolveException {
        ProcessingContext context = new ProcessingContext();
        log.info("Message received on topic {} with message {}", topic,
                payloadMessage);

        ArrayList<String> levels = new ArrayList<String>(Arrays.asList(topic.split("/")));
        TreeNode node = mqttClient.getActiveMappings().resolveTopicPath(levels);
        if (node instanceof MappingNode) {
            context.setMapping(((MappingNode) node).getMapping());
            // if (!context.getMapping().targetAPI.equals(API.INVENTORY)) {
            ArrayList<String> topicLevels = new ArrayList<String>(Arrays.asList(topic.split("/")));
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
            log.info("Adding snoopedTemplate to map: {},{},{}", mapping.topic, mapping.snoopedTemplates.size(),
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
            ArrayList<String> resultDeviceIdentifier = new ArrayList<String>();
            for (MappingSubstitution sub : mapping.substitutions) {
                JsonNode extractedSourceContent = null;
                /*
                 * step 1 extract content from incoming payload
                 */
                try {
                    // escape _DEVICE_IDENT_ with BACKQUOTE "`"
                    sub.pathSource = sub.pathSource.replace (TOKEN_DEVICE_TOPIC, TOKEN_DEVICE_TOPIC_BACKQUOTE);

                    Expressions expr = Expressions.parse(sub.pathSource);
                    extractedSourceContent = expr.evaluate(payloadJsonNode);
                    /*
                     * if ((sub.pathSource).equals(TOKEN_DEVICE_TOPIC)) {
                     * if (ctx.isDeviceIdentifierValid()) {
                     * extractedSourceContent = new TextNode (ctx.getDeviceIdentifier());
                     * } else {
                     * throw new ProcessingException("No device identifier found for: " +
                     * sub.pathSource);
                     * }
                     * } else {
                     * Expressions expr = Expressions.parse(sub.pathSource);
                     * extractedSourceContent = expr.evaluate(payloadJsonNode);
                     * }
                     */
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
                    if (sub.definesIdentifier && mapping.targetAPI.equals(API.INVENTORY)) {
                        if (extractedSourceContent.isArray()) {
                            // extracted result from sourcPayload is an array, so we potentially have to
                            // iterate over the result, e.g. creating multiple devices
                            for (JsonNode jn : extractedSourceContent) {
                                if (jn.isTextual()) {
                                    resultDeviceIdentifier.add(jn.textValue());
                                    substitute = jn.textValue();
                                } else {
                                    log.warn("Since result is not textual it is ignored: {}, {}, {}, {}", jn.asText());
                                }
                            }
                            // create only one device
                        } else if (extractedSourceContent.isTextual()) {
                            resultDeviceIdentifier.add(extractedSourceContent.textValue());
                            substitute = extractedSourceContent.textValue();
                        }
                    } else if (extractedSourceContent.isTextual()) {
                        substitute = extractedSourceContent.textValue();
                    } else {
                        try {
                            substitute = objectMapper.writeValueAsString(extractedSourceContent);
                        } catch (JsonProcessingException e) {
                            log.error("JsonProcessingException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                                    payloadMessage, e);
                        }
                    }
                    log.info("Evaluated substitution {} for: pathSource {},  pathTarget {}, {}, {}, {}", substitute,
                            sub.pathSource, sub.pathTarget, payloadTarget,
                            payloadMessage, mapping.targetAPI.equals(API.INVENTORY));
                }

                /*
                 * step 3 replace target with extract content from incoming payload
                 */
                String[] pathTarget = sub.pathTarget.split(Pattern.quote("."));
                if (pathTarget == null) {
                    pathTarget = new String[] { sub.pathTarget };
                }
                if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    if (sub.pathTarget.equals(SOURCE_ID)
                            && mapping.mapDeviceIdentifier
                            && sub.definesIdentifier) {
                        substitute = resolveExternalId(substitute, mapping.externalIdType);
                        if (substitute == null) {
                            throw new RuntimeException("External id " + substitute + " for type "
                                    + mapping.externalIdType + " not found!");
                        }
                    }
                    substituteValue(substitute, payloadTarget, pathTarget);
                } else {
                    if (!sub.pathTarget.equals(TOKEN_DEVICE_TOPIC)) {
                        // avoid substitution, since _DEVICE_IDENT_ since not present in target payload
                        // for inventory
                        substituteValue(substitute, payloadTarget, pathTarget);
                    }
                }
            }
            /*
             * step 4 send target payload to c8y
             */
            log.info("Posting payload: {}, {}, {}", payloadTarget, mapping.targetAPI.equals(API.INVENTORY),
                    resultDeviceIdentifier.size());
            if (resultDeviceIdentifier.size() > 0 && mapping.targetAPI.equals(API.INVENTORY)) {
                resultDeviceIdentifier.forEach(d -> {
                    c8yAgent.upsertDevice(payloadTarget.toString(), d, mapping.externalIdType);
                });
            } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
                c8yAgent.createMEA(mapping.targetAPI, payloadTarget.toString());
            } else {
                log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI.equals(API.INVENTORY),
                        resultDeviceIdentifier.size());
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

    public JSONObject substituteValue(String value, JSONObject jsonObject, String[] keys) throws JSONException {
        String currentKey = keys[0];

        if (keys.length == 1) {
            return jsonObject.put(currentKey, value);
        } else if (!jsonObject.has(currentKey)) {
            throw new JSONException(currentKey + "is not a valid key.");
        }

        JSONObject nestedJsonObjectVal = jsonObject.getJSONObject(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        JSONObject updatedNestedValue = substituteValue(value, nestedJsonObjectVal, remainingKeys);
        return jsonObject.put(currentKey, updatedNestedValue);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }
}
