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

package dynamic.mapping.processor.processor.fixed;

import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.InvalidProtocolBufferException;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingSubstitution;
import dynamic.mapping.processor.inbound.BasePayloadProcessorInbound;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.MappingType;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import org.joda.time.DateTime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class StaticProtobufProcessor extends BasePayloadProcessorInbound<byte[]> {

    public StaticProtobufProcessor(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public ProcessingContext<byte[]> deserializePayload(Mapping mapping, ConnectorMessage message)
            throws IOException {
        ProcessingContext<byte[]> context = new ProcessingContext<byte[]>();
        context.setPayload(message.getPayload());
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        if (MappingType.PROTOBUF_STATIC.equals(context.getMapping().mappingType)) {
            StaticCustomMeasurementOuter.StaticCustomMeasurement payloadProtobuf;
            try {
                payloadProtobuf = StaticCustomMeasurementOuter.StaticCustomMeasurement
                        .parseFrom((byte[]) context.getPayload());
            } catch (InvalidProtocolBufferException e) {
                throw new ProcessingException(e.getMessage());
            }
            Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

            postProcessingCache
                    .put("time",
                            new ArrayList<MappingSubstitution.SubstituteValue>(
                                    Arrays.asList(new MappingSubstitution.SubstituteValue(
                                            new TextNode(new DateTime(
                                                    payloadProtobuf.getTimestamp())
                                                    .toString()),
                                            MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                            RepairStrategy.DEFAULT))));
            postProcessingCache.put("c8y_GenericMeasurement.Module.value",
                    new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                            new MappingSubstitution.SubstituteValue(new FloatNode(payloadProtobuf.getValue()),
                                    MappingSubstitution.SubstituteValue.TYPE.NUMBER,
                                    RepairStrategy.DEFAULT))));
            postProcessingCache
                    .put("type",
                            new ArrayList<MappingSubstitution.SubstituteValue>(
                                    Arrays.asList(
                                            new MappingSubstitution.SubstituteValue(
                                                    new TextNode(payloadProtobuf
                                                            .getMeasurementType()),
                                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                                    RepairStrategy.DEFAULT))));
            postProcessingCache.put("c8y_GenericMeasurement.Module.unit",
                    new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                            new MappingSubstitution.SubstituteValue(new TextNode(payloadProtobuf.getUnit()),
                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                    RepairStrategy.DEFAULT))));
            postProcessingCache.put(context.getMapping().targetAPI.identifier,
                    new ArrayList<MappingSubstitution.SubstituteValue>(Arrays.asList(
                            new MappingSubstitution.SubstituteValue(
                                    new TextNode(payloadProtobuf.getExternalId()),
                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                    RepairStrategy.DEFAULT))));

        }
    }

@Override
public void applyFilter(ProcessingContext<byte[]> context) {
        //do nothing
}
}