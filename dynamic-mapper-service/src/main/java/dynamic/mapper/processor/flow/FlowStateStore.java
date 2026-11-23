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
 * ({@link #clearMappingState}), a tenant is removed ({@link #removeTenant}),
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

    // Group buffers of collect() live beside the mapping's state under this key prefix; loadState hides them.
    private static final String GROUP_PREFIX = "\u0000group:";
    static final int MAX_OPEN_GROUPS = 1000;

    private final java.util.concurrent.atomic.AtomicLong groupSequence = new java.util.concurrent.atomic.AtomicLong();

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
        Map<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, Object> entry : mappingState.entrySet()) {
            if (!entry.getKey().startsWith(GROUP_PREFIX)) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return snapshot;
    }

    /**
     * When a group is complete. Any one condition that holds hands the group out; at least one must be given.
     *
     * @param count         complete at this many distinct items, or {@code null}
     * @param keys          complete when all of these item keys are present, or {@code null}
     * @param last          complete now: this call carries the last item
     * @param maxAgeSeconds incomplete groups older than this (measured from their first item) are dropped when
     *                      a call with this option arrives, or {@code null}
     */
    public record CollectOptions(Integer count, java.util.Set<String> keys, boolean last, Integer maxAgeSeconds) {
        public static CollectOptions count(int count) {
            return new CollectOptions(count, null, false, null);
        }
    }

    /** Shorthand for {@link #collect(String, String, String, String, Object, CollectOptions)} with a count. */
    public Map<String, Object> collect(String tenant, String mappingIdentifier, String groupKey, String itemKey,
            Object value, int expected) {
        return collect(tenant, mappingIdentifier, groupKey, itemKey, value, CollectOptions.count(expected));
    }

    /**
     * Adds one item to a named group and, in the same atomic step, returns and removes the whole group when it is
     * complete. This is the one operation a Smart Function cannot build from {@code getState}/{@code setState}:
     * those work on a snapshot taken when the message started and commit when it ends, so of {@code expected}
     * messages that run at the same time none sees all the others' items.
     *
     * <p>A group is complete when its item count reaches {@link CollectOptions#count()}, or all of
     * {@link CollectOptions#keys()} are present, or this call has {@link CollectOptions#last()}. Exactly one
     * caller receives a given group; every other call returns {@code null}. A repeated {@code itemKey} replaces
     * the earlier value. A group lives in memory only, is dropped with the mapping's state (mapping deleted,
     * {@code clearState()}, retention expiry), and a mapping keeps at most {@value #MAX_OPEN_GROUPS} incomplete
     * groups: the oldest is dropped when one more opens. Groups can also be flushed early with {@link #take}.</p>
     *
     * @return the group (item key to value), or {@code null} while it is incomplete
     */
    public Map<String, Object> collect(String tenant, String mappingIdentifier, String groupKey, String itemKey,
            Object value, CollectOptions options) {
        if (tenant == null || mappingIdentifier == null || groupKey == null || itemKey == null || value == null
                || options == null) {
            return null;
        }
        ConcurrentHashMap<String, Object> mappingState = store
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new ConcurrentHashMap<>());
        String key = GROUP_PREFIX + groupKey;
        long now = clock.millis();
        Long maxAgeMillis = options.maxAgeSeconds() == null ? null : options.maxAgeSeconds() * 1000L;
        Map<String, Object>[] completed = new Map[1];
        mappingState.compute(key, (k, existing) -> {
            Group group = existing instanceof Group g ? g : new Group(now, groupSequence.incrementAndGet());
            if (maxAgeMillis != null && now - group.created > maxAgeMillis) {
                group = new Group(now, groupSequence.incrementAndGet()); // the earlier values are stale: start over
            }
            group.items.put(itemKey, value);
            boolean complete = options.last()
                    || (options.count() != null && group.items.size() >= options.count())
                    || (options.keys() != null && group.items.keySet().containsAll(options.keys()));
            if (complete) {
                completed[0] = group.items;
                return null;
            }
            return group;
        });
        markSaved(tenant, mappingIdentifier);
        if (maxAgeMillis != null) {
            mappingState.entrySet().removeIf(e -> e.getValue() instanceof Group g && now - g.created > maxAgeMillis);
        }
        if (completed[0] == null) {
            evictOldestGroups(mappingState);
        }
        return completed[0];
    }

    /**
     * Removes a group and returns it as it is, complete or not. Atomic with {@link #collect}: a value added at the
     * same time lands either in the returned group or in a new one, never in both and never nowhere.
     *
     * @return the group (item key to value), or {@code null} if there is no such group
     */
    public Map<String, Object> take(String tenant, String mappingIdentifier, String groupKey) {
        if (tenant == null || mappingIdentifier == null || groupKey == null) {
            return null;
        }
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> tenantStore = store.get(tenant);
        ConcurrentHashMap<String, Object> mappingState = tenantStore == null ? null : tenantStore.get(mappingIdentifier);
        if (mappingState == null) {
            return null;
        }
        Map<String, Object>[] taken = new Map[1];
        mappingState.computeIfPresent(GROUP_PREFIX + groupKey, (k, existing) -> {
            if (existing instanceof Group g) {
                taken[0] = g.items;
                return null;
            }
            return existing;
        });
        return taken[0];
    }

    /**
     * Lists the keys of the incomplete groups whose key starts with the prefix, oldest first. A group that
     * completes while the list is built may still appear in it; {@link #take} then returns {@code null}.
     */
    public java.util.List<String> openGroups(String tenant, String mappingIdentifier, String prefix) {
        if (tenant == null || mappingIdentifier == null) {
            return java.util.List.of();
        }
        ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> tenantStore = store.get(tenant);
        ConcurrentHashMap<String, Object> mappingState = tenantStore == null ? null : tenantStore.get(mappingIdentifier);
        if (mappingState == null) {
            return java.util.List.of();
        }
        String wanted = prefix == null ? "" : prefix;
        return mappingState.entrySet().stream()
                .filter(e -> e.getValue() instanceof Group && e.getKey().startsWith(GROUP_PREFIX + wanted))
                .sorted(java.util.Comparator.comparingLong(e -> ((Group) e.getValue()).sequence))
                .map(e -> e.getKey().substring(GROUP_PREFIX.length()))
                .toList();
    }

    private static void evictOldestGroups(ConcurrentHashMap<String, Object> mappingState) {
        long open = mappingState.values().stream().filter(v -> v instanceof Group).count();
        while (open > MAX_OPEN_GROUPS) {
            String oldest = null;
            long oldestCreated = Long.MAX_VALUE;
            for (Map.Entry<String, Object> entry : mappingState.entrySet()) {
                if (entry.getValue() instanceof Group g && g.sequence < oldestCreated) {
                    oldestCreated = g.sequence;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null || mappingState.remove(oldest) == null) {
                break;
            }
            open--;
        }
    }

    private static final class Group {
        final long created;
        final long sequence; // opening order: clock milliseconds tie within a burst
        final Map<String, Object> items = new java.util.LinkedHashMap<>();

        Group(long created, long sequence) {
            this.created = created;
            this.sequence = sequence;
        }
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
    public void removeTenant(String tenant) {
        if (tenant == null) {
            return;
        }
        store.remove(tenant);
        lastSavedAt.remove(tenant);
        log.debug("{} - Cleared all flow state", tenant);
    }
}
