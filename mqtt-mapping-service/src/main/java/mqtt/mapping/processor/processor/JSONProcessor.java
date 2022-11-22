package mqtt.mapping.processor.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.BasePayloadProcessor;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class JSONProcessor extends BasePayloadProcessor<JsonNode> {

    public JSONProcessor ( ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context,
            MqttMessage mqttMessage) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(mqttMessage.getPayload());
        context.setPayload(jsonNode);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<JsonNode> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        JsonNode payloadJsonNode = context.getPayload();
        Map<String, ArrayList<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        ArrayNode topicLevels = objectMapper.createArrayNode();
        List<String> splitTopicAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
        splitTopicAsList.forEach(s -> topicLevels.add(s));
        if (payloadJsonNode instanceof ObjectNode) {
            ((ObjectNode) payloadJsonNode).set(TOKEN_TOPIC_LEVEL, topicLevels);
        } else {
            log.warn("Parsing this message as JSONArray, no elements from the topic level can be used!");
        }
        String  payload = payloadJsonNode.toPrettyString();
        log.info("Patched payload: {}", payload);

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
                log.error("Exception for: {}, {}", substitution.pathSource,
                        payload, e);
            } catch (EvaluateRuntimeException e) {
                log.error("EvaluateRuntimeException for: {}, {}", substitution.pathSource,
                        payload, e);
            }
            /*
             * step 2 analyse exctracted content: textual, array
             */
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(substitution.pathTarget,
                    new ArrayList<SubstituteValue>());
            if (extractedSourceContent == null) {
                log.error("No substitution for: {}, {}", substitution.pathSource,
                        payload);
                postProcessingCacheEntry
                        .add(new SubstituteValue(extractedSourceContent, TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
            } else {
                if (extractedSourceContent.isArray()) {
                    if (substitution.expandArray) {
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
                                log.warn("Since result is not textual or number it is ignored: {}",
                                        jn.asText());
                            }
                        }
                        context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    } else {
                        // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE substitution
                        context.addCardinality(substitution.pathTarget, 1);
                        postProcessingCacheEntry
                                .add(new SubstituteValue(extractedSourceContent, TYPE.ARRAY, substitution.repairStrategy));
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    }
                } else if (extractedSourceContent.isTextual()) {
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry.add(
                            new SubstituteValue(extractedSourceContent, TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else if (extractedSourceContent.isNumber()) {
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else {
                    log.info("This substitution, involves an objects for: {}, {}",
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new SubstituteValue(extractedSourceContent, TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                }
                if (mqttClient.getServiceConfiguration().logSubstitution) {
                    log.info("Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                            substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
                }
            }

            if (substitution.pathTarget.equals(TIME)) {
                substitutionTimeExists = true;
            }
        }

        // no substitution for the time property exists, then use the system time
        if (!substitutionTimeExists && mapping.targetAPI != API.INVENTORY) {
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(TIME,
                    new ArrayList<SubstituteValue>());
            postProcessingCacheEntry.add(
                    new SubstituteValue(new TextNode(new DateTime().toString()), TYPE.TEXTUAL, RepairStrategy.DEFAULT));
            postProcessingCache.put(TIME, postProcessingCacheEntry);
        }
    }

}