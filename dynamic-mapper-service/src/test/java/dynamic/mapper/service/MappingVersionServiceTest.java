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
import dynamic.mapper.model.SemVer;
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
 * standalone {@code d11r_mapping_version} managed objects keyed by the owning
 * line's identifier; the in-memory fake here holds them in a flat store, with
 * {@code getManagedObjectsByFilter} + {@code findAll} returning all of them and
 * the service filtering by identifier.
 */
@ExtendWith(MockitoExtension.class)
class MappingVersionServiceTest {

    private static final String TENANT = "t1";
    private static final String IDENTIFIER = "abc";

    @Mock private InventoryFacade inventoryApi;
    @Mock private MappingVersionRepository versionRepository;
    @Mock private ServiceConfigurationService serviceConfigurationService;
    @Mock private MappingValidator mappingValidator;
    @Mock private MicroserviceSubscriptionsService subscriptionsService;
    @Mock private ContextService<UserCredentials> contextService;
    @Mock private ConfigurationRegistry configurationRegistry;

    private MappingVersionService service;

    /** Flat store of all version records (id -> version), across the tenant. */
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

        // ----- Fake query-based version inventory -----
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
            String id = "mo-" + idSeq.incrementAndGet();
            mor.setId(GId.asGId(id));
            pendingVersion.setId(id);
            byId.put(id, pendingVersion);
            return mor;
        });

        // The collection content is irrelevant since findAll is stubbed to return the store.
        lenient().when(inventoryApi.getManagedObjectsByFilter(any(), any()))
                .thenReturn(mock(ManagedObjectCollection.class));

        // findAll returns ALL version records (the service then filters by identifier),
        // sorted ascending by semver like the real repository.
        lenient().when(versionRepository.findAll(eq(TENANT), any(ManagedObjectCollection.class))).thenAnswer(inv -> {
            List<MappingVersion> versions = new ArrayList<>(byId.values());
            versions.sort(Comparator.comparing(MappingVersion::getVersion, SemVer.STRING_COMPARATOR));
            return versions;
        });

        lenient().when(inventoryApi.update(any(), any())).thenAnswer(inv -> {
            ManagedObjectRepresentation mor = inv.getArgument(0);
            byId.put(mor.getId().getValue(), pendingVersion);
            return mor;
        });

        lenient().doAnswer(inv -> {
            GId gid = inv.getArgument(0);
            byId.remove(gid.getValue());
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
    void publishStoresSuppliedSemVer() {
        MappingVersion v1 = service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "first", null);
        MappingVersion v2 = service.publish(TENANT, mapping(IDENTIFIER), "2.0.0", "second", "1.0.0");
        MappingVersion v3 = service.publish(TENANT, mapping(IDENTIFIER), "3.0.0", "third", "2.0.0");

        assertEquals("1.0.0", v1.getVersion());
        assertEquals("2.0.0", v2.getVersion());
        assertEquals("3.0.0", v3.getVersion());
        assertEquals("tester", v1.getCreatedBy());
        assertEquals(3, service.listVersions(TENANT, IDENTIFIER).size());
        assertEquals("2.0.0", v2.getSnapshot().getVersion());
        assertEquals("second", v2.getSnapshot().getVersionNote());
    }

    @Test
    void publishRegistersVersionAsChildAdditionOfMapping() {
        MappingVersion v1 = service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "first", null);

        // The version MO is registered as a child addition of the runnable mapping MO
        // (its snapshot id) so the mapping -> versions relationship is navigable.
        verify(inventoryApi).addChildAddition(
                eq(GId.asGId("runnable-" + IDENTIFIER)), eq(GId.asGId(v1.getId())), eq(false));
    }

    @Test
    void publishDoesNotMutateCallerMapping() {
        Mapping original = mapping(IDENTIFIER);
        original.setVersion("1.0.0");
        service.publish(TENANT, original, "1.0.0", "note", null);
        assertEquals("1.0.0", original.getVersion());
        assertNull(original.getVersionNote());
    }

    @Test
    void publishFailsValidationLeavesNoVersion() {
        when(mappingValidator.validate(eq(TENANT), any(), any()))
                .thenReturn(List.of(dynamic.mapper.model.ValidationError.Source_Template_Must_Be_Valid_JSON));

        assertThrows(MappingValidationException.class,
                () -> service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "bad", null));
        assertEquals(0, versionCount());
    }

    @Test
    void retentionPrunesOldestKeepingNewestN() {
        config.setMappingVersionRetention(3);

        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "v1", null);
        service.publish(TENANT, mapping(IDENTIFIER), "2.0.0", "v2", "1.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "3.0.0", "v3", "2.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "4.0.0", "v4", "3.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "5.0.0", "v5", "4.0.0");

        List<MappingVersion> remaining = service.listVersions(TENANT, IDENTIFIER);
        assertEquals(3, remaining.size(), "only the newest 3 versions are kept");
        List<String> versions = remaining.stream().map(MappingVersion::getVersion).sorted().toList();
        assertEquals(List.of("3.0.0", "4.0.0", "5.0.0"), versions);
    }

    @Test
    void retentionNeverPrunesActiveVersion() {
        config.setMappingVersionRetention(3);

        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "v1", "1.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "2.0.0", "v2", "1.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "3.0.0", "v3", "1.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "4.0.0", "v4", "1.0.0");
        service.publish(TENANT, mapping(IDENTIFIER), "5.0.0", "v5", "1.0.0");

        List<String> versions = service.listVersions(TENANT, IDENTIFIER).stream()
                .map(MappingVersion::getVersion).sorted().toList();
        assertTrue(versions.contains("1.0.0"), "active version 1.0.0 must not be pruned");
        assertEquals(List.of("1.0.0", "3.0.0", "4.0.0", "5.0.0"), versions);
    }

    @Test
    void deleteVersionRejectsActive() {
        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "v1", null);
        assertThrows(IllegalStateException.class,
                () -> service.deleteVersion(TENANT, IDENTIFIER, "1.0.0", "1.0.0"));
        assertEquals(1, versionCount());
    }

    @Test
    void deleteVersionRemovesInactive() {
        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "v1", null);
        service.publish(TENANT, mapping(IDENTIFIER), "2.0.0", "v2", "1.0.0");

        service.deleteVersion(TENANT, IDENTIFIER, "1.0.0", "2.0.0");

        List<String> versions = service.listVersions(TENANT, IDENTIFIER).stream()
                .map(MappingVersion::getVersion).toList();
        assertEquals(List.of("2.0.0"), versions);
    }

    @Test
    void updateNoteChangesOnlyTheNote() {
        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "original", null);
        service.updateNote(TENANT, IDENTIFIER, "1.0.0", "renamed");

        MappingVersion v = service.getVersion(TENANT, IDENTIFIER, "1.0.0");
        assertEquals("renamed", v.getNote());
        assertEquals("renamed", v.getSnapshot().getVersionNote());
    }

    @Test
    void saveDraftCreatesThenUpsertsSingleDraft() {
        Mapping edit1 = mapping(IDENTIFIER);
        edit1.setName("draft v1");
        service.saveDraft(TENANT, IDENTIFIER, edit1);

        MappingVersion d1 = service.getDraft(TENANT, IDENTIFIER);
        assertNotNull(d1);
        assertTrue(d1.isDraft());
        assertEquals("draft v1", d1.getSnapshot().getName());
        assertEquals(1, versionCount(), "first save creates one draft record");

        Mapping edit2 = mapping(IDENTIFIER);
        edit2.setName("draft v2");
        edit2.setLastUpdate(d1.getSnapshot().getLastUpdate());
        service.saveDraft(TENANT, IDENTIFIER, edit2);

        MappingVersion d2 = service.getDraft(TENANT, IDENTIFIER);
        assertEquals("draft v2", d2.getSnapshot().getName());
        assertEquals(1, versionCount(), "second save upserts the same draft, not a new record");
    }

    @Test
    void getDraftIsNullWhenNoneExists() {
        assertNull(service.getDraft(TENANT, IDENTIFIER));
    }

    @Test
    void saveDraftRejectsStaleOptimisticBase() {
        service.saveDraft(TENANT, IDENTIFIER, mapping(IDENTIFIER)); // create (lastUpdate 0 -> accepted)
        long stored = service.getDraft(TENANT, IDENTIFIER).getSnapshot().getLastUpdate();

        Mapping stale = mapping(IDENTIFIER);
        stale.setLastUpdate(stored - 1000);
        assertThrows(IllegalStateException.class, () -> service.saveDraft(TENANT, IDENTIFIER, stale));

        Mapping fresh = mapping(IDENTIFIER);
        fresh.setLastUpdate(stored);
        assertDoesNotThrow(() -> service.saveDraft(TENANT, IDENTIFIER, fresh));
    }

    @Test
    void draftIsExcludedFromPublishedListing() {
        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "v1", null);
        service.saveDraft(TENANT, IDENTIFIER, mapping(IDENTIFIER)); // a separate draft

        assertEquals(1, service.listVersions(TENANT, IDENTIFIER).size(), "listVersions excludes the draft");
        assertNotNull(service.getDraft(TENANT, IDENTIFIER), "draft is still retrievable");
        assertEquals(2, versionCount(), "one published version + one draft");
    }

    @Test
    void deleteAllVersionsRemovesPublishedAndDraft() {
        service.publish(TENANT, mapping(IDENTIFIER), "1.0.0", "v1", null);
        service.publish(TENANT, mapping(IDENTIFIER), "2.0.0", "v2", "1.0.0");
        service.saveDraft(TENANT, IDENTIFIER, mapping(IDENTIFIER));
        assertEquals(3, versionCount());

        service.deleteAllVersions(TENANT, IDENTIFIER);

        assertEquals(0, versionCount());
        assertTrue(service.listVersions(TENANT, IDENTIFIER).isEmpty());
        assertNull(service.getDraft(TENANT, IDENTIFIER));
    }

    @Test
    void backfillCreatesV1WhenNoneExist() {
        Mapping runnable = mapping("legacy");
        runnable.setVersion("1.0.0");

        MappingVersion v = service.ensureBackfilled(TENANT, runnable);

        assertNotNull(v);
        assertEquals("1.0.0", v.getVersion());
        assertFalse(v.isDraft());
        assertEquals(1, versionCount());
    }

    @Test
    void backfillIgnoresExistingDraftAndStillCapturesActiveConfig() {
        // A draft exists but no published version yet — backfill must still capture v1.
        service.saveDraft(TENANT, IDENTIFIER, mapping(IDENTIFIER));
        Mapping runnable = mapping(IDENTIFIER);
        runnable.setVersion("1.0.0");

        MappingVersion v = service.ensureBackfilled(TENANT, runnable);

        assertNotNull(v);
        assertEquals("1.0.0", v.getVersion());
        assertFalse(v.isDraft());
        assertEquals(2, versionCount(), "draft plus the backfilled published v1");
    }

    @Test
    void backfillIsIdempotent() {
        Mapping runnable = mapping("legacy");
        runnable.setVersion("1.0.0");

        service.ensureBackfilled(TENANT, runnable);
        service.ensureBackfilled(TENANT, runnable);

        assertEquals(1, versionCount(), "backfill must not create duplicate version records");
    }

    @Test
    void backfillDoesNotMutateRunnableMapping() {
        Mapping runnable = mapping("legacy");
        runnable.setVersion("7.0.0");

        service.ensureBackfilled(TENANT, runnable);

        assertEquals("7.0.0", runnable.getVersion(), "runnable (cache-resident) mapping must be untouched");
    }

    // ========== countVersionsForIdentifiers ==========

    @Test
    void countVersionsForIdentifiers_emptySetReturnsEmptyMapWithoutQueryingInventory() {
        Map<String, Long> result = service.countVersionsForIdentifiers(TENANT, java.util.Set.of());

        assertTrue(result.isEmpty(), "empty input must return empty map");
        verify(inventoryApi, never()).getManagedObjectsByFilter(any(), any());
    }

    @Test
    void countVersionsForIdentifiers_identifierWithNoVersionsReturnsZero() {
        // No versions published yet — byId is empty
        Map<String, Long> result = service.countVersionsForIdentifiers(TENANT, java.util.Set.of("unknown-id"));

        assertEquals(1, result.size());
        assertEquals(0L, result.get("unknown-id"));
    }

    @Test
    void countVersionsForIdentifiers_draftsAreExcluded() {
        String id = "id-with-draft";
        // Publish one real version
        service.publish(TENANT, mapping(id), "1.0.0", "v1", null);
        // Manually inject a draft record into the fake store
        MappingVersion draft = MappingVersion.builder()
                .identifier(id).version(null).isDraft(true)
                .snapshot(mapping(id)).build();
        draft.setId("draft-mo");
        byId.put("draft-mo", draft);

        Map<String, Long> result = service.countVersionsForIdentifiers(TENANT, java.util.Set.of(id));

        assertEquals(1L, result.get(id), "draft must not be counted");
    }

    @Test
    void countVersionsForIdentifiers_countsPublishedVersionsPerIdentifier() {
        String idA = "id-a";
        String idB = "id-b";
        service.publish(TENANT, mapping(idA), "1.0.0", "a-v1", null);
        service.publish(TENANT, mapping(idA), "2.0.0", "a-v2", "1.0.0");
        service.publish(TENANT, mapping(idB), "1.0.0", "b-v1", null);

        Map<String, Long> result = service.countVersionsForIdentifiers(TENANT,
                java.util.Set.of(idA, idB, "id-c"));

        assertEquals(2L, result.get(idA));
        assertEquals(1L, result.get(idB));
        assertEquals(0L, result.get("id-c"), "identifier with no versions must default to 0");
    }

    @Test
    void countVersionsForIdentifiers_identifiersNotInRequestAreIgnored() {
        String idA = "id-a";
        String idB = "id-b";
        service.publish(TENANT, mapping(idA), "1.0.0", "v1", null);
        service.publish(TENANT, mapping(idB), "1.0.0", "v1", null);

        // Only ask for idA — idB versions should not appear in the result
        Map<String, Long> result = service.countVersionsForIdentifiers(TENANT, java.util.Set.of(idA));

        assertEquals(1, result.size());
        assertEquals(1L, result.get(idA));
    }
}
