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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.cumulocity.model.ID;

import dynamic.mapper.configuration.ServiceConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tenant isolation and cleanup for {@link TenantRegistry}.
 *
 * <p>The registry is a singleton shared by every subscribed tenant, so two properties have to
 * hold: one tenant must never observe another's entries, and an unsubscribed tenant's entries
 * must actually be reclaimed. The second one is easy to get wrong silently — the maps are keyed
 * {@code "tenant|…"}, so a missing cleanup does not break anything visibly, it just grows the
 * heap and lets stale device ids survive into a later re-subscription.
 */
class TenantRegistryIsolationTest {

    private static final String TENANT_A = "t1000";
    private static final String TENANT_B = "t2000";

    private TenantRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
    }

    private static String cacheKey(String tenant, String type, String value) {
        return tenant + "|" + type + "|" + value;
    }

    @Test
    @DisplayName("the same external ID in two tenants resolves to each tenant's own device")
    void externalIdsDoNotCollideAcrossTenants() {
        // Device serials are chosen by customers — "sensor-1" very plausibly exists in both.
        registry.cacheExternalId(cacheKey(TENANT_A, "c8y_Serial", "sensor-1"), "device-A");
        registry.cacheExternalId(cacheKey(TENANT_B, "c8y_Serial", "sensor-1"), "device-B");

        assertEquals("device-A", registry.getCachedExternalId(cacheKey(TENANT_A, "c8y_Serial", "sensor-1")));
        assertEquals("device-B", registry.getCachedExternalId(cacheKey(TENANT_B, "c8y_Serial", "sensor-1")));
    }

    @Test
    @DisplayName("evicting one tenant's entry leaves the other tenant's untouched")
    void singleEvictionIsTenantScoped() {
        registry.cacheExternalId(cacheKey(TENANT_A, "c8y_Serial", "sensor-1"), "device-A");
        registry.cacheExternalId(cacheKey(TENANT_B, "c8y_Serial", "sensor-1"), "device-B");

        registry.removeFromExternalIdCache(TENANT_A, new ID("c8y_Serial", "sensor-1"));

        assertNull(registry.getCachedExternalId(cacheKey(TENANT_A, "c8y_Serial", "sensor-1")));
        assertEquals("device-B", registry.getCachedExternalId(cacheKey(TENANT_B, "c8y_Serial", "sensor-1")));
    }

    @Test
    @DisplayName("reverse eviction by internal id is tenant scoped")
    void reverseEvictionIsTenantScoped() {
        // Cumulocity managed-object ids are per tenant, so the same id string can be live in both.
        registry.cacheExternalId(cacheKey(TENANT_A, "c8y_Serial", "a"), "12345");
        registry.cacheExternalId(cacheKey(TENANT_B, "c8y_Serial", "b"), "12345");

        registry.removeFromExternalIdCacheByInternalId(TENANT_A, "12345");

        assertNull(registry.getCachedExternalId(cacheKey(TENANT_A, "c8y_Serial", "a")));
        assertEquals("12345", registry.getCachedExternalId(cacheKey(TENANT_B, "c8y_Serial", "b")),
                "tenant B's device must still resolve");
    }

    @Test
    @DisplayName("clearing a tenant removes its cache entries and leaves the other tenant intact")
    void clearIsTenantScoped() {
        registry.cacheExternalId(cacheKey(TENANT_A, "c8y_Serial", "a1"), "device-A1");
        registry.cacheExternalId(cacheKey(TENANT_A, "c8y_Serial", "a2"), "device-A2");
        registry.cacheExternalId(cacheKey(TENANT_B, "c8y_Serial", "b1"), "device-B1");

        registry.clearExternalIdCache(TENANT_A);

        assertNull(registry.getCachedExternalId(cacheKey(TENANT_A, "c8y_Serial", "a1")));
        assertNull(registry.getCachedExternalId(cacheKey(TENANT_A, "c8y_Serial", "a2")));
        assertEquals("device-B1", registry.getCachedExternalId(cacheKey(TENANT_B, "c8y_Serial", "b1")));
    }

    @Test
    @DisplayName("clearing a tenant also reclaims its per-ID locks")
    void clearReclaimsLocks() {
        String keyA = cacheKey(TENANT_A, "c8y_Serial", "a1");
        String keyB = cacheKey(TENANT_B, "c8y_Serial", "b1");
        Object lockA = registry.getOrCreateExternalIdLock(keyA);
        Object lockB = registry.getOrCreateExternalIdLock(keyB);

        registry.clearExternalIdCache(TENANT_A);

        // A fresh monitor for A proves the old one was dropped rather than accumulating forever;
        // B's must be the identical instance, otherwise an in-flight double-check lock would break.
        assertNotSame(lockA, registry.getOrCreateExternalIdLock(keyA),
                "the tenant's lock must be reclaimed, not leaked");
        assertSame(lockB, registry.getOrCreateExternalIdLock(keyB),
                "another tenant's lock must survive");
    }

    @Test
    @DisplayName("credentials, configuration and service representation are per tenant")
    void perTenantConfigurationIsIsolated() {
        ServiceConfiguration configA = new ServiceConfiguration();
        configA.setMaxCPUTimeMS(1_000);
        ServiceConfiguration configB = new ServiceConfiguration();
        configB.setMaxCPUTimeMS(9_000);

        registry.addServiceConfiguration(TENANT_A, configA);
        registry.addServiceConfiguration(TENANT_B, configB);

        assertEquals(1_000, registry.getServiceConfiguration(TENANT_A).getMaxCPUTimeMS());
        assertEquals(9_000, registry.getServiceConfiguration(TENANT_B).getMaxCPUTimeMS());

        registry.removeServiceConfiguration(TENANT_A);

        assertNull(registry.getServiceConfiguration(TENANT_A));
        assertEquals(9_000, registry.getServiceConfiguration(TENANT_B).getMaxCPUTimeMS());
    }
}
