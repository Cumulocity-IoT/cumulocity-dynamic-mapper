package mqtt.mapping.processor.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8yAgent;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.PayloadProcessor;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.RepairStrategy;
import mqtt.mapping.processor.protobuf.CustomMeasurementOuter;
import mqtt.mapping.service.MQTTClient;

@Slf4j
@Service
public class ProtobufProcessor<O> extends PayloadProcessor<byte[]> implements ApplicationContextAware {

        //@Autowired
        //private ApplicationContext applicationContext;
        private static ApplicationContext applicationContext;

        public ProtobufProcessor(ObjectMapper objectMapper, MQTTClient mqttClient, C8yAgent c8yAgent) {
                super(objectMapper, mqttClient, c8yAgent);
        }

        @Override
        public ProcessingContext<byte[]> deserializePayload(ProcessingContext<byte[]> context, MqttMessage mqttMessage)
                        throws IOException {
                context.setPayload(mqttMessage.getPayload());
                return context;
        }

        @Override
        public void extractFromSource(ProcessingContext<byte[]> context)
                        throws ProcessingException {
                if ("CustomMeasurement".equals(context.getMapping().getSubstitutions()[0].registeredType)) {
                        CustomMeasurementOuter.CustomMeasurement payloadProtobuf;
                        try {
                                payloadProtobuf = CustomMeasurementOuter.CustomMeasurement
                                                .parseFrom(context.getPayload());
                        } catch (InvalidProtocolBufferException e) {
                                throw new ProcessingException(e.getMessage());
                        }
                        Map<String, ArrayList<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

                        postProcessingCache
                                        .put("time",
                                                        new ArrayList<SubstituteValue>(
                                                                        Arrays.asList(new SubstituteValue(
                                                                                        new TextNode(new DateTime(
                                                                                                        payloadProtobuf.getTimestamp())
                                                                                                        .toString()),
                                                                                        TYPE.TEXTUAL,
                                                                                        RepairStrategy.DEFAULT))));
                        postProcessingCache.put("c8y_GenericMeasurement.Module.value",
                                        new ArrayList<SubstituteValue>(Arrays.asList(
                                                        new SubstituteValue(new FloatNode(payloadProtobuf.getValue()),
                                                                        TYPE.NUMBER,
                                                                        RepairStrategy.DEFAULT))));
                        postProcessingCache
                                        .put("type",
                                                        new ArrayList<SubstituteValue>(
                                                                        Arrays.asList(
                                                                                        new SubstituteValue(
                                                                                                        new TextNode(payloadProtobuf
                                                                                                                        .getMeasurementType()),
                                                                                                        TYPE.TEXTUAL,
                                                                                                        RepairStrategy.DEFAULT))));
                        postProcessingCache.put("c8y_GenericMeasurement.Module.unit",
                                        new ArrayList<SubstituteValue>(Arrays.asList(
                                                        new SubstituteValue(new TextNode(payloadProtobuf.getUnit()),
                                                                        TYPE.TEXTUAL,
                                                                        RepairStrategy.DEFAULT))));
                        postProcessingCache.put(context.getMapping().targetAPI.identifier,
                                        new ArrayList<SubstituteValue>(Arrays.asList(
                                                        new SubstituteValue(
                                                                        new TextNode(payloadProtobuf.getExternalId()),
                                                                        TYPE.TEXTUAL,
                                                                        RepairStrategy.DEFAULT))));

                        Object bean = getContext().getBean("CustomEvent");
                        log.info("loaded bean:{}", bean.getClass().getName());
                } else if ("CustomEvent".equals(context.getMapping().getSubstitutions()[0].registeredType)) {

                }
        }

        @Override
        public void setApplicationContext(ApplicationContext appContext) throws BeansException {
                log.info("Setting context was called:{}", appContext);
                applicationContext = appContext;
        }

        public static ApplicationContext getContext() {
                return applicationContext;
            }
}