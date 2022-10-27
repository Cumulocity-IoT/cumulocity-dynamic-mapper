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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import mqtt.mapping.processor.RepairStrategy;

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
    public List<TreeNode> resolveMapping(ProcessingContext context) throws ResolveException {
        return mqttClient.getMappingTree()
                .resolveTopicPath(Mapping.splitTopicIncludingSeparatorAsList(context.getTopic()));
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
        } catch (JsonProcessingException e) {
            log.error("JsonProcessingException parsing: {}, try to continue with wrapped payload:", payload, e);
            context.setError(
                    new ProcessingException("JsonProcessingException parsing: " + payload + " exception:" + e));
            payloadJsonNode = objectMapper.valueToTree(new PayloadWrapper(payload));
            // throw new ProcessingException("JsonProcessingException parsing: " + payload +
            // " exception:" + e);
        }
        ArrayNode topicLevels = objectMapper.createArrayNode();
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
        splitTopicAsList.forEach(s -> topicLevels.add(s));
        if (payloadJsonNode instanceof ObjectNode) {
            ((ObjectNode) payloadJsonNode).set(TOKEN_TOPIC_LEVEL, topicLevels);
        } else {
            log.warn("Parsing this message as JSONArray, no elements from the topic level can be used!");
        }
        // payload = payloadJsonNode.toPrettyString();
        payload = payloadJsonNode.toString();
        log.info("Patched payload: {}", payload);

        // var payloadTarget = new JSONObject(mapping.target);
        JsonNode payloadTarget = null;
        try {
            payloadTarget = objectMapper.readTree(mapping.target);
        } catch (JsonProcessingException e) {
            throw new ProcessingException(e.getMessage());
        }

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
                log.debug("Patched sub.pathSource: {}, {}", substitution.pathSource, p);
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
            var key = (substitution.pathTarget.equals(TOKEN_DEVICE_TOPIC) ? SOURCE_ID : substitution.pathTarget);
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(key,
                    new ArrayList<SubstituteValue>());
            if (extractedSourceContent == null) {
                log.error("No substitution for: {}, {}, {}", substitution.pathSource, payloadTarget,
                        payload);
                postProcessingCacheEntry
                        .add(new SubstituteValue(extractedSourceContent, TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(key, postProcessingCacheEntry);
            } else {
                if (extractedSourceContent.isArray()) {
                    // extracted result from sourcPayload is an array, so we potentially have to
                    // iterate over the result, e.g. creating multiple devices
                    for (JsonNode jn : extractedSourceContent) {
                        if (jn.isTextual()) {
                            postProcessingCacheEntry
                                    .add(new SubstituteValue(jn, TYPE.TEXTUAL, substitution.repairStrategy));
                        } else if (jn.isNumber()) {
                            postProcessingCacheEntry
                                    .add(new SubstituteValue(jn, TYPE.NUMBER, substitution.repairStrategy));
                        } else {
                            log.warn("Since result is not textual or number it is ignored: {}, {}, {}, {}",
                                    jn.asText());
                        }
                    }
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCache.put(key, postProcessingCacheEntry);
                } else if (extractedSourceContent.isTextual()) {
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCacheEntry.add(
                            new SubstituteValue(extractedSourceContent, TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(key, postProcessingCacheEntry);
                } else if (extractedSourceContent.isNumber()) {
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(key, postProcessingCacheEntry);
                } else {
                    log.info("This substitution, involves an objects for: {}, {}",
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(key, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(key, postProcessingCacheEntry);
                }
                log.info("Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                        substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
            }

            if (substitution.pathTarget.equals(TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists) {
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(TIME,
                    new ArrayList<SubstituteValue>());
            postProcessingCacheEntry.add(
                    new SubstituteValue(new TextNode(new DateTime().toString()), TYPE.TEXTUAL, RepairStrategy.DEFAULT));
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
                SubstituteValue substituteValue = new SubstituteValue(new TextNode("NOT_DEFINED"), TYPE.TEXTUAL,
                        RepairStrategy.DEFAULT);
                if (i < postProcessingCache.get(pathTarget).size()) {
                    substituteValue = postProcessingCache.get(pathTarget).get(i).clone();
                } else if (postProcessingCache.get(pathTarget).size() == 1) {
                    // this is an indication that the substitution is the same for all
                    // events/alarms/measurements/inventory
                    if (substituteValue.repairStrategy.equals(RepairStrategy.USE_FIRST_VALUE_OF_ARRAY)) {
                        substituteValue = postProcessingCache.get(pathTarget).get(0).clone();
                    } else if (substituteValue.repairStrategy.equals(RepairStrategy.USE_LAST_VALUE_OF_ARRAY)) {
                        int last = postProcessingCache.get(pathTarget).size() - 1;
                        substituteValue = postProcessingCache.get(pathTarget).get(last).clone();
                    }
                    log.warn("During the processing of this pathTarget: {} a repair strategy: {} was used: {}, {}, {}",
                            pathTarget, substituteValue.repairStrategy);
                }

                if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    if (pathTarget.equals(SOURCE_ID)) {
                        var sourceId = resolveExternalId(substituteValue.typedValue().toString(),
                                mapping.externalIdType);
                        if (sourceId == null && mapping.createNonExistingDevice) {
                            if (send) {
                                var d = c8yAgent.upsertDevice(
                                        "device_" + mapping.externalIdType + "_"
                                                + substituteValue.typedValue().toString(),
                                        "c8y_MQTTMapping_generated_type", substituteValue.typedValue().toString(),
                                        mapping.externalIdType);
                                substituteValue.value = new TextNode(d.getId().getValue());
                            }
                            try {
                                Map<String, Object> map = new HashMap<String, Object>();
                                map.put("c8y_IsDevice", null);
                                map.put("name", "device_" + mapping.externalIdType + "_" + substituteValue.value);
                                var p = objectMapper.writeValueAsString(map);
                                predecessor = context.addRequest(
                                        new C8YRequest(-1, RequestMethod.PATCH, device.value.asText(),
                                                mapping.externalIdType,
                                                p, API.INVENTORY, null));
                            } catch (JsonProcessingException e) {
                                // ignore
                            }
                        } else if (sourceId == null) {
                            throw new RuntimeException("External id " + substituteValue + " for type "
                                    + mapping.externalIdType + " not found!");
                        } else {
                            substituteValue.value = new TextNode(sourceId);
                        }
                    }
                    substituteValueInObject(substituteValue, payloadTarget, pathTarget);
                } else if (!pathTarget.equals(SOURCE_ID)) {
                    substituteValueInObject(substituteValue, payloadTarget, pathTarget);
                }
            }
            /*
             * step 4 prepare target payload for sending to c8y
             */
            if (mapping.targetAPI.equals(API.INVENTORY)) {
                Exception ex = null;
                if (send) {
                    try {
                        c8yAgent.upsertDevice(payloadTarget.toString(), device.typedValue().toString(),
                                mapping.externalIdType);
                    } catch (Exception e) {
                        ex = e;
                    }
                }
                context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.PATCH, device.typedValue().toString(),
                                mapping.externalIdType,
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
                context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.POST, device.type.toString(), mapping.externalIdType,
                                payloadTarget.toString(), mapping.targetAPI, ex));
            } else {
                log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI,
                        postProcessingCache.size());
            }
            log.debug("Added payload for sending: {}, {}, numberDevices: {}", payloadTarget, mapping.targetAPI,
                    deviceEntries.size());
            i++;
        }
    }

}