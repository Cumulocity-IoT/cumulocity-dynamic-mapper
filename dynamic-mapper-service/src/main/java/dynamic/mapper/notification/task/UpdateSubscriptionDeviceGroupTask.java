/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import dynamic.mapper.notification.NotificationClientOperations;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.device.Device;
import dynamic.mapper.notification.NotificationSubscriptionResponse;
import dynamic.mapper.notification.GroupCacheManager;
import dynamic.mapper.notification.GroupCacheManager.CachedGroup;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.processor.model.C8YMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Task to update device subscriptions when a device group membership changes.
 * Compares cached group state with notification payload to determine
 * which devices need to be subscribed/unsubscribed.
 */
@Slf4j
public class UpdateSubscriptionDeviceGroupTask implements Callable<SubscriptionUpdateResult> {

    private final C8YMessage c8yMessage;
    private final ServiceRegistry serviceRegistry;
    private final NotificationClientOperations notificationSubscriber;
    private final GroupCacheManager groupCacheManager;

    public UpdateSubscriptionDeviceGroupTask(
            ServiceRegistry serviceRegistry,
            NotificationClientOperations notificationSubscriber,
            C8YMessage c8yMessage,
            GroupCacheManager groupCacheManager) {
        this.c8yMessage = c8yMessage;
        this.serviceRegistry = serviceRegistry;
        this.notificationSubscriber = notificationSubscriber;
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

        // One notification per group at a time: read-modify-write of the cached membership
        ReentrantLock groupLock = groupCacheManager.lockFor(groupId);
        groupLock.lock();
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

            Set<String> failedAdds = new HashSet<>();
            Set<String> failedRemoves = new HashSet<>();
            SubscriptionUpdateResult result = processSubscriptionChanges(tenant, groupId, toAdd, toRemove,
                    failedAdds, failedRemoves);
            // Record only what was handled: a member whose subscribe failed stays "unknown" so the
            // next delta adds it again, and one whose unsubscribe failed stays known so it is removed again.
            Set<String> newKnown = new HashSet<>(payloadChildIds);
            newKnown.removeAll(failedAdds);
            newKnown.addAll(failedRemoves);
            groupCacheManager.updateSubscribedDevices(groupId, newKnown);

            log.info("{} - Updated group {} subscriptions: {} added, {} removed, {} failed",
                    tenant, groupId, result.getAddedCount(), result.getRemovedCount(), result.getFailedCount());

            return result;

        } catch (Exception e) {
            log.error("{} - Error updating group {} subscription: {}", tenant, groupId, e.getMessage(), e);
            return SubscriptionUpdateResult.withError(e);
        } finally {
            groupLock.unlock();
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
            ManagedObjectRepresentation groupMO = serviceRegistry.getC8yAgent()
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
        Set<String> failedAdds = new HashSet<>();
        SubscriptionUpdateResult result = processSubscriptionChanges(
                tenant, groupId, payloadChildIds, Collections.emptySet(), failedAdds, new HashSet<>());
        Set<String> known = new HashSet<>(payloadChildIds);
        known.removeAll(failedAdds);
        groupCacheManager.updateSubscribedDevices(groupId, known);

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

    /** How long the task waits for the asynchronous subscribe futures of one notification. */
    private static final long SUBSCRIBE_WAIT_SECONDS = 60;

    /**
     * Process subscription changes for the members listed in a group's childAssets. A member can
     * be a device, or a sub-group: a sub-group is expanded to the devices below it (as the REST
     * path does), so its devices are subscribed and unsubscribed instead of the group object.
     * Members whose change failed are reported in {@code failedAdds} / {@code failedRemoves}.
     */
    private SubscriptionUpdateResult processSubscriptionChanges(
            String tenant, String groupId, Set<String> toAdd, Set<String> toRemove,
            Set<String> failedAdds, Set<String> failedRemoves) {

        SubscriptionUpdateResult.Builder resultBuilder = SubscriptionUpdateResult.builder();
        Map<String, List<Future<NotificationSubscriptionRepresentation>>> futuresByMember = new HashMap<>();

        // Process additions
        for (String childId : toAdd) {
            try {
                ManagedObjectRepresentation childMO = serviceRegistry.getC8yAgent()
                        .getManagedObjectForId(tenant, childId, false);

                if (childMO == null) {
                    log.warn("{} - Child device {} not found for subscription", tenant, childId);
                    resultBuilder.addFailed(childId, "Device not found");
                    failedAdds.add(childId);
                    continue;
                }

                for (ManagedObjectRepresentation target : devicesOf(tenant, childMO)) {
                    String targetId = target.getId().getValue();
                    Future<NotificationSubscriptionRepresentation> future = notificationSubscriber
                            // API.ALL, not c8yMessage.getApi(): that is INVENTORY (the group's own
                            // notification channel) and would restrict the device to inventory data.
                            .subscribeDeviceAndConnect(tenant, target, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);

                    // Pre-populate inventory cache for this device to ensure inventory filters work correctly
                    log.debug("{} - Pre-populating inventory cache for child device {} in group {}",
                            tenant, targetId, groupId);
                    serviceRegistry.getC8yAgent().getMOFromInventoryCache(tenant, targetId, false);

                    resultBuilder.addSubscription(targetId, future);
                    futuresByMember.computeIfAbsent(childId, k -> new ArrayList<>()).add(future);
                    log.debug("{} - Subscribed child device {} to group {}", tenant, targetId, groupId);
                }

            } catch (Exception e) {
                log.error("{} - Failed to subscribe child device {}: {}", tenant, childId, e.getMessage(), e);
                resultBuilder.addFailed(childId, e.getMessage());
                failedAdds.add(childId);
            }
        }

        // The subscribes run asynchronously: a failure there must not be recorded as success
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SUBSCRIBE_WAIT_SECONDS);
        for (Map.Entry<String, List<Future<NotificationSubscriptionRepresentation>>> entry : futuresByMember.entrySet()) {
            for (Future<NotificationSubscriptionRepresentation> future : entry.getValue()) {
                try {
                    future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    resultBuilder.addFailed(entry.getKey(), "interrupted");
                    failedAdds.add(entry.getKey());
                    break;
                } catch (Exception e) {
                    // includes a timeout: unknown outcome, so the next delta retries it
                    log.warn("{} - Subscribing member {} of group {} did not succeed: {}",
                            tenant, entry.getKey(), groupId, e.toString());
                    resultBuilder.addFailed(entry.getKey(), String.valueOf(e.getMessage()));
                    failedAdds.add(entry.getKey());
                    break;
                }
            }
        }

        // Process removals
        Set<String> coveredElsewhere = null; // computed live, once, when the first removal needs it
        for (String childId : toRemove) {
            try {
                ManagedObjectRepresentation childMO = serviceRegistry.getC8yAgent()
                        .getManagedObjectForId(tenant, childId, false);

                if (childMO == null) {
                    log.warn("{} - Child device {} not found for unsubscription, deleting stale subscriptions",
                            tenant, childId);
                    int deleted = notificationSubscriber
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

                boolean memberFailed = false;
                for (ManagedObjectRepresentation target : devicesOf(tenant, childMO)) {
                    if (coveredElsewhere == null) {
                        coveredElsewhere = liveCoverage(tenant, groupId);
                    }
                    if (isStillCovered(tenant, groupId, target, coveredElsewhere)) {
                        log.debug("{} - Keeping child device {}: still covered by another group or type subscription",
                                tenant, target.getId().getValue());
                        continue;
                    }
                    try {
                        notificationSubscriber
                                .unsubscribeDeviceAndDisconnect(tenant, target, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
                        resultBuilder.addUnsubscription(target.getId().getValue());
                        log.debug("{} - Unsubscribed child device {} from group {}", tenant,
                                target.getId().getValue(), groupId);
                    } catch (Exception e) {
                        memberFailed = true;
                        log.error("{} - Failed to unsubscribe child device {}: {}", tenant,
                                target.getId().getValue(), e.getMessage(), e);
                        resultBuilder.addFailed(target.getId().getValue(), e.getMessage());
                    }
                }
                if (memberFailed) {
                    failedRemoves.add(childId);
                }

            } catch (Exception e) {
                log.error("{} - Failed to unsubscribe child device {}: {}", tenant, childId, e.getMessage(), e);
                resultBuilder.addFailed(childId, e.getMessage());
                failedRemoves.add(childId);
            }
        }

        return resultBuilder.build();
    }

    /**
     * The devices a group member stands for: itself when it is a device (or any other object, as
     * before), all devices below it when it is a sub-group.
     */
    private List<ManagedObjectRepresentation> devicesOf(String tenant, ManagedObjectRepresentation member) {
        if (!member.hasProperty("c8y_IsDeviceGroup")) {
            return List.of(member);
        }
        List<ManagedObjectRepresentation> devices = new ArrayList<>();
        List<Device> found = notificationSubscriber.findAllRelatedDevicesByMO(tenant, member, new ArrayList<>(), false);
        for (Device d : found == null ? List.<Device>of() : found) {
            ManagedObjectRepresentation mo = serviceRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, d.getId(), false);
            if (mo != null) {
                devices.add(mo);
            }
        }
        return devices;
    }

    /**
     * Ids of all devices reachable from the subscribed groups other than {@code leavingGroupId},
     * read live. The group cache expires after 24 hours and may only hold the inline child
     * references of a group, so it cannot be the only source for "still covered".
     *
     * @throws RuntimeException if the groups cannot be read: callers must not assume "not covered"
     */
    private Set<String> liveCoverage(String tenant, String leavingGroupId) {
        Set<String> covered = new HashSet<>();
        NotificationSubscriptionResponse groups = notificationSubscriber.getSubscriptionsByDeviceGroup(tenant);
        if (groups == null || groups.getDevices() == null) {
            return covered;
        }
        for (Device group : groups.getDevices()) {
            if (leavingGroupId.equals(group.getId())) {
                continue;
            }
            ManagedObjectRepresentation groupMO = serviceRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, group.getId(), false);
            if (groupMO == null) {
                continue;
            }
            List<Device> members = notificationSubscriber.findAllRelatedDevicesByMO(tenant, groupMO,
                    new ArrayList<>(), false);
            for (Device d : members == null ? List.<Device>of() : members) {
                covered.add(d.getId());
            }
        }
        return covered;
    }

    /**
     * True if the device is also a member of another subscribed group (live, or per the group cache)
     * or matches a configured type, in which case its dynamic subscription must stay.
     *
     * @throws RuntimeException if the type subscriptions cannot be read
     */
    private boolean isStillCovered(String tenant, String leavingGroupId, ManagedObjectRepresentation childMO,
            Set<String> coveredByOtherGroups) {
        String childId = childMO.getId().getValue();
        if (coveredByOtherGroups.contains(childId)) {
            return true;
        }
        for (String otherGroupId : groupCacheManager.getCache().keySet()) {
            if (!otherGroupId.equals(leavingGroupId)
                    && groupCacheManager.getSubscribedDevices(otherGroupId).contains(childId)) {
                return true;
            }
        }
        // A failed lookup must not read as "not covered": the caller would then unsubscribe a device
        // that a type subscription still needs. Let it propagate; the caller records the device as failed.
        List<String> types = notificationSubscriber
                .getSubscriptionsByDeviceType(tenant).getTypes();
        return types != null && childMO.getType() != null && types.contains(childMO.getType());
    }

}
