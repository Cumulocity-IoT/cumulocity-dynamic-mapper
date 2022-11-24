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

package mqtt.mapping.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.SnoopStatus;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.system.SysHandler;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class SynchronousDispatcher implements MqttCallback {

    private static final Object TOPIC_PERFORMANCE_METRIC = "__TOPIC_PERFORMANCE_METRIC";

    @Autowired
    protected C8YAgent c8yAgent;

    @Autowired
    protected MQTTClient mqttClient;
    
    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    SysHandler sysHandler;

    @Autowired
    Map<MappingType, BasePayloadProcessor<?>> payloadProcessors;

    @Autowired
    MappingComponent mappingStatusComponent;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        if ((TOPIC_PERFORMANCE_METRIC.equals(topic))) {
            // REPORT MAINTENANCE METRIC
        } else {
            processMessage(topic, mqttMessage, true);
        }
    }

    public List<ProcessingContext<?>> processMessage(String topic, MqttMessage mqttMessage, boolean sendPayload)
            throws Exception {
        MappingStatus mappingStatusUnspecified = mappingStatusComponent.getMappingStatus(Mapping.UNSPECIFIED_MAPPING);
        List<ProcessingContext<?>> processingResult = new ArrayList<ProcessingContext<?>>();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                List<Mapping> resolvedMappings = new ArrayList<>();
                try {
                    resolvedMappings = mqttClient.resolveMappings(topic);
                } catch (Exception e) {
                    log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message!", e);
                    mappingStatusUnspecified.errors++;
                    // TODO review if exception has to be thrown
                    // throw e;
                }

                resolvedMappings.forEach(mapping -> {
                    MappingStatus mappingStatus = mappingStatusComponent.getMappingStatus(mapping);

                    ProcessingContext<?> context;
                    if (mapping.mappingType.payloadType.equals(String.class)) {
                        context = new ProcessingContext<String>();
                    } else {
                        context = new ProcessingContext<byte[]>();

                    }
                    context.setTopic(topic);
                    context.setMappingType(mapping.mappingType);
                    context.setMapping(mapping);
                    context.setSendPayload(sendPayload);
                    // identify the corect processor based on the mapping type
                    MappingType mappingType = context.getMappingType();
                    BasePayloadProcessor processor = payloadProcessors.get(mappingType);

                    if (processor != null) {
                        try {
                            processor.deserializePayload(context, mqttMessage);
                            if (mqttClient.getServiceConfiguration().logPayload) {
                                log.info("New message on topic: '{}', wrapped message: {}", context.getTopic(),
                                        context.getPayload().toString());
                            } else {
                                log.info("New message on topic: '{}'", context.getTopic());
                            }
                            mappingStatus.messagesReceived++;
                            if (mapping.snoopStatus == SnoopStatus.ENABLED
                                    || mapping.snoopStatus == SnoopStatus.STARTED) {
                                String serializedPayload = null;
                                if (context.getPayload() instanceof JsonNode) {
                                    serializedPayload = objectMapper
                                            .writeValueAsString((JsonNode) context.getPayload());
                                } else if (context.getPayload() instanceof String) {
                                    serializedPayload = (String) context.getPayload();
                                }
                                if (context.getPayload() instanceof byte[]) {
                                    serializedPayload = Hex.encodeHexString((byte[]) context.getPayload());
                                }

                                if (serializedPayload != null) {
                                    mappingStatus.snoopedTemplatesActive++;
                                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                    mapping.addSnoopedTemplate(serializedPayload);

                                    log.debug("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mappingStatusComponent.setMappingDirty(mapping);
                                    mappingStatusComponent.setMappingDirty(mapping);

                                } else {
                                    log.warn(
                                            "Message could NOT be parsed, ignoring this message, as class is not valid: {}",
                                            context.getPayload().getClass());
                                }
                            } else {
                                processor.extractFromSource(context);
                                processor.substituteInTargetAndSend(context);
                                List<C8YRequest> resultRequests = context.getRequests();
                                if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                    mappingStatus.errors++;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Message could NOT be parsed, ignoring this message.");
                            e.printStackTrace();
                            mappingStatus.errors++;
                        }
                    } else {
                        mappingStatusUnspecified.errors++;
                        log.error("No process for MessageType: {} registered, ignoring this message!", mappingType);
                    }
                    processingResult.add(context);
                });
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
        }

        return processingResult;
    }

    @Override
    public void connectionLost(Throwable throwable) {
        log.error("Connection Lost to MQTT broker: ", throwable);
        c8yAgent.createEvent("Connection lost to MQTT broker", "mqtt_status_event", DateTime.now(), null);
        mqttClient.submitConnect();
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
    }

}
