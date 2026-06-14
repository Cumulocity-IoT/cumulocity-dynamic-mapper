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

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

/**
 * Per-tenant LRU cache resolving an external {@link ID} to its
 * {@link ExternalIDRepresentation} (and thereby the Cumulocity source id).
 */
public class InboundExternalIdCache extends MetricLRUCache<ID, ExternalIDRepresentation> {

    private static final String METRIC_NAME = "dynmapper_inbound_identity_cache_size";

    // Constructor with default cache size
    public InboundExternalIdCache(String tenant) {
        this(1000, tenant); // Default size of 1000
    }

    // Constructor with custom cache size
    public InboundExternalIdCache(int cacheSize, String tenant) {
        super(cacheSize, tenant, METRIC_NAME);
    }

    // Method to get ID by external ID
    public ExternalIDRepresentation getIdByExternalId(ID key) {
        return cache.get(key);
    }

    // Method to put a new entry in the cache
    public void putIdForExternalId(ID key, ExternalIDRepresentation id) {
        cache.put(key, id);
    }

    // Method to remove an entry from the cache
    public void removeIdForExternalId(ID key) {
        cache.remove(key);
    }
}
