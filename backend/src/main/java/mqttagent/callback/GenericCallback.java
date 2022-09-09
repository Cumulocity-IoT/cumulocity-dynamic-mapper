package mqttagent.callback;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.commons.lang3.mutable.MutableBoolean;
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
/* 
used for JSONPath in sourcePath definitions
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
*/
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqttagent.callback.handler.SysHandler;
import mqttagent.core.C8yAgent;
import mqttagent.model.Mapping;
import mqttagent.model.MappingSubstitution;
import mqttagent.model.MappingNode;
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

    static String TOKEN_DEVICE_TOPIC = "TOPIC";
    static int SNOOP_TEMPLATES_MAX = 5;

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                String payloadMessage = (mqttMessage.getPayload() != null
                        ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                        : "");
                ProcessingContext ctx = resolveMap(topic, payloadMessage);
                handleNewPayload(ctx, payloadMessage);
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }
    }

    private ProcessingContext resolveMap(String topic, String payloadMessage) throws ResolveException {
        ProcessingContext context = new ProcessingContext();
        log.info("Message received on topic {} with message {}", topic ,
                payloadMessage);

        ArrayList<String> levels = new ArrayList<String>(Arrays.asList(topic.split("/")));
        TreeNode node = mqttClient.getActiveMappings().resolveTopicPath(levels);
        if ( node instanceof MappingNode) {
            context.setMapping(((MappingNode) node).getMapping());
            ArrayList<String> topicLevels = new ArrayList<String> (Arrays.asList(topic.split("/")));
            log.info("Resolving deviceIdentifier: {}, {}", topic, context.getMapping().indexDeviceIdentifierInTemplateTopic );
            String deviceIdentifier = topicLevels.get((int) (context.getMapping().indexDeviceIdentifierInTemplateTopic));
            context.setDeviceIdentifier(deviceIdentifier);
        } else {
            throw new ResolveException ("Could not find appropriate mapping for topic: " + topic);
        }
        return context;
    }

    private void handleNewPayload(ProcessingContext ctx, String payloadMessage) {
        if (ctx.getMapping().snoopTemplates.equals(SnoopStatus.ENABLED) || ctx.getMapping().snoopTemplates.equals(SnoopStatus.STARTED)) {
            ctx.getMapping().snoopedTemplates.add(payloadMessage);
            if (ctx.getMapping().snoopedTemplates.size() >= SNOOP_TEMPLATES_MAX) {
                // remove oldest payload
                ctx.getMapping().snoopedTemplates.remove(0);
            } else {
                ctx.getMapping().snoopTemplates = SnoopStatus.STARTED;
            }
            log.info("Adding snoopedTemplate to map: {},{},{}", ctx.getMapping().topic, ctx.getMapping().snoopedTemplates.size(),
                    ctx.getMapping().snoopTemplates);
            mqttClient.setMappingDirty(ctx.getMapping());
        } else {
            var payloadTarget = new JSONObject(ctx.getMapping().target);
            for (MappingSubstitution sub : ctx.getMapping().substitutions) {
                var substitute = "";
                /* 
                used for JSONata in sourcePath definitions
                */ 
                try {
                    if ((sub.pathSource).equals(TOKEN_DEVICE_TOPIC)
                    && ctx.getDeviceIdentifier() != null
                    && !ctx.getDeviceIdentifier().equals("")) {
                        substitute = ctx.getDeviceIdentifier();
                    } else {
                        Expressions expr = Expressions.parse(sub.pathSource);
                        JsonNode jsonObj = objectMapper.readTree(payloadMessage);
                        JsonNode result = expr.evaluate(jsonObj);
                        if (result == null) {
                            log.error("No substitution for: {}, {}, {}", sub.pathSource, payloadTarget,
                            payloadMessage);
                        } else {
                            if (result.isTextual()) {
                                substitute = result.textValue();
                            } else {
                                substitute = objectMapper.writeValueAsString(result);
                            }
                            log.info("Evaluated substitution {} for: {}, {}, {}", substitute, sub.pathSource, payloadTarget,
                            payloadMessage);
                        }
                    }
                } catch (ParseException e) {
                    log.error("ParseException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                    payloadMessage, e);
                } catch (EvaluateRuntimeException e) {
                    log.error("EvaluateRuntimeException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                    payloadMessage, e);
                } catch (JsonProcessingException e) {
                    log.error("JsonProcessingException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                    payloadMessage, e);
                } catch (IOException e) {
                    log.error("IOException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                    payloadMessage, e);
                } catch (EvaluateException e) {
                    log.error("EvaluateException for: {}, {}, {}, {}", sub.pathSource, payloadTarget,
                    payloadMessage, e);
                }

                String[] pathTarget = sub.pathTarget.split(Pattern.quote("."));
                if (pathTarget == null) {
                    pathTarget = new String[] { sub.pathTarget };
                }
                if (sub.pathTarget.equals("source.id") && ctx.getMapping().mapDeviceIdentifier) {
                    var deviceId = resolveExternalId(substitute, ctx.getMapping().externalIdType);
                    if (deviceId == null) {
                        throw new RuntimeException("External id " + deviceId + " for type "
                                + ctx.getMapping().externalIdType + " not found!");
                    }
                    substitute = deviceId;
                }
                substituteValue(substitute, payloadTarget, pathTarget);
            }
            log.info("Posting payload: {}", payloadTarget);
            c8yAgent.createC8Y_MEA(ctx.getMapping().targetAPI, payloadTarget.toString());
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
