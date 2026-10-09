/*
 * Copyright (c) 2025 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dynamic.mapper.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dynamic.mapper.controller.TestController;
import dynamic.mapper.model.Mapping;

/**
 * The location reported for a failing Smart Function must be the line the author sees in the
 * editor, and must not be invented for errors that did not come from JavaScript.
 */
class JsErrorLocationTest {

    /** Same wrapper the processors put around flat-script mapping code: one extra leading line. */
    private static String wrap(String userCode) {
        return "(function() {\n" + userCode + "\n})();";
    }

    @Test
    @DisplayName("runtime error: reported line is the author's line, not the wrapped one")
    void runtimeErrorLineIsRelativeToUserCode() {
        // user line 1: function onMessage() {
        // user line 2:   const x = undefined;
        // user line 3:   return x.y;   <- fails here
        String user = "function onMessage() {\n  const x = undefined;\n  return x.y;\n}\nonMessage();";
        try (Context ctx = Context.create("js")) {
            Source src = Source.newBuilder("js", wrap(user), Mapping.SMART_FUNCTION_NAME + "_abc.js").buildLiteral();
            PolyglotException e = assertThrows(PolyglotException.class, () -> ctx.eval(src));

            assertTrue(CommonProcessor.describeJsLocation(e).startsWith(" (line 3, column"),
                    "was: " + CommonProcessor.describeJsLocation(e));
        }
    }

    @Test
    @DisplayName("syntax error: location comes from the exception itself")
    void syntaxErrorHasLocation() {
        String user = "function onMessage() {\n  return {;\n}";
        try (Context ctx = Context.create("js")) {
            Source src = Source.newBuilder("js", wrap(user), Mapping.SMART_FUNCTION_NAME + "_abc.js").buildLiteral();
            PolyglotException e = assertThrows(PolyglotException.class, () -> ctx.eval(src));

            assertTrue(e.isSyntaxError());
            assertTrue(CommonProcessor.describeJsLocation(e).startsWith(" (line 2, column"),
                    "was: " + CommonProcessor.describeJsLocation(e));
        }
    }

    @Test
    @DisplayName("a non-JavaScript exception gets no location instead of a Java source line")
    void javaExceptionHasNoLocation() {
        assertEquals("", CommonProcessor.describeJsLocation(new NullPointerException("boom")));
        assertEquals("", CommonProcessor.describeJsLocation(new ProcessingException("x")));
    }

    @Test
    @DisplayName("describeError walks the cause chain without repeating text")
    void describeErrorWalksCauses() {
        Exception root = new IllegalStateException("root cause");
        Exception mid = new RuntimeException("middle", root);
        Exception top = new ProcessingException("top: middle", mid);

        // "middle" is already part of the top message, so only the root cause is appended
        assertEquals("top: middle (caused by: root cause)", TestController.describeError(top));
        assertEquals("NullPointerException", TestController.describeError(new NullPointerException()));
    }

    @Test
    @DisplayName("describeCause surfaces the JavaScript error hidden behind a generic wrapper")
    void describeCauseFindsWrappedSyntaxError() {
        String user = "function onMessage() {\n  return {;\n}";
        try (Context ctx = Context.create("js")) {
            Source src = Source.newBuilder("js", wrap(user), Mapping.SMART_FUNCTION_NAME + "_abc.js").buildLiteral();
            PolyglotException syntax = assertThrows(PolyglotException.class, () -> ctx.eval(src));
            // what GraalVMContextService throws when loading the pooled context fails
            Exception wrapper = new RuntimeException("Failed to create pooled GraalVM context for mapping abc", syntax);

            String described = CommonProcessor.describeCause(wrapper);
            assertTrue(described.contains("SyntaxError"), "was: " + described);
            assertTrue(described.contains("(line 2, column"), "was: " + described);
            assertTrue(!described.contains("Failed to create pooled"), "was: " + described);
        }
    }

    @Test
    @DisplayName("describeCause falls back to the root cause message when no JavaScript is involved")
    void describeCauseFallsBackToRoot() {
        Exception wrapper = new RuntimeException("outer", new IllegalStateException("Function 'onMessage' not found"));
        assertEquals("Function 'onMessage' not found", CommonProcessor.describeCause(wrapper));
    }
}
