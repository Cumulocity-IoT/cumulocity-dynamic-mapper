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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.model.ExternalId;
import dynamic.mapper.processor.runtime.ProcessingContext;

/** What a Java extension can and cannot do with its context. */
class JavaExtensionContextApiTest {

    private static final String TENANT = "t1";
    private static final String MAPPING = "m1";

    private final FlowStateStore store = new FlowStateStore();
    private final ProcessingContext<byte[]> processing = ProcessingContext.<byte[]>builder().clientId("client-7").build();

    private Mapping mapping() {
        Mapping mapping = new Mapping();
        mapping.setIdentifier(MAPPING);
        return mapping;
    }

    private JavaExtensionContextImpl context(C8YAgent agent, FlowStateStore withStore,
            java.util.function.BiPredicate<String, Integer> duplicateCheck) {
        return new JavaExtensionContextImpl(null, agent, TENANT, false, mapping(), processing, withStore,
                withStore == null ? null : store.loadState(TENANT, MAPPING), duplicateCheck);
    }

    @Test
    void theClientIdComesFromTheConnectorMessage() {
        JavaExtensionContextImpl ctx = context(null, store, null);
        assertEquals("client-7", ctx.getClientId());
        assertEquals("client-7", ctx.getConfigAsMap().get("clientId"));
    }

    @Test
    void theScriptStateMethodsFailWithAPointerToTheNativeOnes() {
        JavaExtensionContextImpl ctx = context(null, store, null);
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> ctx.setState("k", null));
        assertTrue(e.getMessage().contains("setNativeState"), e.getMessage());
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> ctx.getStateAll()).getMessage()
                .contains("getNativeStateAll"));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> ctx.getManagedObject("1")).getMessage()
                .contains("getManagedObjectAsMap"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.isDuplicate(null, null));
        assertThrows(UnsupportedOperationException.class, () -> ctx.incrementState("k", null));
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> ctx.collect("g", "i", null, null))
                .getMessage().contains("collectNative"));
    }

    @Test
    void anInventoryLookupGivesACopyNotTheSharedCacheMap() {
        Map<String, Object> cached = new HashMap<>();
        cached.put("name", "pump");
        cached.put("tags", new ArrayList<>(List.of("a")));
        C8YAgent agent = mock(C8YAgent.class);
        when(agent.getMOFromInventoryCache(TENANT, "42", false)).thenReturn(cached);
        when(agent.getMOFromInventoryCacheByExternalId(TENANT, new ExternalId("x", "c8y_Serial"), false))
                .thenReturn(cached);
        when(agent.getMOFromInventoryCache(TENANT, "unknown", false)).thenReturn(null);
        JavaExtensionContextImpl ctx = context(agent, store, null);

        Map<String, Object> byId = ctx.getManagedObjectAsMap("42");
        assertEquals("pump", byId.get("name"));
        assertNotSame(cached, byId);
        byId.put("name", "changed");
        ((List<Object>) byId.get("tags")).add("b");
        Map<String, Object> byExternalId = ctx.getManagedObjectAsMap(new ExternalId("x", "c8y_Serial"));
        byExternalId.put("name", "changed too");

        assertEquals("pump", cached.get("name"), "the shared cache entry must not change");
        assertEquals(List.of("a"), cached.get("tags"));
        assertNull(ctx.getManagedObjectAsMap((String) null));
        assertNull(ctx.getManagedObjectAsMap("unknown"));
    }

    @Test
    void warningsAndLogsAreBoundedPerMessage() {
        JavaExtensionContextImpl ctx = context(null, store, null);
        for (int i = 0; i < 2000; i++) {
            ctx.addWarning("w" + i);
            ctx.addLog("l" + i);
        }
        assertEquals(501, processing.getWarnings().size());
        assertTrue(processing.getWarnings().get(500).contains("not recorded"));
        assertEquals(501, processing.getLogs().size());
    }

    @Test
    void incrementNativeStateCountsAtomicallyAcrossMessages() throws Exception {
        int threads = 8, perThread = 400;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    JavaExtensionContextImpl ctx = context(null, store, null);
                    ctx.incrementNativeState("hits", 1);
                    ctx.persistNativeState();
                }
            }));
        }
        for (var f : done) {
            f.get();
        }
        pool.shutdown();
        assertEquals(threads * perThread, store.loadState(TENANT, MAPPING).get("hits"));
    }

    @Test
    void incrementNativeStateReturnsTheNewValueAndRejectsNonNumbers() {
        JavaExtensionContextImpl ctx = context(null, store, null);
        assertEquals(1, ctx.incrementNativeState("n", 1));
        assertEquals(6, ctx.incrementNativeState("n", 5));
        assertEquals(6, ctx.getNativeState("n"));
        ctx.setNativeState("s", "text");
        assertThrows(IllegalArgumentException.class, () -> ctx.incrementNativeState("s", 1));
    }

    @Test
    void incrementNativeStateAfterClearStateStartsFromNothing() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("n", 10), Set.of());
        JavaExtensionContextImpl ctx = context(null, store, null);
        ctx.clearState();
        assertEquals(1, ctx.incrementNativeState("n", 1));
        ctx.persistNativeState();
        assertEquals(1, store.loadState(TENANT, MAPPING).get("n"));
    }

    @Test
    void aTestRunCountsOnThrowAwayState() {
        JavaExtensionContextImpl ctx = context(null, null, null);
        assertEquals(1, ctx.incrementNativeState("n", 1));
        assertEquals(2, ctx.incrementNativeState("n", 1));
        ctx.persistNativeState();
        assertTrue(store.loadState(TENANT, MAPPING).isEmpty());
    }

    @Test
    void isDuplicateAsksTheSharedCheckWithAFingerprintAndFailsOpenOnBadInput() {
        AtomicInteger calls = new AtomicInteger();
        JavaExtensionContextImpl ctx = context(null, store, (fingerprint, window) -> {
            calls.incrementAndGet();
            assertEquals(60, window);
            return "seen".equals(lastKey.get());
        });
        lastKey.set("new");
        assertFalse(ctx.isDuplicate("new", 60));
        lastKey.set("seen");
        assertTrue(ctx.isDuplicate("seen", 60));
        assertEquals(2, calls.get());

        assertFalse(ctx.isDuplicate(null, 60));
        assertFalse(ctx.isDuplicate("k", 0));
        assertFalse(ctx.isDuplicate("k", 3601));
        assertEquals(2, calls.get(), "bad input is not checked");
        assertEquals(3, processing.getWarnings().size());
    }

    private final java.util.concurrent.atomic.AtomicReference<String> lastKey =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Test
    void isDuplicateIsAlwaysFalseWithoutACheck() {
        assertFalse(context(null, null, null).isDuplicate("k", 60));
    }
}
