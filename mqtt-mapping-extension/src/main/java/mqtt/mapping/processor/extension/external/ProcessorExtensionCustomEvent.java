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

package mqtt.mapping.processor.extension.external;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.processor.model.RepairStrategy;


@Slf4j
@Component
public class ProcessorExtensionCustomEvent implements ProcessorExtension<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        CustomEventOuter.CustomEvent payloadProtobuf;
        try {
            payloadProtobuf = CustomEventOuter.CustomEvent
                    .parseFrom(context.getPayload());
        } catch (InvalidProtocolBufferException e) {
            throw new ProcessingException(e.getMessage());
        }
        Map<String, List<SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        postProcessingCache.put("time",
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
        postProcessingCache.put("type",
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
        log.info("New event over protobuf: {}, {}, {}, {}", payloadProtobuf.getTimestamp(),
                payloadProtobuf.getTxt(), payloadProtobuf.getEventType(),
                payloadProtobuf.getExternalId());
    }

}