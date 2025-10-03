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

public class InventoryCache {

    private final Map<String, Map<String, Object>> cache;
    private Gauge cacheSizeGauge = null;
    
    // Listener for eviction events
    private Consumer<String> evictionListener;

    public InventoryCache(int cacheSize, String tenant) {
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                boolean shouldRemove = size() > cacheSize;
                if (shouldRemove && evictionListener != null) {
                    // Notify listener about eviction
                    evictionListener.accept(eldest.getKey());
                }
                return shouldRemove;
            }
        });
        
        Tags tag = Tags.of("tenant", tenant);
        this.cacheSizeGauge = Gauge.builder("dynmapper_inbound_inventory_cache_size", this.cache, Map::size)
                .tags(tag)
                .register(Metrics.globalRegistry);
    }

    /**
     * Set a listener to be notified when entries are evicted
     */
    public void setEvictionListener(Consumer<String> listener) {
        this.evictionListener = listener;
    }

    public void putMO(String sourceId, Map<String, Object> mo) {
        cache.put(sourceId, mo);
    }

    public Gauge getCacheSizeGauge() {
        return cacheSizeGauge;
    }

    public Map<String, Object> getMOBySource(String key) {
        return cache.get(key);
    }
    public void removeMO(String sourceId) {
        cache.remove(sourceId);
    }

    public void clearCache() {
        cache.clear();
    }

    public int getCacheSize() {
        return cache.size();
    }
}