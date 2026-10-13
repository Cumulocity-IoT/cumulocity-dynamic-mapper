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

import java.time.Duration;

import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

/**
 * Per-tenant LRU cache resolving a Cumulocity internal source id (plus external id
 * type) to its {@link ExternalIDRepresentation} — the reverse direction of
 * {@link InboundExternalIdCache}. Used by outbound mapping enrichment
 * ({@code C8YAgent#resolveGlobalId2ExternalId}) so mappings with {@code useExternalId}
 * enabled don't hit the Cumulocity Identity API on every single outbound message.
 *
 * <p>Entries expire {@code ttl} after being written, see {@link InboundExternalIdCache}.
 */
public class OutboundExternalIdCache
        extends MetricLRUCache<OutboundIdKey, ExpiringEntry<ExternalIDRepresentation>> {

    private static final String METRIC_NAME = "dynmapper_outbound_identity_cache_size";

    private final Duration ttl;

    public OutboundExternalIdCache(String tenant) {
        this(1000, tenant); // Default size of 1000
    }

    public OutboundExternalIdCache(int cacheSize, String tenant) {
        this(cacheSize, tenant, ExpiringEntry.DEFAULT_TTL);
    }

    public OutboundExternalIdCache(int cacheSize, String tenant, Duration ttl) {
        super(cacheSize, tenant, METRIC_NAME);
        this.ttl = ttl;
    }

    // Method to get the external id representation for a source id + id type
    public ExternalIDRepresentation getExternalIdForSource(OutboundIdKey key) {
        ExpiringEntry<ExternalIDRepresentation> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    // Method to put a new entry in the cache
    public void putExternalIdForSource(OutboundIdKey key, ExternalIDRepresentation externalId) {
        putEntry(key, ExpiringEntry.of(externalId, ttl));
    }

    // Method to remove an entry from the cache
    public void removeExternalIdForSource(OutboundIdKey key) {
        cache.remove(key);
    }

    /** Removes all entries for the given source id, whatever the external id type. */
    public void removeBySourceId(String sourceId) {
        if (sourceId == null) {
            return;
        }
        synchronized (cache) {
            cache.keySet().removeIf(key -> sourceId.equals(key.sourceId()));
        }
    }
}
