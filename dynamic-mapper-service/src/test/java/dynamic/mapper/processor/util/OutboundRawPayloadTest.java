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
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.processor.ProcessingException;
import dynamic.mapper.processor.model.DeviceMessage;

class OutboundRawPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** As a Java extension: a string or bytes starting with a brace keep the regular JSON path. */
    private OutboundRawPayload.Raw resolve(Object payload, String encoding) throws ProcessingException {
        return OutboundRawPayload.resolve(DeviceMessage.create().payload(payload).payloadEncoding(encoding).build(),
                objectMapper, true);
    }

    /** As a Smart Function: that path never existed, so such a payload is sent unchanged. */
    private OutboundRawPayload.Raw resolveForSmartFunction(Object payload, String encoding)
            throws ProcessingException {
        return OutboundRawPayload.resolve(DeviceMessage.create().payload(payload).payloadEncoding(encoding).build(),
                objectMapper, false);
    }

    @Test
    void anObjectGoesThroughTheJsonPath() throws Exception {
        assertNull(resolve(Map.of("a", 1), null));
        assertNull(resolve(Map.of("a", 1), DeviceMessage.ENCODING_JSON));
    }

    @Test
    void aStringIsTextUnlessItLooksLikeAJsonObject() throws Exception {
        assertEquals("AT+X$", resolve("AT+X$", null).text());
        assertEquals("text/plain", resolve("AT+X$", null).contentType());
        assertNull(resolve("  {\"a\":1}", null));
        assertEquals("{not json}", resolve("{not json}", DeviceMessage.ENCODING_TEXT).text());
    }

    @Test
    void aJsonEncodedStringIsAJsonStringValue() throws Exception {
        OutboundRawPayload.Raw raw = resolve("reboot", DeviceMessage.ENCODING_JSON);

        assertEquals("\"reboot\"", raw.text());
        assertNull(raw.contentType());
    }

    @Test
    void base64AndHexAreDecodedAndWhitespaceIsIgnored() throws Exception {
        byte[] expected = { 0, 1, 2, (byte) 0xFF };

        assertArrayEquals(expected, resolve("AAEC /w==", DeviceMessage.ENCODING_BASE64).bytes());
        assertArrayEquals(expected, resolve("00 01 02 ff", DeviceMessage.ENCODING_HEX).bytes());
        assertEquals("application/octet-stream", resolve("0001", DeviceMessage.ENCODING_HEX).contentType());
    }

    @Test
    void aByteArrayIsBinaryUnlessItLooksLikeAJsonObject() throws Exception {
        byte[] bytes = { 1, 2, 3 };

        assertArrayEquals(bytes, resolve(bytes, null).bytes());
        assertNull(resolve("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
    }

    @Test
    void aMismatchedOrUnknownEncodingFails() {
        assertThrows(ProcessingException.class, () -> resolve(Map.of("a", 1), DeviceMessage.ENCODING_TEXT));
        assertThrows(ProcessingException.class, () -> resolve(new byte[] { 1 }, DeviceMessage.ENCODING_BASE64));
        assertThrows(ProcessingException.class, () -> resolve("not base64!", DeviceMessage.ENCODING_BASE64));
        assertThrows(ProcessingException.class, () -> resolve("0g", DeviceMessage.ENCODING_HEX));
        assertThrows(ProcessingException.class, () -> resolve("x", "gzip"));
    }

    @Test
    void anExplicitContentTypeWins() throws Exception {
        DeviceMessage message = DeviceMessage.create().payload("AT+X$").contentType("text/x-at").build();

        assertEquals("text/x-at", OutboundRawPayload.resolve(message, objectMapper, true).contentType());
    }

    @Test
    void aJsonDocumentThatIsNotAnObjectKeepsTheConnectorContentType() throws Exception {
        for (String json : new String[] { "[1,2]", "  [ {\"a\":1} ]", "123", "true", "null", "\"abc\"" }) {
            OutboundRawPayload.Raw raw = resolve(json, null);

            assertEquals(json, raw.text(), json);
            assertNull(raw.contentType(), json + " must not get a content type of its own");
        }
    }

    @Test
    void textThatOnlyStartsLikeJsonIsStillText() throws Exception {
        for (String text : new String[] { "1,2,3", "true story", "nothing", "[1,2", "-", "12abc" }) {
            assertEquals("text/plain", resolve(text, null).contentType(), text);
        }
    }

    @Test
    void anExplicitTextEncodingIsTextEvenWhenItIsJson() throws Exception {
        assertEquals("text/plain", resolve("[1,2]", DeviceMessage.ENCODING_TEXT).contentType());
    }

    @Test
    void jsonBytesThatAreNotAnObjectAreSentAsJsonText() throws Exception {
        OutboundRawPayload.Raw raw = resolve("[1,2]".getBytes(java.nio.charset.StandardCharsets.UTF_8), null);

        assertEquals("[1,2]", raw.text());
        assertNull(raw.bytes());
        assertNull(raw.contentType());
    }

    @Test
    void binaryThatStartsLikeJsonIsStillBinary() throws Exception {
        byte[] bytes = { '1', 0, (byte) 0xFF, '[' };

        OutboundRawPayload.Raw raw = resolve(bytes, null);

        assertArrayEquals(bytes, raw.bytes());
        assertEquals("application/octet-stream", raw.contentType());
    }

    @Test
    void jsonEncodingWithBytesLeavesThemToTheJsonPath() throws Exception {
        assertNull(resolve(new byte[] { 1, 2 }, DeviceMessage.ENCODING_JSON));
    }

    @Test
    void aSmartFunctionStringThatIsAJsonObjectIsSentUnchangedWithTheConnectorContentType() throws Exception {
        OutboundRawPayload.Raw raw = resolveForSmartFunction("{\"a\":1}", null);

        assertEquals("{\"a\":1}", raw.text());
        assertNull(raw.bytes());
        assertNull(raw.contentType());
    }

    @Test
    void aSmartFunctionStringThatOnlyStartsWithABraceIsText() throws Exception {
        assertEquals("text/plain", resolveForSmartFunction("{not json}", null).contentType());
        assertEquals("text/plain", resolveForSmartFunction("{\"a\":1} trailing", null).contentType());
    }

    @Test
    void aSmartFunctionObjectStillGoesThroughTheJsonPath() throws Exception {
        assertNull(resolveForSmartFunction(Map.of("a", 1), null));
    }
}
