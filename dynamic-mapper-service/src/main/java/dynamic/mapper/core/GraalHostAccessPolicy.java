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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import dynamic.mapper.model.RepairStrategy;
import dynamic.mapper.processor.flow.JavaScriptConsole;
import dynamic.mapper.processor.model.DataPrepContext;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.model.InputMessage;
import dynamic.mapper.processor.model.SubstituteValue;
import dynamic.mapper.processor.model.SubstitutionResult;
import dynamic.mapper.processor.runtime.SubstitutionContext;

/**
 * What Smart Function code may touch in the JVM, and the host functions it gets: the security boundary of
 * every GraalVM context.
 * <p>
 * Two lists define it and must agree: {@link #SCRIPT_ACCESSIBLE_CLASSES} (the members scripts may call) and
 * {@link #isAllowedHostClass} (the classes {@code Java.type(...)} may load). It also provides the
 * {@code atob} and {@code btoa} functions. Everything here is static and holds no per-tenant state.
 */
public final class GraalHostAccessPolicy {

    private GraalHostAccessPolicy() {
    }

    /**
     * {@code atob()} / {@code btoa()}: GraalVM's JS engine is ECMAScript-only and has no Web APIs.
     *
     * <p>Injected as host functions ({@link ProxyExecutable}) instead of a JavaScript polyfill:
     * no {@code Java.type} lookups and no script evaluation per context, no host-access entries,
     * no dependency on {@code js.text-encoding}, and Java speed (a pure-JS implementation is
     * 100x slower under the interpreter, see {@code Base64PolyfillBenchmarkTest}).
     *
     * <p>Semantics: {@code btoa} encodes the string as UTF-8 first; {@code atob} returns the UTF-8
     * decoded string (not a binary string as in browsers). Whitespace in the {@code atob} input is
     * ignored; any other invalid input throws.
     */
    private static final ProxyExecutable BTOA = args ->
            Base64.getEncoder().encodeToString(stringArg(args).getBytes(StandardCharsets.UTF_8));

    private static final ProxyExecutable ATOB = args ->
            new String(Base64.getDecoder().decode(stringArg(args).replaceAll("\\s", "")),
                    StandardCharsets.UTF_8);

    private static String stringArg(Value[] args) {
        if (args.length == 0 || args[0].isNull()) {
            throw new IllegalArgumentException("Base64 function requires a string argument");
        }
        return args[0].isString() ? args[0].asString() : args[0].toString();
    }

    /** Defines {@code atob} and {@code btoa} on the context's global scope. */
    public static void installBase64Functions(Context context) {
        Value globals = context.getBindings("js");
        globals.putMember("btoa", BTOA);
        globals.putMember("atob", ATOB);
    }

    /**
     * Java classes whose public constructors, methods and fields are visible to Smart Functions.
     * Everything else is closed: a script that obtains an instance of any other class (for example
     * through a returned value) cannot call into it, and {@code Object} members such as
     * {@code getClass()} are never exposed. Keep in sync with {@link #isAllowedHostClass}, which
     * governs what {@code Java.type(...)} may load; this list additionally covers the objects the
     * allowed classes hand back.
     *
     * <p>{@link DataPrepContext} is the script-facing {@code context} API; the implementing class
     * ({@code SmartFunctionContext}) is deliberately not listed so its internal setters stay hidden
     * (access is inherited from the interface, see {@code allowAccessInheritance}).
     */
    public static final List<Class<?>> SCRIPT_ACCESSIBLE_CLASSES = List.of(
            // mapper API
            DataPrepContext.class,
            InputMessage.class,
            ExternalId.class,
            JavaScriptConsole.class,
            SubstitutionContext.class,
            SubstitutionResult.class,
            SubstituteValue.class,
            SubstituteValue.TYPE.class,
            RepairStrategy.class,
            // JDK classes reachable through Java.type
            String.class,
            ArrayList.class,
            HashMap.class,
            HashSet.class,
            Arrays.class,
            Base64.class,
            Base64.Encoder.class,
            Base64.Decoder.class,
            StandardCharsets.class,
            Charset.class,
            ByteBuffer.class);

    private static final HostAccess HOST_ACCESS = buildHostAccess();

    /** The shared {@link HostAccess} of every GraalVM context: an explicit allow-list, see {@link #SCRIPT_ACCESSIBLE_CLASSES}. */
    public static HostAccess hostAccess() {
        return HOST_ACCESS;
    }

    private static HostAccess buildHostAccess() {
        HostAccess.Builder builder = HostAccess.newBuilder()
                .allowPublicAccess(false)
                .allowAccessInheritance(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowMapAccess(true);
        for (Class<?> clazz : SCRIPT_ACCESSIBLE_CLASSES) {
            for (Constructor<?> constructor : clazz.getConstructors()) {
                builder.allowAccess(constructor);
            }
            for (Method method : clazz.getMethods()) {
                if (method.getDeclaringClass() != Object.class) {
                    builder.allowAccess(method);
                }
            }
            for (Field field : clazz.getFields()) {
                builder.allowAccess(field);
            }
        }
        return builder.build();
    }

    /**
     * The host-class allow-list for every GraalVM context this service builds — a security
     * boundary, so it is deliberately narrow and lives in exactly one place. All four
     * {@code allowHostClassLookup} call sites reference it: {@code GraalVMContextService.initializeTenant},
     * {@code warmupMappingCodes}, the pooled-context builder, and
     * {@code AbstractEnrichmentProcessor.createGraalContext}. Do not inline a copy — an earlier
     * duplicate in that last one had to be kept in step by review.
     *
     * <p>Public so both that processor (a different package) and
     * {@code GraalVMTemplateHostClassTest} can reach it. The test matters because the shipped
     * templates name these classes as strings via {@code Java.type(...)}: a class that moves
     * package compiles clean and fails only at runtime, as happened when {@code RepairStrategy}
     * moved out of {@code processor.model}.
     */
    public static boolean isAllowedHostClass(String className) {
        return className.equals("dynamic.mapper.processor.runtime.SubstitutionContext")
                || className.equals("dynamic.mapper.processor.model.SubstitutionResult")
                || className.equals("dynamic.mapper.processor.model.SubstituteValue")
                || className.equals("dynamic.mapper.processor.model.SubstituteValue$TYPE")
                || className.equals("dynamic.mapper.model.RepairStrategy")
                || className.equals("java.nio.charset.StandardCharsets")
                || className.equals("java.util.Base64")
                || className.equals("java.lang.String")
                || className.equals("java.util.ArrayList")
                || className.equals("java.util.Arrays")
                || className.equals("java.util.HashMap")
                || className.equals("java.util.HashSet");
    }
}
