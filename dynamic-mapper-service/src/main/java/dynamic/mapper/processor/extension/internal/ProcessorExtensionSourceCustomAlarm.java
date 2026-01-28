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

package dynamic.mapper.processor.extension.internal;

import org.joda.time.DateTime;

import com.google.protobuf.InvalidProtocolBufferException;

import dynamic.mapper.processor.extension.ProcessorExtensionInbound;
import dynamic.mapper.processor.flow.CumulocityObject;
import dynamic.mapper.processor.flow.DataPreparationContext;
import dynamic.mapper.processor.flow.Message;
import lombok.extern.slf4j.Slf4j;

/**
 * Internal extension for processing custom protobuf alarms using the Smart Java Function pattern.
 *
 * <p>This extension parses internal protobuf alarm payloads and returns Cumulocity alarm objects
 * using the builder pattern. It demonstrates:</p>
 * <ul>
 *   <li>Protobuf deserialization for internal formats</li>
 *   <li>Return-value based processing (no side effects)</li>
 *   <li>Builder pattern for alarm construction</li>
 *   <li>Proper error handling with context warnings</li>
 * </ul>
 *
 * <p>Input: Protobuf message with fields: timestamp, txt, alarmType, externalId, severity</p>
 * <p>Output: Cumulocity Alarm object</p>
 */
@Slf4j
public class ProcessorExtensionSourceCustomAlarm implements ProcessorExtensionInbound<byte[]> {

    @Override
    public CumulocityObject[] onMessage(Message<byte[]> message, DataPreparationContext context) {
        try {
            // 1. Parse the protobuf payload
            InternalCustomAlarmOuter.InternalCustomAlarm payloadProtobuf =
                InternalCustomAlarmOuter.InternalCustomAlarm.parseFrom(message.getPayload());

            // 2. Extract fields
            String externalId = payloadProtobuf.getExternalId().toString();
            String alarmType = payloadProtobuf.getAlarmType();
            String text = payloadProtobuf.getTxt();
            String severity = payloadProtobuf.getSeverity();
            DateTime time = new DateTime(payloadProtobuf.getTimestamp());

            log.info("{} - Processing internal custom alarm: type={}, severity={}, text={}, externalId={}, time={}",
                    context.getTenant(), alarmType, severity, text, externalId, time);

            // 3. Build and return Cumulocity alarm using builder pattern
            return new CumulocityObject[] {
                CumulocityObject.alarm()
                    .type(alarmType)
                    .severity(severity)
                    .text(text)
                    .time(time.toString())
                    .status("ACTIVE")
                    .externalId(externalId, context.getMapping().getExternalIdType())
                    .build()
            };

        } catch (InvalidProtocolBufferException e) {
            String errorMsg = "Failed to parse internal protobuf alarm: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        } catch (Exception e) {
            String errorMsg = "Failed to process internal custom alarm: " + e.getMessage();
            log.error("{} - {}", context.getTenant(), errorMsg, e);
            context.addWarning(errorMsg);
            return new CumulocityObject[0];
        }
    }
}
