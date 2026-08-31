/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
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

package dynamic.mapper.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.CacheManager;
import dynamic.mapper.core.cache.InboundExternalIdCache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that tenant-level cache isolation is enforced by {@link CacheManager}:
 * entries stored for tenant A must not be visible to tenant B, and clearing
 * tenant A's cache must leave tenant B's cache intact.
 *
 * <p>No Spring context is required — {@link CacheManager} is instantiated
 * directly.</p>
 */
class MultiTenancyIsolationTest {

    private static final String TENANT_A = "tenantA";
    private static final String TENANT_B = "tenantB";
    private static final int    CACHE_SIZE = 100;

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new CacheManager(null, null);
        cacheManager.initializeInboundExternalIdCache(TENANT_A, CACHE_SIZE);
        cacheManager.initializeInboundExternalIdCache(TENANT_B, CACHE_SIZE);
    }

    // ── Isolation between tenants ─────────────────────────────────────────

    @Test
    void entryInTenantA_isNotVisibleToTenantB() {
        ID key = new ID("c8y_Serial", "device-001");
        ExternalIDRepresentation extId = buildExtId("c8y_Serial", "device-001", "100");

        cacheManager.getInboundExternalIdCache(TENANT_A).putIdForExternalId(key, extId);

        InboundExternalIdCache cacheB = cacheManager.getInboundExternalIdCache(TENANT_B);
        assertNull(cacheB.getIdByExternalId(key),
                "Tenant B must not see an entry stored for tenant A");
    }

    @Test
    void entryInTenantB_isNotVisibleToTenantA() {
        ID key = new ID("c8y_Serial", "device-002");
        ExternalIDRepresentation extId = buildExtId("c8y_Serial", "device-002", "200");

        cacheManager.getInboundExternalIdCache(TENANT_B).putIdForExternalId(key, extId);

        InboundExternalIdCache cacheA = cacheManager.getInboundExternalIdCache(TENANT_A);
        assertNull(cacheA.getIdByExternalId(key),
                "Tenant A must not see an entry stored for tenant B");
    }

    @Test
    void removingTenantA_cache_leavesTenantB_intact() {
        ID keyB = new ID("c8y_Serial", "device-003");
        ExternalIDRepresentation extIdB = buildExtId("c8y_Serial", "device-003", "300");
        cacheManager.getInboundExternalIdCache(TENANT_B).putIdForExternalId(keyB, extIdB);

        // Remove tenant A's cache entirely
        cacheManager.removeInboundExternalIdCache(TENANT_A);

        // Tenant A's cache should now be null
        assertNull(cacheManager.getInboundExternalIdCache(TENANT_A),
                "Tenant A's cache should be gone after removal");

        // Tenant B's cache and its entries must survive
        InboundExternalIdCache cacheB = cacheManager.getInboundExternalIdCache(TENANT_B);
        assertNotNull(cacheB, "Tenant B's cache must still exist after tenant A's removal");
        assertNotNull(cacheB.getIdByExternalId(keyB),
                "Tenant B's entry must survive tenant A's cache removal");
    }

    @Test
    void clearingTenantA_cache_doesNotAffectTenantB() {
        ID keyA = new ID("c8y_Serial", "device-004");
        ID keyB = new ID("c8y_Serial", "device-005");

        cacheManager.getInboundExternalIdCache(TENANT_A)
                .putIdForExternalId(keyA, buildExtId("c8y_Serial", "device-004", "400"));
        cacheManager.getInboundExternalIdCache(TENANT_B)
                .putIdForExternalId(keyB, buildExtId("c8y_Serial", "device-005", "500"));

        // Clear tenant A only
        cacheManager.getInboundExternalIdCache(TENANT_A).clearCache();

        // Tenant A entry is gone
        assertNull(cacheManager.getInboundExternalIdCache(TENANT_A).getIdByExternalId(keyA),
                "Tenant A entry must be cleared");

        // Tenant B entry is still there
        assertNotNull(cacheManager.getInboundExternalIdCache(TENANT_B).getIdByExternalId(keyB),
                "Tenant B entry must not be affected by clearing tenant A's cache");
    }

    // ── Cache size limits ─────────────────────────────────────────────────

    @Test
    void cacheSize_reflectsInsertedEntries() {
        InboundExternalIdCache cache = cacheManager.getInboundExternalIdCache(TENANT_A);
        assertEquals(0, cache.getCacheSize());

        cache.putIdForExternalId(new ID("c8y_Serial", "d1"), buildExtId("c8y_Serial", "d1", "1"));
        cache.putIdForExternalId(new ID("c8y_Serial", "d2"), buildExtId("c8y_Serial", "d2", "2"));
        assertEquals(2, cache.getCacheSize());
    }

    @Test
    void lruEviction_keepsMaxCacheSize() {
        int smallSize = 3;
        cacheManager.initializeInboundExternalIdCache("evictTenant", smallSize);
        InboundExternalIdCache cache = cacheManager.getInboundExternalIdCache("evictTenant");

        for (int i = 0; i < smallSize + 2; i++) {
            String name = "device-" + i;
            cache.putIdForExternalId(new ID("c8y_Serial", name), buildExtId("c8y_Serial", name, "" + i));
        }

        // LRU: size must not exceed smallSize
        assertTrue(cache.getCacheSize() <= smallSize,
                "Cache must evict eldest entries when full, actual size: " + cache.getCacheSize());
    }

    // ── Independent cache instances ───────────────────────────────────────

    @Test
    void multipleTenantsHaveIndependentCacheInstances() {
        InboundExternalIdCache cacheA = cacheManager.getInboundExternalIdCache(TENANT_A);
        InboundExternalIdCache cacheB = cacheManager.getInboundExternalIdCache(TENANT_B);

        assertNotSame(cacheA, cacheB,
                "Each tenant must have a distinct cache instance");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ExternalIDRepresentation buildExtId(String type, String value, String moId) {
        ExternalIDRepresentation rep = new ExternalIDRepresentation();
        rep.setExternalId(value);
        rep.setType(type);
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new com.cumulocity.model.idtype.GId(moId));
        rep.setManagedObject(mo);
        return rep;
    }
}
