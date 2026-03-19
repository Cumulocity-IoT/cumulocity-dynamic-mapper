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

package dynamic.mapper.notification;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GroupCacheManager {
    
    private static final int MAX_CACHE_SIZE = 10000;
    private static final int CACHE_CLEANUP_INTERVAL_MINUTES = 30;

    private final String tenant;
    private final Map<String, CachedGroup> groupCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cacheCleanupExecutor;

    public GroupCacheManager(String tenant) {
        this.tenant = tenant;
        this.cacheCleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "group-cache-cleanup-" + tenant);
            t.setDaemon(true);
            return t;
        });
        
        this.cacheCleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            CACHE_CLEANUP_INTERVAL_MINUTES,
            CACHE_CLEANUP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
    }

    public Map<String, CachedGroup> getCache() {
        return Collections.unmodifiableMap(groupCache);
    }

    /**
     * Returns the device IDs that were last seen as members of the given group.
     * Returns an empty set if the group is not cached or has never been updated.
     */
    public Set<String> getSubscribedDevices(String groupId) {
        CachedGroup entry = groupCache.get(groupId);
        if (entry == null) {
            return new HashSet<>();
        }
        return entry.getSubscribedDeviceIds();
    }

    /**
     * Updates the tracked device membership for a group after a notification is processed.
     * Creates a new CachedGroup entry preserving the existing MO (if any).
     */
    public void updateSubscribedDevices(String groupId, Set<String> deviceIds) {
        CachedGroup existing = groupCache.get(groupId);
        ManagedObjectRepresentation groupMO = (existing != null) ? existing.getGroup() : null;
        groupCache.put(groupId, new CachedGroup(groupMO, LocalDateTime.now(), new HashSet<>(deviceIds)));
        log.debug("{} - Updated subscribed devices for group {}: {} devices", tenant, groupId, deviceIds.size());
    }

    public void addGroup(ManagedObjectRepresentation groupMO) {
        if (groupMO == null || groupMO.getId() == null) {
            log.warn("{} - Cannot add null group to cache", tenant);
            return;
        }

        String groupId = groupMO.getId().getValue();

        if (groupCache.size() >= MAX_CACHE_SIZE) {
            log.warn("{} - Cache size limit reached, cleaning up old entries", tenant);
            cleanupOldestEntries();
        }

        // Preserve existing subscribedDeviceIds across reconnects; if none, try to
        // extract them from the MO's childAssets.references (best-effort — C8Y may or
        // may not embed the first page of references inline).
        Set<String> deviceIds = getSubscribedDevices(groupId);
        if (deviceIds.isEmpty()) {
            deviceIds = extractChildIdsFromMO(groupMO);
        }

        groupCache.put(groupId, new CachedGroup(groupMO, LocalDateTime.now(), deviceIds));
        log.debug("{} - Cached group {} with {} known member device(s)", tenant, groupId, deviceIds.size());
    }

    /**
     * Best-effort extraction of child device IDs from a ManagedObject returned by
     * the C8Y inventory API. The API may embed the first page of childAssets
     * references inline; if not, the returned set will be empty and the state will
     * be populated on the first UPDATE notification instead.
     */
    private Set<String> extractChildIdsFromMO(ManagedObjectRepresentation groupMO) {
        Set<String> childIds = new HashSet<>();
        try {
            if (groupMO.getChildAssets() != null && groupMO.getChildAssets().getReferences() != null) {
                for (ManagedObjectReferenceRepresentation ref : groupMO.getChildAssets().getReferences()) {
                    if (ref != null && ref.getManagedObject() != null
                            && ref.getManagedObject().getId() != null) {
                        childIds.add(ref.getManagedObject().getId().getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("{} - Could not extract child IDs from group MO: {}", tenant, e.getMessage());
        }
        return childIds;
    }

    public void removeGroup(ManagedObjectRepresentation groupMO) {
        if (groupMO == null || groupMO.getId() == null) {
            log.warn("{} - Cannot remove null group from cache", tenant);
            return;
        }
        
        String groupId = groupMO.getId().getValue();
        groupCache.remove(groupId);
        log.debug("{} - Removed group {} from cache", tenant, groupId);
    }

    private void cleanupExpiredEntries() {
        // disabled as this breaks functionality to detect changes in group membership
        //LocalDateTime expiredBefore = LocalDateTime.now().minusHours(24);
        int removedCount = 0;
        
        // Iterator<Map.Entry<String, CachedGroup>> iterator = groupCache.entrySet().iterator();
        // while (iterator.hasNext()) {
        //     Map.Entry<String, CachedGroup> entry = iterator.next();
        //     if (entry.getValue().getLastUpdated().isBefore(expiredBefore)) {
        //         iterator.remove();
        //         removedCount++;
        //     }
        // }
        
        if (removedCount > 0) {
            log.info("{} - Cleaned up {} expired cache entries", tenant, removedCount);
        }
    }

    private void cleanupOldestEntries() {
        int entriesToRemove = groupCache.size() - (MAX_CACHE_SIZE * 3 / 4);
        
        groupCache.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(
                Comparator.comparing(CachedGroup::getLastUpdated)))
            .limit(entriesToRemove)
            .map(Map.Entry::getKey)
            .forEach(groupCache::remove);
        
        log.info("{} - Removed {} oldest cache entries", tenant, entriesToRemove);
    }

    public void cleanup() {
        if (cacheCleanupExecutor != null && !cacheCleanupExecutor.isShutdown()) {
            try {
                cacheCleanupExecutor.shutdown();
                if (!cacheCleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cacheCleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cacheCleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        groupCache.clear();
        log.info("{} - GroupCacheManager cleanup completed", tenant);
    }

    @Getter
    public static class CachedGroup {
        private final ManagedObjectRepresentation group;
        private final LocalDateTime lastUpdated;
        private final Set<String> subscribedDeviceIds;

        public CachedGroup(ManagedObjectRepresentation group, LocalDateTime lastUpdated) {
            this.group = group;
            this.lastUpdated = lastUpdated;
            this.subscribedDeviceIds = new HashSet<>();
        }

        public CachedGroup(ManagedObjectRepresentation group, LocalDateTime lastUpdated, Set<String> subscribedDeviceIds) {
            this.group = group;
            this.lastUpdated = lastUpdated;
            this.subscribedDeviceIds = subscribedDeviceIds;
        }
    }
}
