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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        
        CachedGroup cachedGroup = new CachedGroup(groupMO, LocalDateTime.now());
        groupCache.put(groupId, cachedGroup);
        log.debug("{} - Added group {} to cache", tenant, groupId);
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

        public CachedGroup(ManagedObjectRepresentation group, LocalDateTime lastUpdated) {
            this.group = group;
            this.lastUpdated = lastUpdated;
        }
    }
}
