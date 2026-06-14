/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.core.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Thread-safe, per-tenant LRU cache with a Micrometer size gauge.
 *
 * <p>Backed by a {@link Collections#synchronizedMap(Map) synchronized}
 * {@link LinkedHashMap} that evicts the eldest entry once {@code cacheSize} is
 * exceeded. A {@code <metricName>} gauge tagged with the tenant is registered on
 * the global registry on construction.
 *
 * <p>The gauge is bound to this instance's backing map. Because Micrometer
 * deduplicates meters by name+tags, a replacement cache for the same tenant
 * cannot register its own gauge while this one is still registered — so callers
 * that discard a cache (tenant unsubscribe, or recreate on resize) MUST call
 * {@link #close()} <em>before</em> constructing the replacement, otherwise the
 * gauge leaks and keeps reporting the size of the discarded map.
 */
public abstract class MetricLRUCache<K, V> implements AutoCloseable {

    protected final Map<K, V> cache;

    private final Gauge cacheSizeGauge;

    // Set after construction and read from putEntry on the writing thread,
    // hence volatile for visibility.
    private volatile Consumer<K> evictionListener;

    // Captures the key evicted by removeEldestEntry during a put, so the listener
    // can be invoked by putEntry AFTER the cache lock is released. Per-thread
    // because removeEldestEntry runs synchronously on the thread performing the
    // put, and LinkedHashMap evicts at most one entry per insertion. Only ever
    // set when an eviction listener is registered, and always cleared on read, so
    // it leaves no residue on pooled threads.
    private final ThreadLocal<K> pendingEviction = new ThreadLocal<>();

    protected MetricLRUCache(int cacheSize, String tenant, String metricName) {
        // Making it thread-safe
        this.cache = Collections.synchronizedMap(new LinkedHashMap<K, V>() {
            // Removing oldest entries once the capacity is exceeded
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean shouldRemove = size() > cacheSize;
                if (shouldRemove && evictionListener != null) {
                    // Defer notification until the cache lock is released (see
                    // putEntry); the listener may block on I/O and must not stall
                    // other callers contending for the synchronized map.
                    pendingEviction.set(eldest.getKey());
                }
                return shouldRemove;
            }
        });
        this.cacheSizeGauge = Gauge.builder(metricName, this.cache, Map::size)
                .tags(Tags.of("tenant", tenant))
                .register(Metrics.globalRegistry);
    }

    /**
     * Inserts an entry and dispatches any resulting eviction notification
     * <em>outside</em> the cache lock. Subclasses MUST route all writes through
     * this method so the eviction listener never runs while the synchronized
     * map's monitor is held.
     *
     * @return the previous value associated with {@code key}, or {@code null}
     */
    protected V putEntry(K key, V value) {
        // cache.put acquires and releases the synchronized-map monitor within this
        // call; removeEldestEntry may stash an evicted key into pendingEviction.
        V previous = cache.put(key, value);
        K evicted = pendingEviction.get();
        if (evicted != null) {
            // Clear before invoking the listener so a re-entrant put on this thread
            // starts clean.
            pendingEviction.remove();
            Consumer<K> listener = evictionListener;
            if (listener != null) {
                listener.accept(evicted);
            }
        }
        return previous;
    }

    /**
     * Set a listener to be notified when entries are evicted. The listener is
     * invoked outside the cache lock (see {@link #putEntry}), so it may perform
     * blocking work such as I/O without stalling other cache operations.
     */
    public void setEvictionListener(Consumer<K> listener) {
        this.evictionListener = listener;
    }

    public Gauge getCacheSizeGauge() {
        return cacheSizeGauge;
    }

    public void clearCache() {
        cache.clear();
    }

    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Deregisters the size gauge from the global meter registry. Call when this
     * cache is discarded (tenant unsubscribe or recreate) to avoid leaking the
     * meter. Idempotent.
     */
    @Override
    public void close() {
        Metrics.globalRegistry.remove(cacheSizeGauge);
    }
}
