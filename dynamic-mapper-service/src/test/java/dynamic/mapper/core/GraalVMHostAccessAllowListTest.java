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
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dynamic.mapper.processor.model.InputMessage;

/**
 * Pins the explicit host-access allow-list ({@link GraalVMContextService#getHostAccess()}) and the
 * injected {@code atob}/{@code btoa} functions. The context is built the way production builds it,
 * but deliberately <em>without</em> {@code js.text-encoding}: the polyfill must not depend on it.
 */
class GraalVMHostAccessAllowListTest {

    private Context context;

    @BeforeEach
    void setUp() {
        GraalVMContextService service = new GraalVMContextService();
        context = Context.newBuilder("js")
                .allowHostAccess(service.getHostAccess())
                .allowHostClassLookup(GraalVMContextService::isAllowedHostClass)
                .build();
        GraalVMContextService.installBase64Functions(context);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ── Allow-list: closed by default ─────────────────────────────────────

    @Test
    void getClass_isNotExposedOnAllowedHostObjects() {
        context.getBindings("js").putMember("msg",
                new InputMessage("{}", "t", null, null, null, "now", null, java.util.Map.of()));
        assertEquals("undefined", context.eval("js", "typeof msg.getClass").asString());
    }

    @Test
    void unlistedHostObject_hasNoAccessibleMembers() {
        context.getBindings("js").putMember("sb", new StringBuilder("secret"));
        assertEquals("undefined", context.eval("js", "typeof sb.append").asString());
    }

    @Test
    void unlistedHostObject_publicFieldIsNotAccessible() {
        context.getBindings("js").putMember("probe", new PublicFieldProbe());
        assertEquals("undefined", context.eval("js", "typeof probe.value").asString());
    }

    @Test
    void javaType_stillRestrictedToHostClassAllowList() {
        assertThrows(PolyglotException.class, () -> context.eval("js", "Java.type('java.lang.Runtime')"));
        assertThrows(PolyglotException.class, () -> context.eval("js", "Java.type('java.io.File')"));
    }

    // ── Allow-list: what scripts legitimately use stays open ──────────────

    @Test
    void inputMessage_fieldsAndGettersAreAccessible() {
        context.getBindings("js").putMember("msg",
                new InputMessage("{}", "a/b", "client-1", null, null, "now", "conn", java.util.Map.of()));
        assertEquals("a/b", context.eval("js", "msg.topic").asString());
        assertEquals("client-1", context.eval("js", "msg.clientId").asString());
    }

    @Test
    void javaCollectionsViaJavaType_work() {
        assertEquals(2, context.eval("js", """
                var ArrayList = Java.type('java.util.ArrayList');
                var HashMap = Java.type('java.util.HashMap');
                var l = new ArrayList(); l.add('a'); l.add('b');
                var m = new HashMap(); m.put('k', l.size());
                m.get('k');
                """).asInt());
    }

    @Test
    void substitutionTypes_areConstructibleAndWritable() {
        assertEquals("x", context.eval("js", """
                var SubstituteValue = Java.type('dynamic.mapper.processor.model.SubstituteValue');
                var TYPE = Java.type('dynamic.mapper.processor.model.SubstituteValue$TYPE');
                var Repair = Java.type('dynamic.mapper.model.RepairStrategy');
                var v = new SubstituteValue('x', TYPE.TEXTUAL, Repair.DEFAULT, false);
                v.value;
                """).asString());
    }

    @Test
    void base64ViaJavaType_stillWorksForUserCode() {
        assertEquals("aGk=", context.eval("js", """
                var B64 = Java.type('java.util.Base64');
                var Cs = Java.type('java.nio.charset.StandardCharsets');
                var Arrays = Java.type('java.util.Arrays');
                var bb = Cs.UTF_8.encode('hi');
                B64.getEncoder().encodeToString(Arrays.copyOf(bb.array(), bb.limit()));
                """).asString());
    }

    // ── atob / btoa (injected host functions) ────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = { "", "a", "ab", "abc", "abcd", "hello world", "Grüße, Welt",
            "temperature: 21.5 °C", "日本語", "emoji 😀 pair", "line1\nline2" })
    void btoa_matchesJavaBase64OfUtf8(String plain) {
        context.getBindings("js").putMember("input", plain);
        String expected = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, context.eval("js", "btoa(input)").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "a", "ab", "abc", "abcd", "hello world", "Grüße, Welt",
            "temperature: 21.5 °C", "日本語", "emoji 😀 pair", "line1\nline2" })
    void atob_roundTripsAndMatchesJavaDecode(String plain) {
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        context.getBindings("js").putMember("encoded", encoded);
        assertEquals(plain, context.eval("js", "atob(encoded)").asString());
    }

    @Test
    void atob_toleratesWhitespaceAndMissingPadding() {
        assertEquals("hi", context.eval("js", "atob('aG\\nk')").asString());
        assertEquals("hi", context.eval("js", "atob('aGk')").asString());
        assertEquals("hi", context.eval("js", "atob('aGk=')").asString());
    }

    @Test
    void atob_rejectsInvalidInput() {
        assertThrows(PolyglotException.class, () -> context.eval("js", "atob('a$b=')"));
        assertThrows(PolyglotException.class, () -> context.eval("js", "atob('a')"));
    }

    @Test
    void atob_replacesInvalidUtf8LikeJavaString() {
        // 0xFF is never valid UTF-8
        String encoded = Base64.getEncoder().encodeToString(new byte[] { 'a', (byte) 0xFF, 'b' });
        context.getBindings("js").putMember("encoded", encoded);
        String expected = new String(new byte[] { 'a', (byte) 0xFF, 'b' }, StandardCharsets.UTF_8);
        assertEquals(expected, context.eval("js", "atob(encoded)").asString());
    }

    @Test
    void allowList_neverIncludesObjectMembers() {
        List<Class<?>> classes = GraalVMContextService.SCRIPT_ACCESSIBLE_CLASSES;
        assertTrue(!classes.contains(Object.class));
        assertTrue(!classes.contains(Class.class));
        assertTrue(!classes.contains(Runtime.class));
    }

    /** Public class with a public field: reachable under allowPublicAccess(true), closed now. */
    public static class PublicFieldProbe {
        public String value = "leak";
    }
}
