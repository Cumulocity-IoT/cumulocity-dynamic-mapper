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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDCollectionRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.identity.ExternalIDCollection;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.core.cache.InboundExternalIdCache;
import dynamic.mapper.core.facade.IdentityFacade;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.core.mock.MockIdentity;
import dynamic.mapper.core.mock.MockInventory;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.processor.runtime.ProcessingContext;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MockIsolationRegressionTest {

    private static final String TEST_TENANT = "t12345";
    private static final String OTHER_TENANT = "t67890";
    private static final String EXTERNAL_ID_VAL = "device-serial-001";
    private static final String EXTERNAL_ID_TYPE = "c8y_Serial";
    private static final String REAL_INTERNAL_ID = "99999";

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @Mock
    private IdentityApi identityApi;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private C8YAgent c8yAgent;

    private TenantRegistry tenantRegistry;
    private IdentityResolutionService identityResolutionService;

    @BeforeEach
    void setUp() throws Exception {
        tenantRegistry = new TenantRegistry();
        identityResolutionService = new IdentityResolutionService(c8yAgent, tenantRegistry, new ObjectMapper());

        when(subscriptionsService.callForTenant(anyString(), any())).thenAnswer(inv -> {
            Callable<?> callable = inv.getArgument(1);
            return callable.call();
        });
    }

    @Test
    void testIdentityResolutionService_dryRun_doesNotReadRealCacheNorLeakRealDevice() throws Exception {
        // Given: Warm real production cache with device
        String cacheKey = TEST_TENANT + "|" + EXTERNAL_ID_TYPE + "|" + EXTERNAL_ID_VAL;
        tenantRegistry.cacheExternalId(cacheKey, REAL_INTERNAL_ID);

        // When testing=true, C8YAgent resolveExternalId2GlobalId returns null for mock
        ID identity = new ID(EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL);
        when(c8yAgent.resolveExternalId2GlobalId(eq(TEST_TENANT), eq(identity), eq(true)))
                .thenReturn(null);

        Mapping mapping = Mapping.builder()
                .createNonExistingDevice(false)
                .build();

        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .testing(true)
                .build();

        // When: getOrCreateDeviceThreadSafe is called in test mode
        String result = identityResolutionService.getOrCreateDeviceThreadSafe(
                TEST_TENANT, EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL, identity, context);

        // Then: Result must be null (not found), NOT the production cached ID "99999"
        assertNull(result, "Test mode must report not found against empty mock, not leak real cached device");

        // The real cache entry must remain untouched
        assertEquals(REAL_INTERNAL_ID, tenantRegistry.getCachedExternalId(cacheKey),
                "Production cache in TenantRegistry must remain intact");
    }

    @Test
    void testIdentityResolutionService_productionMode_readsRealCache() throws Exception {
        String cacheKey = TEST_TENANT + "|" + EXTERNAL_ID_TYPE + "|" + EXTERNAL_ID_VAL;
        tenantRegistry.cacheExternalId(cacheKey, REAL_INTERNAL_ID);

        ID identity = new ID(EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL);
        Mapping mapping = Mapping.builder()
                .createNonExistingDevice(false)
                .build();

        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(mapping)
                .testing(false)
                .build();

        String result = identityResolutionService.getOrCreateDeviceThreadSafe(
                TEST_TENANT, EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL, identity, context);

        assertEquals(REAL_INTERNAL_ID, result);
        verify(c8yAgent, never()).resolveExternalId2GlobalId(any(), any(), anyBoolean());
    }

    @Test
    void testC8YAgentResolveExternalId2GlobalId_testingTrue_skipsInboundCache() {
        InboundExternalIdCache inboundCache = mock(InboundExternalIdCache.class);
        when(cacheManager.getInboundExternalIdCache(TEST_TENANT)).thenReturn(inboundCache);

        ID identity = new ID(EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL);
        ExternalIDRepresentation realRep = new ExternalIDRepresentation();
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(new GId(REAL_INTERNAL_ID));
        realRep.setManagedObject(mor);

        when(inboundCache.getIdByExternalId(identity)).thenReturn(realRep);

        // Mock identityApi returning null (not found in mock)
        IdentityFacade identityFacade = mock(IdentityFacade.class);
        when(identityFacade.resolveExternalId2GlobalId(eq(identity), eq(true), any())).thenReturn(null);

        // Instantiate C8YAgent with minimal mocks or verify behaviour
        // We test C8YAgent directly
        C8YAgent agent = new C8YAgent(
                null, null, identityFacade, null, null, null, null, null,
                subscriptionsService, null, null, null, null,
                new ObjectMapper(), tenantRegistry, null, cacheManager, null, null, null, null,
                "1.0.0", 10);

        ExternalIDRepresentation result = agent.resolveExternalId2GlobalId(TEST_TENANT, identity, true);

        // Must be null (mock lookup), not the real cache hit!
        assertNull(result, "C8YAgent must not read InboundExternalIdCache when testing is true");
        verify(inboundCache, never()).getIdByExternalId(any());
        verify(inboundCache, never()).putIdForExternalId(any(), any());
    }

    @Test
    void testIdentityFacade_resolveGlobalId2ExternalId_nullTesting_doesNotThrowNPE() {
        IdentityFacade facade = new IdentityFacade(mock(MockIdentity.class), identityApi);
        GId gid = new GId("12345");
        Semaphore semaphore = new Semaphore(1);

        ExternalIDCollection collection = mock(ExternalIDCollection.class);
        when(identityApi.getExternalIdsOfGlobalId(gid)).thenReturn(collection);
        when(collection.get(any(Integer.class))).thenReturn(mock(com.cumulocity.sdk.client.identity.PagedExternalIDCollectionRepresentation.class));
        when(collection.get(any(Integer.class)).allPages()).thenReturn(java.util.Collections.emptyList());

        // Calling with testing = null must not throw NullPointerException
        assertDoesNotThrow(() -> facade.resolveGlobalId2ExternalId(gid, "c8y_Serial", null, semaphore));
    }

    @Test
    void testMockStores_perTenantClear() {
        MockIdentity mockIdentity = new MockIdentity(subscriptionsService);
        MockInventory mockInventory = new MockInventory(subscriptionsService);

        // Populate test tenant and other tenant
        when(subscriptionsService.getTenant()).thenReturn(TEST_TENANT);

        ManagedObjectRepresentation mo1 = new ManagedObjectRepresentation();
        mo1.setName("Device 1");
        ManagedObjectRepresentation created1 = mockInventory.create(mo1);
        assertNotNull(created1.getId());

        ID id1 = new ID("c8y_Serial", "serial-1");
        ExternalIDRepresentation ext1 = new ExternalIDRepresentation();
        ext1.setType(id1.getType());
        ext1.setExternalId(id1.getValue());
        ext1.setManagedObject(created1);
        mockIdentity.create(ext1);

        // Populate other tenant
        when(subscriptionsService.getTenant()).thenReturn(OTHER_TENANT);

        ManagedObjectRepresentation mo2 = new ManagedObjectRepresentation();
        mo2.setName("Device 2");
        ManagedObjectRepresentation created2 = mockInventory.create(mo2);

        ID id2 = new ID("c8y_Serial", "serial-2");
        ExternalIDRepresentation ext2 = new ExternalIDRepresentation();
        ext2.setType(id2.getType());
        ext2.setExternalId(id2.getValue());
        ext2.setManagedObject(created2);
        mockIdentity.create(ext2);

        // Clear TEST_TENANT
        mockIdentity.clear(TEST_TENANT);
        mockInventory.clear(TEST_TENANT);

        // Verify TEST_TENANT is cleared
        when(subscriptionsService.getTenant()).thenReturn(TEST_TENANT);
        assertNull(mockIdentity.getExternalId(id1), "External ID on TEST_TENANT must be cleared");
        assertNull(mockInventory.get(created1.getId()), "ManagedObject on TEST_TENANT must be cleared");

        // Verify OTHER_TENANT is untouched
        when(subscriptionsService.getTenant()).thenReturn(OTHER_TENANT);
        assertNotNull(mockIdentity.getExternalId(id2), "External ID on OTHER_TENANT must remain intact");
        assertNotNull(mockInventory.get(created2.getId()), "ManagedObject on OTHER_TENANT must remain intact");
    }
}
