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

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.Direction;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.MappingRepresentation;
import dynamic.mapper.processor.model.MappingType;
import dynamic.mapper.processor.model.TransformationType;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Repository responsible for CRUD operations on mappings in the inventory
 */
@Slf4j
@Repository
public class MappingRepository {

    private final ConfigurationRegistry configurationRegistry;
    private final MappingService mappingService;

    // Tracks moIds for which a loading warning has already been logged this session,
    // to prevent flooding the log on every mapping reload.
    // Structure: <"tenant:moId">
    private final Set<String> reportedLoadingWarnings = ConcurrentHashMap.newKeySet();

    public MappingRepository(ConfigurationRegistry configurationRegistry,
                            @Lazy MappingService mappingService) {
        this.configurationRegistry = configurationRegistry;
        this.mappingService = mappingService;
    }

    /**
     * Retrieves a single mapping by ID
     * NOTE: This is a lower-level method that expects inventoryApi to be called from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public Optional<Mapping> findById(String tenant, String id, ManagedObjectRepresentation mo) {
        try {
            if (mo == null) {
                return Optional.empty();
            }

            MappingRepresentation mappingMO = toMappingObject(mo);
            Mapping mapping = mappingMO.getC8yMQTTMapping();
            if(mapping == null) {
                log.warn("{} - Mapping with id {} seems to be outdated. Please migrate it to a newer version: https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/blob/main/resources/script/mgmt/dm.sh", tenant, id);
                return Optional.empty();
            }
            mapping.setId(mappingMO.getId());

            log.debug("{} - Found mapping: {}", tenant, mapping.getId());
            return Optional.of(mapping);

        } catch (IllegalArgumentException e) {
            log.warn("{} - Failed to convert MO {} to mapping: {}", tenant, id,
                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves all mappings, optionally filtered by direction
     * NOTE: This is a lower-level method that expects inventoryApi to be called from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public List<Mapping> findAll(String tenant, Direction direction, ManagedObjectCollection moc) {
        // Phase 1: pure conversion + in-memory migration. The inventory pages are
        // fully drained into a list here; no inventory writes happen while iterating.
        List<LoadResult> loaded = StreamSupport.stream(moc.get().allPages().spliterator(), false)
                .map(mo -> convertToMapping(tenant, mo))
                .collect(Collectors.toList());

        // Phase 2: persist migrations and emit notifications, now that the page
        // iteration has completed. This avoids re-entrant inventory access (via
        // updateMapping -> callForTenant) while the allPages() stream is still live.
        for (LoadResult result : loaded) {
            if (result.migration() != null) {
                applyMigration(tenant, result.mapping(), result.source(), result.migration());
            }
            if (result.loadingError() != null) {
                sendLoadingErrorSafe(tenant, result.source(), result.loadingError());
            }
        }

        List<Mapping> mappings = loaded.stream()
                .filter(result -> result.include() && result.mapping() != null)
                .map(LoadResult::mapping)
                .filter(mapping -> shouldIncludeMapping(mapping, direction))
                .collect(Collectors.toList());

        log.debug("{} - Loaded {} mappings for direction: {}", tenant, mappings.size(), direction);
        return mappings;
    }

    /**
     * Creates a new mapping - only handles conversion, actual persistence is in MappingService
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public Mapping prepareForCreate(String tenant, Mapping mapping) {
        // Normalize deprecated mappingType/transformationType combinations in memory
        // before persisting. The MO is not yet known, so no notification is emitted.
        migrateMapping(tenant, mapping, null);

        // A newly created mapping always starts at v1, regardless of the source
        // (e.g. a duplicated mapping must not inherit the original's version history).
        mapping.setVersion(null);
        mapping.setDraftDirty(false);
        mapping.setLastUpdate(System.currentTimeMillis());
        return mapping;
    }

    /**
     * Prepares a mapping for update - only handles conversion logic
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public Mapping prepareForUpdate(String tenant, Mapping mapping,
            boolean allowUpdateWhenActive, boolean ignoreValidation) {
        // Validation happens in service layer
        if (!allowUpdateWhenActive && mapping.getActive()) {
            throw new IllegalStateException(
                    String.format("Tenant %s - Mapping %s is active, deactivate before updating!",
                            tenant, mapping.getId()));
        }

        // Normalize deprecated mappingType/transformationType combinations in memory
        // before persisting. The MO is already persisted, so no notification is emitted.
        migrateMapping(tenant, mapping, null);

        mapping.setLastUpdate(System.currentTimeMillis());
        return mapping;
    }

    /**
     * Deletes a mapping - only validates, actual deletion is in MappingService
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     * with proper tenant scope activated via subscriptionsService.callForTenant()
     */
    public void prepareForDelete(String tenant, String id, Mapping mapping) {
        if (mapping == null) {
            log.warn("{} - Mapping not found for deletion: {}", tenant, id);
            return;
        }

        if (mapping.getActive()) {
            throw new IllegalStateException(
                    String.format("Tenant %s - Mapping %s is active, deactivate before deleting!", tenant, id));
        }
    }

