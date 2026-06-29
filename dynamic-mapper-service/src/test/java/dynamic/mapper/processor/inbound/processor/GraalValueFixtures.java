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

package dynamic.mapper.processor.inbound.processor;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Test helper that produces real GraalVM {@link Value} graphs from JavaScript
 * expressions, replacing hand-built Mockito mocks of the polyglot API.
 *
 * <p>Real Values exercise the exact semantics the production code relies on
 * (member access, array handling, and number coercion via
 * {@code fitsInInt()}/{@code asDouble()} in {@code JavaScriptInteropHelper}),
 * so the tests stay faithful to runtime behaviour instead of encoding
 * assumptions in dozens of {@code when(...)} stubs.
 *
 * <p>Not thread-safe: create, use, and close on a single thread — GraalVM
 * contexts are single-threaded by default. Close it in an {@code @AfterEach}
 * (or use try-with-resources) to release the underlying context.
 */
public final class GraalValueFixtures implements AutoCloseable {

    private final Context context;

    public GraalValueFixtures() {
        this.context = Context.newBuilder("js").build();
    }

    /**
     * Evaluates a JavaScript expression and returns the resulting polyglot
     * {@link Value}. The expression is wrapped in parentheses so that object and
     * array literals are parsed as expressions rather than statement blocks.
     *
     * @param jsExpression a JavaScript expression, e.g.
     *                     {@code "[{ cumulocityType: 'measurement' }]"}
     * @return the evaluated Value, valid until this fixture is closed
     */
    public Value eval(String jsExpression) {
        return context.eval("js", "(" + jsExpression + ")");
    }

    @Override
    public void close() {
        context.close();
    }
}
