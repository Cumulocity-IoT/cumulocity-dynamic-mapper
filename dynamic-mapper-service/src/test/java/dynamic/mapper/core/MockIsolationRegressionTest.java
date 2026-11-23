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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
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
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.identity.ExternalIDCollection;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.cache.InboundExternalIdCache;
import dynamic.mapper.core.cache.OutboundExternalIdCache;
import dynamic.mapper.core.cache.OutboundIdKey;
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
    void testIdentityResolutionService_inALiveTest_checksAnExistingDeviceIsATestDevice() throws Exception {
        ID identity = new ID(EXTERNAL_ID_TYPE, "d11r_liveTest_" + EXTERNAL_ID_VAL);
        ExternalIDRepresentation existing = new ExternalIDRepresentation();
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(new GId("900"));
        existing.setManagedObject(mor);
        when(c8yAgent.resolveExternalId2GlobalId(eq(TEST_TENANT), any(ID.class), eq(false))).thenReturn(existing);
        ProcessingContext<Object> live = ProcessingContext.<Object>builder().tenant(TEST_TENANT)
                .mapping(Mapping.builder().createNonExistingDevice(true).build())
                .testExternalIdPrefix("d11r_liveTest_").testing(false).build();

        String id = identityResolutionService.getOrCreateDeviceThreadSafe(
                TEST_TENANT, EXTERNAL_ID_TYPE, identity.getValue(), identity, live);
        assertEquals("900", id);
        verify(c8yAgent).assertLiveTestMayUse(TEST_TENANT, live, "900");

        // and a refusal stops the message instead of writing to the device
        org.mockito.Mockito.doThrow(new dynamic.mapper.processor.ProcessingException("Live Test refused"))
                .when(c8yAgent).assertLiveTestMayUse(TEST_TENANT, live, "900");
        org.junit.jupiter.api.Assertions.assertThrows(dynamic.mapper.processor.ProcessingException.class,
                () -> identityResolutionService.getOrCreateDeviceThreadSafe(
                        TEST_TENANT, EXTERNAL_ID_TYPE, identity.getValue(), identity, live));
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
                new ObjectMapper(), tenantRegistry, new DeviceToClientRegistry(), null, cacheManager, null, null, null, null,
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

    // ── Outbound identity cache ───────────────────────────────────────────

    private C8YAgent agentWith(IdentityFacade identityFacade, CacheManager cm) {
        return new C8YAgent(
                null, null, identityFacade, null, null, null, null, null,
                subscriptionsService, null, null, null, null,
                new ObjectMapper(), tenantRegistry, new DeviceToClientRegistry(), null, cm, null, null, null, null,
                "1.0.0", 10);
    }

    private ExternalIDRepresentation liveExternalId(String internalId) {
        ExternalIDRepresentation rep = new ExternalIDRepresentation();
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(new GId(internalId));
        rep.setManagedObject(mor);
        rep.setType(EXTERNAL_ID_TYPE);
        rep.setExternalId(EXTERNAL_ID_VAL);
        return rep;
    }

    @Test
    void testC8YAgentResolveGlobalId2ExternalId_testingTrue_skipsOutboundCache() {
        OutboundExternalIdCache outboundCache = mock(OutboundExternalIdCache.class);
        when(cacheManager.getOutboundExternalIdCache(TEST_TENANT)).thenReturn(outboundCache);
        when(outboundCache.getExternalIdForSource(any())).thenReturn(liveExternalId(REAL_INTERNAL_ID));

        IdentityFacade identityFacade = mock(IdentityFacade.class);
        when(identityFacade.resolveGlobalId2ExternalId(any(), any(), eq(true), any())).thenReturn(null);

        ExternalIDRepresentation result = agentWith(identityFacade, cacheManager)
                .resolveGlobalId2ExternalId(TEST_TENANT, new GId(REAL_INTERNAL_ID), EXTERNAL_ID_TYPE, true);

        assertNull(result, "a dry run must not be served from the live outbound cache");
        verify(outboundCache, never()).getExternalIdForSource(any());
        verify(outboundCache, never()).putExternalIdForSource(any(), any());
    }

    @Test
    void testC8YAgentResolveGlobalId2ExternalId_production_stillUsesOutboundCache() {
        OutboundExternalIdCache outboundCache = mock(OutboundExternalIdCache.class);
        when(cacheManager.getOutboundExternalIdCache(TEST_TENANT)).thenReturn(outboundCache);
        ExternalIDRepresentation cached = liveExternalId(REAL_INTERNAL_ID);
        when(outboundCache.getExternalIdForSource(any())).thenReturn(cached);

        ExternalIDRepresentation result = agentWith(mock(IdentityFacade.class), cacheManager)
                .resolveGlobalId2ExternalId(TEST_TENANT, new GId(REAL_INTERNAL_ID), EXTERNAL_ID_TYPE, false);

        assertSame(cached, result);
    }

    // ── End to end: dry run with warmed live caches ───────────────────────

    @Test
    void testDryRun_withWarmedLiveCaches_isDecidedByMockAndLeavesLiveCachesUntouched() throws Exception {
        when(subscriptionsService.getTenant()).thenReturn(TEST_TENANT);

        // Real caches and real mocks; only the platform edges are faked.
        org.springframework.context.ApplicationEventPublisher eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        CacheManager realCacheManager = new CacheManager(eventPublisher);
        realCacheManager.initializeInboundExternalIdCache(TEST_TENANT, 100);
        realCacheManager.initializeOutboundExternalIdCache(TEST_TENANT, 100);
        realCacheManager.initializeInventoryCache(TEST_TENANT, 100);
        tenantRegistry.addServiceConfiguration(TEST_TENANT, new ServiceConfiguration());

        MockIdentity mockIdentity = new MockIdentity(subscriptionsService);
        MockInventory mockInventory = new MockInventory(subscriptionsService);
        IdentityFacade identityFacade = new IdentityFacade(mockIdentity, identityApi);
        InventoryFacade inventoryFacade = new InventoryFacade(mockInventory,
                mock(com.cumulocity.sdk.client.inventory.InventoryApi.class),
                mock(com.cumulocity.sdk.client.Platform.class));
        C8YAgent agent = agentWith(identityFacade, realCacheManager);

        // Warm every live cache with device REAL_INTERNAL_ID.
        ID identity = new ID(EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL);
        ExternalIDRepresentation liveInbound = liveExternalId(REAL_INTERNAL_ID);
        ExternalIDRepresentation liveOutbound = liveExternalId(REAL_INTERNAL_ID);
        OutboundIdKey outboundKey = new OutboundIdKey(REAL_INTERNAL_ID, EXTERNAL_ID_TYPE);
        Map<String, Object> liveMO = Map.of("name", "real device", "type", "real_type");
        String cacheKey = TEST_TENANT + "|" + EXTERNAL_ID_TYPE + "|" + EXTERNAL_ID_VAL;
        realCacheManager.getInboundExternalIdCache(TEST_TENANT).putIdForExternalId(identity, liveInbound);
        realCacheManager.getOutboundExternalIdCache(TEST_TENANT).putExternalIdForSource(outboundKey, liveOutbound);
        realCacheManager.getInventoryCache(TEST_TENANT).putMO(REAL_INTERNAL_ID, liveMO);
        tenantRegistry.cacheExternalId(cacheKey, REAL_INTERNAL_ID);

        // The resolver the enrichment service uses: the agent for identity, the mock for inventory.
        IdentityResolver resolver = mock(IdentityResolver.class);
        when(resolver.resolveExternalId2GlobalId(any(), any(), any())).thenAnswer(
                inv -> agent.resolveExternalId2GlobalId(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(resolver.getManagedObjectForId(any(), any(), any(), anyBoolean())).thenAnswer(
                inv -> inventoryFacade.get(GId.asGId((String) inv.getArgument(1)), inv.getArgument(2),
                        inv.getArgument(3)));
        InventoryCacheEnrichmentService enrichment = new InventoryCacheEnrichmentService(
                realCacheManager, tenantRegistry, eventPublisher);
        IdentityResolutionService resolution = new IdentityResolutionService(agent, tenantRegistry,
                new ObjectMapper());

        ProcessingContext<Object> dryRun = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT)
                .mapping(Mapping.builder().createNonExistingDevice(false).build())
                .testing(true)
                .build();

        // When: a dry run asks every question that could be answered from a live cache
        assertNull(resolution.getOrCreateDeviceThreadSafe(
                TEST_TENANT, EXTERNAL_ID_TYPE, EXTERNAL_ID_VAL, identity, dryRun),
                "createNonExistingDevice=false + empty mock => not found, although the live device exists");
        assertNull(agent.resolveExternalId2GlobalId(TEST_TENANT, identity, true));
        assertNull(agent.resolveGlobalId2ExternalId(TEST_TENANT, new GId(REAL_INTERNAL_ID), EXTERNAL_ID_TYPE, true));
        assertNull(enrichment.getMOFromInventoryCacheByExternalId(TEST_TENANT,
                new dynamic.mapper.processor.model.ExternalId(EXTERNAL_ID_VAL, EXTERNAL_ID_TYPE), true, resolver));
        Map<String, Object> dryRunMO = enrichment.getMOFromInventoryCache(TEST_TENANT, REAL_INTERNAL_ID, true, resolver);
        assertTrue(dryRunMO.isEmpty(), "a dry run must not see the live managed object's fragments");

        // Then: every live cache is exactly as it was, and no real subscription was opened
        assertSame(liveInbound, realCacheManager.getInboundExternalIdCache(TEST_TENANT).getIdByExternalId(identity));
        assertSame(liveOutbound,
                realCacheManager.getOutboundExternalIdCache(TEST_TENANT).getExternalIdForSource(outboundKey));
        assertSame(liveMO, realCacheManager.getInventoryCache(TEST_TENANT).getMOBySource(REAL_INTERNAL_ID));
        assertEquals(REAL_INTERNAL_ID, tenantRegistry.getCachedExternalId(cacheKey));
        verifyNoInteractions(eventPublisher);

        // Control: the same question in production mode IS answered from the live cache,
        // proving the caches above really were warm.
        assertSame(liveInbound, agent.resolveExternalId2GlobalId(TEST_TENANT, identity, false));
        assertSame(liveMO, enrichment.getMOFromInventoryCache(TEST_TENANT, REAL_INTERNAL_ID, false, resolver));
    }

    // ── Test device ───────────────────────────────────────────────────────

    private InventoryFacade inventoryFacade;
    private IdentityFacade identityFacade;

    /** A C8YAgent over mocked facades; {@code externalIdBinding} selects the atomic creation path. */
    private C8YAgent newAgent(boolean externalIdBinding) throws Exception {
        inventoryFacade = mock(InventoryFacade.class);
        identityFacade = mock(IdentityFacade.class);
        com.cumulocity.microservice.context.ContextService<com.cumulocity.microservice.context.credentials.MicroserviceCredentials> contextService =
                mock(com.cumulocity.microservice.context.ContextService.class);
        when(contextService.getContext()).thenReturn(
                new com.cumulocity.microservice.context.credentials.MicroserviceCredentials(TEST_TENANT, "u", "p", null, null, null, null));
        when(contextService.callWithinContext(any(), any())).thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        when(inventoryFacade.create(any(ManagedObjectRepresentation.class), eq(false))).thenAnswer(inv -> {
            ManagedObjectRepresentation created = inv.getArgument(0);
            created.setId(new GId("4711"));
            return created;
        });
        when(inventoryFacade.update(any(ManagedObjectRepresentation.class), eq(false))).thenAnswer(inv -> inv.getArgument(0));
        when(cacheManager.getInboundExternalIdCache(TEST_TENANT)).thenReturn(mock(InboundExternalIdCache.class));
        ServiceConfiguration configuration = new ServiceConfiguration();
        configuration.setExternalIdBinding(externalIdBinding);
        tenantRegistry.addServiceConfiguration(TEST_TENANT, configuration);
        return new C8YAgent(
                null, inventoryFacade, identityFacade, null, null, null, null, null,
                subscriptionsService, contextService, null, null, null,
                managedObjectMapper(), tenantRegistry, new DeviceToClientRegistry(), null, cacheManager, null, null, null, null,
                "1.0.0", 10);
    }

    /** Reads the fragments of a managed object from its top level, as the platform's mapper does. */
    abstract static class FragmentsAtTopLevel {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        abstract void setProperty(String name, Object value);
    }

    private static ObjectMapper managedObjectMapper() {
        return new ObjectMapper().addMixIn(ManagedObjectRepresentation.class, FragmentsAtTopLevel.class);
    }

    /** A processing context with one INVENTORY request; a Live Test when {@code livePrefix} is set. */
    private ProcessingContext<Object> contextWithDeviceRequest(String livePrefix, String body, String sourceId) {
        ProcessingContext<Object> context = ProcessingContext.<Object>builder()
                .tenant(TEST_TENANT).testExternalIdPrefix(livePrefix).testing(false).build();
        context.addRequest(dynamic.mapper.model.DynamicMapperRequest.builder()
                .api(dynamic.mapper.model.API.INVENTORY)
                .method(org.springframework.web.bind.annotation.RequestMethod.POST)
                .sourceId(sourceId).externalIdType("c8y_Serial").externalId("d11r_liveTest_x")
                .request(body).build());
        return context;
    }

    private static final String ISSUE_700_DEVICE = "{\"name\":\"issue700-name\",\"type\":\"issue700_Type\","
            + "\"c8y_IsDevice\":{},\"d11r_testDevice\":\"overwritten\","
            + "\"dmtest_Custom\":{\"flag\":\"from-contextData\"}}";

    private ManagedObjectRepresentation ownerOf(String id, String type) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(id));
        mo.setType(type);
        return mo;
    }

    private ExternalIDRepresentation externalId(String deviceId) {
        ExternalIDRepresentation representation = new ExternalIDRepresentation();
        representation.setManagedObject(ownerOf(deviceId, null));
        return representation;
    }

    @Test
    void testUpsertDevice_inALiveTest_createsTheDeviceWithTheMappingsNameAndFragmentsButAsATestDevice() throws Exception {
        C8YAgent agent = newAgent(false);
        ProcessingContext<Object> context = contextWithDeviceRequest("d11r_liveTest_", ISSUE_700_DEVICE, null);

        ManagedObjectRepresentation device = agent.upsertDevice(TEST_TENANT,
                new ID("c8y_Serial", "d11r_liveTest_x"), context, 0);

        assertEquals("4711", device.getId().getValue());
        org.mockito.ArgumentCaptor<ManagedObjectRepresentation> created =
                org.mockito.ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryFacade).create(created.capture(), eq(false));
        ManagedObjectRepresentation mo = created.getValue();
        assertEquals("issue700-name", mo.getName(), "the name the mapping defined");
        assertEquals(Map.of("flag", "from-contextData"), mo.get("dmtest_Custom"), "a fragment the mapping defined");
        assertEquals("d11r_testDevice", mo.getType(), "the type stays the test device type");
        assertEquals(Map.of(), mo.get("d11r_testDevice"), "the marker the Test Devices grid queries");
    }

    @Test
    void testUpsertDevice_notInALiveTest_keepsTheMappingsTypeAndAddsNoMarker() throws Exception {
        C8YAgent agent = newAgent(false);
        ProcessingContext<Object> context = contextWithDeviceRequest(null,
                "{\"name\":\"issue700-name\",\"type\":\"issue700_Type\"}", null);

        agent.upsertDevice(TEST_TENANT, new ID("c8y_Serial", "issue700-device-1"), context, 0);

        org.mockito.ArgumentCaptor<ManagedObjectRepresentation> created =
                org.mockito.ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryFacade).create(created.capture(), eq(false));
        assertEquals("issue700_Type", created.getValue().getType(), "a productive device keeps the mapping's type");
        assertNull(created.getValue().get("d11r_testDevice"), "and is not marked as a test device");
    }

    @Test
    void testUpsertDevice_inALiveTest_anUpdateKeepsTheTestDeviceType() throws Exception {
        C8YAgent agent = newAgent(false);
        // the mapping updates a device that already exists (an INVENTORY mapping): the type must not change
        ProcessingContext<Object> context = contextWithDeviceRequest("d11r_liveTest_", ISSUE_700_DEVICE, "4711");

        agent.upsertDevice(TEST_TENANT, new ID("c8y_Serial", "d11r_liveTest_x"), context, 0);

        org.mockito.ArgumentCaptor<ManagedObjectRepresentation> updated =
                org.mockito.ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryFacade).update(updated.capture(), eq(false));
        assertEquals("d11r_testDevice", updated.getValue().getType());
        assertEquals(Map.of(), updated.getValue().get("d11r_testDevice"));
    }

    @Test
    void testUpsertDevice_inALiveTest_refusesARealDeviceThatWonTheCreationRace() throws Exception {
        C8YAgent agent = newAgent(true); // atomic creation: a lost race is an HTTP 409
        when(inventoryFacade.create(any(ManagedObjectRepresentation.class), eq(false)))
                .thenThrow(new com.cumulocity.sdk.client.SDKException(409, "already bound"));
        when(identityFacade.resolveExternalId2GlobalId(any(), eq(false), any())).thenReturn(externalId("555"));
        when(inventoryFacade.get(any(GId.class), eq(false))).thenReturn(ownerOf("555", "c8y_RealDevice"));
        ProcessingContext<Object> context = contextWithDeviceRequest("d11r_liveTest_", ISSUE_700_DEVICE, null);

        dynamic.mapper.processor.ProcessingException e = org.junit.jupiter.api.Assertions.assertThrows(
                dynamic.mapper.processor.ProcessingException.class,
                () -> agent.upsertDevice(TEST_TENANT, new ID("c8y_Serial", "d11r_liveTest_x"), context, 0));

        assertTrue(e.getMessage().contains("not a mapper test device"), e.getMessage());
    }

    @Test
    void testUpsertDevice_inALiveTest_acceptsATestDeviceThatWonTheCreationRace() throws Exception {
        C8YAgent agent = newAgent(true);
        when(inventoryFacade.create(any(ManagedObjectRepresentation.class), eq(false)))
                .thenThrow(new com.cumulocity.sdk.client.SDKException(409, "already bound"));
        when(identityFacade.resolveExternalId2GlobalId(any(), eq(false), any())).thenReturn(externalId("556"));
        when(inventoryFacade.get(any(GId.class), eq(false))).thenReturn(ownerOf("556", "d11r_testDevice"));
        ProcessingContext<Object> context = contextWithDeviceRequest("d11r_liveTest_", ISSUE_700_DEVICE, null);

        ManagedObjectRepresentation device = agent.upsertDevice(TEST_TENANT,
                new ID("c8y_Serial", "d11r_liveTest_x"), context, 0);

        assertEquals("556", device.getId().getValue());
    }

    @Test
    void testLookupTestDevice_neverTrustsAStaleCachedIdOfADeletedTestDevice() throws Exception {
        String externalId = "d11r_liveTest_x";
        String key = TEST_TENANT + "|c8y_Serial|" + externalId;
        tenantRegistry.cacheExternalId(key, "581685638"); // device was deleted, the entry was not
        C8YAgent agent = newAgent(false);
        when(identityFacade.resolveExternalId2GlobalId(any(), eq(false), any())).thenReturn(null); // truly gone

        C8YAgent.TestDeviceLookup lookup = agent.lookupTestDevice(TEST_TENANT, "c8y_Serial", externalId);

        assertNull(lookup.id(), "the stale cached id must not make a deleted device look like it exists");
        assertEquals(false, lookup.foreign());
        assertNull(tenantRegistry.getCachedExternalId(key), "the stale cache entry must be evicted");
        verify(cacheManager).removeDeviceFromInboundExternalIdCache(eq(TEST_TENANT), any(ID.class));
        verify(inventoryFacade, never()).create(any(ManagedObjectRepresentation.class), anyBoolean());
    }

    @Test
    void testLookupTestDevice_findsAnExistingTestDeviceAndFlagsAForeignObject() throws Exception {
        C8YAgent agent = newAgent(false);
        when(identityFacade.resolveExternalId2GlobalId(any(), eq(false), any())).thenReturn(externalId("700"));
        when(inventoryFacade.get(any(GId.class), eq(false))).thenReturn(ownerOf("700", "d11r_testDevice"));
        C8YAgent.TestDeviceLookup own = agent.lookupTestDevice(TEST_TENANT, "c8y_Serial", "d11r_liveTest_a");
        assertEquals("700", own.id());
        assertEquals(false, own.foreign());

        when(inventoryFacade.get(any(GId.class), eq(false))).thenReturn(ownerOf("700", "c8y_RealDevice"));
        C8YAgent.TestDeviceLookup foreign = agent.lookupTestDevice(TEST_TENANT, "c8y_Serial", "d11r_liveTest_a");
        assertEquals("700", foreign.id());
        assertEquals(true, foreign.foreign(), "a real device must never be reused by a Live Test");
    }

    @Test
    void testAssertLiveTestMayUse_onlyChecksALiveTestAndOnlyAllowsTestDevices() throws Exception {
        C8YAgent agent = newAgent(false);
        when(inventoryFacade.get(any(GId.class), eq(false))).thenReturn(ownerOf("800", "c8y_RealDevice"));
        ProcessingContext<Object> live = contextWithDeviceRequest("d11r_liveTest_", "{}", null);
        ProcessingContext<Object> productive = contextWithDeviceRequest(null, "{}", null);

        agent.assertLiveTestMayUse(TEST_TENANT, productive, "800"); // a productive run is not restricted
        verify(inventoryFacade, never()).get(any(GId.class), anyBoolean());
        org.junit.jupiter.api.Assertions.assertThrows(dynamic.mapper.processor.ProcessingException.class,
                () -> agent.assertLiveTestMayUse(TEST_TENANT, live, "800"));

        when(inventoryFacade.get(any(GId.class), eq(false))).thenReturn(ownerOf("801", "d11r_testDevice"));
        agent.assertLiveTestMayUse(TEST_TENANT, live, "801");
    }
}
