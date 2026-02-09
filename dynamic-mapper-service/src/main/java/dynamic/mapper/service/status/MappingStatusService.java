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

package dynamic.mapper.service.status;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.*;
import dynamic.mapper.service.cache.MappingCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages mapping status tracking and reporting for the dynamic mapper service.
 *
 * <p>This service maintains in-memory status information for each mapping across
 * multiple tenants, including execution statistics, error counts, and operational state.
 * Status information is periodically synchronized with the Cumulocity inventory.</p>
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Initialize and maintain tenant-specific mapping statuses</li>
 *   <li>Track failure counts and handle automatic mapping deactivation</li>
 *   <li>Synchronize status updates to Cumulocity inventory</li>
 *   <li>Send error events for mapping loading failures</li>
 * </ul>
 *
 * <p>Thread-safety: This service uses {@link ConcurrentHashMap} for thread-safe operations
 * on the status maps. All public methods include input validation to ensure data integrity.</p>
 *
 * @author Christof Strack, Stefan Witschel
 * @since 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingStatusService {

    private final InventoryFacade inventoryApi;
    private final ConfigurationRegistry configurationRegistry;
    private final MappingCacheManager cacheManager;
    private final MicroserviceSubscriptionsService subscriptionsService;

    // Structure: <Tenant, <MappingIdentifier, MappingStatus>>
    private final Map<String, Map<String, MappingStatus>> mappingStatuses = new ConcurrentHashMap<>();

    // Structure: <Tenant, Initialized>
    private final Map<String, Boolean> initialized = new ConcurrentHashMap<>();

    /**
     * Initializes or resets status tracking for a specific tenant.
     *
     * <p>If {@code reset} is false and existing status data is found in the service representation,
     * that data is loaded into memory. Otherwise, a new empty status map is created. The unspecified
     * mapping status is always ensured to exist after initialization.</p>
     *
     * @param tenant the tenant identifier (must not be null or empty)
     * @param reset if true, clears existing status data; if false, preserves existing data
     * @throws IllegalArgumentException if tenant is null or empty
     */
    public void initializeTenantStatus(String tenant, boolean reset) {
        validateTenant(tenant);
        MapperServiceRepresentation serviceRep = configurationRegistry.getMapperServiceRepresentation(tenant);

        if (serviceRep.getMappingStatus() != null && !reset) {
            log.debug("{} - Initializing status with {} existing entries",
                    tenant, serviceRep.getMappingStatus().size());

            Map<String, MappingStatus> statusMap = new ConcurrentHashMap<>();
            serviceRep.getMappingStatus().forEach(status -> {
                if (status != null && status.identifier != null) {
                    statusMap.put(status.identifier, status);
                } else {
                    log.warn("{} - Skipping null or invalid MappingStatus", tenant);
                }
            });
            mappingStatuses.put(tenant, statusMap);
        } else {
            mappingStatuses.put(tenant, new ConcurrentHashMap<>());
        }

        // Ensure unspecified mapping status exists
        ensureUnspecifiedStatus(tenant);
        initialized.put(tenant, true);

        log.info("{} - Status tracking initialized with {} entries (reset: {})",
                tenant, mappingStatuses.get(tenant).size(), reset);
    }

    /**
     * Removes all status tracking data for a specific tenant.
     *
     * <p>This method should be called when a tenant is being unsubscribed or removed
     * from the service to free up memory resources.</p>
     *
     * @param tenant the tenant identifier to remove
     */
    public void removeTenantStatus(String tenant) {
        mappingStatuses.remove(tenant);
        initialized.remove(tenant);
        log.debug("{} - Status tracking removed", tenant);
    }

    /**
     * Retrieves existing status or creates a new one for the specified mapping.
     *
     * <p>If a status entry already exists for the mapping identifier, it is returned.
     * Otherwise, a new status is created with initial counters set to zero.</p>
     *
     * @param tenant the tenant identifier (must not be null or empty)
     * @param mapping the mapping to get or create status for (must not be null with valid identifier)
     * @return the existing or newly created {@link MappingStatus}
     * @throws IllegalArgumentException if tenant or mapping is invalid
     */
    public MappingStatus getOrCreateStatus(String tenant, Mapping mapping) {
        validateTenant(tenant);
        validateMapping(mapping);
        Map<String, MappingStatus> tenantStatuses = getStatusMap(tenant);

        return tenantStatuses.computeIfAbsent(mapping.getIdentifier(), key -> {
            log.debug("{} - Creating new status for mapping: {}", tenant, mapping.getIdentifier());
            return new MappingStatus(
                    mapping.getId(),
                    mapping.getName(),
                    mapping.getIdentifier(),
                    mapping.getDirection(),
                    mapping.getMappingTopic(),
                    mapping.getPublishTopic(),
                    0, 0, 0, 0, 0, null);
        });
    }

    /**
     * Retrieves all mapping statuses for a specific tenant.
     *
     * <p>Returns a new list containing all status entries for the tenant,
     * including the unspecified mapping status.</p>
     *
     * @param tenant the tenant identifier
     * @return a list of all {@link MappingStatus} entries for the tenant
     */
    public List<MappingStatus> getAllStatuses(String tenant) {
        return new ArrayList<>(getStatusMap(tenant).values());
    }

    /**
     * Removes a specific status entry for a mapping.
     *
     * <p>This method should be called when a mapping is deleted to clean up
     * its associated status information.</p>
     *
     * @param tenant the tenant identifier (must not be null or empty)
     * @param identifier the mapping identifier to remove (must not be null or empty)
     * @throws IllegalArgumentException if tenant is null or empty
     */
    public void removeStatus(String tenant, String identifier) {
        validateTenant(tenant);
        if (identifier == null || identifier.trim().isEmpty()) {
            log.warn("{} - Cannot remove status: identifier is null or empty", tenant);
            return;
        }
        getStatusMap(tenant).remove(identifier);
        log.debug("{} - Removed status for: {}", tenant, identifier);
    }

    /**
     * Increments the failure count for a mapping and handles automatic deactivation.
     *
     * <p>If the failure count reaches or exceeds the mapping's configured max failure count
     * (and max failure count is greater than 0), an event is created and the mapping should
     * be deactivated by the caller.</p>
     *
     * @param tenant the tenant identifier (must not be null or empty)
     * @param mapping the mapping that failed (must not be null with valid identifier)
     * @param status the status to update (must not be null)
     * @throws IllegalArgumentException if tenant or mapping is invalid
     */
    public void incrementFailureCount(String tenant, Mapping mapping, MappingStatus status) {
        validateTenant(tenant);
        validateMapping(mapping);
        if (status == null) {
            log.error("{} - Cannot increment failure count: status is null for mapping {}",
                     tenant, mapping.getIdentifier());
            return;
        }

        status.currentFailureCount++;
        log.debug("{} - Incremented failure count to {} for mapping: {}",
                 tenant, status.currentFailureCount, mapping.getIdentifier());

        if (shouldDeactivateMapping(mapping, status)) {
            handleFailureThresholdExceeded(tenant, mapping, status);
        }
    }

    /**
     * Resets the failure count to zero for a specific mapping.
     *
     * <p>This method should be called after a mapping has been successfully
     * executed to reset its error tracking.</p>
     *
     * @param tenant the tenant identifier (must not be null or empty)
     * @param identifier the mapping identifier (must not be null or empty)
     * @throws IllegalArgumentException if tenant is null or empty
     */
    public void resetFailureCount(String tenant, String identifier) {
        validateTenant(tenant);
        if (identifier == null || identifier.trim().isEmpty()) {
            log.warn("{} - Cannot reset failure count: identifier is null or empty", tenant);
            return;
        }
        MappingStatus status = getStatusMap(tenant).get(identifier);
        if (status != null) {
            status.currentFailureCount = 0;
            log.debug("{} - Reset failure count for: {}", tenant, identifier);
        }
    }

    /**
     * Sends current mapping statuses to the Cumulocity inventory.
     *
     * <p>This method aggregates all mapping statuses for the tenant and updates
     * the mapper service managed object in the inventory with the current status array.
     * Only statuses for active mappings (present in cache) and the unspecified status
     * are included in the update.</p>
     *
     * <p>Status sending can be disabled via service configuration. If disabled or
     * if the tenant is not yet initialized, this method returns without action.</p>
     *
     * @param tenant the tenant identifier
     */
    public void sendStatusToInventory(String tenant) {
        if (!shouldSendStatus(tenant)) {
            log.debug("{} - Skipping status send (not enabled or not initialized)", tenant);
            return;
        }

        try {
            validateTenant(tenant);
            Map<String, MappingStatus> statusMap = getStatusMap(tenant);
            MappingStatus[] statusArray = buildStatusArray(tenant, statusMap);

            if (statusArray.length == 0) {
                log.debug("{} - No statuses to send", tenant);
                return;
            }

            updateInventoryWithStatuses(tenant, statusArray);
            log.debug("{} - Successfully sent {} statuses to inventory", tenant, statusArray.length);

        } catch (IllegalArgumentException e) {
            log.error("{} - Invalid argument when sending status to inventory: {}", tenant, e.getMessage());
        } catch (Exception e) {
            log.error("{} - Unexpected error sending status to inventory", tenant, e);
        }
    }

    /**
     * Sends an error event to Cumulocity when a mapping fails to load.
     *
     * <p>Creates a {@link LoggingEventType#MAPPING_LOADING_ERROR_EVENT_TYPE} event
     * with details about the failure, including the managed object ID and error message.
     * This allows operators to track and diagnose mapping configuration issues.</p>
     *
     * @param tenant the tenant identifier (must not be null or empty)
     * @param mo the managed object representing the mapping (must not be null)
     * @param message the error message describing why loading failed
     * @throws IllegalArgumentException if tenant is null or empty
     */
    public void sendMappingLoadingError(String tenant, ManagedObjectRepresentation mo, String message) {
        try {
            validateTenant(tenant);
            if (mo == null || mo.getId() == null) {
                log.warn("{} - Cannot send mapping loading error: invalid managed object", tenant);
                return;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String date = LocalDateTime.now().format(formatter);

            Map<String, String> eventData = Map.of(
                    "message", message != null ? message : "Unknown error",
                    "id", mo.getId().getValue(),
                    "date", date);

            configurationRegistry.getC8yAgent().createOperationEvent(
                    message,
                    LoggingEventType.MAPPING_LOADING_ERROR_EVENT_TYPE,
                    DateTime.now(),
                    tenant,
                    eventData);

            log.warn("{} - Mapping loading error event created for MO: {} with message: {}",
                    tenant, mo.getId().getValue(), message);
        } catch (Exception e) {
            log.error("{} - Failed to send mapping loading error for MO: {}",
                    tenant, mo != null && mo.getId() != null ? mo.getId().getValue() : "unknown", e);
        }
    }

    // ========== Private Helper Methods ==========

    private void validateTenant(String tenant) {
        if (tenant == null || tenant.trim().isEmpty()) {
            throw new IllegalArgumentException("Tenant cannot be null or empty");
        }
    }

    private void validateMapping(Mapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping cannot be null");
        }
        if (mapping.getIdentifier() == null || mapping.getIdentifier().trim().isEmpty()) {
            throw new IllegalArgumentException("Mapping identifier cannot be null or empty");
        }
    }

    private Map<String, MappingStatus> getStatusMap(String tenant) {
        return mappingStatuses.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
    }

    private void ensureUnspecifiedStatus(String tenant) {
        Map<String, MappingStatus> statusMap = getStatusMap(tenant);
        if (!statusMap.containsKey(MappingStatus.IDENT_UNSPECIFIED_MAPPING)) {
            statusMap.put(
                    MappingStatus.UNSPECIFIED_MAPPING_STATUS.identifier,
                    MappingStatus.UNSPECIFIED_MAPPING_STATUS);
        }
    }

    private Boolean shouldSendStatus(String tenant) {
        ServiceConfiguration config = configurationRegistry.getServiceConfiguration(tenant);
        return config.getSendMappingStatus() && initialized.getOrDefault(tenant, false);
    }

    private Boolean shouldDeactivateMapping(Mapping mapping, MappingStatus status) {
        return mapping.getMaxFailureCount() > 0 &&
                status.currentFailureCount >= mapping.getMaxFailureCount();
    }

    private void handleFailureThresholdExceeded(String tenant, Mapping mapping, MappingStatus status) {
        String message = String.format(
                "Tenant %s - Mapping %s deactivated due to exceeded failure count: %d",
                tenant, mapping.getId(), status.getCurrentFailureCount());

        log.warn(message);

        configurationRegistry.getC8yAgent().createOperationEvent(
                message,
                LoggingEventType.MAPPING_FAILURE_EVENT_TYPE,
                DateTime.now(),
                tenant,
                null);
    }

    private MappingStatus[] buildStatusArray(String tenant, Map<String, MappingStatus> statusMap) {
        return statusMap.values().stream()
                .filter(status -> shouldIncludeStatus(tenant, status))
                .peek(status -> enrichStatusWithName(tenant, status))
                .toArray(MappingStatus[]::new);
    }

    private Boolean shouldIncludeStatus(String tenant, MappingStatus status) {
        if (status == null || status.id == null) {
            log.warn("{} - Skipping null or invalid status", tenant);
            return false;
        }

        return "UNSPECIFIED".equals(status.id) ||
                cacheManager.containsInboundMapping(tenant, status.id) ||
                cacheManager.containsOutboundMapping(tenant, status.id);
    }

    private void enrichStatusWithName(String tenant, MappingStatus status) {
        if (status == null || status.id == null) {
            log.warn("{} - Cannot enrich null or invalid status", tenant);
            return;
        }

        try {
            if ("UNSPECIFIED".equals(status.id)) {
                status.name = "Unspecified";
            } else {
                cacheManager.getInboundMapping(tenant, status.id)
                        .or(() -> cacheManager.getOutboundMapping(tenant, status.id))
                        .ifPresent(mapping -> {
                            if (mapping.getName() != null) {
                                status.name = mapping.getName();
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("{} - Error enriching status name for id: {}", tenant, status.id, e);
        }
    }

    private void updateInventoryWithStatuses(String tenant, MappingStatus[] statuses) {
        MapperServiceRepresentation serviceRep = configurationRegistry.getMapperServiceRepresentation(tenant);

        Map<String, Object> fragment = new ConcurrentHashMap<>();
        fragment.put(MapperServiceRepresentation.MAPPING_FRAGMENT, statuses);

        ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
        updateMor.setId(GId.asGId(serviceRep.getId()));
        updateMor.setAttrs(fragment);

        subscriptionsService.runForTenant(tenant, () -> {
            inventoryApi.update(updateMor, false);
        });
    }
}
