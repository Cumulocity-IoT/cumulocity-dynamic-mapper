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

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionRepresentation;
import dynamic.mapper.model.Qos;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MappingVersionService}. Version records are stored as
 * child additions of the runnable mapping MO, so the in-memory fake here is keyed
 * by parent id: {@code createChildAddition} files a version under its parent and
 * {@code getChildAddition}s returns that parent's versions only.
 */
@ExtendWith(MockitoExtension.class)
class MappingVersionServiceTest {

    private static final String TENANT = "t1";
    private static final String IDENTIFIER = "abc";
    /** mapping(IDENTIFIER) is created with this MO id; it is the parent for the line's versions. */
    private static final String PARENT = "runnable-abc";

    @Mock private InventoryFacade inventoryApi;
    @Mock private MappingVersionRepository versionRepository;
    @Mock private ServiceConfigurationService serviceConfigurationService;
    @Mock private MappingValidator mappingValidator;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private ContextService<UserCredentials> contextService;
    @Mock private ConfigurationRegistry configurationRegistry;

    private MappingVersionService service;

    /** parent id -> child version MO ids, plus a global id -> version index. */
    private final Map<String, List<String>> childIds = new HashMap<>();
    private final Map<String, MappingVersion> byId = new HashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1000);
    private final ServiceConfiguration config = new ServiceConfiguration();
    private MappingVersion pendingVersion;

    @BeforeEach
    void setUp() {
        service = new MappingVersionService(inventoryApi, versionRepository, serviceConfigurationService,
                mappingValidator, subscriptionsService, contextService, configurationRegistry);

        lenient().when(subscriptionsService.callForTenant(eq(TENANT), any())).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> c = inv.getArgument(1);
            return c.call();
        });
        lenient().doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(subscriptionsService).runForTenant(eq(TENANT), any());

        lenient().when(configurationRegistry.getObjectMapper()).thenReturn(new ObjectMapper());
        lenient().when(mappingValidator.validate(eq(TENANT), any(), any())).thenReturn(Collections.emptyList());
        lenient().when(serviceConfigurationService.getServiceConfiguration(TENANT)).thenReturn(config);

        UserCredentials creds = mock(UserCredentials.class);
        lenient().when(creds.getUsername()).thenReturn("tester");
        lenient().when(contextService.getContext()).thenReturn(creds);

        // ----- Fake child-addition inventory -----
        lenient().when(versionRepository.toManagedObject(any())).thenAnswer(inv -> {
            MappingVersionRepresentation rep = inv.getArgument(0);
            ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
            if (rep.getId() != null) {
                mor.setId(GId.asGId(rep.getId()));
            }
            pendingVersion = rep.getMappingVersion();
            return mor;
        });

        lenient().when(inventoryApi.createChildAddition(any(), any(), any())).thenAnswer(inv -> {
            GId parentId = inv.getArgument(0);
            ManagedObjectRepresentation mor = inv.getArgument(1);
            String id = "mo-" + idSeq.incrementAndGet();
            mor.setId(GId.asGId(id));
            pendingVersion.setId(id);
            byId.put(id, pendingVersion);
            childIds.computeIfAbsent(parentId.getValue(), k -> new ArrayList<>()).add(id);
            return mor;
        });

        lenient().when(inventoryApi.getChildAdditions(any(), any())).thenAnswer(inv -> {
            GId parentId = inv.getArgument(0);
            List<ManagedObjectRepresentation> result = new ArrayList<>();
            for (String id : childIds.getOrDefault(parentId.getValue(), List.of())) {
                ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
                mo.setId(GId.asGId(id));
                result.add(mo);
            }
            return result;
        });

        // The real repository converts MOs back to versions; here we resolve via the index.
        lenient().when(versionRepository.findAll(eq(TENANT), any())).thenAnswer(inv -> {
            List<ManagedObjectRepresentation> mos = inv.getArgument(1);
            List<MappingVersion> versions = new ArrayList<>();
            for (ManagedObjectRepresentation mo : mos) {
                MappingVersion v = byId.get(mo.getId().getValue());
                if (v != null) {
                    versions.add(v);
                }
            }
            versions.sort(Comparator.comparingInt(MappingVersion::getVersionNumber));
            return versions;
        });

        lenient().when(inventoryApi.update(any(), any())).thenAnswer(inv -> {
            ManagedObjectRepresentation mor = inv.getArgument(0);
            byId.put(mor.getId().getValue(), pendingVersion);
            return mor;
        });

        lenient().doAnswer(inv -> {
            GId gid = inv.getArgument(0);
            String id = gid.getValue();
            byId.remove(id);
            childIds.values().forEach(list -> list.remove(id));
            return null;
        }).when(inventoryApi).delete(any(), any());
    }

    private int versionCount() {
        return byId.size();
    }

    private Mapping mapping(String identifier) {
        return Mapping.builder()
                .id("runnable-" + identifier)
                .identifier(identifier)
                .name("Mapping " + identifier)
                .direction(Direction.INBOUND)
                .targetAPI(API.MEASUREMENT)
                .mappingType(MappingType.JSON)
                .transformationType(TransformationType.JSONATA)
                .active(true)
                .debug(false)
                .qos(Qos.AT_LEAST_ONCE)
                .sourceTemplate("{}")
                .targetTemplate("{}")
                .build();
    }

    @Test
    void publishAssignsIncrementingVersionNumbers() {
        MappingVersion v1 = service.publish(TENANT, mapping(IDENTIFIER), "first", 0);
        MappingVersion v2 = service.publish(TENANT, mapping(IDENTIFIER), "second", 0);
        MappingVersion v3 = service.publish(TENANT, mapping(IDENTIFIER), "third", 0);

        assertEquals(1, v1.getVersionNumber());
        assertEquals(2, v2.getVersionNumber());
        assertEquals(3, v3.getVersionNumber());
        assertEquals("tester", v1.getCreatedBy());
        assertEquals(3, service.listVersions(TENANT, PARENT).size());
        assertEquals(2, v2.getSnapshot().getVersionNumber());
        assertEquals("second", v2.getSnapshot().getVersionLabel());
    }

    @Test
    void publishDoesNotMutateCallerMapping() {
        Mapping original = mapping(IDENTIFIER);
        original.setVersionNumber(1);
        service.publish(TENANT, original, "note", 0);
        assertEquals(1, original.getVersionNumber());
        assertNull(original.getVersionLabel());
    }

    @Test
    void publishFailsValidationLeavesNoVersion() {
        when(mappingValidator.validate(eq(TENANT), any(), any()))
                .thenReturn(List.of(dynamic.mapper.model.ValidationError.Source_Template_Must_Be_Valid_JSON));

        assertThrows(MappingValidationException.class, () -> service.publish(TENANT, mapping(IDENTIFIER), "bad", 0));
        assertEquals(0, versionCount());
    }

    @Test
    void retentionPrunesOldestKeepingNewestN() {
        config.setMappingVersionRetention(3);

        for (int i = 0; i < 5; i++) {
            service.publish(TENANT, mapping(IDENTIFIER), "v" + i, 0);
        }

        List<MappingVersion> remaining = service.listVersions(TENANT, PARENT);
        assertEquals(3, remaining.size(), "only the newest 3 versions are kept");
        List<Integer> numbers = remaining.stream().map(MappingVersion::getVersionNumber).sorted().toList();
        assertEquals(List.of(3, 4, 5), numbers);
    }

    @Test
    void retentionNeverPrunesActiveVersion() {
        config.setMappingVersionRetention(3);

        for (int i = 0; i < 5; i++) {
            service.publish(TENANT, mapping(IDENTIFIER), "v" + i, 1);
        }

        List<Integer> numbers = service.listVersions(TENANT, PARENT).stream()
                .map(MappingVersion::getVersionNumber).sorted().toList();
        assertTrue(numbers.contains(1), "active version 1 must not be pruned");
        assertEquals(List.of(1, 3, 4, 5), numbers);
    }

    @Test
    void deleteVersionRejectsActive() {
        service.publish(TENANT, mapping(IDENTIFIER), "v1", 0); // version 1
        assertThrows(IllegalStateException.class, () -> service.deleteVersion(TENANT, PARENT, 1, 1));
        assertEquals(1, versionCount());
    }

    @Test
    void deleteVersionRemovesInactive() {
        service.publish(TENANT, mapping(IDENTIFIER), "v1", 0);
        service.publish(TENANT, mapping(IDENTIFIER), "v2", 0);

        service.deleteVersion(TENANT, PARENT, 1, 2);

        List<Integer> numbers = service.listVersions(TENANT, PARENT).stream()
                .map(MappingVersion::getVersionNumber).sorted().toList();
        assertEquals(List.of(2), numbers);
    }

    @Test
    void updateLabelChangesOnlyTheLabel() {
        service.publish(TENANT, mapping(IDENTIFIER), "original", 0);
        service.updateLabel(TENANT, PARENT, 1, "renamed");

        MappingVersion v = service.getVersion(TENANT, PARENT, 1);
        assertEquals("renamed", v.getLabel());
        assertEquals("renamed", v.getSnapshot().getVersionLabel());
    }

    @Test
    void saveDraftCreatesThenUpsertsSingleDraft() {
        Mapping edit1 = mapping(IDENTIFIER);
        edit1.setName("draft v1");
        service.saveDraft(TENANT, PARENT, edit1);

        MappingVersion d1 = service.getDraft(TENANT, PARENT);
        assertNotNull(d1);
        assertTrue(d1.isDraft());
        assertEquals("draft v1", d1.getSnapshot().getName());
        assertEquals(1, versionCount(), "first save creates one draft record");

        Mapping edit2 = mapping(IDENTIFIER);
        edit2.setName("draft v2");
        edit2.setLastUpdate(d1.getSnapshot().getLastUpdate());
        service.saveDraft(TENANT, PARENT, edit2);

        MappingVersion d2 = service.getDraft(TENANT, PARENT);
        assertEquals("draft v2", d2.getSnapshot().getName());
        assertEquals(1, versionCount(), "second save upserts the same draft, not a new record");
    }

    @Test
    void getDraftIsNullWhenNoneExists() {
        assertNull(service.getDraft(TENANT, PARENT));
    }

    @Test
    void saveDraftRejectsStaleOptimisticBase() {
        service.saveDraft(TENANT, PARENT, mapping(IDENTIFIER)); // create (lastUpdate 0 -> accepted)
        long stored = service.getDraft(TENANT, PARENT).getSnapshot().getLastUpdate();

        Mapping stale = mapping(IDENTIFIER);
        stale.setLastUpdate(stored - 1000);
        assertThrows(IllegalStateException.class, () -> service.saveDraft(TENANT, PARENT, stale));

        Mapping fresh = mapping(IDENTIFIER);
        fresh.setLastUpdate(stored);
        assertDoesNotThrow(() -> service.saveDraft(TENANT, PARENT, fresh));
    }

    @Test
    void draftIsExcludedFromPublishedListing() {
        service.publish(TENANT, mapping(IDENTIFIER), "v1", 0); // version 1
        service.saveDraft(TENANT, PARENT, mapping(IDENTIFIER)); // a separate draft

        assertEquals(1, service.listVersions(TENANT, PARENT).size(), "listVersions excludes the draft");
        assertNotNull(service.getDraft(TENANT, PARENT), "draft is still retrievable");
        assertEquals(2, versionCount(), "one published version + one draft");
    }

    @Test
    void deleteAllVersionsRemovesPublishedAndDraft() {
        service.publish(TENANT, mapping(IDENTIFIER), "v1", 0);
        service.publish(TENANT, mapping(IDENTIFIER), "v2", 0);
        service.saveDraft(TENANT, PARENT, mapping(IDENTIFIER));
        assertEquals(3, versionCount());

        service.deleteAllVersions(TENANT, PARENT);

        assertEquals(0, versionCount());
        assertTrue(service.listVersions(TENANT, PARENT).isEmpty());
        assertNull(service.getDraft(TENANT, PARENT));
    }

    @Test
    void backfillCreatesV1WhenNoneExist() {
        Mapping runnable = mapping("legacy");
        runnable.setVersionNumber(1);

        MappingVersion v = service.ensureBackfilled(TENANT, runnable);

        assertNotNull(v);
        assertEquals(1, v.getVersionNumber());
        assertFalse(v.isDraft());
        assertEquals(1, versionCount());
    }

    @Test
    void backfillIsIdempotent() {
        Mapping runnable = mapping("legacy");
        runnable.setVersionNumber(1);

        service.ensureBackfilled(TENANT, runnable);
        service.ensureBackfilled(TENANT, runnable);
        service.ensureBackfilled(TENANT, runnable);

        assertEquals(1, versionCount(), "backfill must not create duplicate version records");
    }

    @Test
    void backfillDoesNotMutateRunnableMapping() {
        Mapping runnable = mapping("legacy");
        runnable.setVersionNumber(7);

        service.ensureBackfilled(TENANT, runnable);

        assertEquals(7, runnable.getVersionNumber(), "runnable (cache-resident) mapping must be untouched");
    }
}
