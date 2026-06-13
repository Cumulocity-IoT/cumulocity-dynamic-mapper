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

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that the production GraalVM host-access configuration
 * enforces sandbox boundaries:
 * <ul>
 *   <li>Normal JS executes correctly.</li>
 *   <li>Java class access ({@code Java.type}) is blocked — production does
 *       not use {@code allowAllAccess(true)}, so {@code Java.type} should throw.</li>
 *   <li>Environment access ({@code process.env}) is blocked.</li>
 *   <li>Infinite loops can be interrupted via
 *       {@link Context#interrupt(Duration)}.</li>
 *   <li>Prototype pollution is confined to the sandbox and does not
 *       affect subsequent contexts.</li>
 * </ul>
 *
 * <p>The test uses {@link TenantRegistry#getHostAccess()} directly so
 * assertions stay aligned with production configuration.</p>
 */
class TenantRegistryGraalVMSandboxSecurityTest {

    private Engine engine;
    private Context context;
    private TenantRegistry tenantRegistry;

    private Context newContext() {
        return Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(tenantRegistry.getHostAccess())
                // Do NOT call allowAllAccess(true) — mirrors production
                .build();
    }

    @BeforeEach
    void setUp() {
        tenantRegistry = new TenantRegistry();
        engine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        context = newContext();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    // ── Normal execution ──────────────────────────────────────────────────

    @Test
    void normalJavaScript_executesCorrectly() {
        Value result = context.eval("js", "1 + 2");
        assertEquals(3, result.asInt());
    }

    @Test
    void stringManipulation_executesCorrectly() {
        Value result = context.eval("js", "'hello'.toUpperCase()");
        assertEquals("HELLO", result.asString());
    }

    @Test
    void arrayOperations_executeCorrectly() {
        Value result = context.eval("js", "[1, 2, 3].reduce((a, b) => a + b, 0)");
        assertEquals(6, result.asInt());
    }

    // ── Java.type blocked ─────────────────────────────────────────────────

    @Test
    void javaType_Runtime_isBlocked() {
        // Without allowAllAccess(true), Java.type is unavailable.
        assertThrows(PolyglotException.class, () ->
                context.eval("js", "Java.type('java.lang.Runtime')"));
    }

    @Test
    void javaType_File_isBlocked() {
        assertThrows(PolyglotException.class, () ->
                context.eval("js", "Java.type('java.io.File')"));
    }

    @Test
    void javaType_ProcessBuilder_isBlocked() {
        assertThrows(PolyglotException.class, () ->
                context.eval("js", "Java.type('java.lang.ProcessBuilder')"));
    }

    // ── process.env blocked ───────────────────────────────────────────────

    @Test
    void processEnv_isNotAccessible() {
        // 'process' is a Node.js global — not present in a vanilla GraalVM JS context.
        // Evaluating process.env.HOME must throw (ReferenceError or PolyglotException).
        assertThrows(PolyglotException.class, () ->
                context.eval("js", "process.env.HOME"));
    }

    // ── Infinite-loop interruption ────────────────────────────────────────

    @Test
    @Timeout(10)  // overall test must complete within 10 s
    void infiniteLoop_canBeInterrupted() throws InterruptedException {
        // Schedule interruption after 200 ms
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                context.interrupt(Duration.ofMillis(200));
            } catch (Exception ignored) {
                // TimeoutException from interrupt() is benign here
            }
        });
        interrupter.setDaemon(true);
        interrupter.start();

        // The infinite loop must be terminated by the interrupt
        PolyglotException ex = assertThrows(PolyglotException.class, () ->
                context.eval("js", "while(true){}"));

        assertTrue(ex.isInterrupted() || ex.isCancelled(),
                "Expected interrupted/cancelled exception but got: " + ex.getMessage());
        interrupter.join(1000);
    }

    // ── Prototype pollution stays in sandbox ──────────────────────────────

    @Test
    void prototypePollution_staysInSandbox() {
        // Pollute Object prototype in this context
        context.eval("js", "Object.prototype.injected = 'pwned'");

        // A fresh context must NOT see the pollution
        try (Context freshContext = newContext()) {
            Value injected = freshContext.eval("js", "({}).injected");
            // Either undefined or the member does not exist
            assertTrue(injected.isNull() || !injected.isString(),
                    "Prototype pollution leaked into a fresh context: " + injected);
        }
    }

    // ── Script injection via eval ─────────────────────────────────────────

    @Test
    void evalInsideEval_doesNotEscapeSandbox() {
        // eval() within JS is allowed, but still cannot access Java.type
        assertThrows(PolyglotException.class, () ->
                context.eval("js", "eval(\"Java.type('java.lang.Runtime')\")"));
    }
}
