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

package dynamic.mapper.processor.util;

import java.util.regex.Pattern;

/**
 * Converts an ES module (ESM) source string into a plain script that can be
 * evaluated by GraalJS without module semantics.
 *
 * <h3>Why this is needed</h3>
 * GraalJS treats a source as an ES module only when its name ends in {@code .mjs}
 * or its MIME type is {@code application/javascript+module}.  The shared/system
 * code must always be evaluated as a <em>plain script</em> so that every
 * top-level declaration lands on {@code globalThis} and is visible to all
 * mapping code running in the same context.
 *
 * <p>When a user pastes a bundled ESM library (e.g. a Zod bundle built with
 * {@code rollup -f es}) as the shared-code template, it will contain export
 * declarations that are a syntax error in script mode.  This class strips them
 * correctly, handling all forms:
 *
 * <pre>
 *   export { foo, bar };                      → removed entirely (single-line)
 *   export {                                  → removed entirely (multi-line block)
 *     foo,   // comment with } chars OK
 *     bar
 *   };
 *   export function foo() { … }               → kept as  function foo() { … }
 *   export class Foo { … }                    → kept as  class Foo { … }
 *   export const / let / var x = …           → kept as  const / let / var x = …
 *   export default foo;                       → removed entirely
 *   import { … } from '…';                   → removed entirely
 * </pre>
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Stripping {@code import} lines removes dependencies — only safe for
 *       <em>bundled</em> ESM where all dependencies are already inlined.</li>
 *   <li>String or comment content that happens to match an export pattern is
 *       not affected because the patterns require the keyword at the start of
 *       a line (after optional whitespace).</li>
 * </ul>
 */
public final class JavaScriptModuleStripper {

    private JavaScriptModuleStripper() {
        // utility class
    }

    // --- compiled patterns ---

    /** {@code export function …}, {@code export async function …},
     *  {@code export class …}, {@code export const …}, etc.
     *  Strips only the leading {@code export } keyword; the declaration is kept. */
    private static final Pattern INLINE_EXPORT = Pattern.compile(
            "(?m)^(\\s*)export\\s+(async\\s+)?(function|class|const|let|var)\\b");

    /** Single-line {@code export { foo, bar };} — no embedded line comments. */
    private static final Pattern EXPORT_BLOCK_SINGLE_LINE = Pattern.compile(
            "(?m)^\\s*export\\s*\\{[^}\\n]*\\}\\s*(from\\s+['\"][^'\"]*['\"]\\s*)?;?\\s*$");

    /** A line that opens a multi-line export block: {@code export {}.
     *  The block does NOT close on the same line. */
    private static final Pattern EXPORT_BLOCK_OPEN = Pattern.compile(
            "^\\s*export\\s*\\{[^}\\n]*$");

    /** A line that closes an export/re-export block: {@code }; } or {@code } from '…';} */
    private static final Pattern EXPORT_BLOCK_CLOSE = Pattern.compile(
            "^\\s*\\}\\s*(from\\s+['\"][^'\"]*['\"]\\s*)?;?\\s*$");

    /** Single-line {@code export default …;} */
    private static final Pattern EXPORT_DEFAULT = Pattern.compile(
            "(?m)^\\s*export\\s+default\\s+.*$");

    /** {@code import … from '…';} and bare {@code import '…';} lines. */
    private static final Pattern IMPORT_LINE = Pattern.compile(
            "(?m)^\\s*import\\s+.*$");

    /**
     * Strips all ESM {@code export} and {@code import} declarations from
     * {@code source} and returns plain-script code.
     *
     * <p>Transformation order matters:
     * <ol>
     *   <li>Multi-line export blocks are removed line-by-line (handles {@code }}
     *       characters inside line comments correctly).</li>
     *   <li>Single-line {@code export { … };} blocks are removed.</li>
     *   <li>Inline export declarations ({@code export function/class/const/let/var})
     *       have the {@code export} keyword stripped but the declaration is kept.</li>
     *   <li>Remaining {@code export default} lines are removed.</li>
     *   <li>{@code import} lines are removed.</li>
     *   <li>Blank lines introduced by removal are collapsed.</li>
     * </ol>
     *
     * @param source raw ESM source text
     * @return plain-script source text safe for GraalJS script evaluation
     */
    public static String toPlainScript(String source) {
        // 1. Remove multi-line export { … } blocks line-by-line.
        //    A regex like [^}]* breaks when comments inside the block contain '}'.
        String result = removeMultiLineExportBlocks(source);

        // 2. Remove remaining single-line export { … } blocks
        result = EXPORT_BLOCK_SINGLE_LINE.matcher(result).replaceAll("");

        // 3. Strip "export " keyword from inline declarations — keep the declaration
        result = INLINE_EXPORT.matcher(result).replaceAll("$1$2$3 ");

        // 4. Remove export default lines
        result = EXPORT_DEFAULT.matcher(result).replaceAll("");

        // 5. Remove import lines
        result = IMPORT_LINE.matcher(result).replaceAll("");

        // 6. Collapse runs of blank lines into a single blank line
        result = result.replaceAll("(?m)(\\r?\\n){3,}", "\n\n").trim();

        return result;
    }

    /**
     * Removes multi-line {@code export { … };} blocks by scanning line by line.
     * This correctly handles {@code }} characters that appear inside line comments
     * within the block, which confuse regex-based approaches.
     */
    private static String removeMultiLineExportBlocks(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;

        for (String line : lines) {
            if (!inBlock) {
                if (EXPORT_BLOCK_OPEN.matcher(line).matches()) {
                    inBlock = true;       // drop this opening line, start skipping
                } else {
                    out.append(line).append('\n');
                }
            } else {
                if (EXPORT_BLOCK_CLOSE.matcher(line).matches()) {
                    inBlock = false;      // drop the closing line, resume output
                }
                // every line inside the block (including closing line) is dropped
            }
        }

        // Remove the trailing newline that was appended after the last line
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Strips only {@code import} lines from {@code source}.
     * Used for mapping code that already has exports handled separately.
     *
     * @param source raw source text
     * @return source text with import declarations removed
     */
    public static String stripImports(String source) {
        return IMPORT_LINE.matcher(source).replaceAll("").trim();
    }
}
