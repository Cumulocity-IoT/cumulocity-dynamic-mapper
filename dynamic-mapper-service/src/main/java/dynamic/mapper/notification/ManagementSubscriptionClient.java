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

import static com.dashjoin.jsonata.Jsonata.jsonata;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.dashjoin.jsonata.json.Json;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.ProcessingResult;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.C8YMessage;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class ManagementSubscriptionClient implements NotificationCallback {

    public static final String CONNECTOR_NAME = "MANAGEMENT_SUBSCRIPTION_CONNECTOR";
    public static final String CONNECTOR_ID = "MANAGEMENT_SUBSCRIPTION_ID";
    
    private static final int MAX_CACHE_SIZE = 10000;
    private static final int CACHE_CLEANUP_INTERVAL_MINUTES = 30;

    private final String tenant;
    private final ExecutorService virtualThreadPool;
    private final ConfigurationRegistry configurationRegistry;
    private final NotificationSubscriber notificationSubscriber;
    
    // Thread-safe cache with cleanup mechanism
    private final Map<String, CachedGroup> groupCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cacheCleanupExecutor;
    
    // Track processing to prevent race conditions
    private final Set<String> processingGroups = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> processingDevices = Collections.synchronizedSet(new HashSet<>());

    public ManagementSubscriptionClient(ConfigurationRegistry configurationRegistry, String tenant) {
        this.tenant = tenant;
        this.configurationRegistry = configurationRegistry;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
        
        // Initialize cache cleanup scheduler
        this.cacheCleanupExecutor = Executors.newScheduledThreadPool(1);
        this.cacheCleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredCacheEntries, 
            CACHE_CLEANUP_INTERVAL_MINUTES, 
            CACHE_CLEANUP_INTERVAL_MINUTES, 
            TimeUnit.MINUTES
        );
        
        log.info("{} - ManagementSubscriptionClient initialized", tenant);
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, managing subscriptions", tenant);
        notificationSubscriber.setDeviceConnectionStatus(tenant, 200);
    }

    @Override
    public ProcessingResult<?> onNotification(Notification notification) {
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder()
            .consolidatedQos(consolidatedQos)
            .build();

        String notificationTenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
        
        try {
            String operation = notification.getOperation();
            if ("UPDATE".equals(operation)) {
                return handleUpdateNotification(notification, notificationTenant);
            } else if ("CREATE".equals(operation)) {
                return handleCreateNotification(notification, notificationTenant);
            } else {
                log.debug("{} - Ignoring notification with operation: {}", notificationTenant, operation);
            }
        } catch (Exception e) {
            log.error("{} - Error processing notification: {}", notificationTenant, e.getMessage(), e);
            result = ProcessingResult.builder()
                .consolidatedQos(consolidatedQos)
                .error(e)
                .build();
        }
        
        return result;
    }

    private ProcessingResult<?> handleUpdateNotification(Notification notification, String notificationTenant) {
        C8YMessage message = createC8YMessage(notification, notificationTenant);
        log.debug("{} - Handling UPDATE notification for: {}", notificationTenant, message.getSourceId());
        
        Future<?> future = virtualThreadPool.submit(
            new UpdateSubscriptionDeviceGroupTask(configurationRegistry, message, groupCache));
        
        return ProcessingResult.builder()
            .consolidatedQos(Qos.AT_LEAST_ONCE)
            .future(future)
            .build();
    }

    private ProcessingResult<?> handleCreateNotification(Notification notification, String notificationTenant) {
        C8YMessage message = createC8YMessage(notification, notificationTenant);
        log.debug("{} - Handling CREATE notification for: {}", notificationTenant, message.getSourceId());
        
        Future<?> future = virtualThreadPool.submit(
            new UpdateSubscriptionDeviceTypeTask(configurationRegistry, message));
        
        return ProcessingResult.builder()
            .consolidatedQos(Qos.AT_LEAST_ONCE)
            .future(future)
            .build();
    }

    private C8YMessage createC8YMessage(Notification notification, String notificationTenant) {
        C8YMessage message = new C8YMessage();
        Map<String, Object> parsedPayload = parsePayload(notification.getMessage());
        
        message.setParsedPayload(parsedPayload);
        message.setApi(notification.getApi());
        message.setOperation(notification.getOperation());
        message.setMessageId(String.valueOf(parsedPayload.get("id")));
        message.setSourceId(extractSourceId(parsedPayload, notification.getApi()));
        message.setPayload(notification.getMessage());
        message.setTenant(notificationTenant);
        message.setSendPayload(true);
        
        return message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payload) {
        try {
            return (Map<String, Object>) Json.parseJson(payload);
        } catch (Exception e) {
            log.warn("Failed to parse notification payload: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String extractSourceId(Map<String, Object> parsedPayload, dynamic.mapper.model.API api) {
        try {
            var expression = jsonata(api.identifier);
            Object result = expression.evaluate(parsedPayload);
            return result instanceof String ? (String) result : null;
        } catch (Exception e) {
            log.warn("Could not extract source.id from payload: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - WebSocket error occurred: {}", tenant, t.getMessage(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed with status {} and reason: {}", 
                tenant, statusCode, reason);
        
        if (reason != null && reason.contains("401")) {
            notificationSubscriber.setDeviceConnectionStatus(tenant, 401);
        } else {
            notificationSubscriber.setDeviceConnectionStatus(tenant, null);
        }
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        if (notificationHeaders == null || notificationHeaders.isEmpty()) {
            log.warn("No notification headers provided");
            return tenant; // fallback to instance tenant
        }
        
        try {
            return notificationHeaders.get(0).split("/")[1];
        } catch (Exception e) {
            log.warn("Failed to extract tenant from headers: {}", e.getMessage());
            return tenant; // fallback to instance tenant
        }
    }

    public void addGroupToCache(ManagedObjectRepresentation groupMO) {
        if (groupMO == null || groupMO.getId() == null) {
            log.warn("{} - Cannot add null group to cache", tenant);
            return;
        }
        
        String groupId = groupMO.getId().getValue();
        
        // Check cache size limit
        if (groupCache.size() >= MAX_CACHE_SIZE) {
            log.warn("{} - Group cache size limit reached, cleaning up old entries", tenant);
            cleanupOldestCacheEntries();
        }
        
        CachedGroup cachedGroup = new CachedGroup(groupMO, LocalDateTime.now());
        groupCache.put(groupId, cachedGroup);
        log.debug("{} - Added group {} to cache", tenant, groupId);
    }

    public void removeGroupFromCache(ManagedObjectRepresentation groupMO) {
        if (groupMO == null || groupMO.getId() == null) {
            log.warn("{} - Cannot remove null group from cache", tenant);
            return;
        }
        
        String groupId = groupMO.getId().getValue();
        groupCache.remove(groupId);
        log.debug("{} - Removed group {} from cache", tenant, groupId);
    }

    private void cleanupExpiredCacheEntries() {
        LocalDateTime expiredBefore = LocalDateTime.now().minusHours(24);
        int removedCount = 0;
        
        Iterator<Map.Entry<String, CachedGroup>> iterator = groupCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedGroup> entry = iterator.next();
            if (entry.getValue().getLastUpdated().isBefore(expiredBefore)) {
                iterator.remove();
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            log.info("{} - Cleaned up {} expired cache entries", tenant, removedCount);
        }
    }

    private void cleanupOldestCacheEntries() {
        int entriesToRemove = groupCache.size() - (MAX_CACHE_SIZE * 3 / 4); // Remove 25% of entries
        
        groupCache.entrySet().stream()
            .sorted(Map.Entry.<String, CachedGroup>comparingByValue(
                Comparator.comparing(CachedGroup::getLastUpdated)))
            .limit(entriesToRemove)
            .map(Map.Entry::getKey)
            .forEach(groupCache::remove);
        
        log.info("{} - Removed {} oldest cache entries", tenant, entriesToRemove);
    }

    @PreDestroy
    public void cleanup() {
        log.info("{} - Cleaning up ManagementSubscriptionClient resources", tenant);
        
        // Shutdown cache cleanup executor
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
        
        // Clear caches
        groupCache.clear();
        processingGroups.clear();
        processingDevices.clear();
        
        log.info("{} - ManagementSubscriptionClient cleanup completed", tenant);
    }

    // Inner class for cached groups
    private static class CachedGroup {
        private final ManagedObjectRepresentation group;
        private final LocalDateTime lastUpdated;

        public CachedGroup(ManagedObjectRepresentation group, LocalDateTime lastUpdated) {
            this.group = group;
            this.lastUpdated = lastUpdated;
        }

        public ManagedObjectRepresentation getGroup() {
            return group;
        }

        public LocalDateTime getLastUpdated() {
            return lastUpdated;
        }
    }

    // Improved UpdateSubscriptionDeviceGroupTask
    public static class UpdateSubscriptionDeviceGroupTask implements Callable<List<Future<NotificationSubscriptionRepresentation>>> {
        private final C8YMessage c8yMessage;
        private final ConfigurationRegistry configurationRegistry;
        private final NotificationSubscriber notificationSubscriber;
        private final Map<String, CachedGroup> groupCache;

        public UpdateSubscriptionDeviceGroupTask(ConfigurationRegistry configurationRegistry,
                C8YMessage c8yMessage, Map<String, CachedGroup> groupCache) {
            this.c8yMessage = c8yMessage;
            this.configurationRegistry = configurationRegistry;
            this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
            this.groupCache = groupCache;
        }

        @Override
        public List<Future<NotificationSubscriptionRepresentation>> call() throws Exception {
            List<Future<NotificationSubscriptionRepresentation>> results = new ArrayList<>();
            String tenant = c8yMessage.getTenant();
            String groupId = c8yMessage.getSourceId();
            
            if (groupId == null || groupId.trim().isEmpty()) {
                log.warn("{} - No group ID found in message, skipping update", tenant);
                return results;
            }

            try {
                CachedGroup cachedGroup = groupCache.get(groupId);
                if (cachedGroup == null) {
                    log.warn("{} - Group {} not found in cache, skipping subscription update", tenant, groupId);
                    return results;
                }

                List<String> cachedChildIds = extractChildIds(cachedGroup.getGroup());
                List<String> payloadChildIds = extractChildIdsFromPayload(c8yMessage.getParsedPayload());

                List<String> toAdd = calculateDifference(payloadChildIds, cachedChildIds);
                List<String> toRemove = calculateDifference(cachedChildIds, payloadChildIds);

                // Process additions
                processChildAdditions(tenant, toAdd, results);

                // Process removals
                processChildRemovals(tenant, toRemove);

                // Update cache if changes occurred
                if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                    updateGroupCache(tenant, groupId);
                    log.info("{} - Updated group {} subscription: +{} devices, -{} devices", 
                            tenant, groupId, toAdd.size(), toRemove.size());
                }

            } catch (Exception e) {
                log.error("{} - Error updating group {} subscription: {}", tenant, groupId, e.getMessage(), e);
                throw e;
            }

            return results;
        }

        private List<String> extractChildIds(ManagedObjectRepresentation group) {
            List<String> childIds = new ArrayList<>();
            try {
                if (group.getChildAssets() != null) {
                    for (ManagedObjectReferenceRepresentation child : group.getChildAssets().getReferences()) {
                        childIds.add(child.getManagedObject().getId().getValue());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract cached child assets: {}", e.getMessage());
            }
            return childIds;
        }

        @SuppressWarnings("unchecked")
        private List<String> extractChildIdsFromPayload(Map<String, Object> payload) {
            List<String> childIds = new ArrayList<>();
            try {
                Object childAssets = payload.get("childAssets");
                if (childAssets instanceof Map) {
                    Object references = ((Map<String, Object>) childAssets).get("references");
                    if (references instanceof List) {
                        for (Object ref : (List<?>) references) {
                            if (ref instanceof Map) {
                                Object managedObject = ((Map<?, ?>) ref).get("managedObject");
                                if (managedObject instanceof Map) {
                                    Object id = ((Map<?, ?>) managedObject).get("id");
                                    if (id != null) {
                                        childIds.add(String.valueOf(id));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract payload child assets: {}", e.getMessage());
            }
            return childIds;
        }

        private List<String> calculateDifference(List<String> list1, List<String> list2) {
            List<String> difference = new ArrayList<>(list1);
            difference.removeAll(list2);
            return difference;
        }

        private void processChildAdditions(String tenant, List<String> toAdd, 
                List<Future<NotificationSubscriptionRepresentation>> results) {
            for (String childId : toAdd) {
                try {
                    ManagedObjectRepresentation childMO = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childId);
                    if (childMO != null) {
                        Future<NotificationSubscriptionRepresentation> future = notificationSubscriber
                                .subscribeDeviceAndConnect(tenant, childMO, c8yMessage.getApi());
                        results.add(future);
                        log.debug("{} - Subscribed child device {} to group notifications", tenant, childId);
                    } else {
                        log.warn("{} - Child device {} not found for subscription", tenant, childId);
                    }
                } catch (Exception e) {
                    log.error("{} - Failed to subscribe child device {}: {}", tenant, childId, e.getMessage(), e);
                }
            }
        }

        private void processChildRemovals(String tenant, List<String> toRemove) {
            for (String childId : toRemove) {
                try {
                    ManagedObjectRepresentation childMO = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childId);
                    if (childMO != null) {
                        notificationSubscriber.unsubscribeDeviceAndDisconnect(tenant, childMO);
                        log.debug("{} - Unsubscribed child device {} from group notifications", tenant, childId);
                    } else {
                        log.warn("{} - Child device {} not found for unsubscription", tenant, childId);
                    }
                } catch (Exception e) {
                    log.error("{} - Failed to unsubscribe child device {}: {}", tenant, childId, e.getMessage(), e);
                }
            }
        }

        private void updateGroupCache(String tenant, String groupId) {
            try {
                ManagedObjectRepresentation updatedGroup = configurationRegistry.getC8yAgent()
                        .getManagedObjectForId(tenant, groupId);
                if (updatedGroup != null) {
                    groupCache.put(groupId, new CachedGroup(updatedGroup, LocalDateTime.now()));
                    log.debug("{} - Updated group cache for {}", tenant, groupId);
                }
            } catch (Exception e) {
                log.warn("{} - Failed to update group cache for {}: {}", tenant, groupId, e.getMessage());
            }
        }
    }

    // Improved UpdateSubscriptionDeviceTypeTask
    public static class UpdateSubscriptionDeviceTypeTask implements Callable<List<Future<NotificationSubscriptionRepresentation>>> {
        private final C8YMessage c8yMessage;
        private final ConfigurationRegistry configurationRegistry;
        private final NotificationSubscriber notificationSubscriber;

        public UpdateSubscriptionDeviceTypeTask(ConfigurationRegistry configurationRegistry, C8YMessage c8yMessage) {
            this.c8yMessage = c8yMessage;
            this.configurationRegistry = configurationRegistry;
            this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
        }

        @Override
        public List<Future<NotificationSubscriptionRepresentation>> call() throws Exception {
            List<Future<NotificationSubscriptionRepresentation>> results = new ArrayList<>();
            String tenant = c8yMessage.getTenant();
            String deviceId = c8yMessage.getSourceId();
            
            if (deviceId == null || deviceId.trim().isEmpty()) {
                log.warn("{} - No device ID found in message, skipping type subscription update", tenant);
                return results;
            }

            Object typeObj = c8yMessage.getParsedPayload().get("type");
            if (typeObj == null) {
                log.warn("{} - No type found in payload for device {}, skipping subscription update", 
                        tenant, deviceId);
                return results;
            }

            String type = String.valueOf(typeObj);
            log.info("{} - Processing new device {} of type {}", tenant, deviceId, type);

            try {
                ManagedObjectRepresentation newMO = new ManagedObjectRepresentation();
                newMO.setId(GId.asGId(deviceId));

                Future<NotificationSubscriptionRepresentation> future = notificationSubscriber
                        .subscribeDeviceAndConnect(tenant, newMO, c8yMessage.getApi());
                results.add(future);
                
                log.info("{} - Successfully subscribed new device {} of type {}", tenant, deviceId, type);
            } catch (Exception e) {
                log.error("{} - Failed to subscribe new device {} of type {}: {}", 
                        tenant, deviceId, type, e.getMessage(), e);
                throw e;
            }

            return results;
        }
    }
}