/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

package dynamic.mapper.core;

import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dynamic.mapper.processor.model.ProcessingContext;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * Thread-safe device identity resolution: create-or-lookup an implicit C8Y
 * device given an external ID.  Extracted from {@link ConfigurationRegistry} so
 * that {@code ConfigurationRegistry} no longer holds a direct reference to
 * {@link C8YAgent}, breaking the {@code ConfigurationRegistry ↔ C8YAgent}
 * circular dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityResolutionService {

    private final C8YAgent c8yAgent;
    private final TenantRegistry tenantRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Thread-safe method to create or retrieve an implicit device.
     * Prevents race conditions when multiple threads try to create the same device simultaneously.
     * Uses double-check locking pattern with per-external-ID locks.
     *
     * @param tenant          the tenant identifier
     * @param externalIdType  the external ID type
     * @param externalIdValue the external ID value
     * @param identity        the ID object for C8Y API calls
     * @param context         the processing context
     * @return the C8Y internal device ID, or null if creation failed and device doesn't exist
     * @throws Exception if an error occurs during device creation or lookup
     */
    public String getOrCreateDeviceThreadSafe(String tenant, String externalIdType, String externalIdValue,
            ID identity, ProcessingContext<?> context) throws Exception {
        String cacheKey = tenant + "|" + externalIdType + "|" + externalIdValue;

        // First check: quick cache hit
        String cached = tenantRegistry.getCachedExternalId(cacheKey);
        if (cached != null) {
            log.debug("{} - Device cache hit for {}: {}", tenant, cacheKey, cached);
            return cached;
        }

        // Get or create lock for this specific externalId (per-ID locking, not global)
        Object lock = tenantRegistry.getOrCreateExternalIdLock(cacheKey);

        synchronized (lock) {
            // Double-check after acquiring lock: another thread may have created it
            cached = tenantRegistry.getCachedExternalId(cacheKey);
            if (cached != null) {
                log.debug("{} - Device found in cache after lock acquired for {}: {}", tenant, cacheKey, cached);
                return cached;
            }

            // Check if device already exists in C8Y
            ExternalIDRepresentation resolved =
                    c8yAgent.resolveExternalId2GlobalId(tenant, identity, context.isTesting());
            if (resolved != null) {
                String internalId = resolved.getManagedObject().getId().getValue();
                // Only cache the resolved ID in production — during dry-run tests
                // resolveExternalId2GlobalId routes to MockIdentity and returns a
                // synthetic ID (e.g. "10000") that must never enter the production cache.
                if (!Boolean.TRUE.equals(context.isTesting())) {
                    tenantRegistry.cacheExternalId(cacheKey, internalId);
                }
                log.debug("{} - Device exists in C8Y for {}: {}", tenant, cacheKey, internalId);
                return internalId;
            }

            // Device doesn't exist, check if we should create it
            if (!Boolean.TRUE.equals(context.getMapping().getCreateNonExistingDevice())) {
                log.debug("{} - Device creation disabled for {}, returning null", tenant, cacheKey);
                return null;
            }

            // Create new device
            log.info("{} - Creating new implicit device for {}/{}", tenant, externalIdType, externalIdValue);
            String newId = ProcessingResultHelper.createImplicitDevice(
                    identity, context, log, c8yAgent, objectMapper);

            if (newId != null) {
                if (!Boolean.TRUE.equals(context.isTesting())) {
                    tenantRegistry.cacheExternalId(cacheKey, newId);
                }
                log.info("{} - Successfully created implicit device for {}: {}", tenant, cacheKey, newId);
            } else {
                log.error("{} - Failed to create implicit device for {}", tenant, cacheKey);
            }

            return newId;
        }
    }
}
