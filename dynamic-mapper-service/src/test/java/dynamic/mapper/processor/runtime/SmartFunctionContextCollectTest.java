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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.core.GraalHostAccessPolicy;
import dynamic.mapper.processor.flow.FlowStateStore;
import dynamic.mapper.processor.model.DataPrepContext;

/**
 * {@code context.collect} with its options, {@code context.take} and {@code context.openGroups}, called from
 * JavaScript under the production host-access policy.
 */
class SmartFunctionContextCollectTest {

    private final FlowStateStore store = new FlowStateStore();
    private Context js;

    @BeforeEach
    void setUp() {
        js = Context.newBuilder("js").allowHostAccess(GraalHostAccessPolicy.hostAccess()).build();
    }

    @AfterEach
    void tearDown() {
        js.close();
    }

    private SmartFunctionContext invocation(boolean testRun) {
        return new SmartFunctionContext(js, "t", null, false, testRun ? null : store, "m1", null);
    }

    /** Runs a script with {@code context} bound to a fresh invocation and returns its JSON. */
    private String run(SmartFunctionContext ctx, String script) {
        js.getBindings("js").putMember("context", ctx);
        return js.eval("js", "JSON.stringify(" + script + ")").asString();
    }

    private String run(String script) {
        return run(invocation(false), script);
    }

    @Test
    void aNumberStillMeansCount() {
        assertEquals("null", run("context.collect('g','a',1,2)"));
        assertEquals("{\"a\":1,\"b\":2}", run("context.collect('g','b',2,2)"));
    }

    @Test
    void keysCompleteTheGroup() {
        assertEquals("null", run("context.collect('g','a',1,{keys:['a','b']})"));
        assertEquals("{\"a\":1,\"b\":2}", run("context.collect('g','b',2,{keys:['a','b']})"));
    }

    @Test
    void lastCompletesTheGroupAtOnce() {
        assertEquals("null", run("context.collect('g','a',1,{count:5,last:false})"));
        assertEquals("{\"a\":1,\"b\":2}", run("context.collect('g','b',2,{count:5,last:true})"));
    }

    @Test
    void takeFlushesAPartialGroupAndOpenGroupsListsTheOpenOnes() {
        run("context.collect('dev|t1','a',1,10)");
        run("context.collect('dev|t1','b',2,10)");
        assertEquals("[\"dev|t1\"]", run("context.openGroups('dev|')"));
        assertEquals("[]", run("context.openGroups('other|')"));

        assertEquals("{\"a\":1,\"b\":2}", run("context.take('dev|t1')"));
        assertEquals("null", run("context.take('dev|t1')"));
        assertEquals("[]", run("context.openGroups('')"));
    }

    @Test
    void invalidArgumentsFailOpenWithWarnings() {
        SmartFunctionContext ctx = invocation(false);
        String[] bad = { "0", "'x'", "null", "{}", "{count:0}", "{keys:[]}", "{keys:[1]}", "{last:'yes'}",
                "{count:2,maxAgeSeconds:0}", "[1,2]" };
        for (String arg : bad) {
            assertEquals("null", run(ctx, "context.collect('g','a',1," + arg + ")"), arg);
        }
        assertEquals(bad.length, ((Value) ctx.getState(DataPrepContext.WARNINGS)).getArraySize());
        assertEquals("[]", run(ctx, "context.openGroups('')"), "nothing was collected");
    }

    @Test
    void aTestRunBuffersNothing() {
        SmartFunctionContext test = invocation(true);
        assertEquals("null", run(test, "context.collect('g','a',1,1)"));
        assertEquals("null", run(test, "context.take('g')"));
        assertEquals("[]", run(test, "context.openGroups('')"));
        assertNull(test.take("g"));
    }

    /** The "flush the previous timestamp when a newer one arrives" example of the Flow state page. */
    @Test
    void aNewerTimestampFlushesThePreviousPartialGroup() {
        String fn = "(function(msg, context) {"
                + " var parts = msg.topic.split('/'); var name = parts[parts.length-1]; var deviceId = parts[parts.length-2];"
                + " var current = deviceId + '|' + msg.payload.time; var out = [];"
                + " context.openGroups(deviceId + '|').forEach(function(key) {"
                + "   if (key !== current) { var partial = context.take(key); if (partial) { out.push({time: key.split('|')[1], values: partial}); } } });"
                + " context.collect(current, name, msg.payload.value, {last:false, count:10000, maxAgeSeconds:3600});"
                + " return out; })";
        String[] messages = {
                "{topic:'uns/p/d1/a', payload:{time:'t1', value:1}}",
                "{topic:'uns/p/d1/b', payload:{time:'t1', value:2}}",
                "{topic:'uns/p/d2/a', payload:{time:'t1', value:9}}",   // another device is not flushed
                "{topic:'uns/p/d1/a', payload:{time:'t2', value:3}}" }; // newer timestamp flushes d1|t1
        String last = null;
        for (String m : messages) {
            SmartFunctionContext ctx = invocation(false);
            js.getBindings("js").putMember("context", ctx);
            last = js.eval("js", "JSON.stringify(" + fn + "(" + m + ", context))").asString();
        }
        assertEquals("[{\"time\":\"t1\",\"values\":{\"a\":1,\"b\":2}}]", last);
        assertEquals("[\"d1|t2\"]", run("context.openGroups('d1|')"));
        assertEquals("[\"d2|t1\"]", run("context.openGroups('d2|')"));
    }

    /** Values added while another thread takes the group land in exactly one group: none lost, none twice. */
    @Test
    void concurrentCollectAndTakeNeverLoseOrDuplicateAValue() throws Exception {
        int values = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ThreadLocal<Context> contexts = ThreadLocal.withInitial(
                () -> Context.newBuilder("js").allowHostAccess(GraalHostAccessPolicy.hostAccess()).build());
        AtomicInteger seen = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < values; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    Context c = contexts.get();
                    SmartFunctionContext ctx = new SmartFunctionContext(c, "t", null, false, store, "m1", null);
                    c.getBindings("js").putMember("context", ctx);
                    // every 7th message flushes whatever is buffered, the others only add (count never reached)
                    Value out = c.eval("js", index % 7 == 0
                            ? "(context.collect('g','k" + index + "',1,{count:10000,last:false}), context.take('g'))"
                            : "context.collect('g','k" + index + "',1,10000)");
                    if (out != null && !out.isNull()) {
                        seen.addAndGet((int) out.getMemberKeys().size());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        var rest = store.take("t", "m1", "g");
        int left = rest == null ? 0 : rest.size();
        assertTrue(seen.get() + left == values, "taken " + seen.get() + " + left " + left + " != " + values);
    }
}
