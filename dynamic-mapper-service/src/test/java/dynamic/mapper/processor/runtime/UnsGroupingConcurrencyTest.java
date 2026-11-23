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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import dynamic.mapper.core.GraalHostAccessPolicy;
import dynamic.mapper.processor.flow.FlowStateStore;

/**
 * Runs the UNS grouping sample (resources/samples/uns/uns-group-by-timestamp.js, 100 single-value messages
 * in, one measurement out) through the real {@link SmartFunctionContext} and {@link FlowStateStore}, the way
 * the enrichment processor does: load a state snapshot, run the function, commit the changed keys.
 */
class UnsGroupingConcurrencyTest {

    private static final String TENANT = "t1";
    private static final String MAPPING = "uns";
    private static final int VALUES = 100;
    private static final Path SAMPLE = Path.of("../resources/samples/uns/uns-group-by-timestamp.js");

    private final FlowStateStore store = new FlowStateStore();

    /** One message = one invocation: snapshot in, function, commit out. Returns the number of emitted objects. */
    private int invoke(Context js, String source, int index, String time) {
        SmartFunctionContext ctx = new SmartFunctionContext(js, TENANT, null, false, store, MAPPING,
                store.loadState(TENANT, MAPPING));
        js.getBindings("js").putMember("context", ctx);
        js.eval("js", source);
        Value fn = js.getBindings("js").getMember("onMessage");
        Value msg = js.eval("js", "({topic:'uns/p1/press7/m" + index + "', payload:{time:'" + time + "', value:"
                + index + "}})");
        Value out = fn.execute(msg, ctx);
        int emitted = (int) out.getArraySize();
        ctx.persistState();
        return emitted;
    }

    private static Context newContext() {
        return Context.newBuilder("js").allowHostAccess(GraalHostAccessPolicy.hostAccess()).build();
    }

    @Test
    void sequentialMessagesProduceExactlyOneMeasurement() throws Exception {
        String source = Files.readString(SAMPLE);
        int emitted = 0;
        try (Context js = newContext()) {
            for (int i = 0; i < VALUES; i++) {
                emitted += invoke(js, source, i, "2026-01-01T10:00:00.000Z");
            }
        }
        assertEquals(1, emitted);
    }

    @Test
    void anIncompleteGroupEmitsNothing() throws Exception {
        String source = Files.readString(SAMPLE);
        int emitted = 0;
        try (Context js = newContext()) {
            for (int i = 0; i < VALUES - 1; i++) {
                emitted += invoke(js, source, i, "2026-01-01T10:00:00.000Z");
            }
        }
        assertEquals(0, emitted);
    }

    @Test
    void groupsOfDifferentTimestampsAreIndependent() throws Exception {
        String source = Files.readString(SAMPLE);
        int emitted = 0;
        try (Context js = newContext()) {
            for (int i = 0; i < VALUES; i++) {
                emitted += invoke(js, source, i, "2026-01-01T10:00:00.000Z");
                if (i < VALUES - 1) {
                    emitted += invoke(js, source, i, "2026-01-01T10:00:01.000Z");
                }
            }
        }
        assertEquals(1, emitted); // second timestamp is one value short
    }

    @Test
    void concurrentMessagesProduceExactlyOneMeasurementPerGroup() throws Exception {
        String source = Files.readString(SAMPLE);
        int rounds = 50;
        int lost = 0;
        int duplicated = 0;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        ThreadLocal<Context> contexts = ThreadLocal.withInitial(UnsGroupingConcurrencyTest::newContext);
        try {
            for (int r = 0; r < rounds; r++) {
                String time = "2026-01-01T10:" + String.format("%02d", r) + ":00.000Z";
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger emitted = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < VALUES; i++) {
                    final int index = i;
                    Callable<Object> task = () -> {
                        start.await();
                        emitted.addAndGet(invoke(contexts.get(), source, index, time));
                        return null;
                    };
                    futures.add(pool.submit(task));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get();
                }
                if (emitted.get() == 0) {
                    lost++;
                } else if (emitted.get() > 1) {
                    duplicated++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, lost, "groups never emitted");
        assertEquals(0, duplicated, "groups emitted more than once");
    }
}