    /**
     * Batch update multiple mappings - only prepares data, actual persistence is in MappingService
     * NOTE: This is a lower-level method that expects inventoryApi calls from MappingService
     */
    public void prepareBatchForUpdate(String tenant, List<Mapping> mappings) {
        mappings.forEach(mapping -> mapping.setLastUpdate(System.currentTimeMillis()));
        log.debug("{} - Prepared {} mappings for batch update", tenant, mappings.size());
    }

    // Helper methods

    /**
     * Applies all automatic in-memory migrations, normalizing deprecated
     * mappingType / transformationType combinations to current standards. Only the
     * in-memory object is mutated; persistence and migration notifications are the
     * caller's responsibility (see {@link #applyMigration}). At most one migration
     * applies to a given mapping.
     *
     * @param mo the source managed object (may be {@code null} on the create/update
     *           path, where the descriptor message is unused)
     * @return a descriptor of the migration that was applied, or {@code null} if the
     *         mapping already conforms
     */
    private Migration migrateMapping(String tenant, Mapping mapping, ManagedObjectRepresentation mo) {
        String moId = moId(mo);

        // Migrate deprecated CODE_BASED mappings to JSON with SMART_FUNCTION transformation
        if (MappingType.CODE_BASED.equals(mapping.getMappingType())) {
            log.info("{} - Migrating deprecated CODE_BASED mapping {} to JSON with SMART_FUNCTION transformation",
                    tenant, moId);
            mapping.setMappingType(MappingType.JSON);
            mapping.setTransformationType(TransformationType.SMART_FUNCTION);
            return new Migration(String.format(
                    "Mapping %s was automatically migrated from deprecated CODE_BASED to JSON with SMART_FUNCTION transformation",
                    moId), MigrationNotice.LOADING_ERROR);
        }

        // Migrate legacy JSON mappings without transformationType to JSONATA
        if (MappingType.JSON.equals(mapping.getMappingType()) &&
                (mapping.getTransformationType() == null
                        || TransformationType.DEFAULT.equals(mapping.getTransformationType()))) {
            log.info("{} - Migrating legacy JSON mapping {} to JSONATA transformation", tenant, moId);
            mapping.setTransformationType(TransformationType.JSONATA);
            return new Migration(String.format(
                    "Mapping %s [%s] was automatically migrated: transformationType set to JSONATA for legacy JSON mapping",
                    mapping.getName(), moId), MigrationNotice.OPERATION_EVENT);
        }

        // Migrate deprecated EXTENSION_JAVA mappingType to ANY_PAYLOAD + EXTENSION_JAVA transformation
        if (MappingType.EXTENSION_JAVA.equals(mapping.getMappingType())) {
            log.info("{} - Migrating deprecated EXTENSION_JAVA mappingType for mapping {} to ANY_PAYLOAD with EXTENSION_JAVA transformation",
                    tenant, moId);
            mapping.setMappingType(MappingType.ANY_PAYLOAD);
            mapping.setTransformationType(TransformationType.EXTENSION_JAVA);
            return new Migration(String.format(
                    "Mapping %s [%s] was automatically migrated: mappingType EXTENSION_JAVA → ANY_PAYLOAD, transformationType → EXTENSION_JAVA",
                    mapping.getName(), moId), MigrationNotice.OPERATION_EVENT);
        }

        // Migrate non-JSONATA mappings: targetTemplate is only used by JSONATA; reset it to "{}"
        // for all other transformation types so it no longer contains stale C8Y sample payloads.
        if (mapping.getTransformationType() != null
                && !TransformationType.JSONATA.equals(mapping.getTransformationType())
                && !TransformationType.DEFAULT.equals(mapping.getTransformationType())
                && (mapping.getTargetTemplate() == null || !mapping.getTargetTemplate().equals("{}"))) {
            log.info("{} - Migrating mapping {} ({}): resetting targetTemplate to '{}' for transformationType {}",
                    tenant, moId, mapping.getName(), mapping.getTransformationType());
            mapping.setTargetTemplate("{}");
            return new Migration(String.format(
                    "Mapping %s [%s] was automatically migrated: targetTemplate reset to {} for transformationType %s",
                    mapping.getName(), moId, mapping.getTransformationType()), MigrationNotice.OPERATION_EVENT);
        }

        return null;
    }

