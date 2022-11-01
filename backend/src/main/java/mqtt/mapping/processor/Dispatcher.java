package mqtt.mapping.processor;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingStatus;
import mqtt.mapping.model.SnoopStatus;
import mqtt.mapping.processor.handler.SysHandler;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class Dispatcher implements MqttCallback {

    @Autowired
    protected C8yAgent c8yAgent;

    @Autowired
    protected MQTTClient mqttClient;

    @Autowired
    SysHandler sysHandler;

    @Autowired
    Map<MappingType, PayloadProcessor<?,?>> payloadProcessors;

    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        processMessage(topic, mqttMessage, true);
    }

    public List<ProcessingContext<?>> processMessage(String topic, MqttMessage mqttMessage, boolean sendPayload)
            throws Exception {
        MappingStatus mappingStatusUnspecified = mqttClient.getMappingStatus(null, true);
        List<ProcessingContext<?>> processingResult = new ArrayList<ProcessingContext<?>>();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                List<Mapping> resolveMappings = new ArrayList<>();
                try {
                    resolveMappings = mqttClient.resolveMappings(topic);
                } catch (Exception e) {
                    log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message: {}", e);
                    mappingStatusUnspecified.errors++;
                    throw e;
                }

                resolveMappings.forEach(mapping -> {
                    MappingStatus mappingStatus = mqttClient.getMappingStatus(mapping, false);
                    
                    ProcessingContext context;
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
                    PayloadProcessor<?,?> processor = payloadProcessors.get(mappingType);
                    if (processor != null) {
                        processor.deserializePayload(context, mqttMessage);
                        if (mqttClient.getServiceConfiguration().logPayload) {
                            log.info("New message on topic: '{}', wrapped message: {}", context.getTopic(),
                                    context.getPayload().toString());
                        } else {
                            log.info("New message on topic: '{}'", context.getTopic());
                        }
                        try {
                            mappingStatus.messagesReceived++;
                            if (mapping.snoopStatus == SnoopStatus.ENABLED
                            || mapping.snoopStatus == SnoopStatus.STARTED ) {
                                if( context.getPayload() instanceof String){
                                    String payload = (String) context.getPayload();
                                    mappingStatus.snoopedTemplatesActive++;
                                    mappingStatus.snoopedTemplatesTotal = mapping.snoopedTemplates.size();
                                    mapping.addSnoopedTemplate(payload);
    
                                    log.debug("Adding snoopedTemplate to map: {},{},{}", mapping.subscriptionTopic,
                                            mapping.snoopedTemplates.size(),
                                            mapping.snoopStatus);
                                    mqttClient.setMappingDirty(mapping);

                                }
                            } else {
                                processor.extractFromSource(context);
                                processor.substituteInTargetAndSend(context);
                                ArrayList<C8YRequest> requests = context.getRequests();
                                if (context.hasError() || requests.stream().anyMatch(r -> r.hasError())) {
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
