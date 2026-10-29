/*
 * Copyright (c) 2025 Cumulocity GmbH.
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

import dynamic.mapper.model.device.Device;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationSubscriptionService {

    private final C8YAgent c8yAgent;

    private final ServiceRegistry serviceRegistry;

    public NotificationSubscriptionResponse createDeviceSubscription(String tenant,
            NotificationSubscriptionRequest request, String subscription) {

        // Keyed by id so devices reachable from several requested roots are subscribed once.
        Map<String, Device> allChildDevices = new LinkedHashMap<>();
        Map<String, Future<?>> pending = new LinkedHashMap<>();

        for (Device device : request.getDevices()) {
            ManagedObjectRepresentation mor = serviceRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);

            if (mor != null) {
                // Fresh list per root: passing the accumulated list would re-subscribe every
                // previously collected device for each further root (quadratic).
                List<Device> related = serviceRegistry.getNotificationSubscriber()
                        .findAllRelatedDevicesByMO(tenant, mor, new ArrayList<>(), false);

                for (Device childDevice : related) {
                    if (allChildDevices.putIfAbsent(childDevice.getId(), childDevice) != null) {
                        continue;
                    }
                    ManagedObjectRepresentation childMor = serviceRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childDevice.getId(), false);
                    pending.put(childDevice.getId(), serviceRegistry.getNotificationSubscriber()
                            .subscribeDeviceAndConnect(tenant, childMor, request.getApi(), subscription));

                    // Pre-populate inventory cache for this device to ensure inventory filters work correctly
                    log.debug("{} - Pre-populating inventory cache for device {} from subscription",
                            tenant, childDevice.getId());
                    serviceRegistry.getC8yAgent().getMOFromInventoryCache(tenant, childDevice.getId(), false);
                }
            } else {
                log.warn("{} - Device with id {} does not exist", tenant, device.getId());
            }
        }

        SubscribeOutcome outcome = awaitSubscriptions(tenant, pending);
        return NotificationSubscriptionResponse.builder()
                .api(request.getApi())
                .subscriptionName(request.getSubscriptionName())
                .devices(new ArrayList<>(allChildDevices.values()))
                .failedDeviceIds(outcome.failedDeviceIds())
                .status(outcome.status())
                .build();
    }

    public NotificationSubscriptionResponse updateDeviceSubscription(String tenant,
            NotificationSubscriptionRequest request, String subscription) {

        // Get current subscriptions for the target bucket
        NotificationSubscriptionResponse current = serviceRegistry.getNotificationSubscriber()
                .getSubscriptionsDevices(tenant, null, subscription);

        // Calculate differences
        List<Device> toAdd = calculateDevicesToAdd(request.getDevices(), current.getDevices());
        List<Device> toRemove = calculateDevicesToRemove(request.getDevices(), current.getDevices());

        // Process additions
        Map<String, Future<?>> pending = processDeviceAdditions(tenant, toAdd, request.getApi(), subscription);

        // Process removals
        processDeviceRemovals(tenant, toRemove, subscription);

        SubscribeOutcome outcome = awaitSubscriptions(tenant, pending);
        return NotificationSubscriptionResponse.builder()
                .api(request.getApi())
                .subscriptionName(request.getSubscriptionName())
                .devices(request.getDevices())
                .failedDeviceIds(outcome.failedDeviceIds())
                .status(outcome.status())
                .build();
    }

    /**
     * Updates group-based notification subscriptions.
     * <p>
     * The caller sends the <strong>full desired set</strong> of groups. This method
     * computes the diff against the current subscriptions internally and applies the
     * necessary additions and removals. The API contract is: "replace the subscribed
     * groups with exactly this list" — not "apply this delta".
     */
    public NotificationSubscriptionResponse updateGroupSubscription(String tenant,
            NotificationSubscriptionRequest request) {
        try {
            NotificationSubscriptionResponse deviceGroupsSubscription = serviceRegistry
                    .getNotificationSubscriber()
                    .getSubscriptionsByDeviceGroup(tenant);

            List<Device> toBeRemovedGroups = new ArrayList<>();
            List<Device> toBeCreatedGroups = new ArrayList<>();

            toBeRemovedGroups.addAll(deviceGroupsSubscription.getDevices());
            toBeCreatedGroups.addAll(request.getDevices());

            request.getDevices()
                    .forEach(device -> toBeRemovedGroups.removeIf(x -> x.getId().equals(device.getId())));
            deviceGroupsSubscription.getDevices()
                    .forEach(entity -> toBeCreatedGroups.removeIf(x -> x.getId().equals(entity.getId())));

            // Unsubscribe removed groups FIRST so a device moving from a removed group to an added
            // one is not subscribed and then immediately unsubscribed again. Devices that remain
            // covered by a desired group or a configured type are left alone.
            if (!toBeRemovedGroups.isEmpty()) {
                Set<String> protectedIds = deviceIdsCoveredByGroups(tenant, request.getDevices());
                Set<String> configuredTypes = configuredTypes(tenant);
                for (Device group : toBeRemovedGroups) {
                    ManagedObjectRepresentation groupMor = c8yAgent.getManagedObjectForId(tenant, group.getId(), false);
                    if (groupMor != null) {
                        serviceRegistry.getNotificationSubscriber().unsubscribeByDeviceGroup(tenant, groupMor);
                        unsubscribeGroupDevices(tenant, groupMor, protectedIds, configuredTypes);
                    } else {
                        log.warn("{} - Could not unsubscribe group with id {}. DeviceGroup does not exist!", tenant,
                                group.getId());
                    }
                }
            }

            List<Device> allChildDevices = new ArrayList<>();
            Map<String, Future<?>> pending = new LinkedHashMap<>();

            // Ensure management client is initialized for group subscriptions
            if (!toBeCreatedGroups.isEmpty()) {
                log.info("{} - Ensuring management client is initialized for group subscriptions", tenant);
                serviceRegistry.getNotificationSubscriber().initializeManagementClient(tenant);
            }

            // Subscribe to new groups
            for (Device group : toBeCreatedGroups) {
                ManagedObjectRepresentation groupMor = c8yAgent.getManagedObjectForId(tenant, group.getId(), false);
                if (groupMor != null) {
                    // add subscription for deviceGroup
                    serviceRegistry.getNotificationSubscriber().subscribeByDeviceGroup(tenant, groupMor);
                    try {
                        allChildDevices = serviceRegistry.getNotificationSubscriber()
                                .findAllRelatedDevicesByMO(tenant, groupMor, allChildDevices, false);
                    } catch (Exception e) {
                        log.error("{} - Error creating group subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("{} - Could not subscribe group with id {}. DeviceGroup does not exist!", tenant,
                            group.getId());
                }
            }

            if (!allChildDevices.isEmpty()) {
                // Ensure dynamic device client is initialized before subscribing devices
                log.info("{} - Ensuring dynamic device client is initialized for group subscription", tenant);
                serviceRegistry.getNotificationSubscriber().initializeDeviceClient(tenant);

                for (Device childDevice : allChildDevices) {
                    ManagedObjectRepresentation childDeviceMor = c8yAgent.getManagedObjectForId(tenant,
                            childDevice.getId(), false);
                    pending.put(childDevice.getId(), serviceRegistry.getNotificationSubscriber()
                            .subscribeDeviceAndConnect(tenant, childDeviceMor,
                                    request.getApi(), Utils.DYNAMIC_DEVICE_SUBSCRIPTION));

                    // Pre-populate inventory cache for this device to ensure inventory filters work correctly
                    log.debug("{} - Pre-populating inventory cache for device {} from group subscription",
                            tenant, childDevice.getId());
                    serviceRegistry.getC8yAgent().getMOFromInventoryCache(tenant, childDevice.getId(), false);
                }
            }

            SubscribeOutcome outcome = awaitSubscriptions(tenant, pending);

            // Get all currently subscribed device groups to return
            NotificationSubscriptionResponse updatedSubscription = serviceRegistry.getNotificationSubscriber()
                    .getSubscriptionsByDeviceGroup(tenant);
            updatedSubscription.setFailedDeviceIds(outcome.failedDeviceIds());
            if (outcome.status() != NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE) {
                updatedSubscription.setStatus(outcome.status());
            }
            return updatedSubscription;

        } catch (Exception e) {
            log.error("{} - Error updating group subscriptions: ", tenant, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    public void deleteGroupSubscription(String tenant, ManagedObjectRepresentation groupMor) {
        // Remove group subscription
        serviceRegistry.getNotificationSubscriber().unsubscribeByDeviceGroup(tenant, groupMor);

        // Devices still covered by another subscribed group or a configured type keep their subscription
        String deletedId = groupMor.getId().getValue();
        List<Device> remainingGroups = new ArrayList<>(serviceRegistry.getNotificationSubscriber()
                .getSubscriptionsByDeviceGroup(tenant).getDevices());
        remainingGroups.removeIf(g -> deletedId.equals(g.getId()));

        int unsubscribed = unsubscribeGroupDevices(tenant, groupMor,
                deviceIdsCoveredByGroups(tenant, remainingGroups), configuredTypes(tenant));

        log.info("{} - Successfully unsubscribed {} devices from group {}", tenant, unsubscribed, deletedId);
    }

    /** Ids of all devices reachable from the given groups. */
    private Set<String> deviceIdsCoveredByGroups(String tenant, List<Device> groups) {
        Set<String> ids = new HashSet<>();
        if (groups == null) {
            return ids;
        }
        for (Device group : groups) {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, group.getId(), false);
            if (mor == null) {
                continue;
            }
            for (Device d : serviceRegistry.getNotificationSubscriber()
                    .findAllRelatedDevicesByMO(tenant, mor, new ArrayList<>(), false)) {
                ids.add(d.getId());
            }
        }
        return ids;
    }

    private Set<String> configuredTypes(String tenant) {
        List<String> types = serviceRegistry.getNotificationSubscriber().getSubscriptionsByDeviceType(tenant)
                .getTypes();
        return types == null ? Set.of() : new HashSet<>(types);
    }

    /** Unsubscribes the group's devices from the dynamic bucket, skipping still-covered ones. */
    private int unsubscribeGroupDevices(String tenant, ManagedObjectRepresentation groupMor,
            Set<String> protectedIds, Set<String> configuredTypes) {
        int count = 0;
        List<Device> devices = serviceRegistry.getNotificationSubscriber()
                .findAllRelatedDevicesByMO(tenant, groupMor, new ArrayList<>(), false);
        for (Device device : devices) {
            if (protectedIds.contains(device.getId())) {
                continue;
            }
            ManagedObjectRepresentation deviceMor = c8yAgent.getManagedObjectForId(tenant, device.getId(), false);
            if (deviceMor == null || configuredTypes.contains(deviceMor.getType())) {
                continue;
            }
            serviceRegistry.getNotificationSubscriber()
                    .unsubscribeDeviceAndDisconnect(tenant, deviceMor, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
            count++;
        }
        return count;
    }

    /** Upper bound the REST call waits for the asynchronous subscribe tasks. */
    private static final long SUBSCRIBE_AWAIT_SECONDS = 60;

    /** Result of waiting for the asynchronous subscribe tasks of one request. */
    private record SubscribeOutcome(List<String> failedDeviceIds, boolean timedOut) {
        NotificationSubscriptionResponse.SubscriptionStatus status() {
            if (!failedDeviceIds.isEmpty()) {
                return NotificationSubscriptionResponse.SubscriptionStatus.ERROR;
            }
            return timedOut ? NotificationSubscriptionResponse.SubscriptionStatus.PENDING
                    : NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE;
        }
    }

    /**
     * Waits for the futures returned by subscribeDeviceAndConnect. A null result is a
     * deduplication skip (device already covered), not a failure. Waits at most
     * {@link #SUBSCRIBE_AWAIT_SECONDS} overall; anything still running is reported as pending.
     */
    private SubscribeOutcome awaitSubscriptions(String tenant, Map<String, Future<?>> pending) {
        List<String> failed = new ArrayList<>();
        boolean timedOut = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SUBSCRIBE_AWAIT_SECONDS);
        for (Map.Entry<String, Future<?>> entry : pending.entrySet()) {
            try {
                entry.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                timedOut = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                timedOut = true;
                break;
            } catch (Exception e) {
                log.warn("{} - Subscription for device {} failed: {}", tenant, entry.getKey(), e.getMessage());
                failed.add(entry.getKey());
            }
        }
        return new SubscribeOutcome(failed, timedOut);
    }

    private List<Device> calculateDevicesToAdd(List<Device> requested, List<Device> current) {
        List<Device> toAdd = new ArrayList<>(requested);
        if (current != null) {
            toAdd.removeAll(current);
        }
        return toAdd;
    }

    private List<Device> calculateDevicesToRemove(List<Device> requested, List<Device> current) {
        List<Device> toRemove = new ArrayList<>();
        if (current != null) {
            toRemove.addAll(current);
            toRemove.removeAll(requested);
        }
        return toRemove;
    }

    private Map<String, Future<?>> processDeviceAdditions(String tenant, List<Device> devices,
            dynamic.mapper.model.API api, String subscription) {
        Map<String, Future<?>> pending = new LinkedHashMap<>();
        for (Device device : devices) {
            ManagedObjectRepresentation mor = serviceRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);
            if (mor != null) {
                pending.put(device.getId(), serviceRegistry.getNotificationSubscriber()
                        .subscribeDeviceAndConnect(tenant, mor, api, subscription));

                // Pre-populate inventory cache for this device to ensure inventory filters work correctly
                log.debug("{} - Pre-populating inventory cache for device {} from device addition",
                        tenant, device.getId());
                serviceRegistry.getC8yAgent().getMOFromInventoryCache(tenant, device.getId(), false);
            }
        }
        return pending;
    }

    private void processDeviceRemovals(String tenant, List<Device> devices, String subscription) {
        for (Device device : devices) {
            ManagedObjectRepresentation mor = serviceRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);
            if (mor != null) {
                serviceRegistry.getNotificationSubscriber()
                        .unsubscribeDeviceAndDisconnect(tenant, mor, subscription);
            } else {
                // Device was deleted in Cumulocity: drop the stale subscription by id.
                int deleted = serviceRegistry.getNotificationSubscriber()
                        .deleteSubscriptionsForDevice(tenant, device.getId(), subscription);
                log.info("{} - Device {} not found, deleted {} stale subscription(s)",
                        tenant, device.getId(), deleted);
            }
        }
    }
}
