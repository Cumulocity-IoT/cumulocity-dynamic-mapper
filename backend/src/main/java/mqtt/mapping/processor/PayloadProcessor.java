package mqtt.mapping.processor;

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
public abstract class PayloadProcessor {

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

    public abstract ProcessingContext deserializePayload(ProcessingContext contect, MqttMessage mqttMessage);

    public abstract void extractSource(ProcessingContext context) throws ProcessingException;

    public void patchTargetAndSend(ProcessingContext context) throws ProcessingException {
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

        Map<String, ArrayList<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();
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
                            if (context.isSendPayload()) {
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
                if (context.isSendPayload()) {
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
                if (context.isSendPayload()) {
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
            throw new JSONException(currentKey + "is not a valid key.");
        }

        JsonNode nestedJsonObjectVal = jsonObject.get(currentKey);
        String[] remainingKeys = Arrays.copyOfRange(keys, 1, keys.length);
        JsonNode updatedNestedValue = substituteValueInObject(sub, nestedJsonObjectVal, remainingKeys);
        return ((ObjectNode) jsonObject).set(currentKey, updatedNestedValue);
    }

}
