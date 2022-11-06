package mqtt.mapping.processor;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.handler.SysHandler;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public abstract class PayloadProcessor<O> {

    public PayloadProcessor(ObjectMapper objectMapper, MQTTClient mqttClient, C8yAgent c8yAgent) {
        this.objectMapper = objectMapper;
        this.mqttClient = mqttClient;
        this.c8yAgent = c8yAgent;
    }

    protected C8yAgent c8yAgent;

    protected ObjectMapper objectMapper;

    protected MQTTClient mqttClient;

    @Autowired
    SysHandler sysHandler;

    public static String SOURCE_ID = "source.id";
    public static String TOKEN_DEVICE_TOPIC = "_DEVICE_IDENT_";
    public static String TOKEN_DEVICE_TOPIC_BACKQUOTE = "`_DEVICE_IDENT_`";
    public static String TOKEN_TOPIC_LEVEL = "_TOPIC_LEVEL_";
    public static String TOKEN_TOPIC_LEVEL_BACKQUOTE = "`_TOPIC_LEVEL_`";

    public static final String TIME = "time";

    public abstract ProcessingContext<O> deserializePayload(ProcessingContext<O> contect, MqttMessage mqttMessage)
            throws IOException;

    public abstract void extractFromSource(ProcessingContext<O> context) throws ProcessingException;

    public void substituteInTargetAndSend(ProcessingContext<O> context) throws ProcessingException {
        /*
         * step 3 replace target with extract content from incoming payload
         */
        Mapping mapping = context.getMapping();
        JsonNode payloadTarget = null;
        try {
            payloadTarget = objectMapper.readTree(mapping.target);
        } catch (JsonProcessingException e) {
            throw new ProcessingException(e.getMessage());
        }

        // if there are to little device idenfified then we replicate the first device
        Map<String, ArrayList<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
        String maxEntry = postProcessingCache.entrySet()
                .stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().size()))
                .max((Entry<String, Integer> e1, Entry<String, Integer> e2) -> e1.getValue()
                        .compareTo(e2.getValue()))
                .get().getKey();

        ArrayList<SubstituteValue> deviceEntries = postProcessingCache.get(mapping.targetAPI.identifier);
        int countMaxlistEntries = postProcessingCache.get(maxEntry).size();
        SubstituteValue toDouble = deviceEntries.get(0);
        while (deviceEntries.size() < countMaxlistEntries) {
            deviceEntries.add(toDouble);
        }
        Set<String> pathTargets = postProcessingCache.keySet();

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
                    log.warn("During the processing of this pathTarget: {} a repair strategy: {} was used.",
                            pathTarget, substituteValue.repairStrategy);
                }

                if (!mapping.targetAPI.equals(API.INVENTORY)) {
                    if (pathTarget.equals(mapping.targetAPI.identifier)) {
                        var sourceId = resolveExternalId(substituteValue.typedValue().toString(),
                                mapping.externalIdType);
                        if (sourceId == null && mapping.createNonExistingDevice) {
                            ManagedObjectRepresentation attocDevice = null;
                            Map<String, Object> map = new HashMap<String, Object>();
                            map.put("name", "device_" + mapping.externalIdType + "_" + substituteValue.value);
                            map.put("c8y_MQTTMapping_generated_type", null);
                            map.put("c8y_IsDevice", null);
                            String request = null;
                            String response = null;
                            try {
                                request = objectMapper.writeValueAsString(map);
                                if (context.isSendPayload()) {
                                    attocDevice = c8yAgent.upsertDevice(request,
                                            substituteValue.typedValue().toString(),
                                            mapping.externalIdType);
                                    response = objectMapper.writeValueAsString(map);
                                    substituteValue.value = new TextNode(attocDevice.getId().getValue());
                                }
                                var newPredecessor = context.addRequest(
                                        new C8YRequest(predecessor, RequestMethod.PATCH, device.value.asText(),
                                                mapping.externalIdType, request, response, API.INVENTORY, null));
                                predecessor = newPredecessor;
                            } catch (ProcessingException | JsonProcessingException e) {
                                context.addRequest(
                                        new C8YRequest(predecessor, RequestMethod.PATCH, device.value.asText(),
                                                mapping.externalIdType, request, response, API.INVENTORY, e));
                                throw new ProcessingException(e.getMessage());

                            }
                        } else if (sourceId == null && context.isSendPayload()) {
                            throw new RuntimeException("External id " + substituteValue + " for type "
                                    + mapping.externalIdType + " not found!");
                        } else if (sourceId == null) {
                            substituteValue.value = null;
                        } else {
                            substituteValue.value = new TextNode(sourceId);
                        }
                    }
                    substituteValueInObject(substituteValue, payloadTarget, pathTarget);
                } else if (!pathTarget.equals(mapping.targetAPI.identifier)) {
                    substituteValueInObject(substituteValue, payloadTarget, pathTarget);
                }
            }
            /*
             * step 4 prepare target payload for sending to c8y
             */
            if (mapping.targetAPI.equals(API.INVENTORY)) {
                Exception ex = null;
                ManagedObjectRepresentation attocDevice = null;
                String response = null;
                if (context.isSendPayload()) {
                    try {
                        attocDevice = c8yAgent.upsertDevice(payloadTarget.toString(), device.typedValue().toString(),
                                mapping.externalIdType);
                        response = objectMapper.writeValueAsString(attocDevice);
                    } catch (Exception e) {
                        ex = e;
                    }
                }
                var newPredecessor = context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.PATCH, device.value.asText(), mapping.externalIdType,
                                payloadTarget.toString(),
                                response, API.INVENTORY, ex));
                predecessor = newPredecessor;
            } else if (!mapping.targetAPI.equals(API.INVENTORY)) {
                Exception ex = null;
                if (context.isSendPayload()) {
                    try {
                        c8yAgent.createMEAO(mapping.targetAPI, payloadTarget.toString());
                    } catch (Exception e) {
                        ex = e;
                    }
                }
                var newPredecessor = context.addRequest(
                        new C8YRequest(predecessor, RequestMethod.POST, device.value.asText(), mapping.externalIdType,
                                payloadTarget.toString(),
                                payloadTarget.toString(), mapping.targetAPI, ex));
                predecessor = newPredecessor;
            } else {
                log.warn("Ignoring payload: {}, {}, {}", payloadTarget, mapping.targetAPI,
                        postProcessingCache.size());
            }
            log.debug("Added payload for sending: {}, {}, numberDevices: {}", payloadTarget, mapping.targetAPI,
                    deviceEntries.size());
            i++;
        }
    }

    public String resolveExternalId(String externalId, String externalIdType) {
        ExternalIDRepresentation extId = c8yAgent.getExternalId(externalId, externalIdType);
        String id = null;
        if (extId != null) {
            id = extId.getManagedObject().getId().getValue();
        }
        log.debug("Found id {} for external id: {}", id, externalId);
        return id;
    }

    public void substituteValueInObject(SubstituteValue sub, JsonNode jsonObject, String keys) throws JSONException {
        String[] splitKeys = keys.split(Pattern.quote("."));
        boolean subValueEmpty = sub.value == null || sub.value.isEmpty();
        if (sub.repairStrategy.equals(RepairStrategy.REMOVE_IF_MISSING) && subValueEmpty) {
            removeValueFromObect(jsonObject, splitKeys);
        } else {
            if (splitKeys == null) {
                splitKeys = new String[] { keys };
            }
            substituteValueInObject(sub, jsonObject, splitKeys);
        }
    }

    public JsonNode removeValueFromObect(JsonNode jsonObject, String[] keys) throws JSONException {
        String currentKey = keys[0];

        if (keys.length == 1) {
            return ((ObjectNode) jsonObject).remove(currentKey);
        } else if (!jsonObject.has(currentKey)) {
            throw new JSONException(currentKey + "is not a valid key.");
        }

        JsonNode nestedJsonObjectVal = jsonObject.get(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        return removeValueFromObect(nestedJsonObjectVal, remainingKeys);
    }

    public JsonNode substituteValueInObject(SubstituteValue sub, JsonNode jsonObject, String[] keys)
            throws JSONException {
        String currentKey = keys[0];

        if (keys.length == 1) {
            return ((ObjectNode) jsonObject).set(currentKey, sub.value);
        } else if (!jsonObject.has(currentKey)) {
            throw new JSONException(currentKey + " is not a valid key.");
        }

        JsonNode nestedJsonObjectVal = jsonObject.get(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        JsonNode updatedNestedValue = substituteValueInObject(sub, nestedJsonObjectVal, remainingKeys);
        return ((ObjectNode) jsonObject).set(currentKey, updatedNestedValue);
    }

}
