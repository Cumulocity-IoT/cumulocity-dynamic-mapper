package mqtt.mapping.processor.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.joda.time.DateTime;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.MappingType;
import mqtt.mapping.processor.ProcessingContext;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.ProcessorExtension;
import mqtt.mapping.processor.RepairStrategy;
import mqtt.mapping.processor.protobuf.CustomEventOuter;

@Slf4j
@Component
public class ProcessorExtensionCustomEvent<O> implements ProcessorExtension<byte[]> {
        @Override
        public void extractFromSource(ProcessingContext<byte[]> context)
                        throws ProcessingException {
                if (MappingType.PROTOBUF_STATIC.equals(context.getMapping().mappingType)) {
                        CustomEventOuter.CustomEvent payloadProtobuf;
                        try {
                                payloadProtobuf = CustomEventOuter.CustomEvent
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
                        postProcessingCache.put("text",
                                        new ArrayList<SubstituteValue>(Arrays.asList(
                                                        new SubstituteValue(new TextNode(payloadProtobuf.getTxt()),
                                                                        TYPE.TEXTUAL,
                                                                        RepairStrategy.DEFAULT))));
                        postProcessingCache
                                        .put("type",
                                                        new ArrayList<SubstituteValue>(
                                                                        Arrays.asList(
                                                                                        new SubstituteValue(
                                                                                                        new TextNode(payloadProtobuf
                                                                                                                        .getEventType()),
                                                                                                        TYPE.TEXTUAL,
                                                                                                        RepairStrategy.DEFAULT))));
                        postProcessingCache.put("c8y_GenericEvent.Module.unit",
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

                }
        }

}