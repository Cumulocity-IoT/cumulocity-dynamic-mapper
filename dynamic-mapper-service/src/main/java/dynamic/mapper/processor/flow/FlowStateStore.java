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
package dynamic.mapper.processor.flow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * ({@link #clearMappingState}), a tenant is removed ({@link #clearTenantState}),
 * or a mapping has not written its state for the retention period
 * ({@link #clearExpiredEntries}). Expiry is decided per mapping, from that mapping's own last
 * write, so a busy mapping neither keeps an idle one alive nor causes a recent one to be removed.</p>
 *
 * <p>State does <em>not</em> survive a JVM restart.</p>
 */
@Service
@Slf4j
public class FlowStateStore {

    // Structure: <tenant, <mappingIdentifier, <key, value>>>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Object>>> store =
            new ConcurrentHashMap<>();

    // Tracks the last time a mapping wrote its state, for TTL expiry: <tenant, <mappingIdentifier, instant>>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Instant>> lastSavedAt =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public FlowStateStore() {
        this(Clock.systemUTC());
    }

    // for tests, which need to move time
    FlowStateStore(Clock clock) {
        this.clock = clock;
    }

    private void markSaved(String tenant, String mappingIdentifier) {
        lastSavedAt.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(mappingIdentifier, Instant.now(clock));
    }

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
     * Saves the state for a mapping, replacing any previously stored state (last writer wins; prefer
     * {@link #commitChanges}, which does not revert other messages' writes).
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
        markSaved(tenant, mappingIdentifier);
        log.debug("{} - Saved flow state for mapping {}: {} keys", tenant, mappingIdentifier, state.size());
    }

    /**
     * Applies only the changes a message made to a mapping's state, leaving every other key as other
     * messages last wrote it.
     *
     * <p>{@link #saveState} replaces the whole map with the caller's snapshot, so a message that
     * finishes late reverts whatever concurrent messages wrote, even when it changed nothing itself,
     * and a concurrent {@link #loadState} can observe the half-rebuilt map. This method touches
     * only the given keys, one {@code ConcurrentHashMap} operation each.</p>
     *
     * @param wipe     remove every stored key first (the function called {@code clearState()})
     * @param upserts  keys to write; a {@code null} value is not storable and removes the key
     * @param removals keys to remove
     */
    public void commitChanges(String tenant, String mappingIdentifier, boolean wipe,
            Map<String, Object> upserts, java.util.Set<String> removals) {
        if (tenant == null || mappingIdentifier == null) {
            return;
        }
        if (!wipe && upserts.isEmpty() && removals.isEmpty()) {
            return;
        }
        ConcurrentHashMap<String, Object> mappingState = store
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new ConcurrentHashMap<>());
        if (wipe) {
            mappingState.clear();
        }
        for (Map.Entry<String, Object> entry : upserts.entrySet()) {
            if (entry.getValue() == null) {
                mappingState.remove(entry.getKey());
            } else {
                mappingState.put(entry.getKey(), entry.getValue());
            }
        }
        removals.forEach(mappingState::remove);
        markSaved(tenant, mappingIdentifier);
        log.debug("{} - Committed flow state changes for mapping {}: {} written, {} removed, wipe={}",
                tenant, mappingIdentifier, upserts.size(), removals.size(), wipe);
    }

    /**
     * Removes the state of every mapping of the tenant that has not written its state within the given
     * retention window. Each mapping is judged by its own last write; a read does not count. Called by
     * the scheduled cache-cleanup job.
     *
     * @param tenant           the tenant identifier
     * @param retentionMinutes number of minutes after a mapping's last write before its state is expired; 0 disables TTL
     */
    public void clearExpiredEntries(String tenant, int retentionMinutes) {
        if (retentionMinutes <= 0 || tenant == null) {
            return;
        }
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> tenantStore = store.get(tenant);
        ConcurrentHashMap<String, Instant> tenantSaved = lastSavedAt.get(tenant);
        if (tenantStore == null) {
            return;
        }
        Instant now = Instant.now(clock);
        Duration retention = Duration.ofMinutes(retentionMinutes);
        for (Map.Entry<String, ConcurrentHashMap<String, Object>> entry : tenantStore.entrySet()) {
            String mappingIdentifier = entry.getKey();
            Instant lastSaved = tenantSaved == null ? null : tenantSaved.get(mappingIdentifier);
            if (lastSaved == null) {
                // never written (created by a load only): nothing to keep
                if (entry.getValue().isEmpty()) {
                    tenantStore.remove(mappingIdentifier, entry.getValue());
                }
                continue;
            }
            if (Duration.between(lastSaved, now).compareTo(retention) >= 0) {
                tenantStore.remove(mappingIdentifier, entry.getValue());
                tenantSaved.remove(mappingIdentifier, lastSaved);
                log.info("{} - Cleared expired flow state of mapping {} (TTL: {} minutes)",
                        tenant, mappingIdentifier, retentionMinutes);
            }
        }
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
        ConcurrentHashMap<String, Instant> tenantSaved = lastSavedAt.get(tenant);
        if (tenantSaved != null) {
            tenantSaved.remove(mappingIdentifier);
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
        lastSavedAt.remove(tenant);
        log.debug("{} - Cleared all flow state", tenant);
    }
}
