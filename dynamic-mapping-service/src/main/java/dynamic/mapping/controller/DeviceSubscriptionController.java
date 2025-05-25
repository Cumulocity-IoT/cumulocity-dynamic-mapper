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

package dynamic.mapping.controller;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapping.configuration.ServiceConfiguration;
import dynamic.mapping.configuration.ServiceConfigurationComponent;
import dynamic.mapping.model.C8YNotificationSubscription;
import dynamic.mapping.model.Device;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConfigurationRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequestMapping("/subscription")
@RestController
public class DeviceSubscriptionController {

    @Autowired
    C8YAgent c8yAgent;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Autowired
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    ServiceConfigurationComponent serviceConfigurationComponent;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionCreate(
            @Valid @RequestBody C8YNotificationSubscription subscription) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            List<Device> allChildDevices = null;
            for (Device managedObject : subscription.getDevices()) {
                log.info("{} - Find all related Devices of Managed Object {}", managedObject.getId());
                ManagedObjectRepresentation mor = c8yAgent
                        .getManagedObjectForId(contextService.getContext().getTenant(), managedObject.getId());
                if (mor != null) {
                    allChildDevices = configurationRegistry.getNotificationSubscriber().findAllRelatedDevicesByMO(mor,
                            allChildDevices, false);
                } else {
                    log.warn("{} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            managedObject.getId());
                }
            }
            if (allChildDevices != null) {
                for (Device device : allChildDevices) {
                    ManagedObjectRepresentation childDeviceMor = c8yAgent
                            .getManagedObjectForId(contextService.getContext().getTenant(), device.getId());
                    // Creates subscription for each connector
                    configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(childDeviceMor,
                            subscription.getApi());
                }
            }
            subscription.setDevices(allChildDevices);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok(subscription);
    }

    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionUpdate(
            @Valid @RequestBody C8YNotificationSubscription subscription) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            // List<NotificationSubscriptionRepresentation> deviceSubscriptions =
            // c8yApiSubscriber.getNotificationSubscriptions(null, null).get();
            C8YNotificationSubscription c8ySubscription = configurationRegistry.getNotificationSubscriber()
                    .getDeviceSubscriptions(tenant,
                            null, null);
            // 3 cases -
            // 1. Device exists in subscription and active subscription --> Do nothing
            // 2. Device exists in subscription and does not have an active subscription -->
            // create subscription
            // 3. Device exists not in subscription and does have an active subscription -->
            // delete subscription
            List<Device> toBeRemovedSub = new ArrayList<>();
            List<Device> toBeCreatedSub = new ArrayList<>();

            c8ySubscription.getDevices().forEach(device -> toBeRemovedSub.add(device));
            subscription.getDevices().forEach(device -> toBeCreatedSub.add(device));

            subscription.getDevices().forEach(device -> toBeRemovedSub.removeIf(x -> x.getId().equals(device.getId())));
            c8ySubscription.getDevices()
                    .forEach(entity -> toBeCreatedSub.removeIf(x -> x.getId().equals(entity.getId())));
            List<Device> allChildDevices = null;
            for (Device device : toBeCreatedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, device.getId());
                if (mor != null) {
                    try {
                        // Creates subscription for each connector
                        allChildDevices = configurationRegistry.getNotificationSubscriber()
                                .findAllRelatedDevicesByMO(mor, allChildDevices, false);
                    } catch (Exception e) {
                        log.error("{} - Error creating subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("{} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            device.getId());
                }
            }
            if (allChildDevices != null) {
                for (Device childDevice : allChildDevices) {
                    // Creates subscription for each connector
                    ManagedObjectRepresentation childDeviceMor = c8yAgent.getManagedObjectForId(tenant,
                            childDevice.getId());
                    configurationRegistry.getNotificationSubscriber().subscribeDeviceAndConnect(childDeviceMor,
                            subscription.getApi());
                }
                subscription.setDevices(allChildDevices);
            }

            for (Device device : toBeRemovedSub) {
                ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(tenant, device.getId());
                if (mor != null) {
                    try {
                        configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(mor);
                    } catch (Exception e) {
                        log.error("{} - Error removing subscriptions: ", tenant, e);
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
                    }
                } else {
                    log.warn("{} - Could not subscribe device with id {}. Device does not exists!", tenant,
                            device.getId());
                }
            }

        } catch (Exception e) {
            log.error("{} - Error updating subscriptions: ", tenant, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok(subscription);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<C8YNotificationSubscription> subscriptionsGet(@RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String subscriptionName) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            C8YNotificationSubscription c8YAPISubscription = configurationRegistry.getNotificationSubscriber()
                    .getDeviceSubscriptions(contextService.getContext().getTenant(), deviceId, subscriptionName);
            return ResponseEntity.ok(c8YAPISubscription);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/{deviceId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> subscriptionDelete(@PathVariable String deviceId) {
        String tenant = contextService.getContext().getTenant();
        ServiceConfiguration serviceConfiguration = serviceConfigurationComponent.getServiceConfiguration(tenant);
        if (!serviceConfiguration.isOutboundMappingEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Output Mapping is disabled!");
        try {
            ManagedObjectRepresentation mor = c8yAgent.getManagedObjectForId(contextService.getContext().getTenant(),
                    deviceId);
            if (mor != null) {
                configurationRegistry.getNotificationSubscriber().unsubscribeDeviceAndDisconnect(mor);
            } else {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Could not delete subscription for device with id " + deviceId + ". Device not found");
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        return ResponseEntity.ok().build();
    }
}
