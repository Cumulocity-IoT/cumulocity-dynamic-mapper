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

package dynamic.mapper.processor.inbound.processor;

import com.google.protobuf.InvalidProtocolBufferException;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.processor.fixed.InternalCustomMeasurementOuter;
import dynamic.mapper.service.MappingService;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;


import org.apache.camel.Exchange;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InternalProtobufProcessor extends BaseProcessor {

    @Autowired
    private MappingService mappingService;
   
    @Override
    public void process(Exchange exchange) throws Exception {
        ProcessingContext<byte[]> context = getProcessingContextAsByteArray(exchange);
        Mapping mapping = context.getMapping();
        String tenant = context.getTenant();

        try {
            extractFromSource(context);
        } catch (Exception e) {
            log.error("Error in InternalProtobufProcessor for mapping: {}",
                    context.getMapping().getName(), e);
            MappingStatus mappingStatus = mappingService.getMappingStatus(tenant, mapping);
            context.addError(new ProcessingException("InternalProtobufProcessor processing failed", e));
            mappingStatus.errors++;
            mappingService.increaseAndHandleFailureCount(tenant, mapping, mappingStatus);
        }

    }

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
                    payloadProtobuf.getValue(), TYPE.NUMBER, RepairStrategy.DEFAULT, false);
            context.addSubstitution("type",
                    payloadProtobuf.getMeasurementType(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addSubstitution("c8y_GenericMeasurement.Module.unit",
                    payloadProtobuf.getUnit(), TYPE.NUMBER, RepairStrategy.DEFAULT, false);

            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addSubstitution(context.getMapping().getGenericDeviceIdentifier(),
                    payloadProtobuf.getExternalId()
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);

        }
    }

    @SuppressWarnings("unchecked")
    ProcessingContext<byte[]> getProcessingContextAsByteArray(Exchange exchange) {
        return exchange.getIn().getHeader("processingContext", ProcessingContext.class);
    }

}