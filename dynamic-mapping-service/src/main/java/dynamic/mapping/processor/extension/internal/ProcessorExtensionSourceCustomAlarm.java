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

package dynamic.mapping.processor.extension.internal;

import org.joda.time.DateTime;

import com.google.protobuf.InvalidProtocolBufferException;

import dynamic.mapping.processor.model.SubstituteValue.TYPE;
import dynamic.mapping.processor.extension.ProcessorExtensionSource;
import dynamic.mapping.processor.model.ProcessingContext;
import dynamic.mapping.processor.model.RepairStrategy;
import jakarta.ws.rs.ProcessingException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessorExtensionSourceCustomAlarm implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        InternalCustomAlarmOuter.InternalCustomAlarm payloadProtobuf;
        try {
            payloadProtobuf = InternalCustomAlarmOuter.InternalCustomAlarm
                    .parseFrom(context.getPayload());
            context.addToProcessingCache("time", new DateTime(
                    payloadProtobuf.getTimestamp())
                    .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addToProcessingCache("text",
                    payloadProtobuf.getTxt(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            context.addToProcessingCache("type",
                    payloadProtobuf.getAlarmType(), TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            // as the mapping uses useExternalId we have to map the id to
            // _IDENTITY_.externalId
            context.addToProcessingCache(context.getMapping().getGenericDeviceIdentifier(),
                    payloadProtobuf.getExternalId()
                            .toString(),
                    TYPE.TEXTUAL, RepairStrategy.DEFAULT, false);
            log.info("Tenant {} - New alarm over protobuf: {}, {}, {}, {}, {}", context.getTenant(),
                    payloadProtobuf.getTimestamp(),
                    payloadProtobuf.getTxt(), payloadProtobuf.getAlarmType(),
                    payloadProtobuf.getExternalId(), payloadProtobuf.getSeverity());
        } catch (InvalidProtocolBufferException e) {
            throw new ProcessingException(e.getMessage());
        }

    }
}