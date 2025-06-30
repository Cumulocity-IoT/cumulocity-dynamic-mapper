/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.processor.processor.fixed;

import com.google.protobuf.InvalidProtocolBufferException;
import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.inbound.BaseProcessorInbound;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import org.joda.time.DateTime;

public class InternalProtobufProcessor extends BaseProcessorInbound<byte[]> {

    public InternalProtobufProcessor(ConfigurationRegistry configurationRegistry) {
        super(configurationRegistry);
    }

    @Override
    public byte[] deserializePayload(Mapping mapping, ConnectorMessage message){
        return message.getPayload();
    }

    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        if (MappingType.PROTOBUF_INTERNAL.equals(context.getMapping().mappingType)) {
            InternalCustomMeasurementOuter.InternalCustomMeasurement payloadProtobuf;
            try {
                payloadProtobuf = InternalCustomMeasurementOuter.InternalCustomMeasurement
                        .parseFrom((byte[]) context.getPayload());
            } catch (InvalidProtocolBufferException e) {
                throw new ProcessingException(e.getMessage());
            }

            context.addSubstitution("time", new DateTime(
                    payloadProtobuf.getTimestamp())
                    .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("c8y_GenericMeasurement.Module.value",
                    payloadProtobuf.getValue(), TYPE.NUMBER, RepairStrategy.DEFAULT,false);
            context.addSubstitution("type",
                    payloadProtobuf.getMeasurementType(), TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);
            context.addSubstitution("c8y_GenericMeasurement.Module.unit",
                    payloadProtobuf.getUnit(), TYPE.NUMBER, RepairStrategy.DEFAULT,false);

            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addSubstitution(context.getMapping().getGenericDeviceIdentifier(),
                    payloadProtobuf.getExternalId()
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

        }
    }

}