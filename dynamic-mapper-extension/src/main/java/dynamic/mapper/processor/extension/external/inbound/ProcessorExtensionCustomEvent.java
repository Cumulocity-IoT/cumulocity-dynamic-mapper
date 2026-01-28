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

package dynamic.mapper.processor.extension.external.inbound;

import com.google.protobuf.InvalidProtocolBufferException;

import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DataPreparationContext;
import dynamic.mapper.processor.flow.Message;
import org.joda.time.DateTime;

import lombok.extern.slf4j.Slf4j;

/**
 * Extension for processing custom protobuf events using the Smart Java Function pattern.
 *
 * <p>This extension parses protobuf payloads and returns Cumulocity event objects using
 * the builder pattern. It demonstrates:</p>
 * <ul>
 *   <li>Protobuf deserialization</li>
 *   <li>Return-value based processing (no side effects)</li>
 *   <li>Builder pattern for clean object construction</li>
 *   <li>Proper error handling with context warnings</li>
 * </ul>
 *
 * <p>Input: Protobuf message with fields: timestamp, txt, eventType, externalId</p>
 * <p>Output: Cumulocity Event object</p>
 */
@Slf4j
public class ProcessorExtensionCustomEvent implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        try {
            // 1. Parse the protobuf payload
            byte[] payload = message.getPayload();
            if (payload == null) {
                String errorMsg = "Protobuf event payload is null";
                log.warn("{} - {}", context.getTenant(), errorMsg);
                context.addWarning(errorMsg);
                return new CumulocityObject[0];
            }

            log.debug("{} - Parsing protobuf event, payload size: {} bytes",
                    context.getTenant(), payload.length);

            CustomEventOuter.CustomEvent payloadProtobuf =
                CustomEventOuter.CustomEvent.parseFrom(payload);

            // 2. Extract fields
            String externalId = payloadProtobuf.getExternalId().toString();
            String eventType = payloadProtobuf.getEventType();
            String text = payloadProtobuf.getTxt();
            DateTime time = new DateTime(payloadProtobuf.getTimestamp());

            log.info("{} - Processing custom event: type={}, text={}, externalId={}, time={}",
                    context.getTenant(), eventType, text, externalId, time);

            // 3. Build and return Cumulocity event using builder pattern
            return new CumulocityObject[] {
                CumulocityObject.event()
                    .type(eventType)
                    .text(text)
                    .time(time.toString())
                    .externalId(externalId, context.getMapping().getExternalIdType())
                    .build()
            };

        } catch (InvalidProtocolBufferException e) {
            String errorMsg = "Failed to parse protobuf event: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        } catch (Exception e) {
            String errorMsg = "Failed to process custom event: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
