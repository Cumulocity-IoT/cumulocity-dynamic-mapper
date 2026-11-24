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

package dynamic.mapper.processor.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** {@code collectNative}, {@code takeNative} and {@code openGroupsNative}: groups for Java extensions. */
class JavaExtensionContextCollectTest {

    private static final String TENANT = "t1";
    private static final String MAPPING = "m1";

    private final FlowStateStore store = new FlowStateStore();

    private JavaExtensionContextImpl invocation(FlowStateStore withStore, ProcessingContext<byte[]> processing) {
        Mapping mapping = new Mapping();
        mapping.setIdentifier(MAPPING);
        return new JavaExtensionContextImpl(null, null, TENANT, false, mapping, processing, withStore,
                withStore == null ? null : store.loadState(TENANT, MAPPING));
    }

    private JavaExtensionContextImpl invocation() {
        return invocation(store, ProcessingContext.<byte[]>builder().build());
    }

    @Test
    void aGroupIsHandedOutWhenTheCountIsReached() {
        assertNull(invocation().collectNative("g", "a", 1, 2));
        assertEquals(Map.of("a", 1, "b", 2), invocation().collectNative("g", "b", 2, 2));
        // the group is gone: the next item opens a new one
        assertNull(invocation().collectNative("g", "a", 3, 2));
    }

    @Test
    void aGroupCompletesWhenAllKeysArePresent() {
        FlowStateStore.CollectOptions options = new FlowStateStore.CollectOptions(null, Set.of("a", "b"), false, null);
        assertNull(invocation().collectNative("g", "a", 1, options));
        assertEquals(Map.of("a", 1, "b", 2), invocation().collectNative("g", "b", 2, options));
    }

    @Test
    void theLastItemCompletesAnIncompleteGroup() {
        assertNull(invocation().collectNative("g", "a", 1, new FlowStateStore.CollectOptions(5, null, false, null)));
        assertEquals(Map.of("a", 1, "b", 2),
                invocation().collectNative("g", "b", 2, new FlowStateStore.CollectOptions(5, null, true, null)));
    }

    @Test
    void takeFlushesAnIncompleteGroupAndOpenGroupsListsIt() {
        invocation().collectNative("ts|1", "a", 1, 5);
        invocation().collectNative("ts|2", "a", 1, 5);
        invocation().collectNative("other", "a", 1, 5);

        assertEquals(List.of("ts|1", "ts|2"), invocation().openGroupsNative("ts|"));
        assertEquals(Map.of("a", 1), invocation().takeNative("ts|1"));
        assertNull(invocation().takeNative("ts|1"));
        assertEquals(List.of("ts|2"), invocation().openGroupsNative("ts|"));
    }

    @Test
    void theValueIsCopiedSoLaterChangesDoNotReachTheGroup() {
        Map<String, Object> value = new HashMap<>(Map.of("x", 1));
        invocation().collectNative("g", "a", value, 2);
        value.put("x", 99);

        Map<String, Object> group = invocation().collectNative("g", "b", 2, 2);
        assertEquals(Map.of("x", 1), group.get("a"));
        assertNotSame(value, group.get("a"));
    }

    @Test
    void badArgumentsFailOpenWithAWarning() {
        ProcessingContext<byte[]> processing = ProcessingContext.<byte[]>builder().build();
        JavaExtensionContextImpl ctx = invocation(store, processing);

        assertNull(ctx.collectNative("g", "a", 1, 0));
        assertNull(ctx.collectNative("g", "a", 1, FlowStateStore.CollectOptions.MAX_EXPECTED_COUNT + 1));
        assertNull(ctx.collectNative(null, "a", 1, 2));
        assertNull(ctx.collectNative("g", null, 1, 2));
        assertNull(ctx.collectNative("g", "a", null, 2));
        assertNull(ctx.collectNative("g", "a", 1, (FlowStateStore.CollectOptions) null));
        assertNull(ctx.collectNative("g", "a", 1, new FlowStateStore.CollectOptions(null, null, false, null)));
        assertNull(ctx.collectNative("g", "a", 1, new FlowStateStore.CollectOptions(2, null, false, 0)));

        assertEquals(8, processing.getWarnings().size(), processing.getWarnings().toString());
        assertTrue(processing.getWarnings().stream().allMatch(w -> w.startsWith("collectNative:")));
        assertTrue(invocation().openGroupsNative("").isEmpty(), "nothing was collected");
    }

    @Test
    void aTestRunKeepsNoGroupsAndSaysSo() {
        ProcessingContext<byte[]> processing = ProcessingContext.<byte[]>builder().build();
        JavaExtensionContextImpl ctx = invocation(null, processing);

        assertNull(ctx.collectNative("g", "a", 1, 1));
        assertNull(ctx.takeNative("g"));
        assertTrue(ctx.openGroupsNative("").isEmpty());
        assertEquals(1, processing.getWarnings().size());
        assertTrue(processing.getWarnings().get(0).contains("test run"));
    }

    @Test
    void theScriptMethodsPointToTheNativeOnes() {
        JavaExtensionContextImpl ctx = invocation();
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> ctx.collect("g", "i", null, null))
                .getMessage().contains("collectNative"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> ctx.take("g"))
                .getMessage().contains("takeNative"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> ctx.openGroups("g"))
                .getMessage().contains("openGroupsNative"));
    }

    @Test
    void ofManyConcurrentMessagesExactlyOneGetsTheGroup() throws Exception {
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Map<String, Object>> complete = new ConcurrentLinkedQueue<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int item = i;
            futures.add(pool.submit(() -> {
                go.await();
                Map<String, Object> group = invocation().collectNative("g", "k" + item, item, n);
                if (group != null) {
                    complete.add(group);
                }
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, complete.size());
        assertEquals(n, complete.peek().size());
    }
}
