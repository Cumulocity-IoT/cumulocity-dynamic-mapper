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

package dynamic.mapper.notification.task;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.GroupCacheManager;
import dynamic.mapper.notification.GroupCacheManager.CachedGroup;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.processor.model.C8YMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Task to update device subscriptions when a device group membership changes.
 * Compares cached group state with notification payload to determine
 * which devices need to be subscribed/unsubscribed.
 */
@Slf4j
public class UpdateSubscriptionDeviceGroupTask implements Callable<SubscriptionUpdateResult> {

    private final C8YMessage c8yMessage;
    private final ConfigurationRegistry configurationRegistry;
    private final GroupCacheManager groupCacheManager;

    public UpdateSubscriptionDeviceGroupTask(
            ConfigurationRegistry configurationRegistry,
            C8YMessage c8yMessage,
            GroupCacheManager groupCacheManager) {
        this.c8yMessage = c8yMessage;
        this.configurationRegistry = configurationRegistry;
        this.groupCacheManager = groupCacheManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SubscriptionUpdateResult call() {
        String tenant = c8yMessage.getTenant();
        String groupId = c8yMessage.getSourceId();

        log.debug("{} - Processing group update for: {}", tenant, groupId);

        if (groupId == null || groupId.trim().isEmpty()) {
            log.warn("{} - No group ID found in message, skipping update", tenant);
            return SubscriptionUpdateResult.empty();
        }

        try {
            // Get cached group state (null means group is not subscribed by this service)
            CachedGroup cachedGroup = groupCacheManager.getCache().get(groupId);
            if (cachedGroup == null) {
                log.warn("{} - Group {} not found in cache, skipping subscription update", tenant, groupId);
                return SubscriptionUpdateResult.empty();
            }

            Map<String, Object> payload = c8yMessage.getParsedPayload();

            // Guard: if the payload contains no childAssets key this is a property update
            // (e.g. group name/description changed), NOT a membership change.
            // Without this check payloadChildIds would be empty, making toRemove equal to
            // ALL cached devices and causing a mass-unsubscription.
            if (payload == null || !payload.containsKey("childAssets")) {
                log.debug("{} - Group {} UPDATE has no childAssets field — property change only, skipping subscription delta",
                        tenant, groupId);
                return SubscriptionUpdateResult.empty();
            }

            // Extract child device IDs from cache and payload
            Set<String> cachedChildIds = groupCacheManager.getSubscribedDevices(groupId);
            Set<String> payloadChildIds = extractChildIdsFromPayload(payload);

            // Calculate differences
            Set<String> toAdd = calculateToAdd(payloadChildIds, cachedChildIds);
            Set<String> toRemove = calculateToRemove(cachedChildIds, payloadChildIds);

            log.debug("{} - Group {} membership delta: +{} to subscribe, -{} to remove",
                    tenant, groupId, toAdd.size(), toRemove.size());

            if (toAdd.isEmpty() && toRemove.isEmpty()) {
                log.debug("{} - No membership changes detected for group {}", tenant, groupId);
                return SubscriptionUpdateResult.empty();
            }

            // Process subscription changes
            SubscriptionUpdateResult result = processSubscriptionChanges(tenant, groupId, toAdd, toRemove);

            // Update cache with the authoritative membership from the payload
            groupCacheManager.updateSubscribedDevices(groupId, payloadChildIds);

            log.info("{} - Updated group {} subscriptions: {} added, {} removed, {} failed",
                    tenant, groupId, result.getAddedCount(), result.getRemovedCount(), result.getFailedCount());

            return result;

        } catch (Exception e) {
            log.error("{} - Error updating group {} subscription: {}", tenant, groupId, e.getMessage(), e);
            return SubscriptionUpdateResult.withError(e);
        }
    }

    /**
     * Extract child device IDs from notification payload
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractChildIdsFromPayload(Map<String, Object> payload) {
        Set<String> childIds = new HashSet<>();

        if (payload == null) {
            return childIds;
        }

        try {
            Object childAssets = payload.get("childAssets");
            if (!(childAssets instanceof Map)) {
                return childIds;
            }

            Object references = ((Map<String, Object>) childAssets).get("references");
            if (!(references instanceof List)) {
                return childIds;
            }

            for (Object ref : (List<?>) references) {
                if (!(ref instanceof Map)) {
                    continue;
                }

                Object managedObject = ((Map<?, ?>) ref).get("managedObject");
                if (!(managedObject instanceof Map)) {
                    continue;
                }

                Object id = ((Map<?, ?>) managedObject).get("id");
                if (id != null) {
                    childIds.add(String.valueOf(id));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract payload child assets: {}", e.getMessage());
        }

        return childIds;
    }

    /**
     * Calculate devices to add (in payload but not in cache)
     */
    private Set<String> calculateToAdd(Set<String> payloadIds, Set<String> cachedIds) {
        Set<String> toAdd = new HashSet<>(payloadIds);
        toAdd.removeAll(cachedIds);
        return toAdd;
    }

    /**
     * Calculate devices to remove (in cache but not in payload)
     */
    private Set<String> calculateToRemove(Set<String> cachedIds, Set<String> payloadIds) {
        Set<String> toRemove = new HashSet<>(cachedIds);
        toRemove.removeAll(payloadIds);
        return toRemove;
    }

    /**
     * Process subscription changes for devices
     */
    private SubscriptionUpdateResult processSubscriptionChanges(
            String tenant, String groupId, Set<String> toAdd, Set<String> toRemove) {

        SubscriptionUpdateResult.Builder resultBuilder = SubscriptionUpdateResult.builder();

        // Process additions
        for (String childId : toAdd) {
            try {
                ManagedObjectRepresentation childMO = configurationRegistry.getC8yAgent()
                        .getManagedObjectForId(tenant, childId, false);

                if (childMO == null) {
                    log.warn("{} - Child device {} not found for subscription", tenant, childId);
                    resultBuilder.addFailed(childId, "Device not found");
                    continue;
                }

                Future<NotificationSubscriptionRepresentation> future = configurationRegistry
                        .getNotificationSubscriber()
                        .subscribeDeviceAndConnect(tenant, childMO, c8yMessage.getApi(), Utils.DYNAMIC_DEVICE_SUBSCRIPTION);

                // Pre-populate inventory cache for this device to ensure inventory filters work correctly
                log.debug("{} - Pre-populating inventory cache for child device {} in group {}",
                        tenant, childId, groupId);
                configurationRegistry.getC8yAgent().getMOFromInventoryCache(tenant, childId, false);

                resultBuilder.addSubscription(childId, future);
                log.debug("{} - Subscribed child device {} to group {}", tenant, childId, groupId);

            } catch (Exception e) {
                log.error("{} - Failed to subscribe child device {}: {}", tenant, childId, e.getMessage(), e);
                resultBuilder.addFailed(childId, e.getMessage());
            }
        }

        // Process removals
        for (String childId : toRemove) {
            try {
                ManagedObjectRepresentation childMO = configurationRegistry.getC8yAgent()
                        .getManagedObjectForId(tenant, childId, false);

                if (childMO == null) {
                    log.warn("{} - Child device {} not found for unsubscription", tenant, childId);
                    resultBuilder.addFailed(childId, "Device not found");
                    continue;
                }

                configurationRegistry.getNotificationSubscriber()
                        .unsubscribeDeviceAndDisconnect(tenant, childMO, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);

                resultBuilder.addUnsubscription(childId);
                log.debug("{} - Unsubscribed child device {} from group {}", tenant, childId, groupId);

            } catch (Exception e) {
                log.error("{} - Failed to unsubscribe child device {}: {}", tenant, childId, e.getMessage(), e);
                resultBuilder.addFailed(childId, e.getMessage());
            }
        }

        return resultBuilder.build();
    }

}
