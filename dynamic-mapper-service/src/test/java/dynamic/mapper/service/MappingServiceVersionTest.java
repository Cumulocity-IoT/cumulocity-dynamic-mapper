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

package dynamic.mapper.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionCount;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import dynamic.mapper.service.cache.FlowStateStore;
import dynamic.mapper.service.cache.MappingCacheManager;
import dynamic.mapper.service.deployment.DeploymentMapService;
import dynamic.mapper.service.resolver.MappingResolverService;
import dynamic.mapper.service.status.MappingStatusService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the P5 version-management delegations on {@link MappingService}
 * (publish-draft orchestration and the active-version guard wiring). Uses a spy so
 * the real orchestration runs while {@code getMapping} is stubbed.
 */
@ExtendWith(MockitoExtension.class)
class MappingServiceVersionTest {

    private static final String TENANT = "t1";
    private static final String MO_ID = "mo-1";
    private static final String IDENTIFIER = "abc";

    @Mock private InventoryFacade inventoryApi;
    @Mock private MappingRepository mappingRepository;
    @Mock private MappingCacheManager cacheManager;
    @Mock private MappingStatusService statusService;
    @Mock private MappingResolverService resolverService;
    @Mock private DeploymentMapService deploymentMapService;
    @Mock private DeviceToClientMapService deviceToClientMapService;
    @Mock private ConfigurationRegistry configurationRegistry;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private MappingValidator mappingValidator;
    @Mock private FlowStateStore flowStateStore;
    @Mock private MappingVersionService mappingVersionService;

    private MappingService service;

    @BeforeEach
    void setUp() {
        service = spy(new MappingService(inventoryApi, mappingRepository, cacheManager, statusService,
                resolverService, deploymentMapService, deviceToClientMapService, configurationRegistry,
                subscriptionsService, mappingValidator, flowStateStore, mappingVersionService));
    }

