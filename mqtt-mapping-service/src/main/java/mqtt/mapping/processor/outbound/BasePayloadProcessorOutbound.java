/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.processor.outbound;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingRepresentation;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.C8YMessage;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;
import mqtt.mapping.processor.system.SysHandler;
import mqtt.mapping.service.MQTTClient;
import org.apache.commons.lang3.mutable.MutableInt;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public abstract class BasePayloadProcessorOutbound<T> {

    public BasePayloadProcessorOutbound(ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent) {
        this.objectMapper = objectMapper;
        this.mqttClient = mqttClient;
        this.c8yAgent = c8yAgent;
    }

    protected C8YAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected MQTTClient mqttClient;

    @Autowired
    SysHandler sysHandler;

    public static String TOKEN_DEVICE_TOPIC = "_DEVICE_IDENT_";
    public static String TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";

    public static final String TIME = "time";

    public abstract ProcessingContext<T> deserializePayload(ProcessingContext<T> context, C8YMessage c8yMessage)
            throws IOException;

    public abstract void extractFromSource(ProcessingContext<T> context) throws ProcessingException;

    public ProcessingContext<T> substituteInTargetAndSend(ProcessingContext<T> context) {
        /*
         * step 3 replace target with extract content from outbound payload
         */
        Mapping mapping = context.getMapping();

        // if there are to little device idenfified then we replicate the first device
        Map<String, List<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        Set<String> pathTargets = postProcessingCache.keySet();

        int predecessor = -1;
        DocumentContext payloadTarget = JsonPath.parse(mapping.target);
        /*
         * step 0 patch payload with dummy property _TOPIC_LEVEL_ in case the content
         * is required in the payload for a substitution
         */
        List<String> splitTopicExAsList = Mapping.splitTopicExcludingSeparatorAsList(context.getTopic());
        payloadTarget.set(TOKEN_TOPIC_LEVEL, splitTopicExAsList);

        String deviceSource = "undefined";

        for (String pathTarget : pathTargets) {
            SubstituteValue substituteValue = new SubstituteValue(new TextNode("NOT_DEFINED"), TYPE.TEXTUAL,
                    RepairStrategy.DEFAULT);
            if (postProcessingCache.get(pathTarget).size() > 0) {
                substituteValue = postProcessingCache.get(pathTarget).get(0).clone();
            }
            if (!mapping.targetAPI.equals(API.INVENTORY)) {
                if (pathTarget.equals(MappingRepresentation.findDeviceIdentifier(mapping).pathTarget)) {
                    ExternalIDRepresentation externalId = c8yAgent.findExternalId(
                            new GId(substituteValue.typedValue().toString()), mapping.externalIdType, context);
                    if (externalId == null && context.isSendPayload()) {
                        throw new RuntimeException("External id " + substituteValue + " for type "
                                + mapping.externalIdType + " not found!");
                    } else if (externalId == null) {
                        substituteValue.value = null;
                    } else {
                        substituteValue.value = new TextNode(externalId.getExternalId());
                        deviceSource = externalId.getExternalId();
                    }
                }
                substituteValueInObject(mapping.mappingType, substituteValue, payloadTarget, pathTarget);
            } else if (!pathTarget.equals(MappingRepresentation.findDeviceIdentifier(mapping).pathTarget)) {
                substituteValueInObject(mapping.mappingType, substituteValue, payloadTarget, pathTarget);
            }
        }
        /*
         * step 4 prepare target payload for sending to mqttBroker
         */
        if (!mapping.targetAPI.equals(API.INVENTORY)) {
            List<String> topicLevels = payloadTarget.read(TOKEN_TOPIC_LEVEL);
            if (topicLevels != null && topicLevels.size() > 0) {
                // now merge the replaced topic levels
                MutableInt c = new MutableInt(0);
                String[] splitTopicInAsList = Mapping.splitTopicIncludingSeparatorAsArray(context.getTopic());
                topicLevels.forEach(tl -> {
                    while (c.intValue() < splitTopicInAsList.length
                            && ("/".equals(splitTopicInAsList[c.intValue()]))) {
                        c.increment();
                    }
                    splitTopicInAsList[c.intValue()] = tl;
                    c.increment();
                });

                StringBuffer resolvedPublishTopic = new StringBuffer();
                for (int d = 0; d < splitTopicInAsList.length; d++) {
                    resolvedPublishTopic.append(splitTopicInAsList[d]);
                }
                context.setResolvedPublishTopic(resolvedPublishTopic.toString());
            } else {
                context.setResolvedPublishTopic(context.getMapping().getPublishTopic());
            }
            AbstractExtensibleRepresentation attocRequest = null;
            // remove TOPIC_LEVEL
            payloadTarget.delete(TOKEN_TOPIC_LEVEL);
            var newPredecessor = context.addRequest(
                    new C8YRequest(predecessor, RequestMethod.POST, deviceSource, mapping.externalIdType,
                            payloadTarget.jsonString(),
                            null, mapping.targetAPI, null));
            try {
                attocRequest = mqttClient.createMEAO(context);

                var response = objectMapper.writeValueAsString(attocRequest);
                context.getCurrentRequest().setResponse(response);
            } catch (Exception e) {
                context.getCurrentRequest().setError(e);
            }
            predecessor = newPredecessor;
        } else {
            log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI,
                    postProcessingCache.size());
        }
        log.debug("Added payload for sending: {}, {}, numberDevices: {}", payloadTarget, mapping.targetAPI,
                1);

        return context;

    }

    public void substituteValueInObject(MappingType type, SubstituteValue sub, DocumentContext jsonObject, String keys)
            throws JSONException {
        boolean subValueMissing = sub.value == null;
        boolean subValueNull =  (sub.value == null) || ( sub.value != null && sub.value.isNull());
        // variant where the default strategy for PROCESSOR_EXTENSION is REMOVE_IF_MISSING
        // if ((sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING) && subValueMissing) ||
        // (sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_NULL) && subValueNull) ||
        // ((type.equals(MappingType.PROCESSOR_EXTENSION) || type.equals(MappingType.PROTOBUF_STATIC))
        //         && (subValueMissing || subValueNull)))
        if ((sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING) && subValueMissing) ||
                (sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_NULL) && subValueNull)) {
            jsonObject.delete(keys);
        } else if (sub.repairStrategy.equals(RepairStrategy.CREATE_IF_MISSING) ) {
            boolean pathIsNested =  keys.contains(".") ||  keys.contains("[") ;
            if (pathIsNested) {
                throw new JSONException ("Can only crrate new nodes ion the root level!");
            }
            jsonObject.put("$", keys, sub.typedValue());
        } else {
            jsonObject.set(keys, sub.typedValue());
        }
    }

}
