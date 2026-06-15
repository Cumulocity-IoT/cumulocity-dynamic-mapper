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

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionRepresentation;
import dynamic.mapper.model.ValidationError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lifecycle owner for mapping versions (managed object type
 * {@code d11r_mapping_version}). Implements publish, listing, retrieval,
 * label edits, deletion, retention pruning, and lazy backfill of legacy
 * mappings.
 *
 * <p>Version records are stored as <b>child additions of the runnable mapping
 * managed object</b>, so all version operations are scoped by the parent
 * managed-object id ({@code parentId}) and bounded by that line's version count
 * (no tenant-wide scan). The active version number is supplied by the caller
 * (which owns the runnable {@code d11r_mapping} record), keeping this service
 * decoupled from {@link MappingService} and independently testable.
 *
 * <p>See {@code docs/feature/REQUIREMENTS-VERSION-MAPPING.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingVersionService {

    /** Fallback retention when configuration is missing or invalid. */
    static final int DEFAULT_RETENTION = 10;

    private final InventoryFacade inventoryApi;
    private final MappingVersionRepository versionRepository;
    private final ServiceConfigurationService serviceConfigurationService;
    private final MappingValidator mappingValidator;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final ContextService<UserCredentials> contextService;
    private final ConfigurationRegistry configurationRegistry;

    // ========== Publish ==========

    /**
     * Publishes the given mapping configuration as a new immutable version of its
     * line. Assigns the next version number, validates the snapshot, persists it as
     * a child addition of the runnable mapping, and applies retention pruning. The
     * newly published version is not activated by this call.
     *
     * @param mapping             the configuration to freeze (typically the draft);
     *                            its {@code id} is the parent/runnable managed-object id
     * @param label               optional change note
     * @param activeVersionNumber the version number currently active for this line
     *                            (protected from pruning); use 0 if none
     */
    public MappingVersion publish(String tenant, Mapping mapping, String label, int activeVersionNumber) {
        String parentId = mapping.getId();
        String identifier = mapping.getIdentifier();
        if (parentId == null || identifier == null) {
            throw new IllegalArgumentException(String.format(
                    "Tenant %s - Cannot publish a mapping without an id and identifier", tenant));
        }

        // Validate the snapshot - publish is the commitment point (a draft may be incomplete).
        List<ValidationError> errors = mappingValidator.validate(tenant, mapping, mapping.getId());
        if (!errors.isEmpty()) {
            throw new MappingValidationException(errors);
        }

        return subscriptionsService.callForTenant(tenant, () -> {
            List<MappingVersion> existing = loadVersions(tenant, parentId);
            int nextVersionNumber = nextVersionNumber(existing);

            Mapping snapshot = copyOf(mapping);
            snapshot.setVersionNumber(nextVersionNumber);
            snapshot.setVersionLabel(label);
            snapshot.setDraftDirty(false);

            MappingVersion version = MappingVersion.builder()
                    .identifier(identifier)
                    .versionNumber(nextVersionNumber)
                    .snapshot(snapshot)
                    .isDraft(false)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .label(label)
                    .build();

            MappingVersion persisted = persistNewVersion(parentId, version);
            log.info("{} - Published version {} of mapping line {} [{}]", tenant, nextVersionNumber,
                    identifier, persisted.getId());

            // Prune using the list we already loaded plus the just-persisted version,
            // avoiding a second child lookup on the hot publish path.
            List<MappingVersion> current = new ArrayList<>(existing);
            current.add(persisted);
            prune(tenant, parentId, activeVersionNumber, current);
            return persisted;
        });
    }

    // ========== Draft editing ==========

    /**
     * Returns the single draft (mutable working copy) of a mapping line, or
     * {@code null} if the line has no draft.
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public MappingVersion getDraft(String tenant, String parentId) {
        return subscriptionsService.callForTenant(tenant,
                () -> loadVersions(tenant, parentId).stream()
                        .filter(MappingVersion::isDraft)
                        .findFirst()
                        .orElse(null));
    }

    /**
     * Saves edits into the line's single draft, creating it if absent. Editing
     * never touches the runnable/active record (FR-1). There is at most one draft
     * per line (D-6).
     *
     * <p>Optimistic concurrency (IMP-2): if a draft already exists and the incoming
     * edits carry a non-zero {@code lastUpdate}, it must match the stored draft's
     * {@code lastUpdate}; otherwise the save is rejected as a concurrent
     * modification. On success the draft is stamped with a fresh {@code lastUpdate}
     * that the next edit must echo back.
     *
     * @param parentId the runnable mapping's managed-object id
     * @param edits    the edited configuration; its {@code identifier} names the line
     */
    public MappingVersion saveDraft(String tenant, String parentId, Mapping edits) {
        return subscriptionsService.callForTenant(tenant, () -> {
            MappingVersion existingDraft = loadVersions(tenant, parentId).stream()
                    .filter(MappingVersion::isDraft)
                    .findFirst()
                    .orElse(null);

            if (existingDraft != null && existingDraft.getSnapshot() != null
                    && edits.getLastUpdate() != 0
                    && edits.getLastUpdate() != existingDraft.getSnapshot().getLastUpdate()) {
                throw new IllegalStateException(String.format(
                        "Tenant %s - Draft of mapping %s was modified concurrently; reload before saving",
                        tenant, parentId));
            }

            Mapping snapshot = copyOf(edits);
            snapshot.setDraftDirty(true);
            snapshot.setLastUpdate(System.currentTimeMillis());

            MappingVersion draft = MappingVersion.builder()
                    .id(existingDraft != null ? existingDraft.getId() : null)
                    .identifier(edits.getIdentifier())
                    .versionNumber(0) // drafts are not numbered
                    .snapshot(snapshot)
                    .isDraft(true)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .build();

            if (existingDraft != null) {
                updateVersionMO(draft);
                log.info("{} - Updated draft of mapping {}", tenant, parentId);
            } else {
                persistNewVersion(parentId, draft);
                log.info("{} - Created draft of mapping {} [{}]", tenant, parentId, draft.getId());
            }
            return draft;
        });
    }

    /**
     * Removes the draft of a mapping line, if any. Called after a draft is
     * published (its content now lives in an immutable version) or when the draft
     * is discarded. No-op when there is no draft.
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public void deleteDraft(String tenant, String parentId) {
        subscriptionsService.runForTenant(tenant, () -> loadVersions(tenant, parentId).stream()
                .filter(MappingVersion::isDraft)
                .findFirst()
                .ifPresent(draft -> {
                    deleteVersionMO(draft);
                    log.info("{} - Deleted draft of mapping {}", tenant, parentId);
                }));
    }

    // ========== Listing & retrieval ==========

    /**
     * Lists all published versions of a mapping line, sorted ascending by version
     * number. Drafts are excluded.
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public List<MappingVersion> listVersions(String tenant, String parentId) {
        return subscriptionsService.callForTenant(tenant,
                () -> loadVersions(tenant, parentId).stream()
                        .filter(v -> !v.isDraft())
                        .collect(Collectors.toList()));
    }

    /**
     * Retrieves a single published version of a mapping line, or {@code null} if
     * not found.
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public MappingVersion getVersion(String tenant, String parentId, int versionNumber) {
        return subscriptionsService.callForTenant(tenant,
                () -> findPublished(loadVersions(tenant, parentId), versionNumber).orElse(null));
    }

    // ========== Label edit ==========

    /**
     * Updates the change-note label of a published version. The label is the only
     * mutable field of a version (D-3); all other fields are immutable.
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public MappingVersion updateLabel(String tenant, String parentId, int versionNumber, String label) {
        return subscriptionsService.callForTenant(tenant, () -> {
            MappingVersion version = findPublished(loadVersions(tenant, parentId), versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Tenant %s - No version %d found for mapping %s", tenant, versionNumber, parentId)));
            version.setLabel(label);
            if (version.getSnapshot() != null) {
                version.getSnapshot().setVersionLabel(label);
            }
            updateVersionMO(version);
            log.info("{} - Updated label of version {} of mapping {}", tenant, versionNumber, parentId);
            return version;
        });
    }

    // ========== Deletion ==========

    /**
     * Deletes a published, inactive version. The active version cannot be deleted
     * (FR-17).
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public void deleteVersion(String tenant, String parentId, int versionNumber, int activeVersionNumber) {
        if (versionNumber == activeVersionNumber) {
            throw new IllegalStateException(String.format(
                    "Tenant %s - Version %d of mapping %s is active, cannot be deleted", tenant, versionNumber,
                    parentId));
        }
        subscriptionsService.runForTenant(tenant, () -> {
            MappingVersion version = findPublished(loadVersions(tenant, parentId), versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Tenant %s - No version %d found for mapping %s", tenant, versionNumber, parentId)));
            deleteVersionMO(version);
            log.info("{} - Deleted version {} of mapping {}", tenant, versionNumber, parentId);
        });
    }

    /**
     * Deletes all version records (published and draft) of a mapping line. Used
     * when the mapping line itself is deleted so its child versions do not leak
     * (FR-18).
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public void deleteAllVersions(String tenant, String parentId) {
        subscriptionsService.runForTenant(tenant, () -> {
            List<MappingVersion> all = loadVersions(tenant, parentId);
            all.forEach(this::deleteVersionMO);
            if (!all.isEmpty()) {
                log.info("{} - Deleted {} version record(s) of mapping {}", tenant, all.size(), parentId);
            }
        });
    }

    // ========== Retention ==========

    /**
     * Prunes a mapping line down to the configured retention limit, deleting the
     * oldest published versions first. The active version is never pruned, even if
     * it falls outside the retention window (FR-19).
     *
     * @param parentId the runnable mapping's managed-object id
     */
    public void pruneVersions(String tenant, String parentId, int activeVersionNumber) {
        subscriptionsService.runForTenant(tenant,
                () -> prune(tenant, parentId, activeVersionNumber, loadVersions(tenant, parentId)));
    }

    /**
     * Prunes from an already-loaded list of versions, avoiding a redundant child
     * lookup. Callers must already hold the tenant scope.
     */
    private void prune(String tenant, String parentId, int activeVersionNumber, List<MappingVersion> loaded) {
        int retention = retention(tenant);
        List<MappingVersion> published = loaded.stream()
                .filter(v -> !v.isDraft())
                .sorted(Comparator.comparingInt(MappingVersion::getVersionNumber))
                .collect(Collectors.toList());

        if (published.size() <= retention) {
            return;
        }

        // Keep the newest `retention` versions; everything older is a deletion candidate.
        int windowStart = published.size() - retention;
        List<MappingVersion> candidates = published.subList(0, windowStart);
        for (MappingVersion v : candidates) {
            if (v.getVersionNumber() == activeVersionNumber) {
                continue; // never prune the active version
            }
            deleteVersionMO(v);
            log.info("{} - Pruned version {} of mapping {} (retention {})", tenant, v.getVersionNumber(),
                    parentId, retention);
        }
    }

    // ========== Backfill ==========

    /**
     * Ensures the given runnable mapping has at least one version record, creating
     * a v1 record from its current snapshot if none exist. Idempotent: a no-op when
     * any version record already exists for the line (NFR-1a).
     *
     * @return the existing-or-newly-created version record for the line's current
     *         version, or {@code null} if the mapping has no id
     */
    public MappingVersion ensureBackfilled(String tenant, Mapping runnable) {
        String parentId = runnable.getId();
        if (parentId == null || runnable.getIdentifier() == null) {
            log.warn("{} - Cannot backfill a mapping without an id/identifier [{}]", tenant, runnable.getId());
            return null;
        }
        return subscriptionsService.callForTenant(tenant, () -> {
            List<MappingVersion> existing = loadVersions(tenant, parentId);
            if (!existing.isEmpty()) {
                return existing.stream()
                        .filter(v -> v.getVersionNumber() == runnable.getVersionNumber() && !v.isDraft())
                        .findFirst()
                        .orElse(existing.get(0));
            }

            int versionNumber = runnable.getVersionNumber() > 0 ? runnable.getVersionNumber() : 1;
            Mapping snapshot = copyOf(runnable);
            snapshot.setVersionNumber(versionNumber);

            MappingVersion version = MappingVersion.builder()
                    .identifier(runnable.getIdentifier())
                    .versionNumber(versionNumber)
                    .snapshot(snapshot)
                    .isDraft(false)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .label(runnable.getVersionLabel())
                    .build();

            MappingVersion persisted = persistNewVersion(parentId, version);
            log.info("{} - Backfilled version {} for legacy mapping {} [{}]", tenant, versionNumber, parentId,
                    persisted.getId());
            return persisted;
        });
    }

    // ========== Internal helpers ==========

    /**
     * Loads all version records (published and draft) for one mapping line by
     * reading the child additions of its runnable managed object. Bounded by the
     * line's version count (retention), with no tenant-wide scan.
     */
    private List<MappingVersion> loadVersions(String tenant, String parentId) {
        List<ManagedObjectRepresentation> children = inventoryApi.getChildAdditions(GId.asGId(parentId), false);
        return versionRepository.findAll(tenant, children);
    }

    private static int nextVersionNumber(List<MappingVersion> existing) {
        return existing.stream()
                .filter(v -> !v.isDraft())
                .mapToInt(MappingVersion::getVersionNumber)
                .max()
                .orElse(0) + 1;
    }

    private static Optional<MappingVersion> findPublished(List<MappingVersion> versions, int versionNumber) {
        return versions.stream()
                .filter(v -> !v.isDraft() && v.getVersionNumber() == versionNumber)
                .findFirst();
    }

    private static String versionName(MappingVersion version) {
        return version.isDraft()
                ? version.getIdentifier() + " draft"
                : version.getIdentifier() + " v" + version.getVersionNumber();
    }

    private MappingVersion persistNewVersion(String parentId, MappingVersion version) {
        MappingVersionRepresentation rep = new MappingVersionRepresentation();
        rep.setType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        rep.setName(versionName(version));
        rep.setMappingVersion(version);

        ManagedObjectRepresentation mor = versionRepository.toManagedObject(rep);
        mor = inventoryApi.createChildAddition(GId.asGId(parentId), mor, false);
        version.setId(mor.getId().getValue());
        return version;
    }

    private void updateVersionMO(MappingVersion version) {
        MappingVersionRepresentation rep = new MappingVersionRepresentation();
        rep.setType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        rep.setId(version.getId());
        rep.setName(versionName(version));
        rep.setMappingVersion(version);

        ManagedObjectRepresentation mor = versionRepository.toManagedObject(rep);
        mor.setId(GId.asGId(version.getId()));
        inventoryApi.update(mor, false);
    }

    private void deleteVersionMO(MappingVersion version) {
        inventoryApi.delete(GId.asGId(version.getId()), false);
    }

    /**
     * Deep-copies a mapping so that storing it as an immutable snapshot does not
     * alias the caller's (possibly cache-resident) object. Uses the shared
     * ObjectMapper, the same mechanism used for managed-object conversion.
     */
    private Mapping copyOf(Mapping mapping) {
        return configurationRegistry.getObjectMapper().convertValue(mapping, Mapping.class);
    }

    private int retention(String tenant) {
        ServiceConfiguration config = serviceConfigurationService.getServiceConfiguration(tenant);
        Integer n = config != null ? config.getMappingVersionRetention() : null;
        return (n == null || n < 1) ? DEFAULT_RETENTION : n;
    }

    private String currentUser() {
        try {
            UserCredentials ctx = contextService.getContext();
            if (ctx != null && ctx.getUsername() != null) {
                return ctx.getUsername();
            }
        } catch (Exception e) {
            log.debug("No user context available, attributing to 'system': {}", e.getMessage());
        }
        return "system";
    }
}
