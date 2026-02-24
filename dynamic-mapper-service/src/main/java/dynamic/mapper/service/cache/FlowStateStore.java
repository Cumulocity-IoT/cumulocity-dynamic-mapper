/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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
package dynamic.mapper.service.cache;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Persistent in-memory state store for Smart Function mappings.
 *
 * <p>State is scoped by tenant and mapping identifier. It survives individual
 * message invocations so that {@code getState}/{@code setState} calls within a
 * Smart Function accumulate data across messages (e.g. counters, running
 * averages, min/max tracking).</p>
 *
 * <p><strong>Concurrency note:</strong> When the same mapping processes
 * multiple messages concurrently, state updates follow a last-writer-wins
 * policy. This is acceptable for statistical use cases but callers that require
 * strict consistency should add application-level guards.</p>
 *
 * <p>State is cleared automatically when a mapping is deleted
 * ({@link #clearMappingState}) or a tenant is removed
 * ({@link #clearTenantState}).</p>
 *
 * <p>State does <em>not</em> survive a JVM restart.</p>
 */
@Service
@Slf4j
public class FlowStateStore {

    // Structure: <tenant, <mappingIdentifier, <key, value>>>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>> store =
            new ConcurrentHashMap<>();

    /**
     * Loads the persisted state for a mapping.
     *
     * @param tenant            the tenant identifier
     * @param mappingIdentifier the mapping's short identifier
     * @return a defensive copy of the stored state (never {@code null})
     */
    public Map<String, Object> loadState(String tenant, String mappingIdentifier) {
        if (tenant == null || mappingIdentifier == null) {
            return Collections.emptyMap();
        }
        ConcurrentHashMap<String, Object> mappingState = store
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new ConcurrentHashMap<>());
        return new HashMap<>(mappingState);
    }

    /**
     * Saves the state for a mapping, replacing any previously stored state.
     *
     * @param tenant            the tenant identifier
     * @param mappingIdentifier the mapping's short identifier
     * @param state             the state map to persist (may be empty, not {@code null})
     */
    public void saveState(String tenant, String mappingIdentifier, Map<String, Object> state) {
        if (tenant == null || mappingIdentifier == null || state == null) {
            return;
        }
        ConcurrentHashMap<String, Object> mappingState = store
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new ConcurrentHashMap<>());
        mappingState.clear();
        mappingState.putAll(state);
        log.debug("{} - Saved flow state for mapping {}: {} keys", tenant, mappingIdentifier, state.size());
    }

    /**
     * Removes all persisted state for a specific mapping.
     * Called when a mapping is deleted.
     *
     * @param tenant            the tenant identifier
     * @param mappingIdentifier the mapping's short identifier
     */
    public void clearMappingState(String tenant, String mappingIdentifier) {
        if (tenant == null || mappingIdentifier == null) {
            return;
        }
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> tenantStore = store.get(tenant);
        if (tenantStore != null) {
            tenantStore.remove(mappingIdentifier);
            log.debug("{} - Cleared flow state for mapping {}", tenant, mappingIdentifier);
        }
    }

    /**
     * Removes all persisted state for an entire tenant.
     * Called when a tenant is removed from the system.
     *
     * @param tenant the tenant identifier
     */
    public void clearTenantState(String tenant) {
        if (tenant == null) {
            return;
        }
        store.remove(tenant);
        log.debug("{} - Cleared all flow state", tenant);
    }
}
