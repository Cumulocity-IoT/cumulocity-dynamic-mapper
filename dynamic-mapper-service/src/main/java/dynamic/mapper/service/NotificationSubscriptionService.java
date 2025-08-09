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
import dynamic.mapper.model.Device;
import dynamic.mapper.core.ConfigurationRegistry;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationSubscriptionService {
    
    private final ConfigurationRegistry configurationRegistry;

    public NotificationSubscriptionResponse createDeviceSubscription(String tenant, 
            NotificationSubscriptionRequest request) {
        
        List<Device> allChildDevices = new ArrayList<>();
        
        for (Device device : request.getDevices()) {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId());
            
            if (mor != null) {
                allChildDevices = configurationRegistry.getNotificationSubscriber()
                        .findAllRelatedDevicesByMO(mor, allChildDevices, false);
                
                // Subscribe each child device  
                for (Device childDevice : allChildDevices) {
                    ManagedObjectRepresentation childMor = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childDevice.getId());
                    configurationRegistry.getNotificationSubscriber()
                            .subscribeDeviceAndConnect(tenant, childMor, request.getApi());
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
                .getSubscriptionsDevices(tenant, null, null);
        
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
        // Implementation similar to updateDeviceSubscription but for groups
        // ... implementation details
        return NotificationSubscriptionResponse.builder().build();
    }

    public void deleteGroupSubscription(String tenant, ManagedObjectRepresentation groupMor) {
        // Remove group subscription
        configurationRegistry.getNotificationSubscriber().unsubscribeByDeviceGroup(groupMor);
        
        // Find and unsubscribe all devices in group
        List<Device> devicesInGroup = configurationRegistry.getNotificationSubscriber()
                .findAllRelatedDevicesByMO(groupMor, new ArrayList<>(), false);
        
        for (Device device : devicesInGroup) {
            ManagedObjectRepresentation deviceMor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId());
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
                    .getManagedObjectForId(tenant, device.getId());
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber()
                        .subscribeDeviceAndConnect(tenant, mor, api);
            }
        }
    }

    private void processDeviceRemovals(String tenant, List<Device> devices) {
        for (Device device : devices) {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId());
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber()
                        .unsubscribeDeviceAndDisconnect(tenant, mor);
            }
        }
    }
}
