package dynamic.mapper.processor.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/** The lazy views must behave like the eager copies for every way a Smart Function can use a payload. */
class LazyJsProxiesTest {

    private static Map<String, Object> payload() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", "dev-1");
        root.put("temp", 21.5);
        root.put("n", 7);
        root.put("flag", true);
        root.put("nothing", null);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", 1);
        nested.put("list", new ArrayList<>(List.of(1, 2, 3)));
        root.put("nested", nested);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("k", "item" + i);
            item.put("v", i * 10);
            items.add(item);
        }
        root.put("items", items);
        return root;
    }

    private static Object run(String body, Function<Object, Object> conversion, Map<String, Object> source) {
        try (Context ctx = Context.newBuilder("js").allowHostAccess(HostAccess.newBuilder().allowPublicAccess(true).build())
                .build()) {
            Value fn = ctx.eval("js", "(function(p){" + body + "})");
            Value result = fn.execute(ctx.asValue(conversion.apply(source)));
            return JavaScriptInteropHelper.convertValueToJavaObject(result);
        }
    }

    /** The result, or the failure: a script that fails on the eager copy must fail the same way on the view. */
    private static Object outcome(String body, Function<Object, Object> conversion, Map<String, Object> source) {
        try {
            return run(body, conversion, source);
        } catch (org.graalvm.polyglot.PolyglotException e) {
            return "FAILED";
        }
    }

    private static void sameAsEager(String body) {
        Object eager = outcome(body, JavaScriptInteropHelper::toJsInterop, payload());
        Object lazy = outcome(body, JavaScriptInteropHelper::toJsInteropLazy, payload());
        assertEquals(eager, lazy, "lazy differs from eager for: " + body);
    }

    @Test
    void readsAndTraversalsMatchTheEagerCopy() {
        sameAsEager("return p.id + ':' + p.temp + ':' + p.n + ':' + p.flag;");
        sameAsEager("return p.nothing === null || p.nothing === undefined;");
        sameAsEager("return p.missing === undefined;");
        sameAsEager("return Object.keys(p);");
        sameAsEager("return Object.keys(p.nested);");
        sameAsEager("return 'id' in p && !('zzz' in p);");
        sameAsEager("return p.items.map(function(i){return i.k + i.v;});");
        sameAsEager("var out=[]; p.items.forEach(function(i){out.push(i.v);}); return out;");
        sameAsEager("return p.items.length + p.nested.list.length;");
        sameAsEager("var s=0; for (var i=0;i<p.items.length;i++){s+=p.items[i].v;} return s;");
        sameAsEager("var ks=[]; for (var k in p){ks.push(k);} return ks;");
        sameAsEager("return JSON.stringify(p);");
        sameAsEager("return p.items.filter(function(i){return i.v>5;}).map(function(i){return i.k;});");
        sameAsEager("return {copy: p, again: p.nested};");
        // the one deliberate difference: reading past the end of an array throws on the eager copy (a
        // ProxyArray.fromList quirk) and gives undefined, as in JavaScript, on the view
        assertEquals("FAILED", outcome("return p.items[5];", JavaScriptInteropHelper::toJsInterop, payload()));
        assertEquals(null, outcome("return p.items[5];", JavaScriptInteropHelper::toJsInteropLazy, payload()));
    }

    @Test
    void childIdentityIsStable() {
        Object result = run("return p.nested === p.nested && p.items === p.items && p.items[0] === p.items[0];",
                JavaScriptInteropHelper::toJsInteropLazy, payload());
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void writesStayPrivateAndMatchTheEagerCopy() {
        String[] bodies = {
                "p.id = 'changed'; p.extra = 1; return p;",
                "delete p.temp; return Object.keys(p);",
                "p.nested.a = 99; return p.nested;",
                "p.items[0].k = 'x'; p.items.push({k:'new'}); return p.items;",
                "p.items.pop(); return p.items.length;",
                "p.nested.list[1] = 'two'; return p.nested.list;",
                "var before = p.items; p.items = []; return [before.length, p.items.length];",
        };
        for (String body : bodies) {
            Map<String, Object> source = payload();
            Object eager = outcome(body, JavaScriptInteropHelper::toJsInterop, payload());
            Object lazy = outcome(body, JavaScriptInteropHelper::toJsInteropLazy, source);
            assertEquals(eager, lazy, body);
            assertEquals(payload(), source, "the script must not change the Java source: " + body);
        }
    }

    @Test
    void unreadPartsAreNeverTouched() {
        // a source that fails when its nested part is read proves the part is not converted up front
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "x");
        source.put("big", new LinkedHashMap<String, Object>() {
            @Override
            public java.util.Set<Map.Entry<String, Object>> entrySet() {
                throw new AssertionError("converted although the script never read it");
            }

            @Override
            public java.util.Set<String> keySet() {
                throw new AssertionError("converted although the script never read it");
            }
        });
        assertEquals("x", run("return p.id;", JavaScriptInteropHelper::toJsInteropLazy, source));
        assertTrue(JavaScriptInteropHelper.toJsInteropLazy(source) != null);
    }
}
