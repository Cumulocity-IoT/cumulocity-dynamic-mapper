/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

package dynamic.mapper.processor.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.core.GraalHostAccessPolicy;
import dynamic.mapper.core.InventoryEnrichmentClient;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.model.ExternalId;

/** Edge cases of values stored in state and of the lookups, called from JavaScript under the production policy. */
class SmartFunctionContextRobustnessTest {

    private final FlowStateStore store = new FlowStateStore();
    private Context js;
    private SmartFunctionContext ctx;

    @BeforeEach
    void setUp() {
        js = Context.newBuilder("js").allowHostAccess(GraalHostAccessPolicy.hostAccess()).build();
        InventoryEnrichmentClient inventory = new InventoryEnrichmentClient() {
            @Override
            public Map<String, Object> getMOFromInventoryCache(String tenant, String sourceId, Boolean testing) {
                return Map.of("id", sourceId);
            }

            @Override
            public Map<String, Object> getMOFromInventoryCacheByExternalId(String tenant, ExternalId externalId,
                    Boolean testing) {
                return Map.of("id", externalId.getExternalId());
            }
        };
        ctx = new SmartFunctionContext(js, "t", inventory, false, store, "m1", null);
        js.getBindings("js").putMember("context", ctx);
    }

    @AfterEach
    void tearDown() {
        js.close();
    }

    private String run(String script) {
        return js.eval("js", "JSON.stringify(" + script + ")").asString();
    }

    private int warnings() {
        return ctx.collectedWarnings().size();
    }

    @Test
    void aDateIsStoredAsIsoText() {
        js.eval("js", "context.setState('d', new Date(0))");
        assertEquals("\"1970-01-01T00:00:00Z\"", run("context.getState('d')"));
    }

    @Test
    void aFunctionIsReplacedByNullWithAWarning() {
        js.eval("js", "context.setState('f', {a: 1, f: function() {}})");
        assertEquals("{\"a\":1,\"f\":null}", run("context.getState('f')"));
        assertEquals(1, warnings());
    }

    @Test
    void theKeyOrderOfAStoredObjectIsKept() {
        js.eval("js", "context.setState('o', {z: 1, a: 2, m: {y: 1, b: 2}})");
        assertEquals("{\"z\":1,\"a\":2,\"m\":{\"y\":1,\"b\":2}}", run("context.getState('o')"));
    }

    @Test
    void aSelfReferenceFailsWithAClearMessageNotAStackOverflow() {
        PolyglotException e = org.junit.jupiter.api.Assertions.assertThrows(PolyglotException.class,
                () -> js.eval("js", "var o = {}; o.self = o; context.setState('cyc', o)"));
        assertTrue(e.getMessage().contains("refers to itself"), e.getMessage());
    }

    @Test
    void aRepeatedButNotCircularObjectIsAccepted() {
        js.eval("js", "var shared = {x: 1}; context.setState('s', {a: shared, b: shared, c: [shared, shared]})");
        assertEquals("{\"a\":{\"x\":1},\"b\":{\"x\":1},\"c\":[{\"x\":1},{\"x\":1}]}", run("context.getState('s')"));
    }

    @Test
    void aKeyStoredAsNullReturnsTheDefaultLikeTheNextMessageWould() {
        js.eval("js", "context.setState('n', null)");
        assertEquals("\"DEFAULT\"", run("context.getState('n', 'DEFAULT')"));
    }

    @Test
    void aNumericExternalIdIsLookedUp() {
        assertEquals("{\"id\":\"12345\"}",
                run("context.getManagedObjectByExternalId({externalId: 12345, type: 'c8y_Serial'})"));
        assertEquals(0, warnings());
    }

    @Test
    void aMissingExternalIdMemberWarnsClearly() {
        assertEquals("null", run("context.getManagedObjectByExternalId({type: 'c8y_Serial'})"));
        assertEquals("null", run("context.getManagedObjectByExternalId(null)"));
        assertEquals("null", run("context.getManagedObjectByExternalId(undefined)"));
        assertEquals("null", run("context.getManagedObjectByExternalId()".replace("()", "('abc')")));
        assertEquals("null", run("context.getManagedObjectByExternalId({externalId: {a: 1}, type: 't'})"));
        assertEquals(5, warnings());
    }

    @Test
    void anUndefinedDeviceIdReturnsNullWithAWarning() {
        assertEquals("null", run("context.getManagedObject(undefined)"));
        assertEquals(1, warnings());
    }

    @Test
    void getDTMAssetReturnsNullAsTheContractSaysAndWarns() {
        assertEquals("null", run("context.getDTMAsset('x')"));
        assertEquals(1, warnings());
    }

    @Test
    void aTestRunSaysThatCollectIsNotEvaluated() {
        SmartFunctionContext test = new SmartFunctionContext(js, "t", null, true, null, "m1", null);
        js.getBindings("js").putMember("context", test);
        assertEquals("null", run("context.collect('g', 'a', 1, 1)"));
        assertEquals(1, test.collectedWarnings().size());
        assertTrue(test.collectedWarnings().get(0).contains("test run"));
    }

    @Test
    void warningsAndLogsAreNotVisibleAsStateAndCannotBeForged() {
        ctx.addWarning("w");
        ctx.addLogMessage("l");
        js.eval("js", "context.setState('_WARNINGS_', ['forged']); context.setState('k', 1)");
        assertEquals("[\"_WARNINGS_\",\"k\"]", run("context.getStateKeySet().slice().sort()"));
        assertEquals(java.util.List.of("w"), ctx.collectedWarnings(), "a state key is not a warning");
        ctx.persistState();
        // the key a script chose is state like any other, but the real warnings never reach the store
        assertEquals(java.util.Set.of("_WARNINGS_", "k"), store.loadState("t", "m1").keySet());
    }

