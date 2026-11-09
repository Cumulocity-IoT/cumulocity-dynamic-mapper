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

package dynamic.mapper.core;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.model.ID;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.model.MapperServiceRepresentation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry holding all per-tenant runtime state.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>GraalVM engine and compiled JS sources (one engine per tenant)</li>
 *   <li>Microservice credentials per tenant</li>
 *   <li>Active service configuration per tenant</li>
 *   <li>Mapper service managed-object reference per tenant</li>
 *   <li>Device-to-connector-client mapping cache per tenant</li>
 *   <li>External-ID resolution cache and per-ID creation locks</li>
 * </ul>
 *
 * <p>All state is keyed by tenant. Thread-safety at the map level is guaranteed
 * by {@link ConcurrentHashMap}. Individual values are not shared across tenants.
 *
 * <p>This class intentionally has no Spring service dependencies so that it can
 * be injected into low-level components without creating circular dependencies.
 * Methods that require C8Y API calls (e.g. device creation) remain in
 * {@link ServiceRegistry}, which calls back into this registry for
 * cache and lock access.
 */
@Slf4j
@Component
public class TenantRegistry implements IMapperConfiguration {

    // ─── Credentials & configuration ─────────────────────────────────────────

    // Structure: < Tenant, MicroserviceCredentials >
    private final Map<String, MicroserviceCredentials>     microserviceCredentials      = new ConcurrentHashMap<>();

    // Structure: < Tenant, ServiceConfiguration >
    private final Map<String, ServiceConfiguration>        serviceConfigurations        = new ConcurrentHashMap<>();

    // Structure: < Tenant, MapperServiceRepresentation >
    private final Map<String, MapperServiceRepresentation> mapperServiceRepresentations = new ConcurrentHashMap<>();

    // ─── External-ID resolution cache & per-ID creation locks ────────────────

    /** Upper bound of cached external-ID resolutions across all tenants (least recently used evicted). */
    private static final int EXTERNAL_ID_CACHE_MAX_ENTRIES = 100_000;

    // Number of striped creation locks; a fixed array, so locks never accumulate per external id.
    private static final int EXTERNAL_ID_LOCK_STRIPES = 256;

    // Guards externalIdCache and externalIdCacheReverse together (they must change atomically).
    private final Object externalIdMonitor = new Object();

