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
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.graalvm.polyglot.Context;

import dynamic.mapper.model.API;
import dynamic.mapper.processor.inbound.processor.FlowInboundProcessor;
import dynamic.mapper.processor.model.InputMessage;
import dynamic.mapper.processor.outbound.processor.FlowOutboundProcessor;
import dynamic.mapper.processor.runtime.ProcessingContext;
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

    /**
     * The in-app Smart Function reference. Not on the classpath — it belongs to the UI module —
     * so it is resolved relative to this module. Both modules live in the same repository and the
     * reactor build spans them, so a missing file means something is genuinely wrong rather than
     * a partial checkout to tolerate.
     */
    private static final Path USER_DOCS =
            Path.of("..", "dynamic-mapper-ui", "public", "docs", "smartfunction.md");

    /**
     * Setters the host calls while wiring the context up. They are public because the runtime
     * needs them, but they are not part of what a Smart Function author should be told about, so
     * the documentation-coverage check below does not require them.
     */
    private static final Set<String> HOST_ONLY = Set.of("setClientId", "setConfig");

    /** The microservice manifest — the only place roles are actually declared to the platform. */
    private static final Path MANIFEST = Path.of("src", "main", "configuration", "cumulocity.json");

    /** Generated API description, committed so clients and reviewers can read it. */
    private static final Path OPENAPI = Path.of("..", "resources", "openAPI", "openapi.json");

    /** The hand-maintained TypeScript mirror of the runtime API. */
    private static final Path TS_TYPES = Path.of("..", "dynamic-mapper-smart-function", "src",
            "types", "smart-function-dynamic-mapper.types.ts");

    /**
     * The two TypeScript interfaces describing {@code msg}. Both must account for every public
     * field of {@link InputMessage}: the runtime hands the same object to each direction, and a
     * field the direction never receives is declared {@code never} rather than left out, so that
     * omission always means "forgotten" and never "deliberately absent".
     */
    private static final List<String> MESSAGE_INTERFACES =
            List.of("DynamicMapperDeviceMessage", "OutboundMessage");

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

    @Test
    @DisplayName("the in-app docs only describe context methods that exist")
    void docsDescribeOnlyRealContextMethods() throws IOException {
        Set<String> api = contextApi();
        Set<String> used = tokens(readDocs(), "context\\.(\\w+)\\s*\\(");
        List<String> phantom = used.stream().filter(n -> !api.contains(n)).collect(Collectors.toList());
        assertTrue(phantom.isEmpty(),
                USER_DOCS + " documents context method(s) the runtime does not have: " + phantom);
    }

    @Test
    @DisplayName("the in-app docs describe every context method a Smart Function can call")
    void docsCoverTheWholeContextApi() throws IOException {
        String docs = readDocs();
        List<String> undocumented = contextApi().stream()
                .filter(name -> !HOST_ONLY.contains(name))
                .filter(name -> !docs.contains(name))
                .collect(Collectors.toList());
        assertTrue(undocumented.isEmpty(),
                "Smart Function context method(s) missing from " + USER_DOCS + ": " + undocumented
                        + ". Every method a user can call should be documented; if one is "
                        + "deliberately internal, add it to HOST_ONLY with a reason.");
    }

    private static String readDocs() throws IOException {
        assertTrue(Files.exists(USER_DOCS),
                "expected the in-app Smart Function docs at " + USER_DOCS.toAbsolutePath());
        return Files.readString(USER_DOCS);
    }

    @Test
    @DisplayName("every role named in code, messages and the OpenAPI spec is actually declared")
    void roleNamesMatchTheManifest() throws IOException {
        assertTrue(Files.exists(MANIFEST), "manifest not found at " + MANIFEST.toAbsolutePath());
        Set<String> declared = tokens(Files.readString(MANIFEST), "\"(ROLE_[A-Z_]+)\"");
        assertTrue(declared.stream().anyMatch(r -> r.startsWith("ROLE_DYNAMIC_MAPPER")),
                "no Dynamic Mapper roles found in " + MANIFEST + " — check the extraction");

        // A wrong role name never fails at compile time: it sits in a @PreAuthorize string, an
        // error message, or the generated spec, and only surfaces when a user is told to grant a
        // role that does not exist. This has happened -- ROLE_MAPPING_HTTP_CONNECTOR_CREATE was
        // reported in a 403 body for a role actually called ROLE_DYNAMIC_MAPPER_HTTP_CONNECTOR_CREATE.
        for (Path file : List.of(OPENAPI)) {
            if (!Files.exists(file)) {
                continue;
            }
            Set<String> used = tokens(Files.readString(file), "(ROLE_DYNAMIC[A-Z_]*|ROLE_MAPPING[A-Z_]*)");
            List<String> unknown = used.stream()
                    .filter(r -> !declared.contains(r))
                    .collect(Collectors.toList());
            assertTrue(unknown.isEmpty(),
                    file + " names role(s) that are not declared in " + MANIFEST + ": " + unknown
                            + ". Declared: " + declared.stream()
                                    .filter(r -> r.startsWith("ROLE_DYNAMIC_MAPPER"))
                                    .collect(Collectors.toList()));
        }
    }

    @Test
    @DisplayName("the TypeScript msg interfaces declare exactly the runtime's fields")
    void typeScriptMirrorsTheMessageFields() throws IOException {
        assertTrue(Files.exists(TS_TYPES), "type definitions not found at " + TS_TYPES.toAbsolutePath());
        String types = Files.readString(TS_TYPES);

        Set<String> runtime = new TreeSet<>();
        for (Field f : InputMessage.class.getFields()) {
            runtime.add(f.getName());
        }

        for (String iface : MESSAGE_INTERFACES) {
            Set<String> declared = tokens(interfaceBody(types, iface), "(?m)^\\s{2}(?:readonly\\s+)?(\\w+)\\??\\s*:");

            List<String> missing = runtime.stream()
                    .filter(f -> !declared.contains(f)).collect(Collectors.toList());
            assertTrue(missing.isEmpty(), iface + " does not declare runtime field(s) " + missing
                    + ". Adding a field to InputMessage.java without mirroring it here leaves it "
                    + "invisible to Smart Function authors. Declare it, using `never` if this "
                    + "direction never receives it.");

            List<String> phantom = declared.stream()
                    .filter(f -> !runtime.contains(f)).collect(Collectors.toList());
            assertTrue(phantom.isEmpty(), iface + " declares field(s) " + phantom
                    + " that InputMessage.java does not have. They would be undefined at runtime.");
        }
    }

    /**
     * Runs the real {@code createInputMessage} of a direction against a context where every source
     * value is present, and reports the fields that came back empty anyway — those are the ones the
     * processor hard-codes to {@code null}.
     *
     * <p>Asking the processor is the only honest way to answer this. Reading the constructor call
     * with a regex would tell us what the source looks like, not what the runtime produces, and
     * {@code transportFields} alone would fool it: the constructor turns a {@code null} argument
     * into an empty map.</p>
     */
    private static Set<String> fieldsLeftEmpty(AbstractFlowProcessor processor, ProcessingContext<?> context) {
        Set<String> empty = new TreeSet<>();
        try (Context graal = Context.newBuilder().allowAllAccess(true).build()) {
            InputMessage message = processor.createInputMessage(graal, context).asHostObject();
            for (Field f : InputMessage.class.getFields()) {
                Object value = f.get(message);
                if (value == null || (value instanceof Map<?, ?> map && map.isEmpty())) {
                    empty.add(f.getName());
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError("InputMessage fields are public; this cannot happen", e);
        }
        return empty;
    }

    /** Every value a direction could draw on, so that anything still null was never passed. */
    private static ProcessingContext<Object> fullyPopulatedContext() {
        return ProcessingContext.builder()
                .payload(new LinkedHashMap<String, Object>(Map.of("temperature", 21)))
                .topic("device/contract-test")
                .clientId("contract-test-client")
                .sourceId("1234")
                .connectorIdentifier("contract-test-connector")
                .api(API.MEASUREMENT)
                .key("contract-test-key")
                .build();
    }

    @Test
    @DisplayName("`never` marks exactly the msg fields a direction does not populate")
    void typeScriptNeverMatchesWhatTheProcessorsActuallyPass() throws IOException {
        assertTrue(Files.exists(TS_TYPES), "type definitions not found at " + TS_TYPES.toAbsolutePath());
        String types = Files.readString(TS_TYPES);

        // The collaborators are unused by createInputMessage, and passing null says so.
        Map<String, Set<String>> unpopulated = new LinkedHashMap<>();
        unpopulated.put("DynamicMapperDeviceMessage",
                fieldsLeftEmpty(new FlowInboundProcessor(null, null), fullyPopulatedContext()));
        unpopulated.put("OutboundMessage",
                fieldsLeftEmpty(new FlowOutboundProcessor(null, null, null), fullyPopulatedContext()));

        for (Map.Entry<String, Set<String>> direction : unpopulated.entrySet()) {
            String iface = direction.getKey();
            Set<String> declaredNever = tokens(interfaceBody(types, iface),
                    "(?m)^\\s{2}(\\w+)\\??\\s*:\\s*never\\b");

            assertEquals(direction.getValue(), declaredNever,
                    iface + ": the fields declared `never` must be exactly the ones this direction "
                            + "leaves unset. Fields the runtime never passes but TypeScript types as "
                            + "present are `undefined` at runtime while autocomplete offers them; "
                            + "fields typed `never` that the runtime does pass are invisible to "
                            + "authors. If a processor started or stopped populating a field, this "
                            + "is the line to change.");
        }

        // A field no direction populates is dead: declaring it `never` everywhere would satisfy
        // the loop above while the field itself does nothing but sit in InputMessage.
        Set<String> deadEverywhere = new TreeSet<>(unpopulated.values().iterator().next());
        unpopulated.values().forEach(deadEverywhere::retainAll);
        assertTrue(deadEverywhere.isEmpty(), "InputMessage field(s) " + deadEverywhere
                + " are populated by neither processor. Either populate the field in the direction "
                + "it belongs to, or remove it — a field that is `never` in both directions is dead "
                + "weight that still has to be mirrored everywhere.");
    }

    /** The text between an interface's braces, matched by depth so nested types do not end it early. */
    private static String interfaceBody(String source, String interfaceName) {
        int at = source.indexOf("export interface " + interfaceName);
        assertTrue(at >= 0, "interface " + interfaceName + " not found in " + TS_TYPES);
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i);
            }
        }
        throw new IllegalStateException("unbalanced braces in " + interfaceName);
    }
}
