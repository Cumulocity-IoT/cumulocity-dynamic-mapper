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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.configuration.ServiceConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.notification.NotificationSubscriber;

/**
 * Covers the eviction-on-delete fix from {@code attic/fix/inconsistant-cache/ISSUE.md}:
 * once a sourceId is confirmed to no longer resolve to a managed object in inventory,
 * any stale {@code TenantRegistry} external-ID cache entry pointing at it must be
 * dropped so the next message for that external ID re-resolves instead of repeating
 * the same failed subscription/lookup forever.
 */
class InventoryCacheEnrichmentServiceTest {

    private static final String TENANT = "t12345";
    private static final String SOURCE_ID = "87168024789";

    private CacheManager cacheManager;
    private TenantRegistry tenantRegistry;
    private NotificationSubscriber notificationSubscriber;
    private IdentityResolver identityResolver;
    private InventoryCacheEnrichmentService service;

    @BeforeEach
    void setUp() {
        notificationSubscriber = mock(NotificationSubscriber.class);
        when(notificationSubscriber.subscribeMOForInventoryCacheUpdates(anyString(), any()))
                .thenReturn(mock(Future.class));

        cacheManager = new CacheManager(mock(C8YAgent.class), notificationSubscriber);
        cacheManager.initializeInventoryCache(TENANT, 1000);

        tenantRegistry = new TenantRegistry();
        ServiceConfiguration serviceConfiguration = new ServiceConfiguration();
        tenantRegistry.addServiceConfiguration(TENANT, serviceConfiguration);

        identityResolver = mock(IdentityResolver.class);

        service = new InventoryCacheEnrichmentService(cacheManager, tenantRegistry, notificationSubscriber);

        // Simulate the device having been created earlier via the implicit-device path:
        // the external-ID cache already resolves "dmtest" to SOURCE_ID.
        tenantRegistry.cacheExternalId(TENANT + "|c8y_Serial|dmtest", SOURCE_ID);
    }

    @Test
    void getMOFromInventoryCache_deviceDeleted_evictsStaleExternalIdCacheEntry() {
        // The managed object no longer exists in inventory (deleted).
        when(identityResolver.getManagedObjectForId(TENANT, SOURCE_ID, false, false)).thenReturn(null);

        Map<String, Object> result = service.getMOFromInventoryCache(TENANT, SOURCE_ID, false, identityResolver);

        assertTrue(result.isEmpty());
        assertNull(tenantRegistry.getCachedExternalId(TENANT + "|c8y_Serial|dmtest"),
                "stale external-ID cache entry for the deleted device must be evicted");
    }

    @Test
    void getMOFromInventoryCache_deviceStillExists_leavesExternalIdCacheUntouched() {
        ManagedObjectRepresentation device = new ManagedObjectRepresentation();
        device.setName("my device");
        when(identityResolver.getManagedObjectForId(TENANT, SOURCE_ID, false, false)).thenReturn(device);

        service.getMOFromInventoryCache(TENANT, SOURCE_ID, false, identityResolver);

        assertEquals(SOURCE_ID, tenantRegistry.getCachedExternalId(TENANT + "|c8y_Serial|dmtest"));
    }

    @Test
    void updateMOInInventoryCache_deviceDeleted_evictsStaleExternalIdCacheEntry() {
        when(identityResolver.getManagedObjectForId(TENANT, SOURCE_ID, false, false)).thenReturn(null);

        service.updateMOInInventoryCache(TENANT, SOURCE_ID, new HashMap<>(), false, identityResolver);

        assertNull(tenantRegistry.getCachedExternalId(TENANT + "|c8y_Serial|dmtest"));
    }
}
