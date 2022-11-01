package mqtt.mapping.processor.impl;

import java.io.IOException;
import java.nio.charset.Charset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;

import org.springframework.stereotype.Service;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.RepairStrategy;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class JSONProcessor<I,O> extends PayloadProcessor<String,String> {

    public JSONProcessor ( ObjectMapper objectMapper, MQTTClient mqttClient, C8yAgent c8yAgent){
        super(objectMapper, mqttClient, c8yAgent);
    }

    @Override
    public ProcessingContext<String> deserializePayload(ProcessingContext<String> context, MqttMessage mqttMessage) {
        String payloadMessage = null;
        if (mqttMessage.getPayload() != null) {
            payloadMessage = (mqttMessage.getPayload() != null
                    ? new String(mqttMessage.getPayload(), Charset.defaultCharset())
                    : "");
        }
        context.setPayload(payloadMessage);
        return context;
    }

    @Override
    public void extractSource(ProcessingContext<String> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        String payload = context.getPayload();
        Map<String, ArrayList<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        JsonNode payloadJsonNode;
        try {
            payloadJsonNode = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            log.error("JsonProcessingException parsing: {}:", payload, e);
            context.setError(
                    new ProcessingException("JsonProcessingException parsing: " + payload + " exception:" + e));
            throw new ProcessingException("JsonProcessingException parsing: " + payload +
            " exception:" + e);
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
                log.error("Exception for: {}, {}, {}", substitution.pathSource,
                        payload, e);
            } catch (EvaluateRuntimeException e) {
                log.error("EvaluateRuntimeException for: {}, {}, {}", substitution.pathSource,
                        payload, e);
            }
            /*
             * step 2 analyse exctracted content: textual, array
             */
            var key = (substitution.pathTarget.equals(TOKEN_DEVICE_TOPIC) ? SOURCE_ID : substitution.pathTarget);
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(key,
                    new ArrayList<SubstituteValue>());
            if (extractedSourceContent == null) {
                log.error("No substitution for: {}, {}", substitution.pathSource,
                        payload);
                postProcessingCacheEntry
                        .add(new SubstituteValue(extractedSourceContent, TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(key, postProcessingCacheEntry);
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
                                log.warn("Since result is not textual or number it is ignored: {}, {}, {}, {}",
                                        jn.asText());
                            }
                        }
                        context.addCardinality(key, extractedSourceContent.size());
                        postProcessingCache.put(key, postProcessingCacheEntry);
                    } else {
                        // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE substitution
                        context.addCardinality(key, 1);
                        postProcessingCacheEntry
                                .add(new SubstituteValue(extractedSourceContent, TYPE.ARRAY, substitution.repairStrategy));
                        postProcessingCache.put(key, postProcessingCacheEntry);
                    }
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
        if (!substitutionTimeExists) {
            ArrayList<SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(TIME,
                    new ArrayList<SubstituteValue>());
            postProcessingCacheEntry.add(
                    new SubstituteValue(new TextNode(new DateTime().toString()), TYPE.TEXTUAL, RepairStrategy.DEFAULT));
            postProcessingCache.put(TIME, postProcessingCacheEntry);
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
            sub.pathSource = SOURCE_ID;
            sub.pathTarget = TOKEN_DEVICE_TOPIC;
            sub.definesIdentifier = true;
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
