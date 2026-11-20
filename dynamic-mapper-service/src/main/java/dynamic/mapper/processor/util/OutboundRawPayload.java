/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
 * @authors Christof Strack
 */

package dynamic.mapper.processor.util;

import java.util.Base64;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DeviceMessage;

/**
 * Decides how the payload of an outbound {@link DeviceMessage} is put on the wire, for every transformation
 * that returns one: Smart Functions and Java extensions.
 *
 * <p>A payload is either a JSON object, which goes through the regular clone, enrich and serialize path (this
 * class returns {@code null}), or a {@link Raw} payload that is sent unchanged: text, or bytes.</p>
 *
 * <ul>
 *   <li>{@code "text"} requires a string and sends it as is.</li>
 *   <li>{@code "base64"} and {@code "hex"} require a string that is decoded to bytes.</li>
 *   <li>{@code "json"} with a string sends it as a JSON string value.</li>
 *   <li>No encoding, a string: text.</li>
 *   <li>No encoding, {@code byte[]} (Java extensions): sent as bytes.</li>
 *   <li>No encoding, a string or {@code byte[]} that is a JSON document: sent unchanged, with no content type of
 *       its own so the connector keeps its JSON default. A Java extension's string was sent that way before, so
 *       its content type must not change.</li>
 *   <li>A Java extension's string or {@code byte[]} that starts with <code>{</code> is a JSON object it serialized
 *       itself: it is left to the regular path, which enriches it and may route it to a Cumulocity API. A Smart
 *       Function never had that path (a string cannot be cloned into an object), so there such a payload is sent
 *       unchanged like any other JSON document.</li>
 * </ul>
 */
public final class OutboundRawPayload {

    /**
     * A payload that is sent as is.
     *
     * @param text        what the request shows: the text itself, or the Base64 or hex text of a binary payload
     * @param bytes       the bytes to send, {@code null} for text
     * @param contentType the explicit or default content type, {@code null} only for a JSON string value
     *                    without an explicit one
     */
    public record Raw(String text, byte[] bytes, String contentType) {
    }

    public static final String CONTENT_TYPE_TEXT = "text/plain";
    public static final String CONTENT_TYPE_BINARY = "application/octet-stream";

    private OutboundRawPayload() {
    }

    /**
     * Returns the payload to send unchanged, or {@code null} when it is a JSON object (or JSON text) for the
     * regular path.
     *
     * @param jsonObjectTextUsesRegularPath {@code true} for a Java extension, whose string or bytes that start with
     *                                      <code>{</code> keep the regular path; {@code false} for a Smart Function
     * @throws ProcessingException for an unknown encoding, a payload that does not suit the stated encoding, or
     *                             text that is not valid Base64 or hex
     */
    public static Raw resolve(DeviceMessage deviceMessage, ObjectMapper objectMapper,
            boolean jsonObjectTextUsesRegularPath) throws ProcessingException {
        String encoding = deviceMessage.getPayloadEncoding();
        Object payload = deviceMessage.getPayload();
        String explicitContentType = deviceMessage.getContentType();

        if (encoding != null && !DeviceMessage.ENCODING_JSON.equals(encoding)
                && !DeviceMessage.ENCODING_TEXT.equals(encoding)
                && !DeviceMessage.ENCODING_BASE64.equals(encoding)
                && !DeviceMessage.ENCODING_HEX.equals(encoding)) {
            throw new ProcessingException(String.format(
                    "Unsupported payloadEncoding '%s', expected '%s', '%s', '%s' or '%s'", encoding,
                    DeviceMessage.ENCODING_JSON, DeviceMessage.ENCODING_TEXT, DeviceMessage.ENCODING_BASE64,
                    DeviceMessage.ENCODING_HEX));
        }
        boolean binary = DeviceMessage.ENCODING_BASE64.equals(encoding) || DeviceMessage.ENCODING_HEX.equals(encoding);
        if ((DeviceMessage.ENCODING_TEXT.equals(encoding) || binary) && !(payload instanceof String)) {
            throw new ProcessingException(
                    String.format("payloadEncoding '%s' requires a string payload", encoding));
        }

        if (binary) {
            String encoded = (String) payload;
            return new Raw(encoded, decodeBinary(encoded, encoding),
                    explicitContentType != null ? explicitContentType : CONTENT_TYPE_BINARY);
        }
        if (payload instanceof byte[] bytes) {
            if (DeviceMessage.ENCODING_JSON.equals(encoding)
                    || (jsonObjectTextUsesRegularPath && startsWithBrace(bytes))) {
                return null;
            }
            if (isJsonDocument(bytes, objectMapper)) {
                // Legacy: JSON bytes were sent as JSON text
                return new Raw(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), null, explicitContentType);
            }
            return new Raw(Base64.getEncoder().encodeToString(bytes), bytes,
                    explicitContentType != null ? explicitContentType : CONTENT_TYPE_BINARY);
        }
        if (!(payload instanceof String text)) {
            return null;
        }
        if (DeviceMessage.ENCODING_JSON.equals(encoding)) {
            try {
                // Not a plain text payload: no content type default, so the connector keeps its JSON default
                return new Raw(objectMapper.writeValueAsString(payload), null, explicitContentType);
            } catch (JsonProcessingException e) {
                throw new ProcessingException("Failed to serialize string payload as JSON: " + e.getMessage(), e);
            }
        }
        if (encoding == null && jsonObjectTextUsesRegularPath && startsWithBrace(text)) {
            return null;
        }
        String contentType = explicitContentType;
        if (contentType == null && !(encoding == null && isJsonDocument(text, objectMapper))) {
            contentType = CONTENT_TYPE_TEXT;
        }
        return new Raw(text, null, contentType);
    }

    private static boolean startsWithBrace(String text) {
        return text.stripLeading().startsWith("{");
    }

    /** Looks at the first non-whitespace byte only: no copy, however large the payload. */
    private static boolean startsWithBrace(byte[] bytes) {
        int first = firstNonWhitespace(bytes);
        return first >= 0 && bytes[first] == '{';
    }

    private static int firstNonWhitespace(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return i;
            }
        }
        return -1;
    }

    /** Whether a byte could start a JSON object, array, string, number, {@code true}, {@code false} or {@code null}. */
    private static boolean canStartJsonDocument(int c) {
        return c == '{' || c == '[' || c == '"' || c == '-' || (c >= '0' && c <= '9') || c == 't' || c == 'f' || c == 'n';
    }

    private static boolean isJsonDocument(String text, ObjectMapper objectMapper) {
        String trimmed = text.stripLeading();
        if (trimmed.isEmpty() || !canStartJsonDocument(trimmed.charAt(0))) {
            return false; // the common case, e.g. "AT+...": no parse attempt
        }
        try {
            objectMapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isJsonDocument(byte[] bytes, ObjectMapper objectMapper) {
        int first = firstNonWhitespace(bytes);
        if (first < 0 || !canStartJsonDocument(bytes[first])) {
            return false; // binary data is rejected on its first byte, without a parse
        }
        try {
            objectMapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] decodeBinary(String encoded, String encoding) throws ProcessingException {
        String compact = encoded.replaceAll("\\s+", "");
        try {
            return DeviceMessage.ENCODING_HEX.equals(encoding)
                    ? HexFormat.of().parseHex(compact)
                    : Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw new ProcessingException(
                    String.format("payload is not valid %s: %s", encoding, e.getMessage()), e);
        }
    }
}
