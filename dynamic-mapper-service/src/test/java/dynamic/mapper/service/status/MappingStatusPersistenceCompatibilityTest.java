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

package dynamic.mapper.service.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MapperServiceRepresentation;
import dynamic.mapper.model.MappingStatus;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.cache.MappingCacheManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Statuses persisted by an earlier release must keep loading and keep being reported — the
 * counters live in the {@code d11r_mapping} inventory fragment and are read back on startup, so
 * a change to {@link MappingStatus} that broke this would silently zero every tenant's statistics.
 */
@ExtendWith(MockitoExtension.class)
class MappingStatusPersistenceCompatibilityTest {

    private static final String TENANT = "t1";

    /**
     * Exactly what an older release wrote: the pre-rename "Unspecified" label, a {@code null}
     * direction on the catch-all entry, a {@code null} loadingError, and {@code snoopedTemplates*}
     * fields that were removed in 6.4.0.
     */
    private static final String PERSISTED_BY_OLDER_RELEASE = """
            [
              {"id":"mo-1","name":"Temperature","identifier":"abc","direction":"INBOUND",
               "mappingTopic":"sensors/+/data","publishTopic":"#","messagesReceived":1247,
               "errors":3,"currentFailureCount":1,"loadingError":null,
               "snoopedTemplatesActive":2,"snoopedTemplatesTotal":5},
              {"id":"UNSPECIFIED","name":"Unspecified","identifier":"UNSPECIFIED","direction":null,
               "mappingTopic":"#","publishTopic":"#","messagesReceived":42,"errors":7,
               "currentFailureCount":0,"loadingError":null}
            ]
            """;

    @Mock private InventoryFacade inventoryApi;
    @Mock private ConfigurationRegistry configurationRegistry;
    @Mock private MappingCacheManager cacheManager;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private C8YAgent c8yAgent;

    private MappingStatusService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new MappingStatusService(inventoryApi, configurationRegistry, cacheManager,
                subscriptionsService);

        MapperServiceRepresentation serviceRep = new MapperServiceRepresentation();
        serviceRep.setId("service-mo");
        serviceRep.setMappingStatus(new ObjectMapper().readValue(PERSISTED_BY_OLDER_RELEASE,
                new com.fasterxml.jackson.core.type.TypeReference<List<MappingStatus>>() {}));

        lenient().when(configurationRegistry.getMapperServiceRepresentation(anyString()))
                .thenReturn(serviceRep);
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
    }

    private Mapping mapping() {
        return Mapping.builder()
                .id("mo-1").identifier("abc").name("Temperature")
                .direction(Direction.INBOUND).targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON).transformationType(TransformationType.JSONATA)
                .active(true).debug(false)
                .mappingTopic("sensors/+/data")
                .sourceTemplate("{}").targetTemplate("{}")
                .build();
    }

    @Test
    @DisplayName("statuses written by an older release load with their counters intact")
    void oldStatusesAreLoaded() {
        service.initializeTenantStatus(TENANT, false);

        List<MappingStatus> loaded = service.getAllStatuses(TENANT);

        MappingStatus mappingStatus = loaded.stream()
                .filter(s -> "abc".equals(s.getIdentifier())).findFirst().orElseThrow();
        assertEquals(1247, mappingStatus.getMessagesReceived(), "counters must survive the restart");
        assertEquals(3, mappingStatus.getErrors());
        assertEquals(1, mappingStatus.getCurrentFailureCount());

        MappingStatus unspecified = loaded.stream()
                .filter(MappingStatus::isUnspecified).findFirst().orElseThrow();
        assertEquals(42, unspecified.getMessagesReceived());
        assertEquals(7, unspecified.getErrors());
    }

    @Test
    @DisplayName("the persisted catch-all entry is reused, not replaced by a fresh empty one")
    void persistedUnspecifiedEntryIsNotOverwritten() {
        service.initializeTenantStatus(TENANT, false);

        MappingStatus unspecified = service.getAllStatuses(TENANT).stream()
                .filter(MappingStatus::isUnspecified).findFirst().orElseThrow();

        // ensureUnspecifiedStatus() must not clobber the loaded entry with a zeroed instance.
        assertEquals(42, unspecified.getMessagesReceived());
    }

    @Test
    @DisplayName("loaded statuses are pushed back to the inventory and therefore show up in the UI")
    void oldStatusesAreReported() {
        lenient().when(configurationRegistry.getServiceConfiguration(TENANT))
                .thenReturn(sendingEnabled());
        lenient().when(cacheManager.containsInboundMappingByIdentifier(TENANT, "abc")).thenReturn(true);
        lenient().when(cacheManager.getInboundMappingByIdentifier(TENANT, "abc"))
                .thenReturn(Optional.of(mapping()));
        lenient().when(cacheManager.getOutboundMappingByIdentifier(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // runForTenant is a no-op mock by default; execute the body so the push really happens.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(subscriptionsService).runForTenant(anyString(), any(Runnable.class));

        service.initializeTenantStatus(TENANT, false);
        service.sendStatusToInventory(TENANT);

        ArgumentCaptor<ManagedObjectRepresentation> captor =
                ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(inventoryApi).update(captor.capture(), anyBoolean());

        Object fragment = captor.getValue().getAttrs().get(MapperServiceRepresentation.MAPPING_FRAGMENT);
        assertNotNull(fragment, "the status fragment must be written");
        MappingStatus[] pushed = (MappingStatus[]) fragment;

        MappingStatus reportedMapping = java.util.Arrays.stream(pushed)
                .filter(s -> "abc".equals(s.getIdentifier())).findFirst().orElseThrow();
        assertEquals(1247, reportedMapping.getMessagesReceived());
        assertEquals("Temperature", reportedMapping.getName());

        MappingStatus reportedUnspecified = java.util.Arrays.stream(pushed)
                .filter(MappingStatus::isUnspecified).findFirst().orElseThrow();
        assertEquals(42, reportedUnspecified.getMessagesReceived());
        assertEquals(MappingStatus.UNSPECIFIED_DISPLAY_NAME, reportedUnspecified.getName(),
                "the stale persisted label is re-enriched on the way out");
        assertTrue(pushed.length >= 2);
    }

    private ServiceConfiguration sendingEnabled() {
        ServiceConfiguration config = new ServiceConfiguration();
        config.setSendMappingStatus(true);
        return config;
    }
}
