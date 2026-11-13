package dynamic.mapper.processor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.inbound.MessageDeduplicationService;
import dynamic.mapper.processor.model.DataPrepContext;

/** {@code context.isDuplicate(key, windowSeconds)} of a Smart Function (issue #692). */
class SmartFunctionContextIsDuplicateTest {

    private final MessageDeduplicationService store = new MessageDeduplicationService();
    private final Set<String> flagged = new HashSet<>();
    private Context js;

    @BeforeEach
    void setUp() {
        js = Context.create("js");
    }

    @AfterEach
    void tearDown() {
        js.close();
    }

    private SmartFunctionContext invocation(boolean testRun) {
        return new SmartFunctionContext(js, "t", null, false, new FlowStateStore(), "m1", null,
                testRun ? null : (fp, window) -> {
                    boolean duplicate = store.isDuplicate("t", "m1", fp, window);
                    if (duplicate) {
                        flagged.add(fp);
                    }
                    return duplicate;
                });
    }

    private Value v(Object o) {
        return js.asValue(o);
    }

    @Test
    void secondCallWithTheSameKeyIsADuplicateEvenFromAnotherInvocation() {
        assertFalse(invocation(false).isDuplicate(v("d1|t1"), v(60)));
        assertTrue(invocation(false).isDuplicate(v("d1|t1"), v(60)));
        assertEquals(1, flagged.size(), "the host is told about the repeat");
    }

    @Test
    void differentKeysAreNotDuplicates() {
        var ctx = invocation(false);
        assertFalse(ctx.isDuplicate(v("a"), v(60)));
        assertFalse(ctx.isDuplicate(v("b"), v(60)));
    }

    @Test
    void numbersBooleansAndObjectsWorkAsKeys() {
        var ctx = invocation(false);
        assertFalse(ctx.isDuplicate(v(42), v(60)));
        assertTrue(ctx.isDuplicate(v(42), v(60)));
        assertFalse(ctx.isDuplicate(v(true), v(60)));
        assertTrue(ctx.isDuplicate(v(true), v(60)));
        assertFalse(ctx.isDuplicate(js.eval("js", "({a: 1, b: [2]})"), v(60)));
        assertTrue(ctx.isDuplicate(js.eval("js", "({a: 1, b: [2]})"), v(60)));
    }

    @Test
    void missingKeyOrInvalidWindowFailsOpenWithAWarning() {
        var ctx = invocation(false);
        assertFalse(ctx.isDuplicate(js.eval("js", "undefined"), v(60)));
        assertFalse(ctx.isDuplicate(v("k"), v("sixty")));
        assertFalse(ctx.isDuplicate(v("k"), v(0)));
        assertFalse(ctx.isDuplicate(v("k"), v(7200)));
        assertFalse(ctx.isDuplicate(v("k"), v(7200)), "a window above 3600 is rejected, not clamped");
        assertFalse(ctx.isDuplicate(v("k"), v(60)), "nothing was recorded by the failed calls");
        assertEquals(5, ((Value) ctx.getState(DataPrepContext.WARNINGS)).getArraySize());
    }

    @Test
    void aTestRunNeverRecordsAndNeverReportsADuplicate() {
        var test = invocation(true);
        assertFalse(test.isDuplicate(v("k"), v(60)));
        assertFalse(test.isDuplicate(v("k"), v(60)));
        assertFalse(invocation(false).isDuplicate(v("k"), v(60)), "the test run left nothing behind");
    }

    @Test
    void numberAndObjectKeysMatchTheFilterFunction() {
        // the filter sees 5.0 as a Double and an object as a Map, the script as a JS number and object
        assertFalse(store.isDuplicate("t", "m1",
                MessageDeduplicationService.fingerprint(MessageDeduplicationService.keyText(5.0)), 60));
        assertTrue(invocation(false).isDuplicate(v(5), v(60)));
        var member = new java.util.LinkedHashMap<String, Object>();
        member.put("b", 2);
        member.put("a", 1);
        assertFalse(store.isDuplicate("t", "m1",
                MessageDeduplicationService.fingerprint(MessageDeduplicationService.keyText(member)), 60));
        assertTrue(invocation(false).isDuplicate(js.eval("js", "({a: 1, b: 2})"), v(60)));
    }

    @Test
    void sharesItsStoreWithTheFilterFunction() {
        // $isDuplicate in the filter and context.isDuplicate hash the key the same way
        assertFalse(store.isDuplicate("t", "m1", MessageDeduplicationService.fingerprint("d1|t1"), 60));
        assertTrue(invocation(false).isDuplicate(v("d1|t1"), v(60)));
    }

    /**
     * A script reaches only the methods of {@link DataPrepContext} under the production host-access policy. The
     * tests above call the Java method directly and cannot see a method that scripts are not allowed to call.
     */
    @Test
    void scriptCanCallIsDuplicateUnderTheProductionHostAccessPolicy() {
        try (Context scripted = Context.newBuilder("js")
                .allowHostAccess(new dynamic.mapper.core.GraalVMContextService().getHostAccess())
                .build()) {
            var ctx = new SmartFunctionContext(scripted, "t", null, false, new FlowStateStore(), "m1", null,
                    (fp, window) -> store.isDuplicate("t", "m1", fp, window));
            scripted.getBindings("js").putMember("context", ctx);
            assertFalse(scripted.eval("js", "context.isDuplicate('dev|t1', 60)").asBoolean());
            assertTrue(scripted.eval("js", "context.isDuplicate('dev|t1', 60)").asBoolean());
            assertFalse(scripted.eval("js", "context.isDuplicate({a: 1}, 60)").asBoolean());
        }
    }
}
