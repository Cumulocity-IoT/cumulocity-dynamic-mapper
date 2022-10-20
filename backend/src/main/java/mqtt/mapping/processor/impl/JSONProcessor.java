package mqtt.mapping.processor.impl;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.*;
import mqtt.mapping.processor.C8YRequest;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.impl.JSONProcessor.SubstituteValue.TYPE;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class JSONProcessor extends PayloadProcessor {

    static class SubstituteValue {
        static enum TYPE {
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
                // check if int
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e1) {
                    // not int
                    try {
                        return Float.parseFloat(value);
                    } catch (NumberFormatException e2) {
                        return null;
                    }
                }
            }
        }

        @Override
        public String toString() {
            return String.format("(%s,%s)", this.value, this.type.toString());
        }
    }

    @Autowired
    ObjectMapper objectMapper;

    @Override
    public String deserializePayload(MqttMessage mqttMessage) {
        String payloadMessage = null;
        if (mqttMessage.getPayload() != null) {
            payloadMessage = (mqttMessage.getPayload() != null
                    ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                    : "");

        }
        return payloadMessage;
    }

    @Override
    public ArrayList<TreeNode> resolveMapping(String topic, String payloadMessage) throws ResolveException {
        log.info("Message received on topic '{}'  with message {}", topic,
                payloadMessage);
        ArrayList<String> levels = new ArrayList<String>(Arrays.asList(topic.split(TreeNode.SPLIT_TOPIC_REGEXP)));
        ArrayList<TreeNode> nodes = mqttClient.getMappingTree().resolveTopicPath(levels);
        return nodes;
    }

    @Override
    public void transformPayload(ProcessingContext ctx, String payloadMessage) throws ProcessingException {
        Mapping mapping = ctx.getMapping();
        String deviceIdentifier = ctx.getDeviceIdentifier();

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
            ctx.setError(
                    new ProcessingException("JsonProcessingException parsing: " + payloadMessage + " exception:" + e));
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
            if (extractedSourceContent == null) {
//                log.error("No substitution for: {}, {}, {}", sub.pathSource, payloadTarget,
//                        payloadMessage);
                log.error("No substitution for: {}", sub.pathSource);
            } else {
                var key = (sub.pathTarget.equals(TOKEN_DEVICE_TOPIC) ? SOURCE_ID : sub.pathTarget);
                ArrayList<SubstituteValue> pl = postProcessingCache.getOrDefault(key,
                        new ArrayList<SubstituteValue>());
                String contentType = "text";
                if (extractedSourceContent.isArray()) {
                    // extracted result from sourcPayload is an array, so we potentially have to
                    // iterate over the result, e.g. creating multiple devices
                    for (JsonNode jn : extractedSourceContent) {
                        if (jn.isTextual()) {
                            pl.add(new SubstituteValue(jn.textValue(), TYPE.TEXTUAL));
                        } else if (jn.isNumber()) {
                            pl.add(new SubstituteValue(jn.numberValue().toString(), TYPE.NUMBER));
                        } else {
                            log.warn("Since result is not textual or number it is ignored: {}, {}, {}, {}",
                                    jn.asText());
                        }
                    }
                    postProcessingCache.put(key, pl);
                    contentType = "Array";
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

                    contentType = "Number";
                } else {
                    log.warn("Ignoring this substitution, sone no objects are allowed for: {}, {}, {}, {}",
                            sub.pathSource, extractedSourceContent);
                }
//                log.info("Evaluated substitution (pathSource, substitute): ({},{}), pathTarget: {}, {}, {}, {}",
//                        sub.pathSource, substitute, sub.pathTarget, payloadTarget,
//                        payloadMessage, mapping.targetAPI.equals(API.INVENTORY));

                log.info("Evaluated substitution (pathSource, substitute): ({},{}),type:{} ",
                        sub.pathSource, extractedSourceContent,contentType);
            }

            if (sub.pathTarget.equals(TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
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

                            var d = c8yAgent.upsertDevice(
                                    "device_" + mapping.externalIdType + "_" + substitute.value,
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
                    log.info("substitute:{}, pathTarget:{}, payloadTarget:{}", substitute, StringUtils.join(pt,"."), payloadTarget);
                } else if (!pathTarget.equals(SOURCE_ID)) {
                    substituteValue(substitute, payloadTarget, pt);
                }
            }
            /*
             * step 4 prepare target payload for sending to c8y
             */
            if (mapping.targetAPI.equals(API.INVENTORY)) {
                // c8yAgent.upsertDevice(payloadTarget.toString(), device.value,
                // mapping.externalIdType);
                ctx.getRequests().add(new C8YRequest(RequestMethod.PATCH, device.value, mapping.externalIdType,
                        payloadTarget.toString(), API.INVENTORY, null));
            } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
                // c8yAgent.createMEA(mapping.targetAPI, payloadTarget.toString());
                ctx.getRequests().add(new C8YRequest(RequestMethod.POST, device.value, mapping.externalIdType,
                        payloadTarget.toString(), mapping.targetAPI, null));

            } else {
                log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI.equals(API.INVENTORY),
                        postProcessingCache.size());
            }
            log.info("Added payload for sending: {}, {}, {}", payloadTarget, mapping.targetAPI.equals(API.INVENTORY),
                    listIdentifier.size());
            i++;
        }
    }

    @Override
    public void transformDownLinkPayload(ProcessingContext ctx, String payloadMessage) throws ProcessingException {
        Mapping mapping = ctx.getMapping();

        /*
         * step 0 patch payload with dummy property _DEVICE_IDENT_ in case of a wildcard
         * in the template topic
         */
        JsonNode payloadJsonNode;
        try {
            payloadJsonNode = objectMapper.readTree(payloadMessage);
        }catch (JsonProcessingException e) {
            log.error("JsonProcessingException parsing: {}, {}", payloadMessage, e);
            throw new ProcessingException("JsonProcessingException parsing: " + payloadMessage + " exception:" + e);
        }

        var payloadTarget = new JSONObject(mapping.target);

        for (MappingSubstitution sub : mapping.substitutions) {
            resolveOneSubstitutionForDownLink(ctx,mapping, sub, payloadJsonNode,
                    payloadTarget);
        }
        //check the external id whether find out
        if(ctx.getDeviceIdentifier()==null){
            MappingSubstitution sub = new MappingSubstitution();
            sub.setPathSource(SOURCE_ID);
            sub.setPathTarget(TOKEN_DEVICE_TOPIC);
            sub.setDefinesIdentifier(true);
            resolveOneSubstitutionForDownLink(ctx, mapping,sub, payloadJsonNode, payloadTarget);
        }

        //update topic
        boolean containsWildcardTemplateTopic = MappingsRepresentation
                .isWildcardTopic(mapping.getTemplateTopic());
        String targetTopic = mapping.getSubscriptionTopic();
        if(containsWildcardTemplateTopic){
            String topic = mapping.getSubscriptionTopic();
            String[] topicLevels = topic.split(TreeNode.SPLIT_TOPIC_REGEXP);
            if(ctx.getMapping().getIndexDeviceIdentifierInTemplateTopic() < topicLevels.length){
                topicLevels[(int)ctx.getMapping().getIndexDeviceIdentifierInTemplateTopic()]
                        = ctx.getDeviceIdentifier();
            }
            targetTopic = StringUtils.join(topicLevels,"");
        }
        /*
         * step 4 send target payload to mqtt broker
         */
        log.info("Posting mqtt payload: {}, {}, {}", payloadTarget,
                ctx.getDeviceIdentifier(), targetTopic);
        try {
            mqttClient.pushMsg(targetTopic, payloadTarget.toString(), mapping.getQos().ordinal());
        }catch(Exception e ){
            log.error(e.getMessage(),e);
        }
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

    private void resolveOneSubstitutionForDownLink(ProcessingContext ctx,
                                                   Mapping mapping,
                                                   MappingSubstitution sub,
                                                   JsonNode payloadJsonNode,
                                                   JSONObject payloadTarget ){
        JsonNode extractedSourceContent = null;
        /*
         * step 1 extract content from incoming payload
         */
        String _pathSource = sub.pathSource;
        if(_pathSource.indexOf(TOKEN_DEVICE_TOPIC) > -1 ){
            return;
        }
        try {
            Expressions expr = Expressions.parse(_pathSource);
            extractedSourceContent = expr.evaluate(payloadJsonNode);
        } catch (ParseException | IOException | EvaluateException e) {
            log.error("Exception for: {}, {}, {}", sub.pathSource, payloadTarget , e);
        } catch (EvaluateRuntimeException e) {
            log.error("EvaluateRuntimeException for: {}, {}, {}", sub.pathSource, payloadTarget,  e);
        }

        /*
         * step 2 analyse exctracted content: textual, array
         */
        SubstituteValue substitute = null;
        if (extractedSourceContent == null) {
            log.error("No substitution for: sub.pathSource:-> [{}], payloadTarget-->: {}", sub.pathSource, payloadTarget );
        } else {
            if(extractedSourceContent.isArray()){

            }else if (extractedSourceContent.isTextual()) {
                // extracted result from sourcPayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                substitute = new SubstituteValue(extractedSourceContent.textValue(), TYPE.TEXTUAL);
            } else if (extractedSourceContent.isNumber()) {
                // extracted result from sourcPayload is an array, so we potentially have to
                // iterate over the result, e.g. creating multiple devices
                substitute = new SubstituteValue(extractedSourceContent.numberValue().toString(), TYPE.NUMBER);
            }
        }

        /*
         * step 3 replace target with extract content from incoming payload
         */
        String[] pathTarget = sub.pathTarget.split(Pattern.quote("."));
        if (pathTarget == null) {
            pathTarget = new String[] { sub.pathTarget };
        }
        if (!mapping.targetAPI.equals(API.INVENTORY)) {
            if (sub.pathSource.equals(SOURCE_ID)
                    && mapping.mapDeviceIdentifier
                    && sub.definesIdentifier) {
                String sourceid = substitute.value;
                String externalId = findoutExternalId(sourceid, mapping.externalIdType);
                if (externalId == null) {
                    throw new RuntimeException("source id " + sourceid + " for type "
                            + mapping.externalIdType + " not found external id!");
                }else{
                    log.info("find out externalid for source {}-{}", sourceid, externalId);
                }
                ctx.setDeviceIdentifier(externalId);
                substitute.value = externalId;
            }
            log.info("try to config-{} with val-{}",StringUtils.join(pathTarget,"."), substitute);
            substituteValue(substitute,payloadTarget, pathTarget);
        } else {
            if (!sub.pathTarget.equals(TOKEN_DEVICE_TOPIC)) {
                substituteValue(substitute,payloadTarget, pathTarget);
            }
        }
    }

    private String findoutExternalId(String sourceId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getMoExternalId(sourceId, externalIdType);
        String externalid = null;
        if(extId!=null){
            externalid = extId.getExternalId();
        }
        log.info("Found external id {} for source id {} externalIdType {}", externalid, sourceId, externalIdType);
        return externalid;
    }
}
