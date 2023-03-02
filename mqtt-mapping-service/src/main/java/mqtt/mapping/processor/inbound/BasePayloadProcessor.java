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

package mqtt.mapping.processor.inbound;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;
import mqtt.mapping.processor.system.SysHandler;
import mqtt.mapping.service.MQTTClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

@Slf4j
@Service
public abstract class BasePayloadProcessor<T> {

    public BasePayloadProcessor(ObjectMapper objectMapper, MQTTClient mqttClient, C8YAgent c8yAgent) {
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
    public static String TOKEN_DEVICE_TOPIC_BACKQUOTE = "`_DEVICE_IDENT_`";
    public static String TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
    public static String TOKEN_TOPIC_LEVEL_BACKQUOTE = "`_TOPIC_LEVEL_`";

    public static final String TIME = "time";

    public abstract ProcessingContext<T> deserializePayload(ProcessingContext<T> context, MqttMessage mqttMessage)
            throws IOException;

    public abstract void extractFromSource(ProcessingContext<T> context) throws ProcessingException;

    public ProcessingContext<T> substituteInTargetAndSend(ProcessingContext<T> context) {
        /*
         * step 3 replace target with extract content from inbound payload
         */
        Mapping mapping = context.getMapping();

        // if there are to little device idenfified then we replicate the first device
        Map<String, List<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        String maxEntry = postProcessingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();

        // List<SubstituteValue> deviceEntries =
        // postProcessingCache.get(mapping.targetAPI.identifier);
        List<SubstituteValue> deviceEntries = postProcessingCache
                .get(MappingRepresentation.findDeviceIdentifier(mapping).pathTarget);
        int countMaxlistEntries = postProcessingCache.get(maxEntry).size();
        SubstituteValue toDouble = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxlistEntries) {
            deviceEntries.add(toDouble);
        }
        Set<String> pathTargets = postProcessingCache.keySet();

        int i = 0;
        for (SubstituteValue device : deviceEntries) {

            int predecessor = -1;
            DocumentContext payloadTarget = JsonPath.parse(mapping.target);
            for (String pathTarget : pathTargets) {
                SubstituteValue substituteValue = new SubstituteValue(new TextNode("NOT_DEFINED"), TYPE.TEXTUAL,
                        RepairStrategy.DEFAULT);
                if (i < postProcessingCache.get(pathTarget).size()) {
                    substituteValue = postProcessingCache.get(pathTarget).get(i).clone();
                } else if (postProcessingCache.get(pathTarget).size() == 1) {
                    // this is an indication that the substitution is the same for all
                    // events/alarms/measurements/inventory
                    if (substituteValue.repairStrategy.equals(RepairStrategy.USE_FIRST_VALUE_OF_ARRAY) ||
                            substituteValue.repairStrategy.equals(RepairStrategy.DEFAULT)) {
                        substituteValue = postProcessingCache.get(pathTarget).get(0).clone();
                    } else if (substituteValue.repairStrategy.equals(RepairStrategy.USE_LAST_VALUE_OF_ARRAY)) {
                        int last = postProcessingCache.get(pathTarget).size() - 1;
                        substituteValue = postProcessingCache.get(pathTarget).get(last).clone();
                    }
                    log.warn("During the processing of this pathTarget: {} a repair strategy: {} was used.",
                            pathTarget, substituteValue.repairStrategy);
                }

                if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    // if (pathTarget.equals(mapping.targetAPI.identifier)) {
                    if (pathTarget.equals(MappingRepresentation.findDeviceIdentifier(mapping).pathTarget)) {

                        ExternalIDRepresentation sourceId = c8yAgent.resolveExternalId(
                                new ID(mapping.externalIdType, substituteValue.typedValue().toString()), context);
                        if (sourceId == null && mapping.createNonExistingDevice) {
                            ManagedObjectRepresentation attocDevice = null;
                            Map<String, Object> request = new HashMap<String, Object>();
                            request.put("name",
                                    "device_" + mapping.externalIdType + "_" + substituteValue.value.asText());
                            request.put(MappingRepresentation.MQTT_MAPPING_GENERATED_TEST_DEVICE, null);
                            request.put("c8y_IsDevice", null);
                            try {
                                var requestString = objectMapper.writeValueAsString(request);
                                var newPredecessor = context.addRequest(
                                        new C8YRequest(predecessor, RequestMethod.PATCH, device.value.asText(),
                                                mapping.externalIdType, requestString, null, API.INVENTORY, null));
                                attocDevice = c8yAgent.upsertDevice(
                                        new ID(mapping.externalIdType, substituteValue.value.asText()), context);
                                var response = objectMapper.writeValueAsString(attocDevice);
                                context.getCurrentRequest().setResponse(response);
                                substituteValue.value = new TextNode(attocDevice.getId().getValue());
                                predecessor = newPredecessor;
                            } catch (ProcessingException | JsonProcessingException e) {
                                context.getCurrentRequest().setError(e);
                            }
                        } else if (sourceId == null && context.isSendPayload()) {
                            throw new RuntimeException("External id " + substituteValue + " for type "
                                    + mapping.externalIdType + " not found!");
                        } else if (sourceId == null) {
                            substituteValue.value = null;
                        } else {
                            substituteValue.value = new TextNode(sourceId.getManagedObject().getId().getValue());
                        }

                    }
                    substituteValueInObject(substituteValue, payloadTarget, pathTarget);
                } else if (!pathTarget.equals(MappingRepresentation.findDeviceIdentifier(mapping).pathTarget)) {
                    substituteValueInObject(substituteValue, payloadTarget, pathTarget);
                }
            }
            /*
             * step 4 prepare target payload for sending to c8y
             */
            if (mapping.targetAPI.equals(API.INVENTORY)) {
                ManagedObjectRepresentation attocDevice = null;
                var newPredecessor = context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.PATCH, device.value.asText(), mapping.externalIdType,
                                payloadTarget.jsonString(),
                                null, API.INVENTORY, null));
                try {
                    attocDevice = c8yAgent.upsertDevice(
                            new ID(mapping.externalIdType, device.value.asText()), context);
                    var response = objectMapper.writeValueAsString(attocDevice);
                    context.getCurrentRequest().setResponse(response);
                } catch (Exception e) {
                    context.getCurrentRequest().setError(e);
                }
                predecessor = newPredecessor;
            } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
                AbstractExtensibleRepresentation attocRequest = null;
                var newPredecessor = context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.POST, device.value.asText(), mapping.externalIdType,
                                payloadTarget.jsonString(),
                                null, mapping.targetAPI, null));
                try {
                    attocRequest = c8yAgent.createMEAO(context);
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
                    deviceEntries.size());
            i++;
        }
        return context;
    }

    public void substituteValueInObject(SubstituteValue sub, DocumentContext jsonObject, String keys)
            throws JSONException {
        boolean subValueEmpty = sub.value == null || sub.value.isEmpty();
        if (sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING) && subValueEmpty) {
            jsonObject.delete(keys);
        } else {
            jsonObject.set(keys, sub.typedValue());
        }
    }

}
