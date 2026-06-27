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
import dynamic.mapper.model.MappingVersion;
import dynamic.mapper.model.MappingVersionRepresentation;
import dynamic.mapper.model.SemVer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Repository responsible for converting between {@link MappingVersion} domain
 * objects and their inventory managed-object representation
 * ({@code d11r_mapping_version}).
 *
 * <p>Mirrors the convention of {@link MappingRepository}: this is a lower-level
 * helper that handles conversion and in-memory filtering only. The actual
 * inventory reads/writes are performed by the service layer with the proper
 * tenant scope activated via {@code subscriptionsService.callForTenant()}.
 */
@Slf4j
@Repository
public class MappingVersionRepository {

    private final ConfigurationRegistry configurationRegistry;

    public MappingVersionRepository(ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    /**
     * Normalizes a version before it is first persisted. Stamps the creation
     * timestamp if not already set.
     */
    public MappingVersion prepareForCreate(String tenant, MappingVersion version) {
        if (version.getCreatedAt() == 0) {
            version.setCreatedAt(System.currentTimeMillis());
        }
        return version;
    }

    /**
     * Converts a single managed object into a {@link MappingVersion}, carrying the
     * managed-object id back onto the version's snapshot is intentionally NOT done
     * here - the snapshot keeps the runnable mapping's own id.
     */
    public Optional<MappingVersion> findOne(String tenant, ManagedObjectRepresentation mo) {
        if (mo == null) {
            return Optional.empty();
        }
        try {
            MappingVersionRepresentation rep = toRepresentation(mo);
            MappingVersion version = rep.getMappingVersion();
            if (version == null) {
                log.warn("{} - Managed object {} has no {} fragment, skipping", tenant,
                        rep.getId(), MappingVersionRepresentation.MAPPING_VERSION_FRAGMENT);
                return Optional.empty();
            }
            // Carry the managed-object id so callers can update/delete this version record.
            version.setId(rep.getId());
            return Optional.of(version);
        } catch (IllegalArgumentException e) {
            log.warn("{} - Failed to convert MO to mapping version: {}", tenant,
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Converts and drains a collection of {@code d11r_mapping_version} managed
     * objects into {@link MappingVersion}s, sorted ascending by version number.
     * Drafts are included; callers filter by {@link MappingVersion#isDraft()} and
     * by the owning line's identifier as needed. Managed objects that are not
     * mapping versions are skipped.
     */
    public List<MappingVersion> findAll(String tenant, ManagedObjectCollection moc) {
        return StreamSupport.stream(moc.get().allPages().spliterator(), false)
                .map(mo -> findOne(tenant, mo))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(MappingVersion::getVersion, SemVer.STRING_COMPARATOR))
                .collect(Collectors.toList());
    }

    // ========== Conversion helpers ==========

    public ManagedObjectRepresentation toManagedObject(MappingVersionRepresentation rep) {
        return configurationRegistry.getObjectMapper().convertValue(rep, ManagedObjectRepresentation.class);
    }

    private MappingVersionRepresentation toRepresentation(ManagedObjectRepresentation mor) {
        return configurationRegistry.getObjectMapper().convertValue(mor, MappingVersionRepresentation.class);
    }
}
