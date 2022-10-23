package mqtt.mapping.processor.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.model.ResolveException;
import mqtt.mapping.model.TreeNode;
import mqtt.mapping.processor.C8YRequest;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.processor.ProcessingException;

@Slf4j
@Service
public class JSONProcessor extends PayloadProcessor {

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
    public List<TreeNode> resolveMapping(ProcessingContext ctx) throws ResolveException {
        log.info("Message received on topic '{}'  with message {}", ctx.getTopic(),
                ctx.getPayload());
        return mqttClient.getMappingTree().resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(ctx.getTopic()));
    }

    @Override
    public void transformPayload(ProcessingContext context, boolean send)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        String payload = context.getPayload();

        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        JsonNode payloadJsonNode;
        try {
            payloadJsonNode = objectMapper.readTree(payload);
            ArrayNode topicLevels = objectMapper.createArrayNode();
            List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
            splitTopicAsList.forEach(s -> topicLevels.add(s));
            if (payloadJsonNode instanceof ObjectNode) {
                ((ObjectNode) payloadJsonNode).set(TOKEN_TOPIC_LEVEL, topicLevels);
            } else {
                log.warn("Parsing this message as JSONArray, no elements from the topic level can be used!");
            }
            payload = payloadJsonNode.toPrettyString();
            log.info("Patched payload:{}", payload);
        } catch (JsonProcessingException e) {
            log.error("JsonProcessingException parsing: {}, {}", payload, e);
            context.setError(
                    new ProcessingException("JsonProcessingException parsing: " + payload + " exception:" + e));
            throw new ProcessingException("JsonProcessingException parsing: " + payload + " exception:" + e);
        }

        var payloadTarget = new JSONObject(mapping.target);
        Map<String, ArrayList<SubstituteValue>> postProcessingCache = new HashMap<String, ArrayList<SubstituteValue>>();
        boolean substitutionTimeExists = false;
        for (MappingSubstitution substitution : mapping.substitutions) {
            JsonNode extractedSourceContent = null;
            /*
             * step 1 extract content from incoming payload
             */
            try {
                // have to escape _DEVICE_IDENT_ , _TOPIC_LEVEL_ with BACKQUOTE "`" since
                // JSONata4Java does work for tokens with starting "_"
                var p = substitution.pathSource.replace(TOKEN_DEVICE_TOPIC, TOKEN_DEVICE_TOPIC_BACKQUOTE);
                p = p.replace(TOKEN_TOPIC_LEVEL, TOKEN_TOPIC_LEVEL_BACKQUOTE);
                log.info("Patched sub.pathSource: {}, {}", substitution.pathSource, p);
                Expressions expr = Expressions.parse(p);
                extractedSourceContent = expr.evaluate(payloadJsonNode);
            } catch (ParseException | IOException | EvaluateException e) {
                log.error("Exception for: {}, {}, {}, {}", substitution.pathSource, payloadTarget,
                        payload, e);
            } catch (EvaluateRuntimeException e) {
                log.error("EvaluateRuntimeException for: {}, {}, {}, {}", substitution.pathSource, payloadTarget,
                        payload, e);
            }
            /*
             * step 2 analyse exctracted content: textual, array
             */
            if (extractedSourceContent == null) {
                log.error("No substitution for: {}, {}, {}", substitution.pathSource, payloadTarget,
                        payload);
            } else {
                var key = (substitution.pathTarget.equals(TOKEN_DEVICE_TOPIC) ? SOURCE_ID : substitution.pathTarget);
                ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(key,
                        new ArrayList<SubstituteValue>());
                if (extractedSourceContent.isArray()) {
                    // extracted result from sourcPayload is an array, so we potentially have to
                    // iterate over the result, e.g. creating multiple devices
                    for (JsonNode jn : extractedSourceContent) {
                        if (jn.isTextual()) {
                            postProcessingCacheEntry.add(new SubstituteValue(jn.textValue(), TYPE.TEXTUAL));
                        } else if (jn.isNumber()) {
                            postProcessingCacheEntry.add(new SubstituteValue(jn.numberValue().toString(), TYPE.NUMBER));
                        } else {
                            log.warn("Since result is not textual or number it is ignored: {}, {}, {}, {}",
                                    jn.asText());
                        }
                    }
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCache.put(key, postProcessingCacheEntry);
                } else if (extractedSourceContent.isTextual()) {
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCacheEntry.add(new SubstituteValue(extractedSourceContent.textValue(), TYPE.TEXTUAL));
                    postProcessingCache.put(key, postProcessingCacheEntry);
                } else if (extractedSourceContent.isNumber()) {
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent.numberValue().toString(), TYPE.NUMBER));
                    postProcessingCache.put(key, postProcessingCacheEntry);
                } else {
                    log.warn("Ignoring this substitution, no objects are allowed for: {}, {}",
                            substitution.pathSource, extractedSourceContent.toString());
                }
                log.info("Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({}), {}, {}",
                        substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget,
                        payload, payloadTarget);
            }

            if (substitution.pathTarget.equals(TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists) {
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(TIME,
                    new ArrayList<SubstituteValue>());
            postProcessingCacheEntry.add(new SubstituteValue(new DateTime().toString(), TYPE.TEXTUAL));
            postProcessingCache.put(TIME, postProcessingCacheEntry);
        }

        /*
         * step 3 replace target with extract content from incoming payload
         */

        // determine the postProcessingCache entry with the most entries. This entry is
        // used for the iteration
        // Stream<Entry<String, ArrayList<SubstituteValue>>> stream1 =
        // postProcessingCache.entrySet().stream();
        // Stream<SimpleEntry<String, Integer>> stream2 = stream1.map(entry -> new
        // AbstractMap.SimpleEntry<String,Integer>(entry.getKey(),
        // entry.getValue().size()));
        // String maxEntry = stream2.reduce ( new
        // AbstractMap.SimpleEntry<String,Integer>(SOURCE_ID,
        // postProcessingCache.get(SOURCE_ID).size()), (r,e) -> {
        // return ( r.getValue()>= e.getValue()? r : e);
        // }).getKey();

        String maxEntry = postProcessingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();

        Set<String> pathTargets = postProcessingCache.keySet();
        ArrayList<SubstituteValue> deviceEntries = postProcessingCache.get(SOURCE_ID);
        int countMaxlistEntries = postProcessingCache.get(maxEntry).size();
        SubstituteValue toDouble = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxlistEntries) {
            deviceEntries.add(toDouble);
        }

        int i = 0;
        for (SubstituteValue device : deviceEntries) {

            int predecessor = -1;
            for (String pathTarget : pathTargets) {
                SubstituteValue substituteValue = new SubstituteValue("NOT_DEFINED", TYPE.TEXTUAL);
                if (i < postProcessingCache.get(pathTarget).size()) {
                    substituteValue = postProcessingCache.get(pathTarget).get(i).clone();
                } else if (postProcessingCache.get(pathTarget).size() == 1) {
                    // this is an indication that the substitution is the same for all
                    // events/alarms/measurements/inventory
                    substituteValue = postProcessingCache.get(pathTarget).get(0).clone();
                }

                if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    if (pathTarget.equals(SOURCE_ID)) {
                        var sourceId = resolveExternalId(substituteValue.value, mapping.externalIdType);
                        if (sourceId == null && mapping.createNonExistingDevice) {
                            if (send) {
                                var d = c8yAgent.upsertDevice(
                                        "device_" + mapping.externalIdType + "_" + substituteValue.value,
                                        "c8y_MQTTMapping_generated_type", substituteValue.value,
                                        mapping.externalIdType);
                                substituteValue.value = d.getId().getValue();
                            }
                            try {
                                Map<String, Object> map = new HashMap<String, Object>();
                                map.put("c8y_IsDevice", null);
                                map.put("name", "device_" + mapping.externalIdType + "_" + substituteValue.value);
                                var p = objectMapper.writeValueAsString(map);
                                predecessor = context.addRequest(
                                        new C8YRequest(-1, RequestMethod.PATCH, device.value, mapping.externalIdType,
                                                p, API.INVENTORY, null));
                            } catch (JsonProcessingException e) {
                                // ignore
                            }
                        } else if (sourceId == null) {
                            throw new RuntimeException("External id " + substituteValue + " for type "
                                    + mapping.externalIdType + " not found!");
                        } else {
                            substituteValue.value = sourceId;
                        }
                    }
                    substituteValue(substituteValue, payloadTarget, pathTarget);
                } else if (!pathTarget.equals(SOURCE_ID)) {
                    substituteValue(substituteValue, payloadTarget, pathTarget);
                }
            }
            /*
             * step 4 prepare target payload for sending to c8y
             */
            if (mapping.targetAPI.equals(API.INVENTORY)) {
                Exception ex = null;
                if (send) {
                    try {
                        c8yAgent.upsertDevice(payloadTarget.toString(), device.value, mapping.externalIdType);
                    } catch (Exception e) {
                        ex = e;
                    }
                }
                context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.PATCH, device.value, mapping.externalIdType,
                                payloadTarget.toString(), API.INVENTORY, ex));
            } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
                Exception ex = null;
                if (send) {
                    try {
                        c8yAgent.createMEA(mapping.targetAPI, payloadTarget.toString());
                    } catch (Exception e) {
                        ex = e;
                    }
                }
                context.addRequest(new C8YRequest(predecessor, RequestMethod.POST, device.value, mapping.externalIdType,
                        payloadTarget.toString(), mapping.targetAPI, ex));
            } else {
                log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI,
                        postProcessingCache.size());
            }
            log.info("Added payload for sending: {}, {}, numberDevices: {}", payloadTarget, mapping.targetAPI,
                    deviceEntries.size());
            i++;
        }
    }

}