    /**
     * Converts a ManagedObjectRepresentation into a Mapping and applies in-memory
     * migrations. Performs no inventory writes or notifications: any required
     * persistence / notification is captured in the returned {@link LoadResult} and
     * carried out by {@link #findAll} after the page iteration has completed.
     */
    private LoadResult convertToMapping(String tenant, ManagedObjectRepresentation mo) {
        try {
            MappingRepresentation mappingMO = toMappingObject(mo);
            Mapping mapping = mappingMO.getC8yMQTTMapping();
            if (mapping == null) {
                log.warn("{} - This mapping with id {} seems to be outdated. Please migrate it to a newer version: https://github.com/Cumulocity-IoT/cumulocity-dynamic-mapper/blob/main/resources/script/mgmt/dm.sh",
                        tenant, mappingMO.getId());
                return LoadResult.skip(mo, null);
            }
            mapping.setId(mappingMO.getId());

            Migration migration = migrateMapping(tenant, mapping, mo);

            if (Direction.INBOUND.equals(mapping.getDirection()) && mapping.getMappingTopic() == null) {
                log.warn("{} - Mapping {} has no mappingTopic, skipping", tenant, mapping.getId());
                return LoadResult.exclude(mapping, mo, migration, null);
            }

            if (TransformationType.EXTENSION_JAVA.equals(mapping.getTransformationType())
                    && mapping.getExtension() == null) {
                String errorMsg = String.format(
                        "Mapping %s [%s] has transformationType EXTENSION_JAVA but no extension defined - skipping",
                        mapping.getName(), moId(mo));
                // De-duplicate the warning per MO; only the first occurrence notifies.
                if (reportedLoadingWarnings.add(tenant + ":" + moId(mo))) {
                    log.warn("{} - {}", tenant, errorMsg);
                    return LoadResult.exclude(mapping, mo, migration, errorMsg);
                }
                log.debug("{} - {}", tenant, errorMsg);
                return LoadResult.exclude(mapping, mo, migration, null);
            }

            return LoadResult.include(mapping, mo, migration);
        } catch (IllegalArgumentException e) {
            String exceptionMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            String detailedErrorMsg = String.format("Failed to convert MO %s to mapping in tenant %s: %s",
                    moId(mo), tenant, exceptionMsg);
            log.warn("{} - Failed to convert MO {} to mapping: {}", tenant, moId(mo), exceptionMsg);
            return LoadResult.skip(mo, detailedErrorMsg);
        }
    }