    // Structure: < tenant|externalIdType|externalId, internalC8YId >, access-ordered and bounded
    private final Map<String, String> externalIdCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            if (size() > EXTERNAL_ID_CACHE_MAX_ENTRIES) {
                unindexReverse(eldest.getKey(), eldest.getValue());
                return true;
            }
            return false;
        }
    };

    // Structure: < tenant|internalC8YId, {tenant|externalIdType|externalId} > — reverse index
    // enabling eviction of stale externalIdCache entries when only the internal id (e.g. from a
    // deleted managed object) is known. One device can have several external ids, so multi-valued.
    private final Map<String, Set<String>> externalIdCacheReverse = new java.util.HashMap<>();

    // Striped monitors for double-check locking during implicit device creation. Striping instead
    // of one lock per key means a lock can never be removed while another thread holds it.
    private final Object[] externalIdLocks = newLocks(EXTERNAL_ID_LOCK_STRIPES);

    private static Object[] newLocks(int n) {
        Object[] locks = new Object[n];
        for (int i = 0; i < n; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    // =========================================================================
    // Credentials
    // =========================================================================

    public MicroserviceCredentials getMicroserviceCredential(String tenant) {
        return microserviceCredentials.get(tenant);
    }

    public void addMicroserviceCredentials(String tenant, MicroserviceCredentials credentials) {
        microserviceCredentials.put(tenant, credentials);
    }

    public void removeMicroserviceCredentials(String tenant) {
        microserviceCredentials.remove(tenant);
    }

    // =========================================================================
    // Service configuration
    // =========================================================================

    public ServiceConfiguration getServiceConfiguration(String tenant) {
        return serviceConfigurations.get(tenant);
    }

    public void addServiceConfiguration(String tenant, ServiceConfiguration configuration) {
        serviceConfigurations.put(tenant, configuration);
    }

    public void removeServiceConfiguration(String tenant) {
        serviceConfigurations.remove(tenant);
    }

    // =========================================================================
    // Mapper service managed-object reference
    // =========================================================================

    public void addMapperServiceRepresentation(String tenant, MapperServiceRepresentation repr) {
        mapperServiceRepresentations.put(tenant, repr);
    }

    @Override
    public MapperServiceRepresentation getMapperServiceRepresentation(String tenant) {
        return mapperServiceRepresentations.get(tenant);
    }

    public void removeMapperServiceRepresentation(String tenant) {
        mapperServiceRepresentations.remove(tenant);
    }

    // =========================================================================
    // External-ID resolution cache and per-ID creation locks
    // =========================================================================

    /**
     * Returns the lock object for the given cache key (one of a fixed set of striped monitors).
     * Callers use this for the double-check locking pattern when creating implicit devices.
     * Different keys may share a lock, which only serializes their creation needlessly.
     */
    public Object getOrCreateExternalIdLock(String cacheKey) {
        return externalIdLocks[Math.floorMod(cacheKey.hashCode(), externalIdLocks.length)];
    }

    public String getCachedExternalId(String cacheKey) {
        synchronized (externalIdMonitor) {
            return externalIdCache.get(cacheKey);
        }
    }

    public void cacheExternalId(String cacheKey, String internalId) {
        String tenant = cacheKey.substring(0, cacheKey.indexOf('|'));
        synchronized (externalIdMonitor) {
            String previous = externalIdCache.put(cacheKey, internalId);
            if (previous != null && !previous.equals(internalId)) {
                unindexReverse(cacheKey, previous);
            }
            externalIdCacheReverse.computeIfAbsent(tenant + "|" + internalId, k -> new HashSet<>()).add(cacheKey);
        }
    }

    /** Caller holds {@link #externalIdMonitor} (or is inside a cache mutation under it). */
    private void unindexReverse(String cacheKey, String internalId) {
        String tenant = cacheKey.substring(0, cacheKey.indexOf('|'));
        String reverseKey = tenant + "|" + internalId;
        Set<String> keys = externalIdCacheReverse.get(reverseKey);
        if (keys != null) {
            keys.remove(cacheKey);
            if (keys.isEmpty()) {
                externalIdCacheReverse.remove(reverseKey);
            }
        }
    }

    /**
     * Removes a single entry from the external-ID cache.
     * Used after a 422 response to force re-resolution on the next message.
     */
    public void removeFromExternalIdCache(String tenant, ID identity) {
        String cacheKey = tenant + "|" + identity.getType() + "|" + identity.getValue();
        synchronized (externalIdMonitor) {
            String internalId = externalIdCache.remove(cacheKey);
            if (internalId != null) {
                unindexReverse(cacheKey, internalId);
            }
        }
        log.debug("{} - Removed external ID from cache: {}", tenant, cacheKey);
    }

    /**
     * Removes every external-ID cache entry that resolves to {@code internalId}.
     * Used when a cached device id is discovered to no longer exist upstream (e.g. the managed
     * object was deleted from inventory) so the next message for the same external ID
     * re-triggers resolution instead of indefinitely reusing the stale, deleted id.
     */
    public void removeFromExternalIdCacheByInternalId(String tenant, String internalId) {
        if (internalId == null) {
            return;
        }
        synchronized (externalIdMonitor) {
            Set<String> cacheKeys = externalIdCacheReverse.remove(tenant + "|" + internalId);
            if (cacheKeys != null) {
                cacheKeys.forEach(externalIdCache::remove);
                log.debug("{} - Removed stale external ID cache entries for deleted device {}: {}",
                        tenant, internalId, cacheKeys);
            }
        }
    }

    /**
     * Clears the external-ID cache. If {@code tenant} is non-null, only entries
     * for that tenant are removed (prefix match). If null, the entire cache is
     * cleared.
     */
    public void clearExternalIdCache(String tenant) {
        synchronized (externalIdMonitor) {
            if (tenant == null) {
                externalIdCache.clear();
                externalIdCacheReverse.clear();
                log.info("Cleared entire external ID cache");
            } else {
                String prefix = tenant + "|";
                externalIdCache.keySet().removeIf(key -> key.startsWith(prefix));
                externalIdCacheReverse.keySet().removeIf(key -> key.startsWith(prefix));
                log.debug("{} - Cleared external ID cache", tenant);
            }
        }
    }

}
