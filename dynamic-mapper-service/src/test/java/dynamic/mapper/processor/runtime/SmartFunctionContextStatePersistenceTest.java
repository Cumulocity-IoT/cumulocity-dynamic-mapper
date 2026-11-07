package dynamic.mapper.processor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.model.DataPrepContext;

/**
 * What a Smart Function invocation writes back to the shared per-mapping state when the message ends.
 */
class SmartFunctionContextStatePersistenceTest {

    private static final String TENANT = "t1";
    private static final String MAPPING = "m1";

    private final FlowStateStore store = new FlowStateStore();
    private Context js;

    @BeforeEach
    void setUp() {
        js = Context.create("js");
    }

    @AfterEach
    void tearDown() {
        js.close();
    }

    private SmartFunctionContext invocation() {
        return new SmartFunctionContext(js, TENANT, null, false, store, MAPPING, store.loadState(TENANT, MAPPING));
    }

    private Value v(Object o) {
        return js.asValue(o);
    }

    @Test
    void persistsWhatTheInvocationWrote() {
        SmartFunctionContext ctx = invocation();
        ctx.setState("count", v(1));
        ctx.persistState();

        assertEquals(1, store.loadState(TENANT, MAPPING).get("count"));
    }

    @Test
    void anInvocationThatOnlyReadsDoesNotRevertConcurrentWrites() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("count", 1), java.util.Set.of());
        SmartFunctionContext slowReader = invocation(); // loaded count=1
        slowReader.getState("count");

        SmartFunctionContext fast = invocation();
        fast.setState("count", v(2));
        fast.persistState();

        slowReader.persistState(); // finishes last, wrote nothing

        assertEquals(2, store.loadState(TENANT, MAPPING).get("count"));
    }

    @Test
    void twoInvocationsWritingDifferentKeysBothSurvive() {
        SmartFunctionContext a = invocation();
        SmartFunctionContext b = invocation();
        a.setState("a", v(1));
        b.setState("b", v(2));
        a.persistState();
        b.persistState();

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertEquals(1, state.get("a"));
        assertEquals(2, state.get("b"));
    }

    @Test
    void settingNullDoesNotLoseTheOtherKeys() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("keep", 1, "drop", 2), java.util.Set.of());
        SmartFunctionContext ctx = invocation();
        ctx.setState("drop", null); // used to throw inside saveState and silently lose all state
        ctx.persistState();

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertEquals(1, state.get("keep"));
        assertTrue(!state.containsKey("drop"));
    }

    @Test
    void clearStateCalledByTheFunctionWipesTheStoredState() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("old", 1), java.util.Set.of());
        SmartFunctionContext ctx = invocation();
        ctx.clearState();
        ctx.setState("fresh", v(3));
        ctx.persistState();

        assertEquals(Map.of("fresh", 3), store.loadState(TENANT, MAPPING));
    }

    @Test
    void clearStateKeepsCollectedLogsAndWarnings() {
        SmartFunctionContext ctx = invocation();
        ctx.addLogMessage("hello");
        ctx.addWarning("careful");
        ctx.clearState();

        assertTrue(ctx.getState(DataPrepContext.LOGS) != null, "logs must survive clearState()");
        assertTrue(ctx.getState(DataPrepContext.WARNINGS) != null, "warnings must survive clearState()");
    }

    @Test
    void ephemeralKeysAreNeverPersisted() {
        SmartFunctionContext ctx = invocation();
        ctx.addLogMessage("hello");
        ctx.setState("k", v(1));
        ctx.persistState();

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertEquals(Map.of("k", 1), state);
    }
}
