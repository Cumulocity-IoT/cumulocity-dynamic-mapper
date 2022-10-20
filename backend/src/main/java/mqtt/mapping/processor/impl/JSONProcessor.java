package mqtt.mapping.processor.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.stream.Stream;

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
    public void transformPayload(ProcessingContext ctx, boolean send)
            throws ProcessingException {
        Mapping mapping = ctx.getMapping();
        String payloadMessage = ctx.getPayload();

        /*
         * step 0 patch payload with dummy property _DEVICE_IDENT_ in case of a wildcard
         * in the template topic
         */
        JsonNode payloadJsonNode;
        try {
            payloadJsonNode = objectMapper.readTree(payloadMessage);
            ArrayNode topicLevels = objectMapper.createArrayNode();
            List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(ctx.getTopic());
            splitTopicAsList.forEach(s -> topicLevels.add(s));
            ((ObjectNode) payloadJsonNode).set(TOKEN_TOPIC_LEVEL, topicLevels);
            payloadMessage = payloadJsonNode.toPrettyString();
            log.info("Patched payload:{}", payloadMessage);
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
                // have to escape _DEVICE_IDENT_ , _TOPIC_LEVEL_ with BACKQUOTE "`" since JSONata4Java does work for tokens with starting "_"
                var p = sub.pathSource.replace(TOKEN_DEVICE_TOPIC, TOKEN_DEVICE_TOPIC_BACKQUOTE);
                p = p.replace(TOKEN_TOPIC_LEVEL, TOKEN_TOPIC_LEVEL_BACKQUOTE);
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
                } else if (extractedSourceContent.isTextual()) {
                    pl.add(new SubstituteValue(extractedSourceContent.textValue(), TYPE.TEXTUAL));
                    postProcessingCache.put(key, pl);
                } else if (extractedSourceContent.isNumber()) {
                    pl.add(new SubstituteValue(extractedSourceContent.numberValue().toString(), TYPE.NUMBER));
                    postProcessingCache.put(key, pl);
                } else {
                    log.warn("Ignoring this substitution, no objects are allowed for: {}, {}",
                            sub.pathSource, extractedSourceContent.toString());
                }
                log.info("Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({}), {}, {}",
                        sub.pathSource, extractedSourceContent.toString(), sub.pathTarget,
                        payloadMessage, payloadTarget);
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

        /*
         * step 3 replace target with extract content from incoming payload
         */

        // determine the postProcessingCache entry with the most entries. This entry is used for the iteration
        Stream<Entry<String, ArrayList<SubstituteValue>>> stream1 =  postProcessingCache.entrySet().stream(); 
        Stream<SimpleEntry<String, Integer>> stream2 = stream1.map(entry -> new AbstractMap.SimpleEntry<String,Integer>(entry.getKey(), entry.getValue().size()));
        String maxEntry = stream2.reduce ( new AbstractMap.SimpleEntry<String,Integer>(SOURCE_ID, postProcessingCache.get(SOURCE_ID).size()), (r,e) -> {
            return ( r.getValue()>= e.getValue()? r : e);
        }).getKey();
        Set<String> pathTargets = postProcessingCache.keySet();
        //ArrayList<SubstituteValue> listIdentifier = postProcessingCache.get(SOURCE_ID);
        ArrayList<SubstituteValue> listIdentifier = postProcessingCache.get(maxEntry);
        if (listIdentifier == null) {
            throw new RuntimeException("Identified mapping has no substitution for source.id defined!");
        }
        int i = 0;
        for (SubstituteValue device : listIdentifier) {
            int predecessor = -1;
            for (String pathTarget : pathTargets) {
                SubstituteValue substitute = new SubstituteValue("NOT_DEFINED", TYPE.TEXTUAL);
                if (i < postProcessingCache.get(pathTarget).size()) {
                    substitute = postProcessingCache.get(pathTarget).get(i);
                } else if (postProcessingCache.get(pathTarget).size() == 1) {
                    // this is an indication that the substitution is the same for all
                    // events/alarms/measurements/inventory
                    substitute = postProcessingCache.get(pathTarget).get(0);
                }

                if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    if (pathTarget.equals(SOURCE_ID)) {
                        var sourceId = resolveExternalId(substitute.value, mapping.externalIdType);
                        if (sourceId == null && mapping.createNonExistingDevice) {
                            if (send) {
                                var d = c8yAgent.upsertDevice(
                                        "device_" + mapping.externalIdType + "_" + substitute.value,
                                        "c8y_MQTTMapping_generated_type", substitute.value, mapping.externalIdType);
                                substitute.value = d.getId().getValue();
                            }
                            try {
                                Map<String, Object> map = new HashMap<String, Object>();
                                map.put("c8y_IsDevice", null);
                                map.put("name", "device_" + mapping.externalIdType + "_" + substitute.value);
                                var p = objectMapper.writeValueAsString(map);
                                predecessor = ctx.addRequest(
                                        new C8YRequest(-1, RequestMethod.PATCH, device.value, mapping.externalIdType,
                                                p, API.INVENTORY, null));
                            } catch (JsonProcessingException e) {
                                // ignore
                            }
                        } else if (sourceId == null) {
                            throw new RuntimeException("External id " + substitute + " for type "
                                    + mapping.externalIdType + " not found!");
                        } else {
                            substitute.value = sourceId;
                        }
                    }
                    substituteValue(substitute, payloadTarget, pathTarget);
                } else if (!pathTarget.equals(SOURCE_ID)) {
                    substituteValue(substitute, payloadTarget, pathTarget);
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
                ctx.addRequest(new C8YRequest(predecessor, RequestMethod.PATCH, device.value, mapping.externalIdType,
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
                ctx.addRequest(new C8YRequest(predecessor, RequestMethod.POST, device.value, mapping.externalIdType,
                        payloadTarget.toString(), mapping.targetAPI, ex));
            } else {
                log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI,
                        postProcessingCache.size());
            }
            log.info("Added payload for sending: {}, {}, numberDevices: {}", payloadTarget, mapping.targetAPI,
                    listIdentifier.size());
            i++;
        }
    }

}