    /**
     * Persists a migrated mapping and emits its migration notification. Invoked from
     * {@link #findAll} after inventory iteration, so the write does not run
     * re-entrantly while the mapping pages are being streamed.
     */
    private void applyMigration(String tenant, Mapping mapping, ManagedObjectRepresentation mo, Migration migration) {
        try {
            mappingService.updateMapping(tenant, mapping, true, true);
            switch (migration.notice()) {
                case LOADING_ERROR -> mappingService.sendMappingLoadingError(tenant, mo, migration.message());
                case OPERATION_EVENT -> configurationRegistry.getC8yAgent().createLoggingEvent(
                        migration.message(), LoggingEventType.MAPPING_MIGRATION_EVENT_TYPE, DateTime.now(), tenant, null);
            }
        } catch (Exception updateEx) {
            log.warn("{} - Failed to persist migrated mapping {}: {}", tenant, moId(mo), updateEx.getMessage());
        }
    }

    private void sendLoadingErrorSafe(String tenant, ManagedObjectRepresentation mo, String message) {
        try {
            mappingService.sendMappingLoadingError(tenant, mo, message);
        } catch (Exception notifyEx) {
            log.warn("{} - Failed to send mapping loading error for MO {}: {}", tenant, moId(mo), notifyEx.getMessage());
        }
    }

    private static String moId(ManagedObjectRepresentation mo) {
        return mo != null && mo.getId() != null ? mo.getId().getValue() : "unknown";
    }

    /** How a migration announces itself once persisted. */
    private enum MigrationNotice { LOADING_ERROR, OPERATION_EVENT }

    /** An in-memory migration that still needs to be persisted and announced. */
    private record Migration(String message, MigrationNotice notice) {
    }

    /**
     * Outcome of converting one managed object: the (possibly migrated) mapping,
     * whether it should be included in the result, and any deferred side-effects
     * (migration persistence, loading-error notification).
     */
    private record LoadResult(Mapping mapping, ManagedObjectRepresentation source, boolean include,
            Migration migration, String loadingError) {

        static LoadResult include(Mapping mapping, ManagedObjectRepresentation source, Migration migration) {
            return new LoadResult(mapping, source, true, migration, null);
        }

        /** Excluded from the result, but the mapping may still carry a migration to persist. */
        static LoadResult exclude(Mapping mapping, ManagedObjectRepresentation source, Migration migration,
                String loadingError) {
            return new LoadResult(mapping, source, false, migration, loadingError);
        }

        /** Conversion produced no usable mapping; only an optional loading-error notification. */
        static LoadResult skip(ManagedObjectRepresentation source, String loadingError) {
            return new LoadResult(null, source, false, null, loadingError);
        }
    }

    /**
     * Clears the de-duplicated loading-warning markers for a tenant. Called on
     * tenant teardown so {@code reportedLoadingWarnings} does not grow unbounded
     * across tenant churn.
     */
    public void clearReportedWarnings(String tenant) {
        if (tenant == null) {
            return;
        }
        // Keys are "tenant:moId"; the trailing colon keeps the prefix unambiguous
        // (e.g. "t1:" does not match "t10:...").
        String prefix = tenant + ":";
        reportedLoadingWarnings.removeIf(key -> key.startsWith(prefix));
    }

    private boolean shouldIncludeMapping(Mapping mapping, Direction direction) {
        return direction == null ||
                Direction.UNSPECIFIED.equals(direction) ||
                (mapping.getDirection() != null && mapping.getDirection().equals(direction));
    }

    // Helper methods - these are used by MappingService for conversion

    public ManagedObjectRepresentation toManagedObject(MappingRepresentation mr) {
        return configurationRegistry.getObjectMapper().convertValue(mr, ManagedObjectRepresentation.class);
    }

    private MappingRepresentation toMappingObject(ManagedObjectRepresentation mor) {
        return configurationRegistry.getObjectMapper().convertValue(mor, MappingRepresentation.class);
    }

}
