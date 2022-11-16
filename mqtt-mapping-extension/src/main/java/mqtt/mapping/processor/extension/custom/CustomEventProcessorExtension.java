package mqtt.mapping.processor.extension.custom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import javax.ws.rs.ProcessingException;

import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue.TYPE;
import mqtt.mapping.processor.extension.ProcessorExtension;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;

@Slf4j
@Component
public class CustomEventProcessorExtension<O> implements ProcessorExtension<byte[]> {
        @Override
        public void extractFromSource(ProcessingContext<byte[]> context)
                        throws ProcessingException {
                if (MappingType.PROTOBUF_EXTENSION.equals(context.getMapping().mappingType)) {
                        CustomEventOuter.CustomEvent payloadProtobuf;
                        try {
                                payloadProtobuf = CustomEventOuter.CustomEvent
                                                .parseFrom(context.getPayload());
                        } catch (InvalidProtocolBufferException e) {
                                throw new ProcessingException(e.getMessage());
                        }
                        Map<String, ArrayList<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

                        postProcessingCache.put("time", new ArrayList<SubstituteValue>(
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
                        postProcessingCache.put(context.getMapping().targetAPI.identifier,
                                        new ArrayList<SubstituteValue>(Arrays.asList(
                                                        new SubstituteValue(
                                                                        new TextNode(payloadProtobuf.getExternalId()),
                                                                        TYPE.TEXTUAL,
                                                                        RepairStrategy.DEFAULT))));
                        log.info("New event over protobuf: {}, {}, {}, {}",payloadProtobuf.getTimestamp(), payloadProtobuf.getTxt(),payloadProtobuf.getEventType() , payloadProtobuf.getExternalId() );

                }
        }

}