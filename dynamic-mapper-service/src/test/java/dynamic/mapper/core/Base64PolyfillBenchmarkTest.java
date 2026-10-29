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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import lombok.extern.slf4j.Slf4j;

/**
 * Compares three {@code atob}/{@code btoa} implementations across payload sizes:
 * <ul>
 *   <li><b>host function</b> — what production uses
 *       ({@link GraalVMContextService#installBase64Functions}): a {@code ProxyExecutable} calling
 *       {@code java.util.Base64} directly;</li>
 *   <li><b>Java.type</b> — the former polyfill, reaching {@code java.util.Base64} through
 *       {@code Java.type} lookups;</li>
 *   <li><b>pure JS</b> — a JavaScript implementation, kept here only as the rejected reference
 *       (about 100x slower under the interpreter). Measured up to 1 MB only; it takes seconds
 *       beyond that.</li>
 * </ul>
 *
 * <p>Opt-in, because it takes a while and timings are only meaningful on a quiet machine:
 * <pre>mvn test -Dtest=Base64PolyfillBenchmarkTest -Dbenchmark=true</pre>
 * Optional: {@code -Dbenchmark.maxMb=20} raises the largest payload (default 8).
 *
 * <p>The class name ends in {@code Test} on purpose: Surefire silently skips other names (see
 * ContextMemoryBenchmark). Without {@code -Dbenchmark=true} the test is reported as skipped.
 *
 * <p>The setup mirrors production: one shared {@link Engine} with the production
 * {@code HostAccess} and the same {@code engine.WarnInterpreterOnly} option. All variants run
 * in the same context, so the only difference is the implementation. The measured time is the
 * JS call including the host-boundary transfer of the string, which is what a Smart Function pays.
 */
