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

import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

/**
 * Per-tenant LRU cache resolving a Cumulocity internal source id (plus external id
 * type) to its {@link ExternalIDRepresentation} — the reverse direction of
 * {@link InboundExternalIdCache}. Used by outbound mapping enrichment
 * ({@code C8YAgent#resolveGlobalId2ExternalId}) so mappings with {@code useExternalId}
 * enabled don't hit the Cumulocity Identity API on every single outbound message.
 */
public class OutboundExternalIdCache extends MetricLRUCache<OutboundIdKey, ExternalIDRepresentation> {

    private static final String METRIC_NAME = "dynmapper_outbound_identity_cache_size";

    // Constructor with default cache size
    public OutboundExternalIdCache(String tenant) {
        this(1000, tenant); // Default size of 1000
    }

    // Constructor with custom cache size
    public OutboundExternalIdCache(int cacheSize, String tenant) {
        super(cacheSize, tenant, METRIC_NAME);
    }

    // Method to get the external id representation for a source id + id type
    public ExternalIDRepresentation getExternalIdForSource(OutboundIdKey key) {
        return cache.get(key);
    }

    // Method to put a new entry in the cache
    public void putExternalIdForSource(OutboundIdKey key, ExternalIDRepresentation externalId) {
        putEntry(key, externalId);
    }

    // Method to remove an entry from the cache
    public void removeExternalIdForSource(OutboundIdKey key) {
        cache.remove(key);
    }
}
