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

package dynamic.mapper.notification.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.*;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.messaging.notifications.*;
import dynamic.mapper.model.API;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages subscription CRUD operations.
 */
@Slf4j
@Service
public class SubscriptionManager {

    private static final String DEVICE_SUBSCRIPTION = "DynamicMapperDeviceSubscription";
    private static final String MANAGEMENT_SUBSCRIPTION = "DynamicMapperManagementSubscription";
    private static final String CACHE_INVENTORY_SUBSCRIPTION = "DynamicMapperCacheInventorySubscription";

    @Autowired
    private NotificationSubscriptionApi subscriptionAPI;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private NotificationConnectionManager connectionManager;

    @Autowired
    private MqttPushManager mqttPushManager;

    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    // Circuit breaker
    private final Set<String> processingDevices = Collections.synchronizedSet(new HashSet<>());

    // === Public API ===

    public boolean isNotificationServiceAvailable(String tenant) {
        if (tenant == null) {
            return false;
        }

        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                subscriptionAPI.getSubscriptions().get(1);
                log.debug("{} - Notification 2.0 service available", tenant);
                return true;
            } catch (SDKException e) {
                log.warn("{} - Notification 2.0 service unavailable: {}", tenant, e.getMessage());
                return false;
            }
        });
    }

    public Future<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(
            String tenant, ManagedObjectRepresentation mor, API api) {

        if (!isValid(mor) || tenant == null || api == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid parameters"));
        }

        String deviceId = mor.getId().getValue();
        
        // Prevent duplicate processing
        if (processingDevices.contains(deviceId)) {
            log.debug("{} - Device {} already being processed", tenant, deviceId);
            return CompletableFuture.completedFuture(null);
        }

        processingDevices.add(deviceId);

        return virtualThreadPool.submit(() -> {
            try {
                return subscriptionsService.callForTenant(tenant, () -> {
                    log.info("{} - Creating subscription for device: {}", tenant, deviceId);

                    // Create subscription
                    NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(
                            tenant, mor, api, DEVICE_SUBSCRIPTION);

                    // Initialize connections if needed
                    connectionManager.initializeConnectionsIfNeeded(tenant);

                    // Activate push connectivity
                    mqttPushManager.activatePushConnectivityForDevice(tenant, mor);

                    log.info("{} - Successfully subscribed device {}", tenant, deviceId);
                    return nsr;
                });
            } catch (Exception e) {
                log.error("{} - Error subscribing device {}: {}", tenant, deviceId, e.getMessage(), e);
                throw new RuntimeException("Failed to subscribe device: " + e.getMessage(), e);
            } finally {
                processingDevices.remove(deviceId);
            }
        });
    }

    public Future<NotificationSubscriptionRepresentation> subscribeByDeviceGroup(
            String tenant, ManagedObjectRepresentation mor) {

        if (!isValid(mor)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid ManagedObject"));
        }

        log.info("{} - Creating group subscription for {}", tenant, mor.getId().getValue());

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            try {
                NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(
                        tenant, mor, API.INVENTORY, MANAGEMENT_SUBSCRIPTION);
                log.info("{} - Successfully created group subscription", tenant);
                return nsr;
            } catch (Exception e) {
                log.error("{} - Error creating group subscription: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to create group subscription: " + e.getMessage(), e);
            }
        }));
    }

    public Future<NotificationSubscriptionRepresentation> subscribeMOForInventoryCacheUpdates(
            String tenant, ManagedObjectRepresentation mor) {

        if (!isValid(mor)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid ManagedObject"));
        }

        log.info("{} - Creating cache inventory subscription for {}", tenant, mor.getId().getValue());

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            try {
                NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(
                        tenant, mor, API.INVENTORY, CACHE_INVENTORY_SUBSCRIPTION);
                log.info("{} - Successfully created cache inventory subscription", tenant);
                return nsr;
            } catch (Exception e) {
                log.error("{} - Error creating cache inventory subscription: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to create cache inventory subscription: " + e.getMessage(), e);
            }
        }));
    }

    public boolean unsubscribeMOForInventoryCacheUpdates(String tenant, ManagedObjectRepresentation mor) {
        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(
                                new NotificationSubscriptionFilter()
                                        .bySource(mor.getId())
                                        .byContext("mo"))
                        .get().allPages().iterator();

                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    if (CACHE_INVENTORY_SUBSCRIPTION.equals(nsr.getSubscription())) {
                        subscriptionAPI.delete(nsr);
                        log.info("{} - Unsubscribed MO {} from cache updates", tenant, mor.getId().getValue());
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                log.error("{} - Error unsubscribing MO from cache updates: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to unsubscribe MO from cache updates: " + e.getMessage(), e);
            }
        });
    }

    public void unsubscribeAllMOForInventoryCacheUpdates(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(
                                new NotificationSubscriptionFilter()
                                        .bySubscription(CACHE_INVENTORY_SUBSCRIPTION))
                        .get().allPages().iterator();

                int deletedCount = 0;
                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    subscriptionAPI.delete(nsr);
                    deletedCount++;
                }
                log.info("{} - Unsubscribed {} MOs from cache updates", tenant, deletedCount);
            } catch (Exception e) {
                log.error("{} - Error unsubscribing all MOs from cache updates: {}", tenant, e.getMessage(), e);
            }
        });
    }

    public void unsubscribeDeviceAndDisconnect(String tenant, ManagedObjectRepresentation mor) {
        if (!isValid(mor)) {
            log.warn("Cannot unsubscribe device: invalid ManagedObject");
            return;
        }

        String deviceId = mor.getId().getValue();
        log.info("{} - Unsubscribing device {}", tenant, deviceId);

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                // Delete subscriptions
                int deletedCount = deleteSubscriptionsForDevice(tenant, deviceId, DEVICE_SUBSCRIPTION);
                log.info("{} - Deleted {} subscriptions for device {}", tenant, deletedCount, deviceId);

                // Disconnect if no more subscriptions
                if (shouldDisconnectAfterUnsubscribe(tenant)) {
                    connectionManager.disconnect(tenant);
                }

                // Deactivate push connectivity
                mqttPushManager.deactivatePushConnectivityForDevice(tenant, mor);

            } catch (Exception e) {
                log.error("{} - Error unsubscribing device {}: {}", tenant, deviceId, e.getMessage(), e);
            }
        });
    }

    public void unsubscribeByDeviceGroup(String tenant, ManagedObjectRepresentation mor) {
        if (!isValid(mor)) {
            log.warn("Cannot unsubscribe group: invalid ManagedObject");
            return;
        }

        String groupId = mor.getId().getValue();
        log.info("{} - Unsubscribing group {}", tenant, groupId);

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                int deletedCount = deleteSubscriptionsForDevice(tenant, groupId, MANAGEMENT_SUBSCRIPTION);
                log.info("{} - Deleted {} subscriptions for group {}", tenant, deletedCount, groupId);

                // Remove from cache
                connectionManager.removeGroupFromCache(tenant, mor);

            } catch (Exception e) {
                log.error("{} - Error unsubscribing group {}: {}", tenant, groupId, e.getMessage(), e);
            }
        });
    }

    public void unsubscribeAllDevices(String tenant) {
        log.info("{} - Unsubscribing all devices", tenant);

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                subscriptionAPI.deleteByFilter(
                        new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION));
                log.info("{} - Successfully unsubscribed all devices", tenant);
            } catch (Exception e) {
                log.error("{} - Error unsubscribing all devices: {}", tenant, e.getMessage(), e);
            }
        });
    }

    public NotificationSubscriptionResponse updateSubscriptionByType(String tenant, List<String> types) {
        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                // Get and delete existing subscription
                NotificationSubscriptionRepresentation existing = findExistingTypeSubscription();
                String existingTypeFilter = null;
                if (existing != null && existing.getSubscriptionFilter() != null) {
                    existingTypeFilter = existing.getSubscriptionFilter().getTypeFilter();
                    subscriptionAPI.delete(existing);
                    log.info("{} - Deleted existing type subscription", tenant);
                }

                // Create new subscription
                String newTypeFilter = Utils.createChangedTypeFilter(types, existingTypeFilter);
                NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = 
                        NotificationSubscriptionResponse.builder()
                                .subscriptionName(MANAGEMENT_SUBSCRIPTION);

                if (newTypeFilter != null && !newTypeFilter.trim().isEmpty()) {
                    NotificationSubscriptionRepresentation nsr = createTypeSubscription(newTypeFilter);
                    responseBuilder.types(new ArrayList<>(Utils.parseTypesFromFilter(newTypeFilter)))
                            .subscriptionId(nsr.getId().getValue())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE);
                    log.info("{} - Created type subscription with {} types", tenant, types.size());
                } else {
                    responseBuilder.types(new ArrayList<>())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.INACTIVE);
                }

                return responseBuilder.build();
            } catch (Exception e) {
                log.error("{} - Error updating type subscription: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to update type subscription: " + e.getMessage(), e);
            }
        });
    }

    // === Private Helper Methods ===

    private NotificationSubscriptionRepresentation createSubscriptionByMO(
            String tenant, ManagedObjectRepresentation mor, API api, String subscriptionName) {

        // Check if already exists
        try {
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(subscriptionName)
                                    .bySource(mor.getId()))
                    .get().allPages().iterator();

            while (subIt.hasNext()) {
                NotificationSubscriptionRepresentation existing = subIt.next();
                if (subscriptionName.equals(existing.getSubscription())) {
                    log.debug("{} - Subscription already exists for source {}",
                            tenant, mor.getId().getValue());
                    return existing;
                }
            }
        } catch (Exception e) {
            log.warn("{} - Error checking existing subscriptions: {}", tenant, e.getMessage());
        }

        // Create new
        try {
            NotificationSubscriptionRepresentation nsr = new NotificationSubscriptionRepresentation();
            nsr.setSource(mor);
            nsr.setContext("mo");
            nsr.setSubscription(subscriptionName);

            NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
            filter.setApis(List.of(api.notificationFilter));
            nsr.setSubscriptionFilter(filter);

            NotificationSubscriptionRepresentation result = subscriptionAPI.subscribe(nsr);
            log.debug("{} - Created subscription for source {}", tenant, mor.getId().getValue());
            return result;
        } catch (Exception e) {
            log.error("{} - Error creating subscription: {}", tenant, e.getMessage(), e);
            throw new RuntimeException("Failed to create subscription: " + e.getMessage(), e);
        }
    }

    private int deleteSubscriptionsForDevice(String tenant, String deviceId, String subscriptionName) {
        try {
            GId id = new GId();
            id.setValue(deviceId);

            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(subscriptionName)
                                    .bySource(id)
                                    .byContext("mo"))
                    .get().allPages().iterator();

            int deletedCount = 0;
            while (subIt.hasNext()) {
                NotificationSubscriptionRepresentation sub = subIt.next();
                subscriptionAPI.delete(sub);
                deletedCount++;
            }
            return deletedCount;
        } catch (Exception e) {
            log.error("{} - Error deleting subscriptions for device {}: {}",
                    tenant, deviceId, e.getMessage(), e);
            return 0;
        }
    }

    private boolean shouldDisconnectAfterUnsubscribe(String tenant) {
        try {
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator();

            return !subIt.hasNext(); // Disconnect if no more subscriptions
        } catch (Exception e) {
            log.warn("{} - Error checking remaining subscriptions: {}", tenant, e.getMessage());
            return false;
        }
    }

    private NotificationSubscriptionRepresentation findExistingTypeSubscription() {
        try {
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(MANAGEMENT_SUBSCRIPTION)
                                    .byContext("tenant"))
                    .get().allPages().iterator();

            while (subIt.hasNext()) {
                NotificationSubscriptionRepresentation nsr = subIt.next();
                if ("tenant".equals(nsr.getContext())) {
                    return nsr;
                }
            }
        } catch (Exception e) {
            log.warn("Error finding existing type subscription: {}", e.getMessage());
        }
        return null;
    }

    private NotificationSubscriptionRepresentation createTypeSubscription(String typeFilter) {
        try {
            NotificationSubscriptionRepresentation nsr = new NotificationSubscriptionRepresentation();
            nsr.setContext("tenant");
            nsr.setSubscription(MANAGEMENT_SUBSCRIPTION);

            NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
            filter.setApis(List.of(API.INVENTORY.notificationFilter));
            filter.setTypeFilter(typeFilter);
            nsr.setSubscriptionFilter(filter);

            return subscriptionAPI.subscribe(nsr);
        } catch (Exception e) {
            log.error("Error creating type subscription: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create type subscription: " + e.getMessage(), e);
        }
    }

    private boolean isValid(ManagedObjectRepresentation mor) {
        return mor != null && mor.getId() != null;
    }
}
