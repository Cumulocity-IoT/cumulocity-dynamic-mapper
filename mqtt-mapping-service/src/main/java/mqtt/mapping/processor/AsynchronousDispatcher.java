package mqtt.mapping.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.commons.codec.binary.Hex;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class AsynchronousDispatcher implements MqttCallback {

    public static class MappingProcessor implements Callable<List<ProcessingContext<?>>> {

        List<Mapping> resolvedMappings;
        String topic;
        Map<MappingType, BasePayloadProcessor<?>> payloadProcessors;
        boolean sendPayload;
        MqttMessage mqttMessage;
        MappingComponent mappingStatusComponent;
        C8YAgent c8yAgent;
        ObjectMapper objectMapper;

        public MappingProcessor(List<Mapping> mappings, MappingComponent mappingStatusComponent, C8YAgent c8yAgent,
                String topic,
                Map<MappingType, BasePayloadProcessor<?>> payloadProcessors, boolean sendPayload,
                MqttMessage mqttMessage, ObjectMapper objectMapper) {
            this.resolvedMappings = mappings;
            this.mappingStatusComponent = mappingStatusComponent;
            this.c8yAgent = c8yAgent;
            this.topic = topic;
            this.payloadProcessors = payloadProcessors;
            this.sendPayload = sendPayload;
            this.mqttMessage = mqttMessage;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<ProcessingContext<?>> call() throws Exception {
            List<ProcessingContext<?>> processingResult = new ArrayList<ProcessingContext<?>>();
            MappingStatus mappingStatusUnspecified = mappingStatusComponent.getMappingStatus(null, true);
            resolvedMappings.forEach(mapping -> {
                MappingStatus mappingStatus = mappingStatusComponent.getMappingStatus(mapping, false);

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
                        if (c8yAgent.getServiceConfiguration().logPayload) {
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
                                serializedPayload = objectMapper.writeValueAsString((JsonNode) context.getPayload());
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
                            // processor.substituteInTargetAndSend(context);
                            ArrayList<C8YRequest> resultRequests = context.getRequests();
                            if (context.hasError() || resultRequests.stream().anyMatch(r -> r.hasError())) {
                                mappingStatus.errors++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Message could NOT be parsed, ignoring this message: {}", e.getMessage());
                        e.printStackTrace();
                        mappingStatus.errors++;
                    }
                } else {
                    mappingStatusUnspecified.errors++;
                    log.error("No process for MessageType: {} registered, ignoring this message!", mappingType);
                }
                processingResult.add(context);
            });
            return processingResult;
        }
    }

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
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

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

    public Future<List<ProcessingContext<?>>> processMessage(String topic, MqttMessage mqttMessage,
            boolean sendPayload) throws Exception {
        MappingStatus mappingStatusUnspecified = mappingStatusComponent.getMappingStatus(null, true);
        Future<List<ProcessingContext<?>>> futureProcessingResult = null;
        List<Mapping> resolvedMappings = new ArrayList<>();

        if (topic != null && !topic.startsWith("$SYS")) {
            if (mqttMessage.getPayload() != null) {
                try {
                    resolvedMappings = mqttClient.resolveMappings(topic);
                } catch (Exception e) {
                    log.warn("Error resolving appropriate map. Could NOT be parsed. Ignoring this message!", e);
                    mappingStatusUnspecified.errors++;
                    // TODO review if exception has to be thrown
                    // throw e;
                }
            } else {
                return futureProcessingResult;
            }
        } else {
            sysHandler.handleSysPayload(topic, mqttMessage);
            return futureProcessingResult;
        }

        futureProcessingResult = cachedThreadPool.submit(
                new MappingProcessor(resolvedMappings, mappingStatusComponent, c8yAgent, topic, payloadProcessors,
                        sendPayload, mqttMessage, objectMapper));

        return futureProcessingResult;

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