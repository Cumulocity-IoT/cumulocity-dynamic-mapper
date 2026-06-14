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

import java.util.Map;

/**
 * Per-tenant LRU cache holding managed-object fragments keyed by source id.
 *
 * <p>An eviction listener (see {@link #setEvictionListener}) is used to
 * unsubscribe from inventory notifications when an entry is evicted.
 */
public class InventoryCache extends MetricLRUCache<String, Map<String, Object>> {

    private static final String METRIC_NAME = "dynmapper_inbound_inventory_cache_size";

    public InventoryCache(int cacheSize, String tenant) {
        super(cacheSize, tenant, METRIC_NAME);
    }

    public void putMO(String sourceId, Map<String, Object> mo) {
        putEntry(sourceId, mo);
    }

    public Map<String, Object> getMOBySource(String key) {
        return cache.get(key);
    }

    public void removeMO(String sourceId) {
        cache.remove(sourceId);
    }
}