@Slf4j
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class Base64PolyfillBenchmarkTest {

    /** The former implementation, registered as atobJava/btoaJava. */
    private static final Source JAVA_TYPE_POLYFILL = Source.newBuilder("js", """
            (function() {
              var _Base64   = Java.type('java.util.Base64');
              var _JString  = Java.type('java.lang.String');
              var _Charsets = Java.type('java.nio.charset.StandardCharsets');
              var _Arrays   = Java.type('java.util.Arrays');
              globalThis.atobJava = function(encoded) {
                return new _JString(_Base64.getDecoder().decode(encoded), _Charsets.UTF_8);
              };
              globalThis.btoaJava = function(plain) {
                var buf = _Charsets.UTF_8.encode(plain);
                return _Base64.getEncoder().encodeToString(
                  _Arrays.copyOfRange(buf.array(), buf.position(), buf.limit()));
              };
            })();
            """, "__base64_java_type__.js").buildLiteral();

    /** The rejected pure-JS implementation, registered as atobJs/btoaJs. */
    private static final Source PURE_JS_POLYFILL = Source.newBuilder("js", """
            (function() {
              var ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
              var LOOKUP = {};
              for (var i = 0; i < ALPHABET.length; i++) LOOKUP[ALPHABET.charAt(i)] = i;
              function utf8Encode(str) {
                var out = [];
                for (var i = 0; i < str.length; i++) {
                  var c = str.charCodeAt(i);
                  if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
                    var d = str.charCodeAt(i + 1);
                    if (d >= 0xDC00 && d <= 0xDFFF) { c = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00); i++; }
                  }
                  if (c >= 0xD800 && c <= 0xDFFF) c = 0xFFFD;
                  if (c < 0x80) out.push(c);
                  else if (c < 0x800) out.push(0xC0 | (c >> 6), 0x80 | (c & 63));
                  else if (c < 0x10000) out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
                  else out.push(0xF0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
                }
                return out;
              }
              function utf8Decode(bytes) {
                var out = '';
                for (var i = 0; i < bytes.length; ) {
                  var b = bytes[i++], need = 0, cp = 0xFFFD, min = 0;
                  if (b < 0x80) { out += String.fromCharCode(b); continue; }
                  if (b >= 0xC2 && b <= 0xDF) { need = 1; cp = b & 0x1F; min = 0x80; }
                  else if (b >= 0xE0 && b <= 0xEF) { need = 2; cp = b & 0x0F; min = 0x800; }
                  else if (b >= 0xF0 && b <= 0xF4) { need = 3; cp = b & 0x07; min = 0x10000; }
                  else { out += '\\uFFFD'; continue; }
                  var ok = true;
                  for (var k = 0; k < need; k++) {
                    if (i >= bytes.length || (bytes[i] & 0xC0) !== 0x80) { ok = false; break; }
                    cp = (cp << 6) | (bytes[i++] & 63);
                  }
                  if (!ok || cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) out += '\\uFFFD';
                  else if (cp >= 0x10000) { cp -= 0x10000; out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)); }
                  else out += String.fromCharCode(cp);
                }
                return out;
              }
              globalThis.btoaJs = function(plain) {
                var bytes = utf8Encode(String(plain));
                var out = '';
                for (var p = 0; p < bytes.length; p += 3) {
                  var n = (bytes[p] << 16) | ((bytes[p + 1] || 0) << 8) | (bytes[p + 2] || 0);
                  out += ALPHABET.charAt((n >> 18) & 63) + ALPHABET.charAt((n >> 12) & 63)
                       + (p + 1 < bytes.length ? ALPHABET.charAt((n >> 6) & 63) : '=')
                       + (p + 2 < bytes.length ? ALPHABET.charAt(n & 63) : '=');
                }
                return out;
              };
              globalThis.atobJs = function(encoded) {
                var input = String(encoded).replace(/\\s/g, '').replace(/=+$/, '');
                var bytes = [], bits = 0, value = 0;
                for (var q = 0; q < input.length; q++) {
                  value = ((value << 6) | LOOKUP[input.charAt(q)]) & 0xFFFFFF;
                  bits += 6;
                  if (bits >= 8) { bits -= 8; bytes.push((value >> bits) & 0xFF); }
                }
                return utf8Decode(bytes);
              };
            })();
            """, "__base64_pure_js__.js").buildLiteral();

    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURED_ROUNDS = 7;
    /** Pure JS is measured only up to this size; beyond it a run takes many seconds. */
    private static final int PURE_JS_MAX_BYTES = 1_024 * 1_024;

    @Test
    void compareBase64Implementations() {
        int maxMb = Integer.getInteger("benchmark.maxMb", 8);
        int[] sizesBytes = { 1_024, 64 * 1_024, 1_024 * 1_024, maxMb * 1_024 * 1_024 };

        GraalVMContextService service = new GraalVMContextService();
        try (Engine engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();
                Context context = Context.newBuilder("js")
                        .engine(engine)
                        .allowHostAccess(service.getHostAccess())
                        .allowHostClassLookup(GraalVMContextService::isAllowedHostClass)
                        .build()) {

            GraalVMContextService.installBase64Functions(context); // atob / btoa
            context.eval(JAVA_TYPE_POLYFILL);                      // atobJava / btoaJava
            context.eval(PURE_JS_POLYFILL);                        // atobJs / btoaJs
            Value globals = context.getBindings("js");

            StringBuilder report = new StringBuilder("\nBase64 benchmark (median of ")
                    .append(MEASURED_ROUNDS).append(" runs, ms; ratio = vs host function)\n")
                    .append(String.format("%-8s | %-5s | %10s | %10s | %6s | %10s | %7s%n",
                            "payload", "op", "host fn", "Java.type", "ratio", "pure JS", "ratio"))
                    .append("-".repeat(74)).append('\n');

            for (int size : sizesBytes) {
                String plain = textOfUtf8Size(size);
                String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));

                // Correctness first: a fast wrong answer is worthless.
                for (String suffix : new String[] { "", "Java", "Js" }) {
                    if (suffix.equals("Js") && size > PURE_JS_MAX_BYTES) {
                        continue;
                    }
                    assertEquals(encoded, call(globals, "btoa" + suffix, plain).asString(),
                            "btoa" + suffix + " mismatch at " + size);
                    assertEquals(plain, call(globals, "atob" + suffix, encoded).asString(),
                            "atob" + suffix + " mismatch at " + size);
                }

                report.append(row(globals, size, "btoa", plain));
                report.append(row(globals, size, "atob", encoded));
            }
            log.info(report.toString());
            System.out.println(report);
        }
    }

    private static String row(Value globals, int size, String op, String input) {
        double hostMs = medianMillis(globals, op, input);
        double javaTypeMs = medianMillis(globals, op + "Java", input);
        String pureJs = "n/a";
        String pureJsRatio = "";
        if (size <= PURE_JS_MAX_BYTES) {
            double jsMs = medianMillis(globals, op + "Js", input);
            pureJs = String.format("%.2f", jsMs);
            pureJsRatio = String.format("%.1fx", jsMs / Math.max(hostMs, 0.001));
        }
        return String.format("%-8s | %-5s | %10.2f | %10.2f | %5.1fx | %10s | %7s%n",
                humanSize(size), op, hostMs, javaTypeMs, javaTypeMs / Math.max(hostMs, 0.001),
                pureJs, pureJsRatio);
    }

    private static double medianMillis(Value globals, String fn, String input) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            call(globals, fn, input);
        }
        double[] samples = new double[MEASURED_ROUNDS];
        for (int i = 0; i < MEASURED_ROUNDS; i++) {
            long start = System.nanoTime();
            Value result = call(globals, fn, input);
            // force the result across the boundary so lazy conversion is included in the timing
            result.asString();
            samples[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static Value call(Value globals, String fn, String input) {
        return globals.getMember(fn).execute(input);
    }

    /** Text of roughly {@code utf8Bytes} UTF-8 bytes with ASCII, 2-byte and 3-byte characters. */
    private static String textOfUtf8Size(int utf8Bytes) {
        String unit = "temperature: 21.5 °C, Grüße aus Düsseldorf, 日本語 — payload line\n";
        int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder(utf8Bytes);
        for (int total = 0; total < utf8Bytes; total += unitBytes) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private static String humanSize(int bytes) {
        return bytes >= 1_024 * 1_024 ? (bytes / (1_024 * 1_024)) + " MB" : (bytes / 1_024) + " KB";
    }
}