    private Mapping runnable() {
        return Mapping.builder()
                .id(MO_ID).identifier(IDENTIFIER).name("Runnable")
                .direction(Direction.INBOUND).targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON).transformationType(TransformationType.JSONATA)
                .active(true).debug(false).qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate("{}").targetTemplate("{}")
                .versionNumber(1)
                .build();
    }

    private MappingVersion draft(String name) {
        Mapping snapshot = Mapping.builder()
                .id(MO_ID).identifier(IDENTIFIER).name(name)
                .direction(Direction.INBOUND).targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON).transformationType(TransformationType.JSONATA)
                .active(false).debug(false).qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate("{}").targetTemplate("{}")
                .versionNote("draft-label")
                .build();
        return MappingVersion.builder().identifier(IDENTIFIER).isDraft(true).snapshot(snapshot).build();
    }

    @Test
    void publishDraftBackfillsThenPublishesThenClearsDraft() {
        Mapping runnable = runnable();
        MappingVersion d = draft("drafted content");
        MappingVersion published = MappingVersion.builder().identifier(IDENTIFIER).versionNumber(2)
                .snapshot(d.getSnapshot()).build();

        doReturn(runnable).when(service).getMapping(TENANT, MO_ID);
        when(mappingVersionService.getDraft(TENANT, IDENTIFIER)).thenReturn(d);
        when(mappingVersionService.publish(eq(TENANT), eq(d.getSnapshot()), eq("explicit"), eq(1)))
                .thenReturn(published);

        MappingVersion result = service.publishDraft(TENANT, MO_ID, "explicit");

        // Order matters: capture active config first, publish, then drop the draft.
        var inOrder = inOrder(mappingVersionService);
        inOrder.verify(mappingVersionService).ensureBackfilled(TENANT, runnable);
        inOrder.verify(mappingVersionService).publish(TENANT, d.getSnapshot(), "explicit", 1);
        inOrder.verify(mappingVersionService).deleteDraft(TENANT, IDENTIFIER);
        assertSame(published, result);
    }

    @Test
    void publishDraftFallsBackToDraftLabelWhenNoneGiven() {
        Mapping runnable = runnable();
        MappingVersion d = draft("x");
        doReturn(runnable).when(service).getMapping(TENANT, MO_ID);
        when(mappingVersionService.getDraft(TENANT, IDENTIFIER)).thenReturn(d);
        when(mappingVersionService.publish(any(), any(), any(), anyInt()))
                .thenReturn(MappingVersion.builder().versionNumber(2).build());

        service.publishDraft(TENANT, MO_ID, null);

        // Falls back to the draft snapshot's own note.
        verify(mappingVersionService).publish(TENANT, d.getSnapshot(), "draft-label", 1);
    }

    @Test
    void publishDraftWithoutDraftThrowsAndDoesNotPublish() {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID);
        when(mappingVersionService.getDraft(TENANT, IDENTIFIER)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.publishDraft(TENANT, MO_ID, "x"));

        verify(mappingVersionService, never()).publish(any(), any(), any(), anyInt());
        verify(mappingVersionService, never()).deleteDraft(any(), any());
    }

    @Test
    void deleteVersionPassesActiveVersionNumberForGuard() {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID); // active versionNumber = 1

        service.deleteVersion(TENANT, MO_ID, 3);

        verify(mappingVersionService).deleteVersion(TENANT, IDENTIFIER, 3, 1);
    }

    @Test
    void listAndGetDelegateByIdentifier() {
        doReturn(runnable()).when(service).getMapping(TENANT, MO_ID);

        service.listVersions(TENANT, MO_ID);
        service.getVersion(TENANT, MO_ID, 2);
        service.updateVersionNote(TENANT, MO_ID, 2, "note");

        verify(mappingVersionService).listVersions(TENANT, IDENTIFIER);
        verify(mappingVersionService).getVersion(TENANT, IDENTIFIER, 2);
        verify(mappingVersionService).updateNote(TENANT, IDENTIFIER, 2, "note");
    }

    @Test
    void operationsOnMissingMappingThrowNotFound() {
        doReturn(null).when(service).getMapping(TENANT, "missing");

        assertThrows(IllegalArgumentException.class, () -> service.publishDraft(TENANT, "missing", null));
        assertThrows(IllegalArgumentException.class, () -> service.listVersions(TENANT, "missing"));
        assertThrows(IllegalArgumentException.class, () -> service.deleteVersion(TENANT, "missing", 1));
    }

    // ========== getVersionCounts ==========

    private Mapping mappingWithId(String moId, String identifier, Direction direction) {
        return Mapping.builder()
                .id(moId).identifier(identifier).name("M-" + moId)
                .direction(direction).targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON).transformationType(TransformationType.JSONATA)
                .active(false).debug(false).qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate("{}").targetTemplate("{}")
                .build();
    }

    @Test
    void getVersionCounts_returnsCountPerMapping() {
        Mapping m1 = mappingWithId("mo-1", "id-1", Direction.INBOUND);
        Mapping m2 = mappingWithId("mo-2", "id-2", Direction.INBOUND);
        doReturn(List.of(m1, m2)).when(service).getMappings(TENANT, Direction.INBOUND);
        when(mappingVersionService.countVersionsForIdentifiers(TENANT, Set.of("id-1", "id-2")))
                .thenReturn(Map.of("id-1", 3L, "id-2", 1L));

        List<MappingVersionCount> result = service.getVersionCounts(TENANT, Direction.INBOUND);

        assertEquals(2, result.size());
        MappingVersionCount c1 = result.stream().filter(c -> "mo-1".equals(c.id())).findFirst().orElseThrow();
        MappingVersionCount c2 = result.stream().filter(c -> "mo-2".equals(c.id())).findFirst().orElseThrow();
        assertEquals(3L, c1.versionCount());
        assertEquals(1L, c2.versionCount());
    }

    @Test
    void getVersionCounts_identifierWithNoVersionsReturnsZero() {
        Mapping m = mappingWithId("mo-1", "id-1", Direction.INBOUND);
        doReturn(List.of(m)).when(service).getMappings(TENANT, Direction.INBOUND);
        when(mappingVersionService.countVersionsForIdentifiers(TENANT, Set.of("id-1")))
                .thenReturn(Map.of("id-1", 0L));

        List<MappingVersionCount> result = service.getVersionCounts(TENANT, Direction.INBOUND);

        assertEquals(1, result.size());
        assertEquals(0L, result.get(0).versionCount());
    }

    @Test
    void getVersionCounts_noMappingsReturnsEmptyList() {
        doReturn(List.of()).when(service).getMappings(TENANT, Direction.OUTBOUND);
        when(mappingVersionService.countVersionsForIdentifiers(TENANT, Set.of()))
                .thenReturn(Map.of());

        List<MappingVersionCount> result = service.getVersionCounts(TENANT, Direction.OUTBOUND);

        assertTrue(result.isEmpty());
    }

    @Test
    void getVersionCounts_directionFilterIsApplied() {
        Mapping inbound = mappingWithId("mo-in", "id-in", Direction.INBOUND);
        doReturn(List.of(inbound)).when(service).getMappings(TENANT, Direction.INBOUND);
        when(mappingVersionService.countVersionsForIdentifiers(TENANT, Set.of("id-in")))
                .thenReturn(Map.of("id-in", 2L));

        List<MappingVersionCount> result = service.getVersionCounts(TENANT, Direction.INBOUND);

        assertEquals(1, result.size());
        assertEquals("mo-in", result.get(0).id());
        // getMappings was called with INBOUND, not OUTBOUND
        verify(service).getMappings(TENANT, Direction.INBOUND);
        verify(service, never()).getMappings(TENANT, Direction.OUTBOUND);
    }
}
