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

package dynamic.mapping.core.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class InventoryCache {

	//private final LRUMap<ID, ExternalIDRepresentation> cache;
	private final Map<String, Map<String,Object>> cache;

	private Gauge cacheSizeGauge = null;

	// Constructor with default cache size
	public InventoryCache(String tenant) {
		this(1000, tenant); // Default size of 1000
	}

	// Constructor with custom cache size
	public InventoryCache(int cacheSize,  String tenant) {
		//Making it thread-safe
		this.cache = Collections.synchronizedMap(new LinkedHashMap<String, Map<String,Object>>() {
			//Removing oldest entries
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Map<String,Object>> eldest) {
				return size() > cacheSize;
			}
		});
		Tags tag = Tags.of("tenant", tenant);
		this.cacheSizeGauge = Gauge.builder("dynmapper_inbound_identity_cache_size", this.cache, Map::size)
				.tags(tag)
				.register(Metrics.globalRegistry);
	}

	public Gauge getCacheSizeGauge() {
		return cacheSizeGauge;
	}

	// Method to get mo by source id
	public Map<String,Object> getMOBySource(String key) {
		return cache.get(key);
	}

	// Method to put a new entry in the cache
	public void putMOforSource(String sourceId, Map<String,Object> mo) {
		cache.put(sourceId, mo);
	}

	// Method to remove an entry from the cache
	public void removeMOforSource(String sourceId) {
		cache.remove(sourceId);
	}

	// Method to clear the entire cache
	public void clearCache() {
		cache.clear();
	}

	// Method to get the current size of the cache
	public int getCacheSize() {
		return cache.size();
	}
}