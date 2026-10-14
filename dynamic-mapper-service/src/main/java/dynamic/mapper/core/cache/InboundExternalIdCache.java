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

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;

/**
 * Per-tenant LRU cache resolving an external {@link ID} to its
 * {@link ExternalIDRepresentation} (and thereby the Cumulocity source id).
 *
 * <p>Entries also expire {@code ttl} after being written: Notification 2.0 cannot report
 * external-ID changes (e.g. a replaced device, or an external ID removed on its own), so the
 * TTL bounds how long such a stale entry can survive.
 */
public class InboundExternalIdCache extends MetricLRUCache<ID, ExpiringEntry<ExternalIDRepresentation>> {

    private static final String METRIC_NAME = "dynmapper_inbound_identity_cache_size";

    private final Duration ttl;

    public InboundExternalIdCache(String tenant) {
        this(1000, tenant); // Default size of 1000
    }

    public InboundExternalIdCache(int cacheSize, String tenant) {
        this(cacheSize, tenant, ExpiringEntry.DEFAULT_TTL);
    }

    public InboundExternalIdCache(int cacheSize, String tenant, Duration ttl) {
        super(cacheSize, tenant, METRIC_NAME);
        this.ttl = ttl;
    }

    // Method to get ID by external ID; expired entries are dropped and reported as a miss
    public ExternalIDRepresentation getIdByExternalId(ID key) {
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
    public void putIdForExternalId(ID key, ExternalIDRepresentation id) {
        putEntry(key, ExpiringEntry.of(id, ttl));
    }

    // Method to remove an entry from the cache
    public void removeIdForExternalId(ID key) {
        cache.remove(key);
    }

    /**
     * Removes every entry resolving to the given Cumulocity source id (e.g. after the
     * managed object was deleted). The cache is keyed by external ID, so this scans.
     */
    public void removeBySourceId(String sourceId) {
        if (sourceId == null) {
            return;
        }
        synchronized (cache) {
            cache.values().removeIf(entry -> {
                ExternalIDRepresentation rep = entry.value();
                return rep != null && rep.getManagedObject() != null
                        && rep.getManagedObject().getId() != null
                        && sourceId.equals(rep.getManagedObject().getId().getValue());
            });
        }
    }
}
