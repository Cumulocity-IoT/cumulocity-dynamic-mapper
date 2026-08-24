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
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.notification.Utils;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

/**
 * Manages subscription CRUD operations.
 */
@Slf4j
@Service
public class SubscriptionManager {

    private final NotificationSubscriptionApi subscriptionAPI;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final NotificationConnectionManager connectionManager;
    private final MqttPushManager mqttPushManager;
    private final ExecutorService virtualThreadPool;
    private final ConfigurationRegistry configurationRegistry;

    public SubscriptionManager(NotificationSubscriptionApi subscriptionAPI,
                                MicroserviceSubscriptionsService subscriptionsService,
                                NotificationConnectionManager connectionManager,
                                MqttPushManager mqttPushManager,
                                @Qualifier("virtualThreadPool") ExecutorService virtualThreadPool,
                                @Lazy ConfigurationRegistry configurationRegistry) {
        this.subscriptionAPI = subscriptionAPI;
        this.subscriptionsService = subscriptionsService;
        this.connectionManager = connectionManager;
        this.mqttPushManager = mqttPushManager;
        this.virtualThreadPool = virtualThreadPool;
        this.configurationRegistry = configurationRegistry;
    }

    // H1+H2: use ConcurrentHashMap.newKeySet() so each add() is atomic (no separate contains)
    // and keys are tenant-scoped to avoid cross-tenant collisions.
    private final Set<String> processingDevices = ConcurrentHashMap.newKeySet();

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
            String tenant, ManagedObjectRepresentation mor, API api, String subscription) {

        if (!isValid(mor) || tenant == null || api == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid parameters"));
        }

        String deviceId = mor.getId().getValue();
        String processingKey = tenant + ":" + deviceId;  // H2: tenant-scoped key

        // H1: single atomic add() — returns false if already present, eliminating the race
        if (!processingDevices.add(processingKey)) {
            log.debug("{} - Device {} already being processed", tenant, deviceId);
            return CompletableFuture.completedFuture(null);
        }

        return virtualThreadPool.submit(() -> {
            try {
                return subscriptionsService.callForTenant(tenant, () -> {
                    // Deduplication: enforce priority order group/dynamic (2/3) > static (1)
                    if (checkAndHandleDeduplication(tenant, deviceId, subscription)) {
                        log.info("{} - Skipping subscription for device {} due to deduplication (subscription={})",
                                tenant, deviceId, subscription);
                        return null;
                    }

                    log.info("{} - Creating subscription for device: {}", tenant, deviceId);

                    // Create subscription
                    NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(
                            tenant, mor, api, subscription);

                    // Reconnect existing static AND dynamic WebSocket clients so they immediately
                    // receive notifications from the newly created device subscription without
                    // waiting for the 60-second reconnect cycle. Both must be triggered: a device
                    // subscription can land in either bucket, and an already-open client in either
                    // one never learns about a device added afterward otherwise.
                    connectionManager.reconnectStaticDeviceClientsForNewSubscription(tenant);
                    connectionManager.reconnectDynamicDeviceClientsForNewSubscription(tenant);

                    // Activate push connectivity
                    mqttPushManager.activatePushConnectivityForDevice(tenant, mor);

                    log.info("{} - Successfully subscribed device {}", tenant, deviceId);
                    return nsr;
                });
            } catch (Exception e) {
                log.error("{} - Error subscribing device {}: {}", tenant, deviceId, e.getMessage(), e);
                throw new RuntimeException("Failed to subscribe device: " + e.getMessage(), e);
            } finally {
                processingDevices.remove(processingKey);
            }
        });
    }

    /**
     * Batch variant of {@link #subscribeDeviceAndConnect(String, ManagedObjectRepresentation, API, String)}
     * for bulk callers requesting {@link Utils#DYNAMIC_DEVICE_SUBSCRIPTION} for many devices at once (e.g.
     * resyncing all existing devices of a type). If {@code knownDynamicDeviceIds} already contains this
     * device's id, it's assumed to already have a dynamic subscription — mirroring
     * {@code checkAndHandleDeduplication}'s dynamic-vs-dynamic skip rule — and the call is skipped without
     * hitting the per-device lookup GETs. Only takes effect when {@code subscription} is
     * {@link Utils#DYNAMIC_DEVICE_SUBSCRIPTION}; for any other subscription family this behaves exactly
     * like the 4-arg overload (other families, e.g. {@link Utils#EXPLORER_DEVICE_SUBSCRIPTION}, are not
     * deduplicated against dynamic subscriptions — see {@code checkAndHandleDeduplication}). Pass
     * {@code null} to always fall back to the unconditional single-device path.
     */
    public Future<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(
            String tenant, ManagedObjectRepresentation mor, API api, String subscription,
            Set<String> knownDynamicDeviceIds) {

        if (knownDynamicDeviceIds != null && isValid(mor)
                && Utils.DYNAMIC_DEVICE_SUBSCRIPTION.equals(subscription)
                && knownDynamicDeviceIds.contains(mor.getId().getValue())) {
            log.debug("{} - Device {} already dynamically subscribed (batch pre-check), skipping",
                    tenant, mor.getId().getValue());
            return CompletableFuture.completedFuture(null);
        }
        return subscribeDeviceAndConnect(tenant, mor, api, subscription);
    }

    /**
     * Fetches all device ids currently holding a subscription of the given name, tenant-wide, via a
     * single paged query. Intended for bulk callers that would otherwise call
     * {@code checkAndHandleDeduplication} once per device (2 filtered GETs each) — call this once up
     * front instead and check membership in-memory.
     */
    public Set<String> fetchDeviceIdsForSubscription(String tenant, String subscriptionName) {
        return subscriptionsService.callForTenant(tenant, () -> {
            Set<String> ids = new HashSet<>();
            try {
                Iterator<NotificationSubscriptionRepresentation> it = subscriptionAPI
                        .getSubscriptionsByFilter(
                                new NotificationSubscriptionFilter()
                                        .bySubscription(subscriptionName)
                                        .byContext("mo"))
                        .get().allPages().iterator();
                while (it.hasNext()) {
                    ids.add(it.next().getSource().getId().getValue());
                }
            } catch (Exception e) {
                log.warn("{} - Error fetching device ids for subscription {}: {}",
                        tenant, subscriptionName, e.getMessage());
            }
            return ids;
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
                        tenant, mor, API.INVENTORY, Utils.MANAGEMENT_SUBSCRIPTION);
                // Add group to cache so UpdateSubscriptionDeviceGroupTask can find it
                connectionManager.addGroupToCache(tenant, mor);
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
                        tenant, mor, API.INVENTORY, Utils.CACHE_INVENTORY_SUBSCRIPTION);
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
                    if (Utils.CACHE_INVENTORY_SUBSCRIPTION.equals(nsr.getSubscription())) {
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
                                        .bySubscription(Utils.CACHE_INVENTORY_SUBSCRIPTION))
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

    public void unsubscribeDeviceAndDisconnect(String tenant, ManagedObjectRepresentation mor, String subscription) {
        if (!isValid(mor)) {
            log.warn("Cannot unsubscribe device: invalid ManagedObject");
            return;
        }

        String deviceId = mor.getId().getValue();
        log.info("{} - Unsubscribing device {}", tenant, deviceId);

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                // Delete subscriptions
                int deletedCount = deleteSubscriptionsForDevice(tenant, deviceId, subscription);
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
                int deletedCount = deleteSubscriptionsForDevice(tenant, groupId, Utils.MANAGEMENT_SUBSCRIPTION);
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
                Iterator<NotificationSubscriptionRepresentation> staticIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                        new NotificationSubscriptionFilter().bySubscription(Utils.STATIC_DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator();
                int deletedCount = 0;
                while (staticIt.hasNext()) {
                    subscriptionAPI.delete(staticIt.next());
                    deletedCount++;
                }

                Iterator<NotificationSubscriptionRepresentation> dynamicIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                        new NotificationSubscriptionFilter().bySubscription(Utils.DYNAMIC_DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator();
                while (dynamicIt.hasNext()) {
                    subscriptionAPI.delete(dynamicIt.next());
                    deletedCount++;
                }
                log.info("{} - Successfully unsubscribed {} devices", tenant, deletedCount);
            } catch (Exception e) {
                log.error("{} - Error unsubscribing all devices: {}", tenant, e.getMessage(), e);
            }
        });
    }

    public NotificationSubscriptionResponse updateSubscriptionByType(String tenant, List<String> types) {
        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                NotificationSubscriptionRepresentation existing = findExistingTypeSubscription();
                String existingTypeFilter = null;
                if (existing != null && existing.getSubscriptionFilter() != null) {
                    existingTypeFilter = existing.getSubscriptionFilter().getTypeFilter();
                }

                String newTypeFilter = Utils.createChangedTypeFilter(types, existingTypeFilter);
                NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = NotificationSubscriptionResponse
                        .builder()
                        .subscriptionName(Utils.MANAGEMENT_SUBSCRIPTION);

                if (newTypeFilter != null && !newTypeFilter.trim().isEmpty()) {
                    // DELETE first, then CREATE.
                    // C8Y subscriptions are keyed by (subscription-name, context): only one
                    // "DynamicMapperManagementSubscription / tenant" entry may exist at a time.
                    // Creating a new one while the old one is still present always returns 409,
                    // which caused createTypeSubscription() to return the stale existing NSR —
                    // followed by deleting it — leaving zero types registered.
                    if (existing != null) {
                        subscriptionAPI.delete(existing);
                        log.info("{} - Deleted old type subscription before re-creating with new filter", tenant);
                    }
                    NotificationSubscriptionRepresentation nsr = createTypeSubscription(newTypeFilter);
                    responseBuilder.types(new ArrayList<>(Utils.parseTypesFromFilter(newTypeFilter)))
                            .subscriptionId(nsr.getId().getValue())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE);
                    log.info("{} - Created type subscription with {} types", tenant, types.size());
                } else {
                    // No new filter — just delete the existing one
                    if (existing != null) {
                        subscriptionAPI.delete(existing);
                        log.info("{} - Deleted type subscription (no replacement needed)", tenant);
                    }
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

    /**
     * Resyncs already-existing devices of {@code type} into the dynamic device subscription
     * bucket. Notification 2.0's tenant-level type filter only fires on future inventory CREATE
     * events (see the notification package Javadoc) — devices that existed before the type was
     * added to the filter are never picked up automatically. This scans the current inventory for
     * {@code type} and subscribes any device not already covered.
     *
     * <p>{@code type} must already be part of the tenant's configured type filter
     * ({@link #updateSubscriptionByType}) — this resyncs an existing configuration, it does not
     * add a new type. The scan itself runs in the background; this method only validates and
     * kicks it off.
     *
     * @throws IllegalArgumentException if {@code type} is not currently configured
     */
    public void resyncTypeSubscription(String tenant, String type) {
        NotificationSubscriptionRepresentation existing = subscriptionsService.callForTenant(tenant,
                this::findExistingTypeSubscription);
        String existingTypeFilter = existing != null && existing.getSubscriptionFilter() != null
                ? existing.getSubscriptionFilter().getTypeFilter()
                : null;
        Set<String> configuredTypes = Utils.parseTypesFromFilter(existingTypeFilter);

        if (!configuredTypes.contains(type)) {
            throw new IllegalArgumentException(
                    "Type '" + type + "' is not part of the configured type subscription");
        }

        backfillDevicesForType(tenant, type);
    }

    /**
     * Scans the current inventory for devices of {@code type} and subscribes any that aren't
     * already dynamically subscribed. Runs as a background job; status/progress/completion is
     * reported back to Cumulocity as {@link LoggingEventType#BACKFILL_SUBSCRIPTION_EVENT_TYPE}
     * events rather than via any polling API, matching how connector/mapping lifecycle changes
     * are already reported elsewhere in this service.
     */
    private void backfillDevicesForType(String tenant, String type) {
        virtualThreadPool.submit(() -> {
            C8YAgent c8yAgent = configurationRegistry.getC8yAgent();
            Map<String, String> startProps = new HashMap<>();
            startProps.put("type", type);
            c8yAgent.createLoggingEvent("Resync started for type: " + type,
                    LoggingEventType.BACKFILL_SUBSCRIPTION_EVENT_TYPE, DateTime.now(), tenant, startProps);

            // Batch dedup pre-fetch: one paged query for the whole tenant instead of the
            // per-device lookup GETs subscribeDeviceAndConnect's checkAndHandleDeduplication
            // would otherwise issue for every device of this type.
            Set<String> alreadyDynamic = fetchDeviceIdsForSubscription(tenant, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);

            AtomicInteger skipped = new AtomicInteger();
            List<PendingSubscribe> pending = new ArrayList<>();
            c8yAgent.forEachManagedObjectByType(tenant, type, false, mor -> {
                String deviceId = mor.getId().getValue();
                if (alreadyDynamic.contains(deviceId)) {
                    skipped.incrementAndGet();
                    return;
                }
                // Submits asynchronously (subscribeDeviceAndConnect dispatches its own work to
                // the virtual thread pool) so subscribe calls for this type run concurrently;
                // resolved below once the whole inventory scan has been enumerated.
                pending.add(new PendingSubscribe(deviceId,
                        subscribeDeviceAndConnect(tenant, mor, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION,
                                alreadyDynamic)));
            });

            int subscribed = 0, failed = 0;
            for (PendingSubscribe p : pending) {
                try {
                    p.future().get();
                    subscribed++;
                } catch (Exception e) {
                    failed++;
                    log.warn("{} - Resync: failed to subscribe device {} of type {}",
                            tenant, p.deviceId(), type, e);
                }
            }

            Map<String, String> endProps = new HashMap<>();
            endProps.put("type", type);
            endProps.put("subscribed", String.valueOf(subscribed));
            endProps.put("skipped", String.valueOf(skipped.get()));
            endProps.put("failed", String.valueOf(failed));
            c8yAgent.createLoggingEvent(
                    String.format("Resync finished for type %s: %d subscribed, %d already subscribed, %d failed",
                            type, subscribed, skipped.get(), failed),
                    LoggingEventType.BACKFILL_SUBSCRIPTION_EVENT_TYPE, DateTime.now(), tenant, endProps);
        });
    }

    private record PendingSubscribe(String deviceId, Future<NotificationSubscriptionRepresentation> future) {
    }

    // === Private Helper Methods ===

    /**
     * Creates a subscription for a managed object using optimistic approach.
     * If subscription already exists (409), fetches and returns the existing one.
     */
    private NotificationSubscriptionRepresentation createSubscriptionByMO(
            String tenant, ManagedObjectRepresentation mor, API api, String subscriptionName) {

        // Try to create directly (optimistic approach)
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

        } catch (SDKException e) {
            if (e.getHttpStatus() == 409) {
                // Already exists, fetch it
                log.debug("{} - Subscription already exists for source {}, fetching it",
                        tenant, mor.getId().getValue());
                return fetchExistingSubscription(tenant, mor, subscriptionName);
            }
            log.error("{} - Error creating subscription: {}", tenant, e.getMessage(), e);
            throw new RuntimeException("Failed to create subscription: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("{} - Error creating subscription: {}", tenant, e.getMessage(), e);
            throw new RuntimeException("Failed to create subscription: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches an existing subscription by source and subscription name.
     */
    private NotificationSubscriptionRepresentation fetchExistingSubscription(
            String tenant, ManagedObjectRepresentation mor, String subscriptionName) {
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
                    log.debug("{} - Found existing subscription for source {}",
                            tenant, mor.getId().getValue());
                    return existing;
                }
            }

            // Should not happen, but handle gracefully
            log.warn("{} - Subscription not found after 409 error for source {}",
                    tenant, mor.getId().getValue());
            throw new RuntimeException("Subscription not found after duplicate error");

        } catch (Exception e) {
            log.error("{} - Failed to fetch existing subscription: {}", tenant, e.getMessage(), e);
            throw new RuntimeException("Failed to fetch existing subscription: " + e.getMessage(), e);
        }
    }

    public int deleteSubscriptionsForDevice(String tenant, String deviceId, String subscriptionName) {
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

    private Boolean shouldDisconnectAfterUnsubscribe(String tenant) {
        try {
            Iterator<NotificationSubscriptionRepresentation> staticSubIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(Utils.STATIC_DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator();

            Iterator<NotificationSubscriptionRepresentation> dynamicSubIt = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(Utils.DYNAMIC_DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator();

            return !staticSubIt.hasNext() && !dynamicSubIt.hasNext(); // Disconnect if no more subscriptions
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
                                    .bySubscription(Utils.MANAGEMENT_SUBSCRIPTION)
                                    .byContext("tenant"))
                    .get().allPages().iterator();

            // L7: byContext("tenant") filter already guarantees context; return first match
            if (subIt.hasNext()) {
                return subIt.next();
            }
        } catch (Exception e) {
            log.warn("Error finding existing type subscription: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Creates a type subscription using optimistic approach.
     * If subscription already exists (409), fetches and returns the existing one.
     */
    private NotificationSubscriptionRepresentation createTypeSubscription(String typeFilter) {
        try {
            NotificationSubscriptionRepresentation nsr = new NotificationSubscriptionRepresentation();
            nsr.setContext("tenant");
            nsr.setSubscription(Utils.MANAGEMENT_SUBSCRIPTION);

            NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
            filter.setApis(List.of(API.INVENTORY.notificationFilter));
            filter.setTypeFilter(typeFilter);
            nsr.setSubscriptionFilter(filter);

            return subscriptionAPI.subscribe(nsr);

        } catch (SDKException e) {
            if (e.getHttpStatus() == 409) {
                // Already exists, fetch it
                log.debug("Type subscription already exists, fetching it");
                NotificationSubscriptionRepresentation existing = findExistingTypeSubscription();
                if (existing != null) {
                    return existing;
                }
                throw new RuntimeException("Type subscription not found after duplicate error");
            }
            log.error("Error creating type subscription: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create type subscription: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error creating type subscription: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create type subscription: " + e.getMessage(), e);
        }
    }

    /**
     * Enforces subscription priority order: dynamic/group (priority 2/3) > static (priority 1).
     *
     * Rules:
     * - Requesting STATIC  and DYNAMIC already exists → skip (return true)
     * - Requesting DYNAMIC and DYNAMIC already exists → skip (return true)
     * - Requesting DYNAMIC and STATIC  already exists → remove static, proceed (return false)
     *
     * @return true if the new subscription should be skipped, false if it should proceed
     */
    private boolean checkAndHandleDeduplication(String tenant, String deviceId, String requestedSubscription) {
        if (!Utils.STATIC_DEVICE_SUBSCRIPTION.equals(requestedSubscription)
                && !Utils.DYNAMIC_DEVICE_SUBSCRIPTION.equals(requestedSubscription)) {
            // Priority-based dedup only applies between static/dynamic. Other families
            // (e.g. EXPLORER_DEVICE_SUBSCRIPTION) are independent and always proceed —
            // skip the lookup GETs below since their result would be discarded anyway.
            return false;
        }

        boolean hasDynamic = hasSubscriptionForDevice(tenant, deviceId, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
        boolean hasStatic = hasSubscriptionForDevice(tenant, deviceId, Utils.STATIC_DEVICE_SUBSCRIPTION);

        if (Utils.STATIC_DEVICE_SUBSCRIPTION.equals(requestedSubscription)) {
            // Static is lowest priority: skip if a dynamic subscription already exists
            if (hasDynamic) {
                logDeduplicationEvent(tenant, deviceId,
                        Utils.STATIC_DEVICE_SUBSCRIPTION, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
                return true;
            }
        } else if (Utils.DYNAMIC_DEVICE_SUBSCRIPTION.equals(requestedSubscription)) {
            // Dynamic already covers this device: skip duplicate
            if (hasDynamic) {
                logDeduplicationEvent(tenant, deviceId,
                        Utils.DYNAMIC_DEVICE_SUBSCRIPTION, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
                return true;
            }
            // Remove lower-priority static subscription before creating dynamic one
            if (hasStatic) {
                int removed = deleteSubscriptionsForDevice(tenant, deviceId, Utils.STATIC_DEVICE_SUBSCRIPTION);
                log.info("{} - Removed {} static subscription(s) for device {} before creating dynamic subscription",
                        tenant, removed, deviceId);
                logDeduplicationEvent(tenant, deviceId,
                        Utils.STATIC_DEVICE_SUBSCRIPTION, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
            }
        }
        return false;
    }

    private boolean hasSubscriptionForDevice(String tenant, String deviceId, String subscriptionName) {
        try {
            GId id = new GId();
            id.setValue(deviceId);
            Iterator<NotificationSubscriptionRepresentation> it = subscriptionAPI
                    .getSubscriptionsByFilter(
                            new NotificationSubscriptionFilter()
                                    .bySubscription(subscriptionName)
                                    .bySource(id)
                                    .byContext("mo"))
                    .get().allPages().iterator();
            return it.hasNext();
        } catch (Exception e) {
            log.warn("{} - Error checking existing subscription for device {}: {}", tenant, deviceId, e.getMessage());
            return false;
        }
    }

    private void logDeduplicationEvent(String tenant, String deviceId,
            String removedSubscription, String keptSubscription) {
        String message = String.format(
                "Subscription deduplication for device %s: skipped/removed '%s', kept '%s'",
                deviceId, removedSubscription, keptSubscription);
        log.info("{} - {}", tenant, message);

        try {
            Map<String, String> eventMap = Map.of(
                    "deviceId", deviceId,
                    "removedSubscription", removedSubscription,
                    "keptSubscription", keptSubscription);
            configurationRegistry.getC8yAgent().createLoggingEvent(
                    message,
                    LoggingEventType.SUBSCRIPTION_DEDUPLICATION_EVENT_TYPE,
                    DateTime.now(),
                    tenant,
                    eventMap);
        } catch (Exception e) {
            log.warn("{} - Failed to create deduplication event for device {}: {}", tenant, deviceId, e.getMessage());
        }
    }

    private Boolean isValid(ManagedObjectRepresentation mor) {
        return mor != null && mor.getId() != null;
    }
}