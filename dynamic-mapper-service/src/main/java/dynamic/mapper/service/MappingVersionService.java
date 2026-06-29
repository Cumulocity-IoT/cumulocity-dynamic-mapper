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
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionRepresentation;
import dynamic.mapper.model.SemVer;
import dynamic.mapper.model.ValidationError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle owner for mapping versions (managed object type
 * {@code d11r_mapping_version}). Implements publish, listing, retrieval,
 * note edits, deletion, retention pruning, and lazy backfill of legacy
 * mappings.
 *
 * <p>Version records are persisted as standalone managed objects of type
 * {@code d11r_mapping_version}, each carrying the owning line's functional
 * {@code identifier}. Lookups query by type and filter by that identifier — a
 * plain, reliable inventory query (the earlier child-addition storage was
 * dropped because the child-addition read path was not reliable against the
 * platform). The active version number is supplied by the caller (which owns the
 * runnable {@code d11r_mapping} record), keeping this service decoupled from
 * {@link MappingService} and independently testable.
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
     * line. Uses the caller-supplied semver string, validates the snapshot and
     * uniqueness, persists the version, and applies retention pruning. The newly
     * published version is not activated by this call.
     *
     * @param mapping       the configuration to freeze (typically the draft)
     * @param version       the semver string for the new version (MAJOR.MINOR.PATCH)
     * @param note          optional change note
     * @param activeVersion the version string currently active for this line
     *                      (protected from pruning); null if none
     */
    public MappingVersion publish(String tenant, Mapping mapping, String version, String note, String activeVersion) {
        String identifier = mapping.getIdentifier();
        if (identifier == null) {
            throw new IllegalArgumentException(
                    String.format("Tenant %s - Cannot publish a mapping without an identifier", tenant));
        }
        if (!SemVer.isValid(version)) {
            throw new IllegalArgumentException(
                    String.format("Tenant %s - Invalid semver '%s': expected MAJOR.MINOR.PATCH", tenant, version));
        }

        // Validate the snapshot - publish is the commitment point (a draft may be incomplete).
        List<ValidationError> errors = mappingValidator.validate(tenant, mapping, mapping.getId());
        if (!errors.isEmpty()) {
            throw new MappingValidationException(errors);
        }

        return subscriptionsService.callForTenant(tenant, () -> {
            List<MappingVersion> existing = loadVersions(tenant, identifier);

            // Uniqueness check: each version label is immutable once published (semver contract).
            boolean alreadyExists = existing.stream()
                    .filter(v -> !v.isDraft())
                    .anyMatch(v -> version.equals(v.getVersion()));
            if (alreadyExists) {
                throw new IllegalArgumentException(
                        String.format("Tenant %s - Version %s already exists for mapping line %s",
                                tenant, version, identifier));
            }

            Mapping snapshot = copyOf(mapping);
            snapshot.setVersion(version);
            snapshot.setVersionNote(note);
            snapshot.setDraftDirty(false);

            MappingVersion mv = MappingVersion.builder()
                    .identifier(identifier)
                    .version(version)
                    .snapshot(snapshot)
                    .isDraft(false)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .note(note)
                    .build();

            MappingVersion persisted = persistNewVersion(tenant, mv);
            log.info("{} - Published version {} of mapping line {} [{}]", tenant, version,
                    identifier, persisted.getId());

            // Prune using the list we already loaded plus the just-persisted version,
            // avoiding a second query on the hot publish path.
            List<MappingVersion> current = new ArrayList<>(existing);
            current.add(persisted);
            prune(tenant, identifier, activeVersion, current);
            return persisted;
        });
    }

    /**
     * Suggests the next patch, minor, and major versions based on the highest
     * published version for the mapping line. Returns {@code "1.0.0"} suggestions
     * when no versions exist yet.
     *
     * @return a three-element array: [patch, minor, major] suggestions
     */
    public String[] suggestNextVersions(String tenant, String identifier) {
        return subscriptionsService.callForTenant(tenant, () -> {
            SemVer highest = loadVersions(tenant, identifier).stream()
                    .filter(v -> !v.isDraft() && SemVer.isValid(v.getVersion()))
                    .map(v -> SemVer.parse(v.getVersion()))
                    .max(SemVer::compareTo)
                    .orElse(new SemVer(0, 9, 9)); // so patch → 1.0.0, minor → 0.10.0 → normalised
            // When no versions exist at all, suggest starting at 1.0.0
            if (highest.equals(new SemVer(0, 9, 9))) {
                return new String[] { "1.0.0", "1.0.0", "1.0.0" };
            }
            return new String[] {
                highest.bumpPatch().toString(),
                highest.bumpMinor().toString(),
                highest.bumpMajor().toString()
            };
        });
    }

    // ========== Draft editing ==========

    /**
     * Returns the single draft (mutable working copy) of a mapping line, or
     * {@code null} if the line has no draft.
     */
    public MappingVersion getDraft(String tenant, String identifier) {
        return subscriptionsService.callForTenant(tenant,
                () -> loadVersions(tenant, identifier).stream()
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
     */
    public MappingVersion saveDraft(String tenant, String identifier, Mapping edits) {
        return subscriptionsService.callForTenant(tenant, () -> {
            MappingVersion existingDraft = loadVersions(tenant, identifier).stream()
                    .filter(MappingVersion::isDraft)
                    .findFirst()
                    .orElse(null);

            if (existingDraft != null && existingDraft.getSnapshot() != null
                    && edits.getLastUpdate() != 0
                    && edits.getLastUpdate() != existingDraft.getSnapshot().getLastUpdate()) {
                throw new IllegalStateException(String.format(
                        "Tenant %s - Draft of mapping line %s was modified concurrently; reload before saving",
                        tenant, identifier));
            }

            Mapping snapshot = copyOf(edits);
            snapshot.setIdentifier(identifier);
            snapshot.setDraftDirty(true);
            snapshot.setLastUpdate(System.currentTimeMillis());

            MappingVersion draft = MappingVersion.builder()
                    .id(existingDraft != null ? existingDraft.getId() : null)
                    .identifier(identifier)
                    .version(null) // drafts have no version label
                    .snapshot(snapshot)
                    .isDraft(true)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .build();

            if (existingDraft != null) {
                updateVersionMO(draft);
                log.info("{} - Updated draft of mapping line {}", tenant, identifier);
            } else {
                persistNewVersion(tenant, draft);
                log.info("{} - Created draft of mapping line {} [{}]", tenant, identifier, draft.getId());
            }
            return draft;
        });
    }

    /**
     * Removes the draft of a mapping line, if any. Called after a draft is
     * published (its content now lives in an immutable version) or when the draft
     * is discarded. No-op when there is no draft.
     */
    public void deleteDraft(String tenant, String identifier) {
        subscriptionsService.runForTenant(tenant, () -> loadVersions(tenant, identifier).stream()
                .filter(MappingVersion::isDraft)
                .findFirst()
                .ifPresent(draft -> {
                    deleteVersionMO(draft);
                    log.info("{} - Deleted draft of mapping line {}", tenant, identifier);
                }));
    }

    // ========== Listing & retrieval ==========

    /**
     * Lists all published versions of a mapping line, sorted ascending by version
     * number. Drafts are excluded.
     */
    public List<MappingVersion> listVersions(String tenant, String identifier) {
        return subscriptionsService.callForTenant(tenant,
                () -> loadVersions(tenant, identifier).stream()
                        .filter(v -> !v.isDraft())
                        .collect(Collectors.toList()));
    }

    /**
     * Returns the number of published (non-draft) versions for each of the given
     * mapping line identifiers in a single inventory scan — avoiding the N-query
     * problem when counting versions for many mappings at once.
     *
     * @param tenant      the tenant scope
     * @param identifiers functional identifiers of the mapping lines to count
     * @return map of identifier → published version count; identifiers with no
     *         versions are present with count 0
     */
    public Map<String, Long> countVersionsForIdentifiers(String tenant, Set<String> identifiers) {
        if (identifiers.isEmpty()) {
            return new java.util.HashMap<>();
        }
        return subscriptionsService.callForTenant(tenant, () -> {
            InventoryFilter filter = new InventoryFilter().byType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
            ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(filter, false);
            Map<String, Long> counts = versionRepository.findAll(tenant, moc).stream()
                    .filter(v -> !v.isDraft() && identifiers.contains(v.getIdentifier()))
                    .collect(Collectors.groupingBy(MappingVersion::getIdentifier, Collectors.counting()));
            // Ensure every requested identifier is present, defaulting to 0
            identifiers.forEach(id -> counts.putIfAbsent(id, 0L));
            return counts;
        });
    }

    /**
     * Retrieves a single published version of a mapping line, or {@code null} if
     * not found.
     */
    public MappingVersion getVersion(String tenant, String identifier, String version) {
        return subscriptionsService.callForTenant(tenant,
                () -> findPublished(loadVersions(tenant, identifier), version).orElse(null));
    }

    // ========== Note edit ==========

    /**
     * Updates the change note of a published version. The note is the only
     * mutable field of a version (D-3); all other fields are immutable.
     */
    public MappingVersion updateNote(String tenant, String identifier, String version, String note) {
        return subscriptionsService.callForTenant(tenant, () -> {
            MappingVersion mv = findPublished(loadVersions(tenant, identifier), version)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Tenant %s - No version %s found for mapping line %s", tenant, version, identifier)));
            mv.setNote(note);
            if (mv.getSnapshot() != null) {
                mv.getSnapshot().setVersionNote(note);
            }
            updateVersionMO(mv);
            log.info("{} - Updated note of version {} of mapping line {}", tenant, version, identifier);
            return mv;
        });
    }

    // ========== Deletion ==========

    /**
     * Deletes a published, inactive version. The active version cannot be deleted
     * (FR-17).
     */
    public void deleteVersion(String tenant, String identifier, String version, String activeVersion) {
        if (version != null && version.equals(activeVersion)) {
            throw new IllegalStateException(String.format(
                    "Tenant %s - Version %s of mapping line %s is active, cannot be deleted", tenant, version,
                    identifier));
        }
        subscriptionsService.runForTenant(tenant, () -> {
            MappingVersion mv = findPublished(loadVersions(tenant, identifier), version)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Tenant %s - No version %s found for mapping line %s", tenant, version, identifier)));
            deleteVersionMO(mv);
            log.info("{} - Deleted version {} of mapping line {}", tenant, version, identifier);
        });
    }

    /**
     * Deletes all version records (published and draft) of a mapping line. Used
     * when the mapping line itself is deleted so its version records do not leak
     * (FR-18).
     */
    public void deleteAllVersions(String tenant, String identifier) {
        subscriptionsService.runForTenant(tenant, () -> {
            List<MappingVersion> all = loadVersions(tenant, identifier);
            all.forEach(this::deleteVersionMO);
            if (!all.isEmpty()) {
                log.info("{} - Deleted {} version record(s) of mapping line {}", tenant, all.size(), identifier);
            }
        });
    }

    // ========== Retention ==========

    /**
     * Prunes a mapping line down to the configured retention limit, deleting the
     * oldest published versions first (by publish date). The active version is
     * never pruned, even if it falls outside the retention window (FR-19).
     */
    public void pruneVersions(String tenant, String identifier, String activeVersion) {
        subscriptionsService.runForTenant(tenant,
                () -> prune(tenant, identifier, activeVersion, loadVersions(tenant, identifier)));
    }

    /**
     * Prunes from an already-loaded list of versions, avoiding a redundant query.
     * Callers must already hold the tenant scope.
     */
    private void prune(String tenant, String identifier, String activeVersion, List<MappingVersion> loaded) {
        int retention = retention(tenant);
        List<MappingVersion> published = loaded.stream()
                .filter(v -> !v.isDraft())
                .sorted(Comparator.comparingLong(MappingVersion::getCreatedAt))
                .collect(Collectors.toList());

        if (published.size() <= retention) {
            return;
        }

        // Keep the newest `retention` versions; everything older is a deletion candidate.
        int windowStart = published.size() - retention;
        List<MappingVersion> candidates = published.subList(0, windowStart);
        for (MappingVersion v : candidates) {
            if (v.getVersion() != null && v.getVersion().equals(activeVersion)) {
                continue; // never prune the active version
            }
            deleteVersionMO(v);
            log.info("{} - Pruned version {} of mapping line {} (retention {})", tenant, v.getVersion(),
                    identifier, retention);
        }
    }

    // ========== Backfill ==========

    /**
     * Ensures the given runnable mapping has at least one published version,
     * creating a {@code 1.0.0} record from its current snapshot if none exist.
     * Idempotent: a no-op when any published version already exists for the line
     * (NFR-1a). Handles legacy integer versions stored as strings by migrating them
     * to MAJOR.0.0 format on first access. A draft alone does not count.
     *
     * @return the existing-or-newly-created version record, or {@code null} if the
     *         mapping has no identifier
     */
    public MappingVersion ensureBackfilled(String tenant, Mapping runnable) {
        String identifier = runnable.getIdentifier();
        if (identifier == null) {
            log.warn("{} - Cannot backfill a mapping without an identifier [{}]", tenant, runnable.getId());
            return null;
        }
        return subscriptionsService.callForTenant(tenant, () -> {
            List<MappingVersion> published = loadVersions(tenant, identifier).stream()
                    .filter(v -> !v.isDraft())
                    .toList();
            if (!published.isEmpty()) {
                String activeVer = runnable.getVersion();
                return published.stream()
                        .filter(v -> activeVer != null && activeVer.equals(v.getVersion()))
                        .findFirst()
                        .orElse(published.get(0));
            }

            // Determine the version label: use the runnable's version if it looks like
            // valid semver; migrate a bare integer (e.g. "3") to "3.0.0"; default 1.0.0.
            String ver = resolveBackfillVersion(runnable.getVersion());
            Mapping snapshot = copyOf(runnable);
            snapshot.setVersion(ver);

            MappingVersion version = MappingVersion.builder()
                    .identifier(identifier)
                    .version(ver)
                    .snapshot(snapshot)
                    .isDraft(false)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .note(runnable.getVersionNote())
                    .build();

            MappingVersion persisted = persistNewVersion(tenant, version);
            log.info("{} - Backfilled version {} for legacy mapping line {} [{}]", tenant, ver, identifier,
                    persisted.getId());
            return persisted;
        });
    }

    /** Derives a valid semver string from a raw version stored on an older mapping. */
    private static String resolveBackfillVersion(String raw) {
        if (SemVer.isValid(raw)) {
            return raw;
        }
        if (raw != null) {
            try {
                int n = Integer.parseInt(raw.trim());
                if (n > 0) return n + ".0.0";
            } catch (NumberFormatException ignored) { }
        }
        return SemVer.INITIAL.toString();
    }

    // ========== Internal helpers ==========

    /**
     * Loads all version records (published and draft) for one mapping line by
     * querying for {@code d11r_mapping_version} managed objects and filtering by
     * the owning line's functional identifier.
     */
    private List<MappingVersion> loadVersions(String tenant, String identifier) {
        InventoryFilter filter = new InventoryFilter().byType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(filter, false);
        return versionRepository.findAll(tenant, moc).stream()
                .filter(v -> identifier.equals(v.getIdentifier()))
                .collect(Collectors.toList());
    }

    private static Optional<MappingVersion> findPublished(List<MappingVersion> versions, String version) {
        return versions.stream()
                .filter(v -> !v.isDraft() && version != null && version.equals(v.getVersion()))
                .findFirst();
    }

    private static String versionName(MappingVersion version) {
        return version.isDraft()
                ? version.getIdentifier() + " draft"
                : version.getIdentifier() + " v" + version.getVersion();
    }

    private MappingVersion persistNewVersion(String tenant, MappingVersion version) {
        MappingVersionRepresentation rep = new MappingVersionRepresentation();
        rep.setType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        rep.setName(versionName(version));
        rep.setMappingVersion(version);

        ManagedObjectRepresentation mor = versionRepository.toManagedObject(rep);
        mor = inventoryApi.create(mor, false);
        version.setId(mor.getId().getValue());

        // Register the version as a child addition of the runnable mapping MO so the
        // parent -> versions relationship is navigable in the inventory. Best-effort:
        // the version is found by query regardless, so a failed link must not fail the
        // operation. The snapshot's id is the parent (runnable) mapping MO id.
        String parentId = version.getSnapshot() != null ? version.getSnapshot().getId() : null;
        if (parentId != null && !parentId.equals(version.getId())) {
            try {
                inventoryApi.addChildAddition(GId.asGId(parentId), mor.getId(), false);
            } catch (Exception e) {
                log.warn("{} - Could not register version {} [{}] as child addition of mapping {}: {}",
                        tenant, version.getVersion(), version.getId(), parentId, e.getMessage());
            }
        }
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
