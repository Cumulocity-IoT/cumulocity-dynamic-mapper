package dynamic.mapper.processor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * Micro benchmark: eager {@code toJsInterop} versus {@code toJsInteropLazy} on a medium complex
 * document. Not named *Test, so the normal build skips it; run with
 * {@code mvn test -Dtest=LazyJsProxiesBenchmark}.
 */
class LazyJsProxiesBenchmark {

    /** ~60 readings (7 fields each, one nested object and one list) plus device metadata: ~900 values. */
    private static Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("deviceId", "dev-4711");
        root.put("ts", "2026-11-17T10:15:30.000Z");
        root.put("firmware", "1.42.7");
        Map<String, Object> meta = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            meta.put("attr" + i, "value-" + i);
        }
        root.put("meta", meta);
        List<Object> readings = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sensor", "s" + i);
            r.put("value", i * 1.5);
            r.put("unit", "C");
            r.put("quality", i % 3);
            r.put("valid", i % 2 == 0);
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("lat", 48.1 + i / 1000.0);
            pos.put("lng", 11.5 + i / 1000.0);
            pos.put("alt", 520 + i);
            r.put("pos", pos);
            r.put("tags", new ArrayList<>(List.of("a" + i, "b" + i, "c" + i)));
            readings.add(r);
        }
        root.put("readings", readings);
        return root;
    }

    private static final String[][] SCENARIOS = {
            { "convert only (script ignores payload)", "return 1;" },
            { "read 3 fields (typical filter/route)", "return p.deviceId + p.meta.attr3 + p.readings[10].value;" },
            { "read one reading (first match)", "return p.readings[0].sensor + p.readings[0].pos.lat;" },
            { "traverse everything (sum all values)",
                    "var s=0; p.readings.forEach(function(r){s+=r.value+r.pos.lat+r.pos.lng+r.tags.length;}); return s;" },
    };

    private static double measure(Context ctx, Value fn, Function<Object, Object> conv, Map<String, Object> doc,
            int iterations) {
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            fn.execute(ctx.asValue(conv.apply(doc)));
        }
        return (System.nanoTime() - t0) / 1000.0 / iterations; // microseconds per message
    }

    @Test
    void eagerVersusLazy() {
        Map<String, Object> doc = document();
        int warmup = 3000;
        int iterations = 5000;
        StringBuilder out = new StringBuilder("\n%-42s %12s %12s %8s%n".formatted("scenario (us/message)", "eager", "lazy", "speedup"));
        try (Context ctx = Context.newBuilder("js")
                .allowHostAccess(HostAccess.newBuilder().allowPublicAccess(true).build()).build()) {
            for (String[] s : SCENARIOS) {
                Value fn = ctx.eval("js", "(function(p){" + s[1] + "})");
                double eager = 0;
                double lazy = 0;
                // alternate and keep the best of 3 rounds to damp JIT/GC noise
                eager = Double.MAX_VALUE;
                lazy = Double.MAX_VALUE;
                measure(ctx, fn, JavaScriptInteropHelper::toJsInterop, doc, warmup);
                measure(ctx, fn, JavaScriptInteropHelper::toJsInteropLazy, doc, warmup);
                for (int round = 0; round < 3; round++) {
                    eager = Math.min(eager, measure(ctx, fn, JavaScriptInteropHelper::toJsInterop, doc, iterations));
                    lazy = Math.min(lazy, measure(ctx, fn, JavaScriptInteropHelper::toJsInteropLazy, doc, iterations));
                }
                out.append("%-42s %12.1f %12.1f %7.1fx%n".formatted(s[0], eager, lazy, eager / lazy));
            }
        }
        System.out.println(out);
    }
}
