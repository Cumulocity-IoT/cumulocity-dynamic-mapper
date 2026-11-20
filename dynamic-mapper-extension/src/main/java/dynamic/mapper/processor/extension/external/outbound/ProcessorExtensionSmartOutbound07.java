/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor.extension.external.outbound;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.extension.ProcessorExtensionOutbound;
import dynamic.mapper.processor.model.DeviceMessage;
import dynamic.mapper.processor.model.JavaExtensionContext;
import dynamic.mapper.processor.model.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Java Extension equivalent of template-SMART-OUTBOUND-07.js
 *
 * <p>Sends a Cumulocity operation as a raw text command. Use it with a mapping of type Any Payload
 * (transformation Java Extension) for device protocols that expect a plain command string, for example an
 * AT command that ends with {@code $}.</p>
 *
 * <p>Input (Cumulocity operation):</p>
 * <pre>
 * {
 *   "deviceId": "12345",
 *   "c8y_Command": {
 *     "text": "GTRTO=gv300,3,,,,,,0001$"
 *   }
 * }
 * </pre>
 *
 * <p>Output (device message):</p>
 * <pre>
 * Topic: command/berlin_01      (the external id of the device)
 * Payload: AT+GTRTO=gv300,3,,,,,,0001$   (sent as is: no quotes, no JSON)
 * Content type: text/plain
 * </pre>
 *
 * <p>A {@code String} payload is sent as text. Use {@code payloadEncoding(DeviceMessage.ENCODING_BASE64)} or
 * {@code ENCODING_HEX} with a string, or return a {@code byte[]}, for a binary protocol. Requires the mapping to
 * have {@code useExternalId} enabled and an {@code externalIdType} configured.</p>
 */
@Slf4j
public class ProcessorExtensionSmartOutbound07 implements ProcessorExtensionOutbound<Object> {

    static final String COMMAND_PREFIX = "AT+";

    @Override
    @SuppressWarnings("unchecked")
    public DeviceMessage[] onMessage(Message<Object> message, JavaExtensionContext context)
            throws ProcessingException {
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();

        Object command = payload.get("c8y_Command");
        Object text = command instanceof Map<?, ?> commandMap ? commandMap.get("text") : null;
        if (text == null || text.toString().isEmpty()) {
            context.addWarning("Operation has no c8y_Command text - no message published.");
            return new DeviceMessage[0];
        }

        String externalId = context.getExternalId();
        if (externalId == null || externalId.isEmpty()) {
            context.addWarning("No external id resolved for the device - no message published.");
            return new DeviceMessage[0];
        }

        return new DeviceMessage[] {
            DeviceMessage.forTopic("command/" + externalId)
                // A string payload is sent as text; the prefix is added here so the operation only carries the command body
                .payload(COMMAND_PREFIX + text)
                .payloadEncoding(DeviceMessage.ENCODING_TEXT)
                .contentType("text/plain")
                .build()
        };
    }
}
