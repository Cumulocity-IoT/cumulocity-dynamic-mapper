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
import com.cumulocity.sdk.client.inventory.InventoryApi;
import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.*;
import dynamic.mapper.service.cache.MappingCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages mapping status tracking and reporting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingStatusService {

    private final InventoryApi inventoryApi;
    private final ConfigurationRegistry configurationRegistry;
    private final MappingCacheManager cacheManager;
    private final MicroserviceSubscriptionsService subscriptionsService;

    // Structure: <Tenant, <MappingIdentifier, MappingStatus>>
    private final Map<String, Map<String, MappingStatus>> mappingStatuses = new ConcurrentHashMap<>();

    // Structure: <Tenant, Initialized>
    private final Map<String, Boolean> initialized = new ConcurrentHashMap<>();

    /**
     * Initializes status tracking for a tenant
     */
    public void initializeTenantStatus(String tenant, boolean reset) {
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

        log.info("{} - Status tracking initialized", tenant);
    }

    /**
     * Removes status tracking for a tenant
     */
    public void removeTenantStatus(String tenant) {
        mappingStatuses.remove(tenant);
        initialized.remove(tenant);
        log.debug("{} - Status tracking removed", tenant);
    }

    /**
     * Gets or creates a status for a mapping
     */
    public MappingStatus getOrCreateStatus(String tenant, Mapping mapping) {
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
     * Gets all statuses for a tenant
     */
    public List<MappingStatus> getAllStatuses(String tenant) {
        return new ArrayList<>(getStatusMap(tenant).values());
    }

    /**
     * Removes a status entry
     */
    public void removeStatus(String tenant, String identifier) {
        getStatusMap(tenant).remove(identifier);
        log.debug("{} - Removed status for: {}", tenant, identifier);
    }

    /**
     * Increments failure count and handles deactivation if threshold exceeded
     */
    public void incrementFailureCount(String tenant, Mapping mapping, MappingStatus status) {
        status.currentFailureCount++;

        if (shouldDeactivateMapping(mapping, status)) {
            handleFailureThresholdExceeded(tenant, mapping, status);
        }
    }

    /**
     * Resets failure count to zero
     */
    public void resetFailureCount(String tenant, String identifier) {
        MappingStatus status = getStatusMap(tenant).get(identifier);
        if (status != null) {
            status.currentFailureCount = 0;
            log.debug("{} - Reset failure count for: {}", tenant, identifier);
        }
    }

    /**
     * Sends current mapping statuses to the inventory
     */
    public void sendStatusToInventory(String tenant) {
        if (!shouldSendStatus(tenant)) {
            log.debug("{} - Skipping status send (not enabled or not initialized)", tenant);
            return;
        }

        try {
            Map<String, MappingStatus> statusMap = getStatusMap(tenant);
            MappingStatus[] statusArray = buildStatusArray(tenant, statusMap);

            if (statusArray.length == 0) {
                log.debug("{} - No statuses to send", tenant);
                return;
            }

            updateInventoryWithStatuses(tenant, statusArray);
            log.debug("{} - Sent {} statuses to inventory", tenant, statusArray.length);

        } catch (Exception e) {
            log.error("{} - Failed to send status to inventory", tenant, e);
        }
    }

    /**
     * Sends an error event when mapping loading fails
     */
    public void sendMappingLoadingError(String tenant, ManagedObjectRepresentation mo, String message) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String date = dateFormat.format(new Date());

        Map<String, String> eventData = Map.of(
                "message", message,
                "id", mo.getId().getValue(),
                "date", date);

        configurationRegistry.getC8yAgent().createOperationEvent(
                message,
                LoggingEventType.MAPPING_LOADING_ERROR_EVENT_TYPE,
                DateTime.now(),
                tenant,
                eventData);

        log.warn("{} - Mapping loading error sent for MO {}", tenant, mo.getId().getValue());
    }

    // ========== Private Helper Methods ==========

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

    private boolean shouldSendStatus(String tenant) {
        ServiceConfiguration config = configurationRegistry.getServiceConfiguration(tenant);
        return config.isSendMappingStatus() && initialized.getOrDefault(tenant, false);
    }

    private boolean shouldDeactivateMapping(Mapping mapping, MappingStatus status) {
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
                LoggingEventType.STATUS_MAPPING_FAILURE_EVENT_TYPE,
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

    private boolean shouldIncludeStatus(String tenant, MappingStatus status) {
        return "UNSPECIFIED".equals(status.id) ||
                cacheManager.containsInboundMapping(tenant, status.id) ||
                cacheManager.containsOutboundMapping(tenant, status.id);
    }

    private void enrichStatusWithName(String tenant, MappingStatus status) {
        if ("UNSPECIFIED".equals(status.id)) {
            status.name = "Unspecified";
        } else {
            cacheManager.getInboundMapping(tenant, status.id)
                    .or(() -> cacheManager.getOutboundMapping(tenant, status.id))
                    .ifPresent(mapping -> status.name = mapping.getName());
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
            inventoryApi.update(updateMor);
        });
    }
}
