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

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionRepresentation;
import dynamic.mapper.model.Qos;
import dynamic.mapper.model.SemVer;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import dynamic.mapper.service.DeviceToClientMapService;
import dynamic.mapper.service.MappingService;
import dynamic.mapper.service.MappingValidator;
import dynamic.mapper.service.MappingVersionRepository;
import dynamic.mapper.service.MappingVersionService;
import dynamic.mapper.service.ServiceConfigurationService;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test wiring the <b>real</b> {@link MappingService} and the
 * <b>real</b> {@link MappingVersionService} together, so the cross-service
 * orchestration that the per-service unit tests stub out is actually exercised:
 * draft → publish (with legacy backfill) → activate (version swap, C-1) →
 * rollback. Only the inventory boundary is faked — the runnable mapping is held
 * in an in-memory map (via spied {@code getMapping}/{@code updateMapping}) and
 * version records live in an in-memory child-addition store.
 */
@ExtendWith(MockitoExtension.class)
class MappingVersioningIntegrationTest {

    private static final String TENANT = "t1";
    private static final String MO_ID = "runnable-1";
    private static final String IDENTIFIER = "abc";

    // Real services under test
    private MappingService mappingService;
    private MappingVersionService mappingVersionService;

    // MappingVersionService collaborators
    @Mock private InventoryFacade inventoryApi;
    @Mock private MappingVersionRepository versionRepository;
    @Mock private ServiceConfigurationService serviceConfigurationService;
    @Mock private MappingValidator mappingValidator;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private ContextService<UserCredentials> contextService;
    @Mock private ConfigurationRegistry configurationRegistry;
    @Mock private C8YAgent c8yAgent;

    // MappingService peripheral collaborators (no-op for these scenarios)
    @Mock private dynamic.mapper.service.MappingRepository mappingRepository;
    @Mock private MappingCacheManager cacheManager;
    @Mock private MappingStatusService statusService;
    @Mock private MappingResolverService resolverService;
    @Mock private DeploymentMapService deploymentMapService;
    @Mock private DeviceToClientMapService deviceToClientMapService;
    @Mock private FlowStateStore flowStateStore;

    private final ObjectMapper om = new ObjectMapper();
    private final ServiceConfiguration config = new ServiceConfiguration();

