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

package dynamic.mapper.processor.extension.external;

import jakarta.ws.rs.ProcessingException;

import dynamic.mapper.processor.model.SubstituteValue.TYPE;
import dynamic.mapper.processor.extension.ProcessorExtensionSource;
import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.model.RepairStrategy;
import org.joda.time.DateTime;

import com.google.protobuf.InvalidProtocolBufferException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessorExtensionCustomEvent implements ProcessorExtensionSource<byte[]> {
    @Override
    public void extractFromSource(ProcessingContext<byte[]> context)
            throws ProcessingException {
        CustomEventOuter.CustomEvent payloadProtobuf;
        try {
            byte[] payload = context.getPayload();
            if (payload == null) {
                log.info("{} - Preparing new event failed, payload == null",
                        context.getTenant());

            } else {
                log.info("{} - Preparing new event: {}", context.getTenant(),
                        new String(payload));
            }
            payloadProtobuf = CustomEventOuter.CustomEvent
                    .parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ProcessingException(e.getMessage());
        }

        context.addSubstitution("time", new DateTime(
                payloadProtobuf.getTimestamp())
                .toString(), TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);
        context.addSubstitution("text",
                payloadProtobuf.getTxt(), TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);
        context.addSubstitution("type", 
                payloadProtobuf.getEventType(), TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);

        // as the mapping uses useExternalId we have to map the id to
        // _IDENTITY_.externalId
        context.addSubstitution(context.getMapping().getGenericDeviceIdentifier(),
                payloadProtobuf.getExternalId()
                        .toString(),
                TYPE.TEXTUAL, RepairStrategy.DEFAULT,false);

        log.info("{} - New event over protobuf: {}, {}, {}, {}", context.getTenant(),
                payloadProtobuf.getTimestamp(),
                payloadProtobuf.getTxt(), payloadProtobuf.getEventType(),
                payloadProtobuf.getExternalId());
    }
}