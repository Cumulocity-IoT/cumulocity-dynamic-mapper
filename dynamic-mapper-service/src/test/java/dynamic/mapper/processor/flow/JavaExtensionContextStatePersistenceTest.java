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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dynamic.mapper.model.Mapping;

/**
 * Native state of a Java extension must reach the shared per-mapping store, and only the keys the
 * invocation changed.
 */
class JavaExtensionContextStatePersistenceTest {

    private static final String TENANT = "t1";
    private static final String MAPPING = "m1";

    private final FlowStateStore store = new FlowStateStore();

    private JavaExtensionContextImpl invocation(FlowStateStore withStore) {
        Mapping mapping = new Mapping();
        mapping.setIdentifier(MAPPING);
        return new JavaExtensionContextImpl(null, null, TENANT, false, mapping, null, withStore,
                store.loadState(TENANT, MAPPING));
    }

    @Test
    void stateWrittenInOneMessageIsReadByTheNext() {
        JavaExtensionContextImpl first = invocation(store);
        first.setNativeState("lastSeen", "abc");
        first.persistNativeState();

        assertEquals("abc", invocation(store).getNativeState("lastSeen"));
    }

    @Test
    void anInvocationThatOnlyReadsDoesNotRevertConcurrentWrites() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("count", 1), Set.of());
        JavaExtensionContextImpl slowReader = invocation(store);
        slowReader.getNativeState("count");

        JavaExtensionContextImpl fast = invocation(store);
        fast.setNativeState("count", 2);
        fast.persistNativeState();
        slowReader.persistNativeState();

        assertEquals(2, store.loadState(TENANT, MAPPING).get("count"));
    }

    @Test
    void settingNullRemovesTheKey() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("a", 1, "b", 2), Set.of());
        JavaExtensionContextImpl ctx = invocation(store);
        ctx.setNativeState("a", null);
        ctx.persistNativeState();

        Map<String, Object> state = store.loadState(TENANT, MAPPING);
        assertFalse(state.containsKey("a"));
        assertEquals(2, state.get("b"));
    }

    @Test
    void clearStateWipesTheStoredState() {
        store.commitChanges(TENANT, MAPPING, false, Map.of("old", 1), Set.of());
        JavaExtensionContextImpl ctx = invocation(store);
        ctx.clearState();
        ctx.setNativeState("fresh", 3);
        ctx.persistNativeState();

        assertEquals(Map.of("fresh", 3), store.loadState(TENANT, MAPPING));
    }

    @Test
    void withoutAStoreNothingIsWritten() {
        // a test run: the context gets no store, so production state is untouched
        store.commitChanges(TENANT, MAPPING, false, Map.of("k", 1), Set.of());
        JavaExtensionContextImpl ctx = invocation(null);
        ctx.setNativeState("k", 99);
        ctx.persistNativeState();

        assertEquals(1, store.loadState(TENANT, MAPPING).get("k"));
    }

    @Test
    void nestedCollectionsAreCopiedOnLoad() {
        List<Object> shared = new ArrayList<>(List.of("a"));
        store.commitChanges(TENANT, MAPPING, false, Map.of("list", shared), Set.of());

        @SuppressWarnings("unchecked")
        List<Object> mine = (List<Object>) invocation(store).getNativeState("list");

        assertNotSame(shared, mine);
        mine.add("b"); // must not leak into the object parallel messages hold
        assertEquals(List.of("a"), shared);
    }
}
