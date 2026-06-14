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

    // Set after construction and read from the eviction callback on other
    // threads, hence volatile for visibility.
    private volatile Consumer<K> evictionListener;

    protected MetricLRUCache(int cacheSize, String tenant, String metricName) {
        // Making it thread-safe
        this.cache = Collections.synchronizedMap(new LinkedHashMap<K, V>() {
            // Removing oldest entries once the capacity is exceeded
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean shouldRemove = size() > cacheSize;
                if (shouldRemove) {
                    Consumer<K> listener = evictionListener;
                    if (listener != null) {
                        // Notify listener about eviction
                        listener.accept(eldest.getKey());
                    }
                }
                return shouldRemove;
            }
        });
        this.cacheSizeGauge = Gauge.builder(metricName, this.cache, Map::size)
                .tags(Tags.of("tenant", tenant))
                .register(Metrics.globalRegistry);
    }

    /**
     * Set a listener to be notified when entries are evicted. The listener runs
     * while the cache lock is held, so it must not perform long-running work.
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
