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

package dynamic.mapper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cumulocity.model.ID;
import org.junit.jupiter.api.Test;

/**
 * Covers the external-ID resolution cache and its reverse index, which
 * exists specifically so a stale entry can be evicted once the internal id
 * it resolves to is discovered to no longer exist in inventory (see
 * {@code attic/fix/inconsistant-cache/ISSUE.md}).
 */
class TenantRegistryTest {

    private static final String TENANT = "t12345";

    @Test
    void removeFromExternalIdCacheByInternalId_evictsMatchingEntry() {
        TenantRegistry registry = new TenantRegistry();
        String cacheKey = TENANT + "|c8y_Serial|dev-1";
        registry.cacheExternalId(cacheKey, "1001");

        registry.removeFromExternalIdCacheByInternalId(TENANT, "1001");

        assertNull(registry.getCachedExternalId(cacheKey));
    }

    @Test
    void removeFromExternalIdCacheByInternalId_isNoOpForUnknownInternalId() {
        TenantRegistry registry = new TenantRegistry();
        String cacheKey = TENANT + "|c8y_Serial|dev-1";
        registry.cacheExternalId(cacheKey, "1001");

        registry.removeFromExternalIdCacheByInternalId(TENANT, "does-not-exist");

        assertEquals("1001", registry.getCachedExternalId(cacheKey));
    }

    @Test
    void removeFromExternalIdCacheByInternalId_doesNotCrossTenants() {
        TenantRegistry registry = new TenantRegistry();
        String otherTenant = "t99999";
        String cacheKey = TENANT + "|c8y_Serial|dev-1";
        registry.cacheExternalId(cacheKey, "1001");

        // Same internal id string, but scoped to a different tenant — must not evict.
        registry.removeFromExternalIdCacheByInternalId(otherTenant, "1001");

        assertEquals("1001", registry.getCachedExternalId(cacheKey));
    }

    @Test
    void removeFromExternalIdCache_alsoClearsReverseIndex() {
        TenantRegistry registry = new TenantRegistry();
        String cacheKey = TENANT + "|c8y_Serial|dev-1";
        registry.cacheExternalId(cacheKey, "1001");

        registry.removeFromExternalIdCache(TENANT, new ID("c8y_Serial", "dev-1"));

        // The reverse index must have been cleaned up too, otherwise a later re-use of the
        // same internal id for an unrelated external id would evict the wrong entry.
        registry.removeFromExternalIdCacheByInternalId(TENANT, "1001");
        assertNull(registry.getCachedExternalId(cacheKey));
    }

    @Test
    void cacheExternalId_overwritingSameKeyUpdatesReverseIndex() {
        TenantRegistry registry = new TenantRegistry();
        String cacheKey = TENANT + "|c8y_Serial|dev-1";
        registry.cacheExternalId(cacheKey, "1001");

        // Device was deleted and recreated with a new internal id under the same external id.
        registry.cacheExternalId(cacheKey, "2002");

        registry.removeFromExternalIdCacheByInternalId(TENANT, "2002");
        assertNull(registry.getCachedExternalId(cacheKey));
    }

    @Test
    void clearExternalIdCache_forTenant_clearsReverseIndexToo() {
        TenantRegistry registry = new TenantRegistry();
        String cacheKey = TENANT + "|c8y_Serial|dev-1";
        registry.cacheExternalId(cacheKey, "1001");

        registry.clearExternalIdCache(TENANT);

        assertNull(registry.getCachedExternalId(cacheKey));
        // Reverse index must be consistent: re-adding a fresh mapping to the same internal id
        // must not accidentally resolve leftover state from before the clear.
        String otherKey = TENANT + "|c8y_Serial|dev-2";
        registry.cacheExternalId(otherKey, "1001");
        registry.removeFromExternalIdCacheByInternalId(TENANT, "1001");
        assertNull(registry.getCachedExternalId(otherKey));
    }
}
