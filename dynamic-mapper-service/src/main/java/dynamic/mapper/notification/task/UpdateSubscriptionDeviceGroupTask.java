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
            Map<String, Object> payload = c8yMessage.getParsedPayload();

            log.debug("{} - Group {} UPDATE notification received. Payload top-level keys: {}",
                    tenant, groupId, payload == null ? "null (unparsable payload)" : payload.keySet());

            // Guard: if the payload contains no childAssets key this is a property update
            // (e.g. group name/description changed), NOT a membership change.
            // Without this check payloadChildIds would be empty, making toRemove equal to
            // ALL cached devices and causing a mass-unsubscription.
            if (payload == null || !payload.containsKey("childAssets")) {
                log.debug("{} - Group {} UPDATE has no childAssets field — property change only, skipping subscription delta",
                        tenant, groupId);
                return SubscriptionUpdateResult.empty();
            }

            log.debug("{} - Group {} raw childAssets fragment: {}", tenant, groupId, payload.get("childAssets"));

            // Cache miss: entry was expired or never populated. Re-sync from payload
            // instead of skipping — this makes time-based cache expiry safe.
            CachedGroup cachedGroup = groupCacheManager.getCache().get(groupId);
            if (cachedGroup == null) {
                log.debug("{} - Group {} cache miss (never populated or evicted) — falling back to handleCacheMiss",
                        tenant, groupId);
                return handleCacheMiss(tenant, groupId, payload);
            }

            // Normal delta path
            Set<String> cachedChildIds = groupCacheManager.getSubscribedDevices(groupId);
            Set<String> payloadChildIds = extractChildIdsFromPayload(payload);

            log.debug("{} - Group {} cached child IDs (previously known members): {}", tenant, groupId, cachedChildIds);
            log.debug("{} - Group {} payload child IDs (current members per notification): {}", tenant, groupId, payloadChildIds);

            Set<String> toAdd = calculateToAdd(payloadChildIds, cachedChildIds);
            Set<String> toRemove = calculateToRemove(cachedChildIds, payloadChildIds);

            log.debug("{} - Group {} membership delta: +{} to subscribe {}, -{} to remove {}",
                    tenant, groupId, toAdd.size(), toAdd, toRemove.size(), toRemove);

            if (toAdd.isEmpty() && toRemove.isEmpty()) {
                log.debug("{} - No membership changes detected for group {}", tenant, groupId);
                return SubscriptionUpdateResult.empty();
            }

            SubscriptionUpdateResult result = processSubscriptionChanges(tenant, groupId, toAdd, toRemove);
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
     * Handles a cache miss for a group that has a childAssets membership-change payload.
     *
     * <p>A miss means the cache entry was evicted (time-based expiry) or was never populated
     * (e.g. first notification after a restart). We cannot compute a safe delta without the
     * previous state, so we treat the payload as the authoritative current state and subscribe
     * every device in it. We deliberately do NOT unsubscribe anything — without knowing what
     * was previously subscribed, removing devices would risk dropping live subscriptions.
     *
     * <p>After this call the cache is fully populated, so subsequent notifications for the
     * same group will follow the normal delta path.
     */
    private SubscriptionUpdateResult handleCacheMiss(String tenant, String groupId,
            Map<String, Object> payload) {
        log.info("{} - Group {} not in cache (expired or first-seen) — re-syncing from UPDATE payload",
                tenant, groupId);

        Set<String> payloadChildIds = extractChildIdsFromPayload(payload);
        log.debug("{} - Group {} payload child IDs extracted during cache-miss re-sync: {}",
                tenant, groupId, payloadChildIds);

        // Best-effort: restore the group MO so future addGroup() calls have the full object
        try {
            ManagedObjectRepresentation groupMO = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, groupId, false);
            if (groupMO != null) {
                groupCacheManager.addGroup(groupMO);
            }
        } catch (Exception e) {
            log.debug("{} - Could not fetch group MO {} during cache re-sync: {}", tenant, groupId, e.getMessage());
        }

        if (payloadChildIds.isEmpty()) {
            log.info("{} - Re-sync for group {}: payload has no child devices", tenant, groupId);
            groupCacheManager.updateSubscribedDevices(groupId, Collections.emptySet());
            return SubscriptionUpdateResult.empty();
        }

        log.info("{} - Re-sync for group {}: subscribing {} device(s) from payload state (no removals — prior state unknown)",
                tenant, groupId, payloadChildIds.size());

        // toRemove is empty: we have no prior knowledge of what was subscribed
        SubscriptionUpdateResult result = processSubscriptionChanges(
                tenant, groupId, payloadChildIds, Collections.emptySet());
        groupCacheManager.updateSubscribedDevices(groupId, payloadChildIds);

        log.info("{} - Re-sync completed for group {}: {} subscribed, {} failed",
                tenant, groupId, result.getAddedCount(), result.getFailedCount());

        return result;
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
                log.debug("Unexpected childAssets type in payload: {}",
                        childAssets == null ? "null" : childAssets.getClass());
                return childIds;
            }

            Object references = ((Map<String, Object>) childAssets).get("references");
            if (!(references instanceof List)) {
                log.debug("childAssets fragment has no 'references' list: {}", childAssets);
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
                    log.warn("{} - Child device {} not found for unsubscription, deleting stale subscriptions",
                            tenant, childId);
                    int deleted = configurationRegistry.getNotificationSubscriber()
                            .deleteSubscriptionsForDevice(tenant, childId, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
                    if (deleted > 0) {
                        log.info("{} - Deleted {} stale subscription(s) for non-existent device {}",
                                tenant, deleted, childId);
                        resultBuilder.addUnsubscription(childId);
                    } else {
                        resultBuilder.addFailed(childId, "Device not found");
                    }
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
