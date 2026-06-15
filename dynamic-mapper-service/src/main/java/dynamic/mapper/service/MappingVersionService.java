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
import dynamic.mapper.model.ValidationError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lifecycle owner for mapping versions (managed object type
 * {@code d11r_mapping_version}). Implements publish, listing, retrieval,
 * label edits, deletion, retention pruning, and lazy backfill of legacy
 * mappings.
 *
 * <p>This service deliberately does NOT resolve which version is currently
 * active - the active version number is supplied by the caller (which owns the
 * runnable {@code d11r_mapping} record). This keeps the service decoupled from
 * {@link MappingService} and independently testable. Draft-record editing and
 * activation wiring are handled in later phases.
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
     * line. Assigns the next version number, validates the snapshot, persists it,
     * and applies retention pruning. The newly published version is not activated
     * by this call.
     *
     * @param mapping             the configuration to freeze (typically the draft)
     * @param label               optional change note
     * @param activeVersionNumber the version number currently active for this line
     *                            (protected from pruning); use 0 if none
     */
    public MappingVersion publish(String tenant, Mapping mapping, String label, int activeVersionNumber) {
        String identifier = mapping.getIdentifier();
        if (identifier == null) {
            throw new IllegalArgumentException(
                    String.format("Tenant %s - Cannot publish a mapping without an identifier", tenant));
        }

        // Validate the snapshot - publish is the commitment point (a draft may be incomplete).
        List<ValidationError> errors = mappingValidator.validate(tenant, mapping, mapping.getId());
        if (!errors.isEmpty()) {
            throw new MappingValidationException(errors);
        }

        return subscriptionsService.callForTenant(tenant, () -> {
            List<MappingVersion> existing = loadVersions(tenant, identifier);
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

            MappingVersion persisted = persistNewVersion(tenant, version);
            log.info("{} - Published version {} of mapping line {} [{}]", tenant, nextVersionNumber,
                    identifier, persisted.getId());

            pruneVersions(tenant, identifier, activeVersionNumber);
            return persisted;
        });
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
     * Retrieves a single published version of a mapping line, or {@code null} if
     * not found.
     */
    public MappingVersion getVersion(String tenant, String identifier, int versionNumber) {
        return subscriptionsService.callForTenant(tenant,
                () -> findPublished(loadVersions(tenant, identifier), versionNumber).orElse(null));
    }

    // ========== Label edit ==========

    /**
     * Updates the change-note label of a published version. The label is the only
     * mutable field of a version (D-3); all other fields are immutable.
     */
    public MappingVersion updateLabel(String tenant, String identifier, int versionNumber, String label) {
        return subscriptionsService.callForTenant(tenant, () -> {
            MappingVersion version = findPublished(loadVersions(tenant, identifier), versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Tenant %s - No version %d found for mapping line %s", tenant, versionNumber, identifier)));
            version.setLabel(label);
            if (version.getSnapshot() != null) {
                version.getSnapshot().setVersionLabel(label);
            }
            updateVersionMO(version);
            log.info("{} - Updated label of version {} of mapping line {}", tenant, versionNumber, identifier);
            return version;
        });
    }

    // ========== Deletion ==========

    /**
     * Deletes a published, inactive version. The active version cannot be deleted
     * (FR-17).
     */
    public void deleteVersion(String tenant, String identifier, int versionNumber, int activeVersionNumber) {
        if (versionNumber == activeVersionNumber) {
            throw new IllegalStateException(String.format(
                    "Tenant %s - Version %d of mapping line %s is active, cannot be deleted", tenant, versionNumber,
                    identifier));
        }
        subscriptionsService.runForTenant(tenant, () -> {
            MappingVersion version = findPublished(loadVersions(tenant, identifier), versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Tenant %s - No version %d found for mapping line %s", tenant, versionNumber, identifier)));
            deleteVersionMO(version);
            log.info("{} - Deleted version {} of mapping line {}", tenant, versionNumber, identifier);
        });
    }

    // ========== Retention ==========

    /**
     * Prunes a mapping line down to the configured retention limit, deleting the
     * oldest published versions first. The active version is never pruned, even if
     * it falls outside the retention window (FR-19).
     */
    public void pruneVersions(String tenant, String identifier, int activeVersionNumber) {
        subscriptionsService.runForTenant(tenant, () -> {
            int retention = retention(tenant);
            List<MappingVersion> published = loadVersions(tenant, identifier).stream()
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
                log.info("{} - Pruned version {} of mapping line {} (retention {})", tenant, v.getVersionNumber(),
                        identifier, retention);
            }
        });
    }

    // ========== Backfill ==========

    /**
     * Ensures the given runnable mapping has at least one version record, creating
     * a v1 record from its current snapshot if none exist. Idempotent: a no-op when
     * any version record already exists for the line (NFR-1a).
     *
     * @return the existing-or-newly-created version record for the line's current
     *         version, or {@code null} if the mapping has no identifier
     */
    public MappingVersion ensureBackfilled(String tenant, Mapping runnable) {
        String identifier = runnable.getIdentifier();
        if (identifier == null) {
            log.warn("{} - Cannot backfill a mapping without an identifier [{}]", tenant, runnable.getId());
            return null;
        }
        return subscriptionsService.callForTenant(tenant, () -> {
            List<MappingVersion> existing = loadVersions(tenant, identifier);
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
                    .identifier(identifier)
                    .versionNumber(versionNumber)
                    .snapshot(snapshot)
                    .isDraft(false)
                    .createdAt(System.currentTimeMillis())
                    .createdBy(currentUser())
                    .label(runnable.getVersionLabel())
                    .build();

            MappingVersion persisted = persistNewVersion(tenant, version);
            log.info("{} - Backfilled version {} for legacy mapping line {} [{}]", tenant, versionNumber, identifier,
                    persisted.getId());
            return persisted;
        });
    }

    // ========== Internal helpers ==========

    /**
     * Loads all version records (published and draft) for one mapping line.
     * Queries by type and filters by identifier in memory; the number of version
     * managed objects is bounded by the retention policy.
     */
    private List<MappingVersion> loadVersions(String tenant, String identifier) {
        InventoryFilter filter = new InventoryFilter().byType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        ManagedObjectCollection moc = inventoryApi.getManagedObjectsByFilter(filter, false);
        return versionRepository.findAll(tenant, moc).stream()
                .filter(v -> identifier.equals(v.getIdentifier()))
                .collect(Collectors.toList());
    }

    private static int nextVersionNumber(List<MappingVersion> existing) {
        return existing.stream()
                .filter(v -> !v.isDraft())
                .mapToInt(MappingVersion::getVersionNumber)
                .max()
                .orElse(0) + 1;
    }

    private static java.util.Optional<MappingVersion> findPublished(List<MappingVersion> versions, int versionNumber) {
        return versions.stream()
                .filter(v -> !v.isDraft() && v.getVersionNumber() == versionNumber)
                .findFirst();
    }

    private MappingVersion persistNewVersion(String tenant, MappingVersion version) {
        MappingVersionRepresentation rep = new MappingVersionRepresentation();
        rep.setType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        rep.setName(version.getIdentifier() + " v" + version.getVersionNumber());
        rep.setMappingVersion(version);

        ManagedObjectRepresentation mor = versionRepository.toManagedObject(rep);
        mor = inventoryApi.create(mor, false);
        version.setId(mor.getId().getValue());
        return version;
    }

    private void updateVersionMO(MappingVersion version) {
        MappingVersionRepresentation rep = new MappingVersionRepresentation();
        rep.setType(MappingVersionRepresentation.MAPPING_VERSION_TYPE);
        rep.setId(version.getId());
        rep.setName(version.getIdentifier() + " v" + version.getVersionNumber());
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