    /** In-memory runnable mapping store (stands in for the d11r_mapping MO). */
    private final Map<String, Mapping> runnableStore = new HashMap<>();
    /** Flat store of version records (id -> version). */
    private final Map<String, MappingVersion> versionsById = new HashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1000);
    private MappingVersion pendingVersion;

    @BeforeEach
    void setUp() {
        mappingVersionService = new MappingVersionService(inventoryApi, versionRepository,
                serviceConfigurationService, mappingValidator, subscriptionsService, contextService,
                configurationRegistry);

        MappingService real = new MappingService(inventoryApi, mappingRepository, cacheManager, statusService,
                resolverService, deploymentMapService, deviceToClientMapService, configurationRegistry,
                subscriptionsService, mappingValidator, flowStateStore, mappingVersionService);
        mappingService = spy(real);

        // ----- shared infrastructure -----
        lenient().when(subscriptionsService.callForTenant(eq(TENANT), any())).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> c = inv.getArgument(1);
            return c.call();
        });
        lenient().doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(subscriptionsService).runForTenant(eq(TENANT), any());

        lenient().when(configurationRegistry.getObjectMapper()).thenReturn(om);
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        lenient().when(mappingValidator.validate(eq(TENANT), any(), any())).thenReturn(Collections.emptyList());
        lenient().when(serviceConfigurationService.getServiceConfiguration(TENANT)).thenReturn(config);
        lenient().when(cacheManager.removeMapping(any(), any())).thenReturn(Optional.empty());

        UserCredentials creds = mock(UserCredentials.class);
        lenient().when(creds.getUsername()).thenReturn("tester");
        lenient().when(contextService.getContext()).thenReturn(creds);

        // ----- runnable mapping store (via the MappingService spy) -----
        lenient().doAnswer(inv -> {
            Mapping m = runnableStore.get(inv.getArgument(1, String.class));
            return m == null ? null : copy(m);
        }).when(mappingService).getMapping(eq(TENANT), anyString());
        lenient().doAnswer(inv -> {
            Mapping m = inv.getArgument(1);
            runnableStore.put(m.getId(), copy(m));
            return m;
        }).when(mappingService).updateMapping(eq(TENANT), any(Mapping.class), anyBoolean(), anyBoolean());

        // ----- version inventory boundary (query-based: type query + filter by identifier) -----
        lenient().when(versionRepository.toManagedObject(any())).thenAnswer(inv -> {
            MappingVersionRepresentation rep = inv.getArgument(0);
            ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
            if (rep.getId() != null) {
                mor.setId(GId.asGId(rep.getId()));
            }
            pendingVersion = rep.getMappingVersion();
            return mor;
        });
        lenient().when(inventoryApi.create(any(), any())).thenAnswer(inv -> {
            ManagedObjectRepresentation mor = inv.getArgument(0);
            String id = "ver-" + idSeq.incrementAndGet();
            mor.setId(GId.asGId(id));
            pendingVersion.setId(id);
            versionsById.put(id, pendingVersion);
            return mor;
        });
        lenient().when(inventoryApi.getManagedObjectsByFilter(any(), any()))
                .thenReturn(mock(ManagedObjectCollection.class));
        lenient().when(versionRepository.findAll(eq(TENANT), any(ManagedObjectCollection.class))).thenAnswer(inv -> {
            List<MappingVersion> versions = new ArrayList<>(versionsById.values());
            versions.sort(Comparator.comparing(MappingVersion::getVersion, SemVer.STRING_COMPARATOR));
            return versions;
        });
        lenient().when(inventoryApi.update(any(), any())).thenAnswer(inv -> {
            ManagedObjectRepresentation mor = inv.getArgument(0);
            versionsById.put(mor.getId().getValue(), pendingVersion);
            return mor;
        });
        lenient().doAnswer(inv -> {
            versionsById.remove(inv.getArgument(0, GId.class).getValue());
            return null;
        }).when(inventoryApi).delete(any(), any());
    }

    private Mapping copy(Mapping m) {
        return om.convertValue(m, Mapping.class);
    }

    private Mapping seedRunnable(String name) {
        Mapping m = Mapping.builder()
                .id(MO_ID).identifier(IDENTIFIER).name(name)
                .direction(Direction.INBOUND).targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON).transformationType(TransformationType.JSONATA)
                .active(true).debug(false).qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate("{\"v\":1}").targetTemplate("{}")
                .version("1.0.0")
                .build();
        runnableStore.put(MO_ID, m);
        return m;
    }

    private Mapping edit(String name, String source) {
        Mapping m = copy(runnableStore.get(MO_ID));
        m.setName(name);
        m.setSourceTemplate(source);
        return m;
    }

    @Test
    void fullLifecycle_draftPublishActivateRollback() throws Exception {
        seedRunnable("original");

        // 1) Edit -> draft (running config unchanged)
        mappingService.saveDraftMapping(TENANT, MO_ID, edit("edited", "{\"v\":2}"));
        assertEquals("original", runnableStore.get(MO_ID).getName(), "active config unchanged by editing");
        assertNotNull(mappingService.getDraftMapping(TENANT, MO_ID), "a draft now exists");

        // 2) Publish the draft -> backfills the active config as v1, draft becomes v2
        MappingVersion published = mappingService.publishDraft(TENANT, MO_ID, "2.0.0", "second version");
        assertEquals("2.0.0", published.getVersion());
        assertNull(mappingService.getDraftMapping(TENANT, MO_ID), "draft cleared after publish");

        List<MappingVersion> versions = mappingService.listVersions(TENANT, MO_ID);
        assertEquals(List.of("1.0.0", "2.0.0"), versions.stream().map(MappingVersion::getVersion).sorted().toList());
        assertEquals("original", versions.stream().filter(v -> "1.0.0".equals(v.getVersion())).findFirst().get()
                .getSnapshot().getName(), "v1 is the backfilled original active config");
        assertEquals("edited", versions.stream().filter(v -> "2.0.0".equals(v.getVersion())).findFirst().get()
                .getSnapshot().getName(), "v2 is the published draft");

        // active config is STILL v1 until we activate v2 (publish does not activate)
        assertEquals("1.0.0", runnableStore.get(MO_ID).getVersion());
        assertEquals("original", runnableStore.get(MO_ID).getName());

        // 3) Activate v2 -> runnable swapped to the published content (C-1: one active version)
        mappingService.setActivationMapping(TENANT, MO_ID, true, "2.0.0");
        assertEquals("2.0.0", runnableStore.get(MO_ID).getVersion());
        assertEquals("edited", runnableStore.get(MO_ID).getName());
        assertEquals("{\"v\":2}", runnableStore.get(MO_ID).getSourceTemplate());

        // 4) Roll back to v1 -> runnable swapped back; forward history (v2) survives
        mappingService.setActivationMapping(TENANT, MO_ID, true, "1.0.0");
        assertEquals("1.0.0", runnableStore.get(MO_ID).getVersion());
        assertEquals("original", runnableStore.get(MO_ID).getName());
        assertEquals(List.of("1.0.0", "2.0.0"),
                mappingService.listVersions(TENANT, MO_ID).stream()
                        .map(MappingVersion::getVersion).sorted().toList(),
                "rollback must not delete newer versions");
    }

    @Test
    void activatingMissingVersionLeavesRunningVersionUnchanged() {
        seedRunnable("original");

        assertThrows(IllegalArgumentException.class,
                () -> mappingService.setActivationMapping(TENANT, MO_ID, true, "99.0.0"));

        // FR-9: a failed activation does not change what is running
        assertEquals("1.0.0", runnableStore.get(MO_ID).getVersion());
        assertEquals("original", runnableStore.get(MO_ID).getName());
    }

    @Test
    void deleteInactiveVersionThenActiveIsProtected() throws Exception {
        seedRunnable("original");
        mappingService.saveDraftMapping(TENANT, MO_ID, edit("edited", "{\"v\":2}"));
        mappingService.publishDraft(TENANT, MO_ID, "2.0.0", "v2"); // versions 1.0.0 (backfill) + 2.0.0

        // v1.0.0 is active (runnable.version == 1.0.0); deleting it is rejected
        assertThrows(IllegalStateException.class, () -> mappingService.deleteVersion(TENANT, MO_ID, "1.0.0"));
        // v2.0.0 is inactive; it can be deleted
        mappingService.deleteVersion(TENANT, MO_ID, "2.0.0");

        assertEquals(List.of("1.0.0"),
                mappingService.listVersions(TENANT, MO_ID).stream()
                        .map(MappingVersion::getVersion).sorted().toList());
    }
}
