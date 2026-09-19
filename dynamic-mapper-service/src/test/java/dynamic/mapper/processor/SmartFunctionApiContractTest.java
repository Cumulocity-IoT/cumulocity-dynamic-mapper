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

package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.model.InputMessage;
import dynamic.mapper.processor.runtime.SmartFunctionContext;

/**
 * Pins the Smart Function API that JavaScript actually sees, and checks the AI prompts against it.
 *
 * <p>The same contract is described in nine places (see {@code docs/contract-sync.md}); none are
 * generated from the runtime, so all of them can drift silently. The prompts are the worst case:
 * a wrong prompt is not one wrong page, it is every mapping the AI generates from then on, and
 * the user has no reason to suspect the tool. Nothing else in the build reads them.</p>
 *
 * <p>These tests reflect over the concrete classes handed to the GraalVM engine —
 * {@link SmartFunctionContext} and {@link InputMessage} — rather than the interfaces, because an
 * interface is not the contract: {@code DataPrepContext} declares 13 methods while the object
 * actually passed to JavaScript exposes 17.</p>
 */
class SmartFunctionApiContractTest {

    /**
     * Every public method on the context object a Smart Function receives.
     *
     * <p>When this list changes, the change is real and the other surfaces need updating too:
     * the TypeScript definitions, the generated editor completion table, the in-app docs and the
     * prompts. Update this list last, once the rest is done — it exists to force that decision,
     * not to be silenced.</p>
     */
    private static final Set<String> CANONICAL_CONTEXT_API = Set.of(
            "addLogMessage",
            "addWarning",
            "clearState",
            "getClientId",
            "getConfig",
            "getDTMAsset",
            "getExternalId",
            "getManagedObject",
            "getManagedObjectByExternalId",
            "getState",
            "getStateAll",
            "getStateKeySet",
            "getTesting",
            "logMessage",
            "setClientId",
            "setConfig",
            "setState");

    private static final List<String> PROMPTS = List.of(
            "prompts/smartfunction_prompt.txt",
            "prompts/jsonata_prompt.txt");

    private static Set<String> contextApi() {
        Set<String> names = new TreeSet<>();
        for (Method m : SmartFunctionContext.class.getMethods()) {
            if (m.getDeclaringClass() != Object.class) {
                names.add(m.getName());
            }
        }
        return names;
    }

    /** Public fields plus their getter aliases — GraalVM exposes both to JavaScript. */
    private static Set<String> messageApi() {
        Set<String> names = new TreeSet<>();
        for (Field f : InputMessage.class.getFields()) {
            names.add(f.getName());
        }
        for (Method m : InputMessage.class.getMethods()) {
            if (m.getDeclaringClass() != Object.class) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = SmartFunctionApiContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertTrue(in != null, "prompt not found on the classpath: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> tokens(String text, String regex) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("the context API JavaScript sees matches the canonical list")
    void contextApiMatchesCanonicalList() {
        Set<String> actual = contextApi();
        assertEquals(new TreeSet<>(CANONICAL_CONTEXT_API), actual,
                "The Smart Function context API changed. Before updating CANONICAL_CONTEXT_API, "
                        + "mirror the change in: dynamic-mapper-smart-function/src/types (and its "
                        + "mock helpers), the generated editor table (npm run generate:editor-api), "
                        + "public/docs/smartfunction.md, and resources/prompts/. "
                        + "See docs/contract-sync.md.");
    }

    @Test
    @DisplayName("prompts only teach context methods that exist")
    void promptsReferenceOnlyRealContextMethods() throws IOException {
        Set<String> api = contextApi();
        for (String prompt : PROMPTS) {
            Set<String> used = tokens(read(prompt), "context\\.(\\w+)\\s*\\(");
            List<String> phantom = used.stream().filter(n -> !api.contains(n)).collect(Collectors.toList());
            assertTrue(phantom.isEmpty(),
                    prompt + " teaches context method(s) the runtime does not have: " + phantom
                            + ". Available: " + api);
        }
    }

    @Test
    @DisplayName("prompts only teach msg fields that exist")
    void promptsReferenceOnlyRealMessageFields() throws IOException {
        Set<String> api = messageApi();
        for (String prompt : PROMPTS) {
            Set<String> used = tokens(read(prompt), "\\bmsg\\.(\\w+)");
            List<String> phantom = used.stream().filter(n -> !api.contains(n)).collect(Collectors.toList());
            assertTrue(phantom.isEmpty(),
                    prompt + " teaches msg field(s) the runtime does not set: " + phantom
                            + ". Available: " + api);
        }
    }
}
