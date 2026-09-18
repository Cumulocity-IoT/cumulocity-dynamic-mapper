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
package dynamic.mapper.processor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stripper runs when "Support ESM modules" is off: it turns module source into plain script
 * so GraalJS can evaluate it and {@code onMessage} ends up on globalThis.
 *
 * <p>These focus on {@code export default}, which was the broken case — {@code INLINE_EXPORT}
 * does not match it (the {@code default} keyword sits between {@code export} and the
 * declaration), so it fell through to the whole-line {@code EXPORT_DEFAULT} removal and the
 * function was deleted along with its {@code export} prefix.
 */
class JavaScriptModuleStripperTest {

    /**
     * Collapses runs of spaces. Stripping a keyword leaves a double space behind (the
     * replacement re-adds one the source already had) — long-standing, harmless, and not what
     * these tests are about.
     */
    private static String normalized(String code) {
        return code.replaceAll("[ \\t]+", " ");
    }

    @Test
    void keepsASingleLineDefaultExportedFunction() {
        String result = JavaScriptModuleStripper.toPlainScript(
                "export default function onMessage(msg, context) { return []; }\n");

        assertTrue(normalized(result).contains("function onMessage(msg, context)"),
                "The declaration must survive, got: " + result);
        assertFalse(normalized(result).contains("export"), "The export keyword must be gone, got: " + result);
    }

    @Test
    void keepsAMultiLineDefaultExportedFunction() {
        String result = JavaScriptModuleStripper.toPlainScript(
                "export default function onMessage(msg, context) {\n"
                        + "    return [];\n"
                        + "}\n");

        // Deleting only the signature line used to leave an orphaned body — a syntax error.
        assertTrue(normalized(result).contains("function onMessage(msg, context) {"), result);
        assertTrue(normalized(result).contains("return [];"), result);
        assertFalse(normalized(result).contains("export"), result);
    }

    @Test
    void keepsADefaultExportedAsyncFunctionAndClass() {
        assertTrue(normalized(JavaScriptModuleStripper
                .toPlainScript("export default async function onMessage(m) { return []; }\n"))
                .contains("async function onMessage(m)"));
        assertTrue(normalized(JavaScriptModuleStripper
                .toPlainScript("export default class Handler {}\n"))
                .contains("class Handler"));
    }

    @Test
    void stillRemovesAPlainDefaultExportExpression() {
        String result = JavaScriptModuleStripper.toPlainScript(
                "function onMessage(msg, context) { return []; }\n"
                        + "export default onMessage;\n");

        assertTrue(normalized(result).contains("function onMessage(msg, context)"), result);
        // `export default onMessage;` is not a declaration — there is nothing to keep.
        assertFalse(normalized(result).contains("export default"), result);
    }

    @Test
    void stillHandlesTheNamedExportFormsTheTemplatesUse() {
        String named = JavaScriptModuleStripper.toPlainScript(
                "function onMessage(msg, context) { return []; }\nexport { onMessage };\n");
        assertTrue(normalized(named).contains("function onMessage(msg, context)"), named);
        assertFalse(normalized(named).contains("export"), named);

        String inline = JavaScriptModuleStripper.toPlainScript(
                "export function onMessage(msg, context) { return []; }\n");
        assertTrue(normalized(inline).contains("function onMessage(msg, context)"), inline);
        assertFalse(normalized(inline).contains("export"), inline);
    }
}
