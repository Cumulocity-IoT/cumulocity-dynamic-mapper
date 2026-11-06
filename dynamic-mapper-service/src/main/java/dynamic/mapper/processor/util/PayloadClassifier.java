/*
 * Copyright (c) 2025 Cumulocity GmbH.
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
 *  @authors Christof Strack
 *
 */
package dynamic.mapper.processor.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Classifies the raw payload of a broker message (Message Explorer, processors, logging).
 *
 * <p>Text detection is strict: bytes must be well-formed UTF-8 (a lenient decode would replace
 * invalid sequences with U+FFFD and wrongly report e.g. Latin-1 or Protobuf data as text) and
 * must not contain control characters other than tab, CR and LF.
 */
public final class PayloadClassifier {

    public enum Type { EMPTY, JSON, XML, TEXT, BINARY }

    /**
     * @param type         detected payload type
     * @param text         decoded text (without a UTF-8 BOM) for all non-binary types, else null
     * @param binaryFormat well-known container/format name for BINARY payloads (e.g. GZIP), else null
     */
    public record Result(Type type, String text, String binaryFormat) {
        public boolean isBinary() {
            return type == Type.BINARY;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadClassifier() {
    }

    public static Result classify(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Result(Type.EMPTY, "", null);
        }
        String text = decodeStrictUtf8(bytes);
        if (text == null) {
            return new Result(Type.BINARY, null, detectBinaryFormat(bytes));
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return new Result(Type.TEXT, text, null);
        }
        char first = trimmed.charAt(0);
        if ((first == '{' || first == '[') && isJson(trimmed)) {
            return new Result(Type.JSON, text, null);
        }
        if (first == '<' && trimmed.endsWith(">")) {
            return new Result(Type.XML, text, null);
        }
        return new Result(Type.TEXT, text, null);
    }

    /** Returns the decoded text, or null if the bytes are not well-formed, printable UTF-8. */
    public static String decodeStrictUtf8(byte[] bytes) {
        String s = decodeUtf8(bytes);
        if (s == null) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 ? (c != '\n' && c != '\r' && c != '\t') : (c == 0x7f || (c >= 0x80 && c <= 0x9f))) {
                return null;
            }
        }
        return s;
    }

    /**
     * Returns the decoded text (a UTF-8 BOM is skipped), or null if the bytes are not well-formed
     * UTF-8. Unlike {@link #decodeStrictUtf8} it does not apply the printable-text heuristic, so
     * valid text such as a JSON string holding U+0085 is accepted and syntax checks are left to the
     * caller's parser.
     */
    public static String decodeUtf8(byte[] bytes) {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        return s;
    }

    /** Maximum number of payload characters written to a log line. */
    public static final int LOG_PREVIEW_CHARS = 2000;

    /**
     * Renders a payload for a log line. Text payloads are shown as text (truncated);
     * binary payloads are described by size and format instead of being decoded as UTF-8,
     * which would put replacement characters and control bytes into the log.
     */
    public static String describeForLog(byte[] bytes) {
        if (bytes == null) {
            return "<null>";
        }
        Result r = classify(bytes);
        if (r.isBinary()) {
            return "<binary " + bytes.length + " bytes" + (r.binaryFormat() != null ? ", " + r.binaryFormat() : "") + ">";
        }
        String text = r.text();
        return text.length() > LOG_PREVIEW_CHARS
                ? text.substring(0, LOG_PREVIEW_CHARS) + "... (" + bytes.length + " bytes)"
                : text;
    }

    /** Strips a leading UTF-8 byte order mark from the text, if present. */
    public static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private static boolean hasUtf8Bom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF;
    }

    private static boolean isJson(String s) {
        try {
            JsonNode node = MAPPER.readTree(s);
            return node != null && (node.isObject() || node.isArray());
        } catch (Exception e) {
            return false;
        }
    }

    /** Recognizes common formats by their magic bytes; null if unknown (e.g. Protobuf has none). */
    public static String detectBinaryFormat(byte[] b) {
        if (startsWith(b, 0x1f, 0x8b)) return "GZIP";
        if (startsWith(b, 0x50, 0x4b, 0x03, 0x04)) return "ZIP";
        if (startsWith(b, 0x28, 0xb5, 0x2f, 0xfd)) return "ZSTD";
        if (startsWith(b, 0x04, 0x22, 0x4d, 0x18)) return "LZ4";
        if (startsWith(b, 'O', 'b', 'j', 0x01)) return "AVRO";
        if (startsWith(b, 0x89, 'P', 'N', 'G')) return "PNG";
        if (startsWith(b, 0xff, 0xd8, 0xff)) return "JPEG";
        if (startsWith(b, '%', 'P', 'D', 'F')) return "PDF";
        if (startsWith(b, 0xfe, 0xff) || startsWith(b, 0xff, 0xfe)) return "UTF-16 text";
        if (startsWith(b, 'P', 'A', 'R', '1')) return "PARQUET";
        return null;
    }

    private static boolean startsWith(byte[] b, int... magic) {
        if (b.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((b[i] & 0xff) != magic[i]) return false;
        }
        return true;
    }
}
