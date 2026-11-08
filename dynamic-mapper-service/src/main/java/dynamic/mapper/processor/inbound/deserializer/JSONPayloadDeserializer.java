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

package dynamic.mapper.processor.inbound.deserializer;

import java.io.IOException;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.util.PayloadClassifier;

public class JSONPayloadDeserializer implements PayloadDeserializer<Object> {

    @Override
    public Object deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        byte[] bytes = message.getPayload();
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Payload is null or empty");
        }

        // Well-formed UTF-8 only (also strips a UTF-8 BOM): a lenient decode would turn binary or
        // Latin-1 bytes into U+FFFD and report a misleading JSON syntax error further down. The
        // printable-text heuristic of the Explorer is deliberately not applied, since valid JSON may
        // contain e.g. U+0085 inside a string; the parser decides what is valid JSON.
        String text = PayloadClassifier.decodeUtf8(bytes);
        if (text == null) {
            String format = PayloadClassifier.detectBinaryFormat(bytes);
            throw new IOException("Payload is not UTF-8 text (" + bytes.length + " bytes"
                    + (format != null ? ", " + format : "")
                    + "), a JSON mapping cannot read it - use an Any Payload mapping for binary data");
        }
        try {
            return Json.parseJson(text);
        } catch (Exception e) {
            throw new IOException("Failed to deserialize JSON payload: " + e.getMessage(), e);
        }
    }
}