    @Test
    void messagesPerInvocationAreBounded() {
        for (int i = 0; i < 2000; i++) {
            ctx.addWarning("w" + i);
            ctx.addLogMessage("l" + i);
        }
        assertEquals(501, ctx.collectedWarnings().size());
        assertTrue(ctx.collectedWarnings().get(500).contains("not recorded"));
        assertEquals(501, ctx.collectedLogs().size());
    }

    @Test
    void incrementStateCountsAndReturnsTheNewValue() {
        assertEquals("1", run("context.incrementState('n')"));
        assertEquals("6", run("context.incrementState('n', 5)"));
        assertEquals("6", run("context.getState('n')"), "visible to the same message");
        assertEquals("6.5", run("context.incrementState('n', 0.5)"));
        ctx.persistState();
        assertEquals(6.5, store.loadState("t", "m1").get("n"), "written by the increment, not written again");
    }

    @Test
    void incrementStateRejectsBadInputWithAWarningAndChangesNothing() {
        js.eval("js", "context.setState('s', 'text')");
        assertEquals("null", run("context.incrementState('s')"));
        assertEquals("null", run("context.incrementState('x', 'a')"));
        assertEquals("null", run("context.incrementState('x', NaN)"));
        assertEquals("null", run("context.incrementState('x', Infinity)"));
        assertEquals(4, warnings());
        assertEquals("\"text\"", run("context.getState('s')"));
    }

    @Test
    void concurrentIncrementsFromManyMessagesAreNeverLost() throws Exception {
        int threads = 16, perThread = 500;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                try (Context own = Context.newBuilder("js").allowHostAccess(GraalHostAccessPolicy.hostAccess()).build()) {
                    for (int i = 0; i < perThread; i++) {
                        SmartFunctionContext message = new SmartFunctionContext(own, "t", null, false, store, "m1",
                                store.loadState("t", "m1"));
                        own.getBindings("js").putMember("context", message);
                        own.eval("js", "context.incrementState('hits')");
                        message.persistState();
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        assertEquals(threads * perThread, store.loadState("t", "m1").get("hits"));
    }

    @Test
    void incrementAfterClearStateStartsFromNothing() {
        store.commitChanges("t", "m1", false, Map.of("n", 10), java.util.Set.of());
        SmartFunctionContext message = new SmartFunctionContext(js, "t", null, false, store, "m1",
                store.loadState("t", "m1"));
        js.getBindings("js").putMember("context", message);
        js.eval("js", "context.clearState()");
        assertEquals("1", js.eval("js", "JSON.stringify(context.incrementState('n'))").asString());
        message.persistState();
        assertEquals(1, store.loadState("t", "m1").get("n"));
    }

    @Test
    void incrementStateInATestRunCountsOnThrowAwayState() {
        SmartFunctionContext test = new SmartFunctionContext(js, "t", null, true, null, "m1", null);
        js.getBindings("js").putMember("context", test);
        assertEquals("1", run("context.incrementState('n')"));
        assertEquals("2", run("context.incrementState('n')"));
        assertTrue(store.loadState("t", "m1").isEmpty());
    }

    @Test
    void aValueThatDoesNotFitTheMappingIsRefusedWithAWarning() {
        String big = "x".repeat(1024 * 1024);
        for (int i = 0; i < 8; i++) {
            js.getBindings("js").putMember("big", big);
            js.eval("js", "context.setState('k" + i + "', big)");
            ctx.persistState();
            ctx = new SmartFunctionContext(js, "t", null, false, store, "m1", store.loadState("t", "m1"));
            js.getBindings("js").putMember("context", ctx);
        }
        assertTrue(store.loadState("t", "m1").size() < 8, "the 8 MB budget must stop the writes");
        assertTrue(ctx.collectedWarnings().isEmpty());
        js.getBindings("js").putMember("big", big);
        js.eval("js", "context.setState('over', big)");
        assertEquals(1, ctx.collectedWarnings().size());
        assertTrue(ctx.collectedWarnings().get(0).contains("was not stored"), ctx.collectedWarnings().get(0));
        assertEquals("null", run("context.getState('over')"));
    }

    @Test
    void aMappingKeepsAtMostThe1000Keys() {
        for (int i = 0; i < FlowStateStore.MAX_STATE_KEYS; i++) {
            js.eval("js", "context.setState('k" + i + "', 1)");
            if (i % 100 == 99) {
                ctx.persistState();
                ctx = new SmartFunctionContext(js, "t", null, false, store, "m1", store.loadState("t", "m1"));
                js.getBindings("js").putMember("context", ctx);
            }
        }
        js.eval("js", "context.setState('one-too-many', 1)");
        assertEquals(1, ctx.collectedWarnings().size());
        assertEquals("2", run("context.incrementState('k5')"), "an existing key still works");
        assertEquals("null", run("context.incrementState('another-new-key')"));
        assertEquals(2, ctx.collectedWarnings().size());
    }

    @Test
    void aCollectedGroupHoldsAtMost10000Items() {
        js.eval("js", "for (var i = 0; i < 10001; i++) context.collect('g', 'i' + i, 1, {last: false, keys: ['never']})");
        assertEquals(1, ctx.collectedWarnings().size());
        assertTrue(ctx.collectedWarnings().get(0).contains("10000 items"), ctx.collectedWarnings().get(0));
        assertEquals("[\"g\"]", run("context.openGroups('')"), "the group itself is kept");
    }
}
