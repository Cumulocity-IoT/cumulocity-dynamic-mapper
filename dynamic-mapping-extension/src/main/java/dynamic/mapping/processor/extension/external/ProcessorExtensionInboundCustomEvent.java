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

package dynamic.mapping.processor.extension.external;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.ws.rs.ProcessingException;

import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.extension.ProcessorExtensionInbound;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@Component
public class ProcessorExtensionInboundCustomEvent implements ProcessorExtensionInbound<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        CustomEventOuter.CustomEvent payloadProtobuf;
        try {
                byte[] payload = context.getPayload();
                if (payload == null) {
                   log.info("Preparing new event failed, payload == null");

                } else {
                   log.info("Preparing new event: {}", new String(payload));
                }
                payloadProtobuf = CustomEventOuter.CustomEvent
                    .parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ProcessingException(e.getMessage());
        }
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        postProcessingCache.put("time",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(new MappingSubstitution.SubstituteValue(
                                new TextNode(new DateTime(
                                        payloadProtobuf.getTimestamp())
                                        .toString()),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));
        postProcessingCache.put("text",
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(new TextNode(payloadProtobuf.getTxt()),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));
        postProcessingCache.put("type",
                new ArrayList<MappingSubstitution.SubstituteValue>(
                        Arrays.asList(
                                new MappingSubstitution.SubstituteValue(
                                        new TextNode(payloadProtobuf
                                                .getEventType()),
                                        MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                        RepairStrategy.DEFAULT))));
        postProcessingCache.put(context.getMapping().targetAPI.identifier,
                new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                        new MappingSubstitution.SubstituteValue(
                                new TextNode(payloadProtobuf.getExternalId()),
                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                RepairStrategy.DEFAULT))));
        log.info("New event over protobuf: {}, {}, {}, {}", payloadProtobuf.getTimestamp(),
                payloadProtobuf.getTxt(), payloadProtobuf.getEventType(),
                payloadProtobuf.getExternalId());
    }

}