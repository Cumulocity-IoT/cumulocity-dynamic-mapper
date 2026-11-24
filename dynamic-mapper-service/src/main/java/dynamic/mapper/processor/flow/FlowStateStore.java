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
 * or a mapping has neither written nor read its state for the retention period
 * ({@link #clearExpiredEntries}). Expiry is decided per mapping, from that mapping's own last
 * write or read of state, so a busy mapping neither keeps an idle one alive nor causes a recent one to be removed.</p>
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

    /** Most keys a mapping may keep in state (collect groups are limited separately). */
    public static final int MAX_STATE_KEYS = 1000;
    /** Estimated memory a mapping may use for its state and its open collect groups together. */
    public static final long MAX_MAPPING_BYTES = 8L * 1024 * 1024;
    /** Most items one collect group may hold. */
    public static final int MAX_GROUP_ITEMS = 10_000;

    /** A write was refused because the mapping reached one of the limits above; nothing was changed. */
    public static class StateLimitExceededException extends RuntimeException {
        public StateLimitExceededException(String message) {
            super(message);
        }
    }

    /** What a mapping holds, kept beside its entries so that checking a limit never walks the state. */
    private static final class Stats {
        final ConcurrentHashMap<String, Long> keyBytes = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicLong stateBytes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong groupBytes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger openGroups = new java.util.concurrent.atomic.AtomicInteger();
    }

    // <tenant, <mappingIdentifier, stats>>; dropped wherever the mapping's entries are dropped
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Stats>> stats = new ConcurrentHashMap<>();

    private Stats stats(String tenant, String mappingIdentifier) {
        return stats.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new Stats());
    }

    private static void release(Stats st, Group group) {
        st.openGroups.decrementAndGet();
        st.groupBytes.addAndGet(-group.bytes);
    }

    /** A rough memory estimate (UTF-16 text, object headers); it only has to be proportional, not exact. */
    static long estimateBytes(Object value) {
        if (value == null) {
            return 8;
        }
        if (value instanceof String s) {
            return 40L + 2L * s.length();
        }
        if (value instanceof Map<?, ?> m) {
            long n = 48;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                n += 40L + 2L * String.valueOf(e.getKey()).length() + estimateBytes(e.getValue());
            }
            return n;
        }
        if (value instanceof java.util.Collection<?> c) {
            long n = 32;
            for (Object o : c) {
                n += 8 + estimateBytes(o);
            }
            return n;
        }
        return 16;
    }

    private static long entryBytes(String key, Object value) {
        return 40L + 2L * key.length() + estimateBytes(value);
    }

    /** Whether writing {@code size} bytes under {@code key} keeps the mapping inside its limits; the reason if not. */
    private static String limitReason(Stats st, String key, long size) {
        Long old = st.keyBytes.get(key);
        if (old == null && st.keyBytes.size() >= MAX_STATE_KEYS) {
            return "state holds the maximum of " + MAX_STATE_KEYS + " keys per mapping";
        }
        if (st.stateBytes.get() - (old == null ? 0 : old) + size + st.groupBytes.get() > MAX_MAPPING_BYTES) {
            return "state and collected groups would exceed the limit of " + (MAX_MAPPING_BYTES / (1024 * 1024))
                    + " MB per mapping";
        }
        return null;
    }

    /**
     * Checks, before a function stores a value, whether the mapping has room for it. Advisory: concurrent
     * messages can fill the room meanwhile, so {@link #commitChanges} checks again.
     *
     * @return why the value does not fit, or {@code null} if it does
     */
    public String stateLimitReason(String tenant, String mappingIdentifier, String key, Object value) {
        if (tenant == null || mappingIdentifier == null || key == null) {
            return null;
        }
        ConcurrentHashMap<String, Stats> tenantStats = stats.get(tenant);
        Stats st = tenantStats == null ? null : tenantStats.get(mappingIdentifier);
        return st == null ? null : limitReason(st, key, entryBytes(key, value));
    }

    /**
     * Adds {@code by} to the number stored under {@code key} (or stores {@code by} if there is none) in one atomic
     * step, so concurrent messages never lose an increment, which {@code getState}/{@code setState} cannot promise.
     *
     * @return the new value
     * @throws IllegalArgumentException   if the key holds something that is not a number, or the result is not finite
     * @throws StateLimitExceededException if the key is new and the mapping already holds the maximum number of keys
     */
    public Number increment(String tenant, String mappingIdentifier, String key, Number by) {
        ConcurrentHashMap<String, Object> mappingState = store
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mappingIdentifier, k -> new ConcurrentHashMap<>());
        Stats st = stats(tenant, mappingIdentifier);
        Number[] result = new Number[1];
        mappingState.compute(key, (k, existing) -> {
            if (existing == null) {
                if (st.keyBytes.size() >= MAX_STATE_KEYS) {
                    throw new StateLimitExceededException(
                            "state holds the maximum of " + MAX_STATE_KEYS + " keys per mapping");
                }
                result[0] = add(0, by);
            } else if (existing instanceof Number n) {
                result[0] = add(n, by);
            } else {
                throw new IllegalArgumentException("the state key '" + key + "' does not hold a number");
            }
            return result[0];
        });
        long size = entryBytes(key, result[0]);
        Long old = st.keyBytes.put(key, size);
        st.stateBytes.addAndGet(size - (old == null ? 0 : old));
        markSaved(tenant, mappingIdentifier);
        return result[0];
    }

    /** Integer addition stays an Integer or Long (a Double on overflow); anything else is a Double. */
    public static Number add(Number a, Number b) {
        boolean integral = (a instanceof Integer || a instanceof Long) && (b instanceof Integer || b instanceof Long);
        if (integral) {
            try {
                long sum = Math.addExact(a.longValue(), b.longValue());
                return sum >= Integer.MIN_VALUE && sum <= Integer.MAX_VALUE ? (Number) (int) sum : (Number) sum;
            } catch (ArithmeticException overflow) {
                // fall through to double
            }
        }
        double sum = a.doubleValue() + b.doubleValue();
        if (!Double.isFinite(sum)) {
            throw new IllegalArgumentException("the result is not a finite number");
        }
        return sum;
    }

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
        if (!snapshot.isEmpty()) {
            // State that is still being read is in use: it must not expire because it was written once.
            markSaved(tenant, mappingIdentifier);
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
        /** The most items a group may be told to wait for. */
        public static final int MAX_EXPECTED_COUNT = 10_000;
        /** The longest {@code maxAgeSeconds} accepted: one day. */
        public static final int MAX_AGE_SECONDS = 86_400;

        public static CollectOptions count(int count) {
            return new CollectOptions(count, null, false, null);
        }

        /**
         * Checks the options the way {@code context.collect} of a Smart Function does.
         *
         * @return what is wrong with them, or {@code null} if they are valid
         */
        public String problem() {
            if (count != null && (count < 1 || count > MAX_EXPECTED_COUNT)) {
                return "count must be a whole number from 1 to " + MAX_EXPECTED_COUNT;
            }
            if (keys != null) {
                if (keys.isEmpty() || keys.size() > MAX_EXPECTED_COUNT) {
                    return "keys must be a list of 1 to " + MAX_EXPECTED_COUNT + " item keys";
                }
                if (keys.stream().anyMatch(java.util.Objects::isNull)) {
                    return "keys must be strings";
                }
            }
            if (maxAgeSeconds != null && (maxAgeSeconds < 1 || maxAgeSeconds > MAX_AGE_SECONDS)) {
                return "maxAgeSeconds must be a whole number from 1 to " + MAX_AGE_SECONDS;
            }
            if (count == null && keys == null && !last) {
                return "give count, keys or last, otherwise the group never completes";
            }
            return null;
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
     * retention expiry; {@code clearState()} leaves groups alone), and a mapping keeps at most {@value #MAX_OPEN_GROUPS} incomplete
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
        Stats st = stats(tenant, mappingIdentifier);
        String key = GROUP_PREFIX + groupKey;
        long now = clock.millis();
        Long maxAgeMillis = options.maxAgeSeconds() == null ? null : options.maxAgeSeconds() * 1000L;
        long itemBytes = 16L + 2L * itemKey.length() + estimateBytes(value);
        Map<String, Object>[] completed = new Map[1];
        String[] refused = new String[1];
        mappingState.compute(key, (k, existing) -> {
            Group group = existing instanceof Group g ? g : null;
            if (group != null && maxAgeMillis != null && now - group.created > maxAgeMillis) {
                release(st, group); // the earlier values are stale: start over
                group = null;
            }
            boolean counted = group != null; // already part of the mapping's counters
            if (group == null) {
                group = new Group(now, groupSequence.incrementAndGet());
            }
            if (maxAgeMillis != null) {
                group.maxAgeMillis = maxAgeMillis;
            }
            Object previous = group.items.put(itemKey, value);
            boolean complete = options.last()
                    || (options.count() != null && group.items.size() >= options.count())
                    || (options.keys() != null && group.items.keySet().containsAll(options.keys()));
            if (complete) {
                completed[0] = group.items;
                if (counted) {
                    release(st, group);
                }
                return null;
            }
            long delta = itemBytes - (previous == null ? 0 : 16L + 2L * itemKey.length() + estimateBytes(previous));
            if (group.items.size() > MAX_GROUP_ITEMS) {
                refused[0] = "a collected group holds at most " + MAX_GROUP_ITEMS + " items";
            } else if (st.groupBytes.get() + st.stateBytes.get() + delta > MAX_MAPPING_BYTES) {
                refused[0] = "state and collected groups would exceed the limit of "
                        + (MAX_MAPPING_BYTES / (1024 * 1024)) + " MB per mapping";
            }
            if (refused[0] != null) {
                if (previous == null) {
                    group.items.remove(itemKey);
                } else {
                    group.items.put(itemKey, previous);
                }
                return counted ? group : null;
            }
            group.bytes += delta;
            st.groupBytes.addAndGet(delta);
            if (!counted) {
                st.openGroups.incrementAndGet();
            }
            return group;
        });
        if (refused[0] != null) {
            throw new StateLimitExceededException(refused[0]);
        }
        markSaved(tenant, mappingIdentifier);
        if (maxAgeMillis != null) {
            // Each group is judged by the age limit it was collected with, never by this call's: a group of
            // another kind (no limit, or a longer one) must not be dropped because this call has a short one.
            for (Map.Entry<String, Object> e : mappingState.entrySet()) {
                if (e.getValue() instanceof Group g && g.maxAgeMillis != null && now - g.created > g.maxAgeMillis
                        && mappingState.remove(e.getKey(), g)) {
                    release(st, g);
                }
            }
        }
        if (completed[0] == null) {
            evictOldestGroups(mappingState, st);
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
                release(stats(tenant, mappingIdentifier), g);
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

    private static void removeKey(ConcurrentHashMap<String, Object> mappingState, Stats st, String key) {
        mappingState.remove(key);
        Long old = st.keyBytes.remove(key);
        if (old != null) {
            st.stateBytes.addAndGet(-old);
        }
    }

    /** Removes the getState/setState entries; collected groups are not state and survive {@code clearState()}. */
    private static void clearPlainState(ConcurrentHashMap<String, Object> mappingState) {
        mappingState.entrySet().removeIf(e -> !(e.getValue() instanceof Group));
    }

    private static void evictOldestGroups(ConcurrentHashMap<String, Object> mappingState, Stats st) {
        // The counter makes the common case free: nothing is scanned until the cap is actually exceeded.
        while (st.openGroups.get() > MAX_OPEN_GROUPS) {
            String oldest = null;
            long oldestSequence = Long.MAX_VALUE;
            for (Map.Entry<String, Object> entry : mappingState.entrySet()) {
                if (entry.getValue() instanceof Group g && g.sequence < oldestSequence) {
                    oldestSequence = g.sequence;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            if (mappingState.remove(oldest) instanceof Group removed) {
                release(st, removed);
            }
        }
    }

    private static final class Group {
        final long created;
        final long sequence; // opening order: clock milliseconds tie within a burst
        volatile Long maxAgeMillis; // the age limit of the latest call that carried one, or null
        long bytes; // estimated size of the items; changed only inside the compute of this group's key
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
        clearPlainState(mappingState);
        mappingState.putAll(state);
        Stats st = stats(tenant, mappingIdentifier);
        st.keyBytes.clear();
        long total = 0;
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            long size = entryBytes(entry.getKey(), entry.getValue());
            st.keyBytes.put(entry.getKey(), size);
            total += size;
        }
        st.stateBytes.set(total);
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
        Stats st = stats(tenant, mappingIdentifier);
        if (wipe) {
            clearPlainState(mappingState);
            st.keyBytes.clear();
            st.stateBytes.set(0);
        }
        for (Map.Entry<String, Object> entry : upserts.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() == null) {
                removeKey(mappingState, st, key);
                continue;
            }
            long size = entryBytes(key, entry.getValue());
            String reason = limitReason(st, key, size);
            if (reason != null) {
                // The function was told when it stored the value, if the room was gone then; this is the backstop.
                log.warn("{} - Not stored in flow state of mapping {}, key '{}': {}", tenant, mappingIdentifier, key,
                        reason);
                continue;
            }
            mappingState.put(key, entry.getValue());
            Long old = st.keyBytes.put(key, size);
            st.stateBytes.addAndGet(size - (old == null ? 0 : old));
        }
        removals.forEach(key -> removeKey(mappingState, st, key));
        markSaved(tenant, mappingIdentifier);
        log.debug("{} - Committed flow state changes for mapping {}: {} written, {} removed, wipe={}",
                tenant, mappingIdentifier, upserts.size(), removals.size(), wipe);
    }

    /**
     * Removes the state of every mapping of the tenant that has not written its state within the given
     * retention window. Each mapping is judged by its own last write or load of non-empty state. Called by
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
                dropStats(tenant, mappingIdentifier);
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
        dropStats(tenant, mappingIdentifier);
    }

    private void dropStats(String tenant, String mappingIdentifier) {
        ConcurrentHashMap<String, Stats> tenantStats = stats.get(tenant);
        if (tenantStats != null) {
            tenantStats.remove(mappingIdentifier);
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
        stats.remove(tenant);
        log.debug("{} - Cleared all flow state", tenant);
    }
}
