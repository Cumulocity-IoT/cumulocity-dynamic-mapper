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
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MappingVersionService}, focused on the P2 behaviors:
 * publish + version numbering, retention pruning (incl. active protection), and
 * idempotent backfill. Inventory is faked with an in-memory list driven through
 * the {@link InventoryFacade} / {@link MappingVersionRepository} boundary.
 */
@ExtendWith(MockitoExtension.class)
class MappingVersionServiceTest {

    private static final String TENANT = "t1";

    @Mock
    private InventoryFacade inventoryApi;
    @Mock
    private MappingVersionRepository versionRepository;
    @Mock
    private ServiceConfigurationService serviceConfigurationService;
    @Mock
    private MappingValidator mappingValidator;
    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;
    @Mock
    private ContextService<UserCredentials> contextService;
    @Mock
    private ConfigurationRegistry configurationRegistry;

    private MappingVersionService service;

    /** In-memory stand-in for the d11r_mapping_version managed objects. */
    private final List<MappingVersion> store = new ArrayList<>();
    private final AtomicInteger idSeq = new AtomicInteger(1000);
    private final ServiceConfiguration config = new ServiceConfiguration();

    @BeforeEach
    void setUp() {
        service = new MappingVersionService(inventoryApi, versionRepository, serviceConfigurationService,
                mappingValidator, subscriptionsService, contextService, configurationRegistry);

        // Execute tenant-scoped callbacks synchronously.
        lenient().when(subscriptionsService.callForTenant(eq(TENANT), any())).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> c = inv.getArgument(1);
            return c.call();
        });
        lenient().doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(subscriptionsService).runForTenant(eq(TENANT), any());

        // Real ObjectMapper for deep-copy of snapshots.
        lenient().when(configurationRegistry.getObjectMapper()).thenReturn(new ObjectMapper());

        // Validation passes by default.
        lenient().when(mappingValidator.validate(eq(TENANT), any(), any())).thenReturn(Collections.emptyList());

        // Config with default retention 10 (overridable per-test).
        lenient().when(serviceConfigurationService.getServiceConfiguration(TENANT)).thenReturn(config);

        // User context.
        UserCredentials creds = mock(UserCredentials.class);
        lenient().when(creds.getUsername()).thenReturn("tester");
        lenient().when(contextService.getContext()).thenReturn(creds);

        // ----- Fake inventory wiring -----
        // findAll returns a snapshot of the current store (same references).
        lenient().when(versionRepository.findAll(eq(TENANT), any())).thenAnswer(inv -> new ArrayList<>(store));
        lenient().when(inventoryApi.getManagedObjectsByFilter(any(), any()))
                .thenReturn(mock(ManagedObjectCollection.class));

        // toManagedObject records the version being persisted and echoes any id.
        lenient().when(versionRepository.toManagedObject(any())).thenAnswer(inv -> {
            MappingVersionRepresentation rep = inv.getArgument(0);
            ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
            if (rep.getId() != null) {
                mor.setId(GId.asGId(rep.getId()));
            }
            pendingVersion = rep.getMappingVersion();
            return mor;
        });

        // create assigns an id and adds the pending version to the store.
        lenient().when(inventoryApi.create(any(), any())).thenAnswer(inv -> {
            ManagedObjectRepresentation mor = inv.getArgument(0);
            String id = "mo-" + idSeq.incrementAndGet();
            mor.setId(GId.asGId(id));
            pendingVersion.setId(id);
            store.add(pendingVersion);
            return mor;
        });

        // update replaces the matching version in the store (draft upsert).
        lenient().when(inventoryApi.update(any(), any())).thenAnswer(inv -> {
            ManagedObjectRepresentation mor = inv.getArgument(0);
            String id = mor.getId().getValue();
            store.removeIf(v -> id.equals(v.getId()));
            pendingVersion.setId(id);
            store.add(pendingVersion);
            return mor;
        });

        // delete removes the matching version from the store.
        lenient().doAnswer(inv -> {
            GId gid = inv.getArgument(0);
            store.removeIf(v -> gid.getValue().equals(v.getId()));
            return null;
        }).when(inventoryApi).delete(any(), any());
    }

    private MappingVersion pendingVersion;

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
        MappingVersion v1 = service.publish(TENANT, mapping("abc"), "first", 0);
        MappingVersion v2 = service.publish(TENANT, mapping("abc"), "second", 0);
        MappingVersion v3 = service.publish(TENANT, mapping("abc"), "third", 0);

        assertEquals(1, v1.getVersionNumber());
        assertEquals(2, v2.getVersionNumber());
        assertEquals(3, v3.getVersionNumber());
        assertEquals("tester", v1.getCreatedBy());
        assertEquals(3, service.listVersions(TENANT, "abc").size());
        // Snapshot carries the assigned version number and label.
        assertEquals(2, v2.getSnapshot().getVersionNumber());
        assertEquals("second", v2.getSnapshot().getVersionLabel());
    }

    @Test
    void publishDoesNotMutateCallerMapping() {
        Mapping original = mapping("abc");
        original.setVersionNumber(1);
        service.publish(TENANT, original, "note", 0);
        // The caller's object must be untouched (deep copy used for the snapshot).
        assertEquals(1, original.getVersionNumber());
        assertNull(original.getVersionLabel());
    }

    @Test
    void publishFailsValidationLeavesNoVersion() {
        when(mappingValidator.validate(eq(TENANT), any(), any()))
                .thenReturn(List.of(dynamic.mapper.model.ValidationError.Source_Template_Must_Be_Valid_JSON));

        assertThrows(MappingValidationException.class, () -> service.publish(TENANT, mapping("abc"), "bad", 0));
        assertTrue(store.isEmpty());
    }

    @Test
    void retentionPrunesOldestKeepingNewestN() {
        config.setMappingVersionRetention(3);

        for (int i = 0; i < 5; i++) {
            service.publish(TENANT, mapping("abc"), "v" + i, 0);
        }

        List<MappingVersion> remaining = service.listVersions(TENANT, "abc");
        assertEquals(3, remaining.size(), "only the newest 3 versions are kept");
        List<Integer> numbers = remaining.stream().map(MappingVersion::getVersionNumber).sorted().toList();
        assertEquals(List.of(3, 4, 5), numbers);
    }

    @Test
    void retentionNeverPrunesActiveVersion() {
        config.setMappingVersionRetention(3);

        // Version 1 is the active one; it must survive even though it is the oldest.
        for (int i = 0; i < 5; i++) {
            service.publish(TENANT, mapping("abc"), "v" + i, 1);
        }

        List<Integer> numbers = service.listVersions(TENANT, "abc").stream()
                .map(MappingVersion::getVersionNumber).sorted().toList();
        assertTrue(numbers.contains(1), "active version 1 must not be pruned");
        // Newest 3 (3,4,5) plus the protected active 1 = 4 retained.
        assertEquals(List.of(1, 3, 4, 5), numbers);
    }

    @Test
    void deleteVersionRejectsActive() {
        service.publish(TENANT, mapping("abc"), "v1", 0); // version 1
        assertThrows(IllegalStateException.class, () -> service.deleteVersion(TENANT, "abc", 1, 1));
        assertEquals(1, store.size());
    }

    @Test
    void deleteVersionRemovesInactive() {
        service.publish(TENANT, mapping("abc"), "v1", 0);
        service.publish(TENANT, mapping("abc"), "v2", 0);

        service.deleteVersion(TENANT, "abc", 1, 2);

        List<Integer> numbers = service.listVersions(TENANT, "abc").stream()
                .map(MappingVersion::getVersionNumber).sorted().toList();
        assertEquals(List.of(2), numbers);
    }

    @Test
    void updateLabelChangesOnlyTheLabel() {
        service.publish(TENANT, mapping("abc"), "original", 0);
        service.updateLabel(TENANT, "abc", 1, "renamed");

        MappingVersion v = service.getVersion(TENANT, "abc", 1);
        assertEquals("renamed", v.getLabel());
        assertEquals("renamed", v.getSnapshot().getVersionLabel());
    }

    @Test
    void backfillCreatesV1WhenNoneExist() {
        Mapping runnable = mapping("legacy");
        runnable.setVersionNumber(1);

        MappingVersion v = service.ensureBackfilled(TENANT, runnable);

        assertNotNull(v);
        assertEquals(1, v.getVersionNumber());
        assertFalse(v.isDraft());
        assertEquals(1, store.size());
    }

    @Test
    void backfillIsIdempotent() {
        Mapping runnable = mapping("legacy");
        runnable.setVersionNumber(1);

        service.ensureBackfilled(TENANT, runnable);
        service.ensureBackfilled(TENANT, runnable);
        service.ensureBackfilled(TENANT, runnable);

        assertEquals(1, store.size(), "backfill must not create duplicate version records");
    }

    @Test
    void saveDraftCreatesThenUpsertsSingleDraft() {
        Mapping edit1 = mapping("abc");
        edit1.setName("draft v1");
        service.saveDraft(TENANT, "abc", edit1);

        MappingVersion d1 = service.getDraft(TENANT, "abc");
        assertNotNull(d1);
        assertTrue(d1.isDraft());
        assertEquals("draft v1", d1.getSnapshot().getName());
        assertEquals(1, store.size(), "first save creates one draft record");

        Mapping edit2 = mapping("abc");
        edit2.setName("draft v2");
        edit2.setLastUpdate(d1.getSnapshot().getLastUpdate()); // correct optimistic base
        service.saveDraft(TENANT, "abc", edit2);

        MappingVersion d2 = service.getDraft(TENANT, "abc");
        assertEquals("draft v2", d2.getSnapshot().getName());
        assertEquals(1, store.size(), "second save upserts the same draft, not a new record");
    }

    @Test
    void getDraftIsNullWhenNoneExists() {
        assertNull(service.getDraft(TENANT, "abc"));
    }

    @Test
    void saveDraftRejectsStaleOptimisticBase() {
        service.saveDraft(TENANT, "abc", mapping("abc")); // create (lastUpdate 0 -> accepted)
        long stored = service.getDraft(TENANT, "abc").getSnapshot().getLastUpdate();

        Mapping stale = mapping("abc");
        stale.setLastUpdate(stored - 1000);
        assertThrows(IllegalStateException.class, () -> service.saveDraft(TENANT, "abc", stale));

        Mapping fresh = mapping("abc");
        fresh.setLastUpdate(stored);
        assertDoesNotThrow(() -> service.saveDraft(TENANT, "abc", fresh));
    }

    @Test
    void draftIsExcludedFromPublishedListing() {
        service.publish(TENANT, mapping("abc"), "v1", 0); // version 1
        service.saveDraft(TENANT, "abc", mapping("abc")); // a separate draft

        assertEquals(1, service.listVersions(TENANT, "abc").size(), "listVersions excludes the draft");
        assertNotNull(service.getDraft(TENANT, "abc"), "draft is still retrievable");
        assertEquals(2, store.size(), "one published version + one draft");
    }

    @Test
    void backfillDoesNotMutateRunnableMapping() {
        Mapping runnable = mapping("legacy");
        runnable.setVersionNumber(7);

        service.ensureBackfilled(TENANT, runnable);

        assertEquals(7, runnable.getVersionNumber(), "runnable (cache-resident) mapping must be untouched");
    }
}
