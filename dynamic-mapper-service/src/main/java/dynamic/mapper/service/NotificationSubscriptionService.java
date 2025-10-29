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

package dynamic.mapper.service;

import dynamic.mapper.model.NotificationSubscriptionRequest;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.model.Device;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationSubscriptionService {

    @Autowired
    C8YAgent c8yAgent;

    private final ConfigurationRegistry configurationRegistry;

    public NotificationSubscriptionResponse createDeviceSubscription(String tenant,
            NotificationSubscriptionRequest request, String subscription) {

        List<Device> allChildDevices = new ArrayList<>();

        for (Device device : request.getDevices()) {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);

            if (mor != null) {
                allChildDevices = configurationRegistry.getNotificationSubscriber()
                        .findAllRelatedDevicesByMO(tenant, mor, allChildDevices, false);

                // Subscribe each child device
                for (Device childDevice : allChildDevices) {
                    ManagedObjectRepresentation childMor = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childDevice.getId(), false);
                    configurationRegistry.getNotificationSubscriber()
                            .subscribeDeviceAndConnect(tenant, childMor, request.getApi(), subscription);
                }
            } else {
                log.warn("{} - Device with id {} does not exist", tenant, device.getId());
            }
        }

        return NotificationSubscriptionResponse.builder()
                .api(request.getApi())
                .subscriptionName(request.getSubscriptionName())
                .devices(allChildDevices)
                .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE)
                .build();
    }

    public NotificationSubscriptionResponse updateDeviceSubscription(String tenant,
            NotificationSubscriptionRequest request) {

        // Get current subscriptions
        NotificationSubscriptionResponse current = configurationRegistry.getNotificationSubscriber()
                .getSubscriptionsDevices(tenant, null, Utils.STATIC_DEVICE_SUBSCRIPTION);

        // Calculate differences
        List<Device> toAdd = calculateDevicesToAdd(request.getDevices(), current.getDevices());
        List<Device> toRemove = calculateDevicesToRemove(request.getDevices(), current.getDevices());

        // Process additions
        processDeviceAdditions(tenant, toAdd, request.getApi());

        // Process removals
        processDeviceRemovals(tenant, toRemove);

        // Return updated response
        return NotificationSubscriptionResponse.builder()
                .api(request.getApi())
                .subscriptionName(request.getSubscriptionName())
                .devices(request.getDevices())
                .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE)
                .build();
    }

    public NotificationSubscriptionResponse updateGroupSubscription(String tenant,
            NotificationSubscriptionRequest request) {
        try {
            NotificationSubscriptionResponse deviceGroupsSubscription = configurationRegistry
                    .getNotificationSubscriber()
                    .getSubscriptionsByDeviceGroup(tenant);

            List<Device> toBeRemovedGroups = new ArrayList<>();
            List<Device> toBeCreatedGroups = new ArrayList<>();

            deviceGroupsSubscription.getDevices().forEach(device -> toBeRemovedGroups.add(device));
            request.getDevices().forEach(device -> toBeCreatedGroups.add(device));

            request.getDevices()
                    .forEach(device -> toBeRemovedGroups.removeIf(x -> x.getId().equals(device.getId())));
            deviceGroupsSubscription.getDevices()
                    .forEach(entity -> toBeCreatedGroups.removeIf(x -> x.getId().equals(entity.getId())));

            List<Device> allChildDevices = new ArrayList<>();

            // Subscribe to new groups
            for (Device group : toBeCreatedGroups) {
                ManagedObjectRepresentation groupMor = c8yAgent.getManagedObjectForId(tenant, group.getId(), false);
                if (groupMor != null) {
                    // add subscription for deviceGroup
                    configurationRegistry.getNotificationSubscriber().subscribeByDeviceGroup(tenant, groupMor);
                    try {
                        allChildDevices = configurationRegistry.getNotificationSubscriber()
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
                for (Device childDevice : allChildDevices) {
                    ManagedObjectRepresentation childDeviceMor = c8yAgent.getManagedObjectForId(tenant,
                            childDevice.getId(), false);
                    configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(tenant, childDeviceMor,
                            request.getApi(), Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
                }
            }

            // Unsubscribe from removed groups
            for (Device group : toBeRemovedGroups) {
                ManagedObjectRepresentation groupMor = c8yAgent.getManagedObjectForId(tenant, group.getId(), false);
                if (groupMor != null) {
                    // remove subscription for deviceGroup
                    configurationRegistry.getNotificationSubscriber().unsubscribeByDeviceGroup(tenant, groupMor);
                    try {
                        List<Device> devicesToRemove = configurationRegistry.getNotificationSubscriber()
                                .findAllRelatedDevicesByMO(tenant, groupMor, new ArrayList<>(), false);
                        for (Device deviceToRemove : devicesToRemove) {
                            ManagedObjectRepresentation deviceMor = c8yAgent.getManagedObjectForId(tenant,
                                    deviceToRemove.getId(), false);
                            configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(tenant,
                                    deviceMor);
                        }
                    } catch (Exception e) {
                        log.error("{} - Error removing group subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("{} - Could not unsubscribe group with id {}. DeviceGroup does not exist!", tenant,
                            group.getId());
                }
            }

            // Get all currently subscribed device groups to return
            NotificationSubscriptionResponse updatedSubscription = configurationRegistry.getNotificationSubscriber()
                    .getSubscriptionsByDeviceGroup(tenant);
            return updatedSubscription;

        } catch (Exception e) {
            log.error("{} - Error updating group subscriptions: ", tenant, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    public void deleteGroupSubscription(String tenant, ManagedObjectRepresentation groupMor) {
        // Remove group subscription
        configurationRegistry.getNotificationSubscriber().unsubscribeByDeviceGroup(tenant, groupMor);

        // Find and unsubscribe all devices in group
        List<Device> devicesInGroup = configurationRegistry.getNotificationSubscriber()
                .findAllRelatedDevicesByMO(tenant, groupMor, new ArrayList<>(), false);

        for (Device device : devicesInGroup) {
            ManagedObjectRepresentation deviceMor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);
            if (deviceMor != null) {
                configurationRegistry.getNotificationSubscriber()
                        .unsubscribeDeviceAndDisconnect(tenant, deviceMor);
            }
        }

        log.info("{} - Successfully unsubscribed {} devices from group {}",
                tenant, devicesInGroup.size(), groupMor.getId().getValue());
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

    private void processDeviceAdditions(String tenant, List<Device> devices, dynamic.mapper.model.API api) {
        for (Device device : devices) {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber()
                        .subscribeDeviceAndConnect(tenant, mor, api, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
            }
        }
    }

    private void processDeviceRemovals(String tenant, List<Device> devices) {
        for (Device device : devices) {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber()
                        .unsubscribeDeviceAndDisconnect(tenant, mor);
            }
        }
    }
}
