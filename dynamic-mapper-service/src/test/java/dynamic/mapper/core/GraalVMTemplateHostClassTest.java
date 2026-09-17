/*
 * Copyright (c) 2025 Cumulocity GmbH
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
package dynamic.mapper.core;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped JavaScript templates against the Java classes they name.
 *
 * <p>A template reaches the Java world through {@code Java.type('<fqn>')}, which is a plain
 * string: the compiler cannot see it, so moving or renaming a class breaks the templates with
 * no build failure at all. It surfaces only at runtime, as
 * {@code TypeError: Access to host class <fqn> is not allowed or does not exist} — which is
 * exactly what happened when {@code RepairStrategy} moved from {@code processor.model} to
 * {@code dynamic.mapper.model} and {@code template-SYSTEM.js} was left behind.
 *
 * <p>Two things have to hold for every such reference, and this test checks both, because either
 * one alone fails the same way: the class must exist, and
 * {@link GraalVMContextService#isAllowedHostClass(String)} must permit it.
 */
class GraalVMTemplateHostClassTest {

    /** Matches {@code Java.type('some.fqn')} and {@code Java.type("some.fqn")}. */
    private static final Pattern JAVA_TYPE = Pattern.compile("Java\\.type\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    private record HostClassRef(String template, String className) {
        @Override
        public String toString() {
            return template + " -> " + className;
        }
    }

    private static List<HostClassRef> hostClassReferences() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:templates/template*.js");
        assertTrue(resources.length > 0, "no templates found on the classpath");

        List<HostClassRef> refs = new ArrayList<>();
        for (Resource resource : resources) {
            String content;
            try (InputStream is = resource.getInputStream()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher matcher = JAVA_TYPE.matcher(content);
            while (matcher.find()) {
                refs.add(new HostClassRef(resource.getFilename(), matcher.group(1)));
            }
        }
        return refs;
    }

    @Test
    void everyJavaTypeInTheTemplatesResolvesToAnExistingClass() throws IOException {
        List<String> broken = new ArrayList<>();
        for (HostClassRef ref : hostClassReferences()) {
            try {
                // Java.type uses '$' for nested classes, which is what Class.forName wants too.
                Class.forName(ref.className(), false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                broken.add(ref.toString());
            }
        }
        assertTrue(broken.isEmpty(),
                "Templates name Java classes that do not exist — they will fail at runtime with "
                        + "\"Access to host class ... is not allowed or does not exist\". "
                        + "Did a class move package? " + broken);
    }

    @Test
    void everyJavaTypeInTheTemplatesIsOnTheHostClassAllowList() throws IOException {
        List<String> denied = new ArrayList<>();
        for (HostClassRef ref : hostClassReferences()) {
            if (!GraalVMContextService.isAllowedHostClass(ref.className())) {
                denied.add(ref.toString());
            }
        }
        assertTrue(denied.isEmpty(),
                "Templates name Java classes the GraalVM allow-list rejects: " + denied);
    }

    /**
     * The allow-list is a security boundary, so it is deliberately narrow. This does not assert a
     * specific size — it asserts that nothing in it is dead, which is how a stale entry for a
     * class that moved would otherwise linger and mask the move.
     */
    @Test
    void theAllowListContainsNoClassesThatNoLongerExist() {
        Set<String> allowed = new LinkedHashSet<>(List.of(
                "dynamic.mapper.processor.runtime.SubstitutionContext",
                "dynamic.mapper.processor.model.SubstitutionResult",
                "dynamic.mapper.processor.model.SubstituteValue",
                "dynamic.mapper.processor.model.SubstituteValue$TYPE",
                "dynamic.mapper.model.RepairStrategy"));

        List<String> missing = new ArrayList<>();
        for (String className : allowed) {
            // Guards the mirror of this list in AbstractEnrichmentProcessor.createGraalContext too:
            // if this fails, both copies need the same edit.
            assertTrue(GraalVMContextService.isAllowedHostClass(className),
                    className + " is expected on the allow-list but was rejected");
            try {
                Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                missing.add(className);
            }
        }
        assertTrue(missing.isEmpty(), "Allow-listed classes that no longer exist: " + missing);
    }

    @Test
    void theAllowListRejectsAnythingElse() {
        assertFalse(GraalVMContextService.isAllowedHostClass("java.lang.Runtime"));
        assertFalse(GraalVMContextService.isAllowedHostClass("java.io.File"));
        // The pre-move package must not be resurrected silently.
        assertFalse(GraalVMContextService.isAllowedHostClass("dynamic.mapper.processor.model.RepairStrategy"));
    }
}
