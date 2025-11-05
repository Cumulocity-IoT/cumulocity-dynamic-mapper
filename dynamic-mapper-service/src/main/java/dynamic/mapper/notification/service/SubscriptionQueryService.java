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
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Device;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.notification.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * Service for querying notification subscriptions.
 * Provides methods to retrieve subscription information for devices, groups, and types.
 */
@Slf4j
@Service
public class SubscriptionQueryService {

    @Autowired
    private NotificationSubscriptionApi subscriptionAPI;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ConfigurationRegistry configurationRegistry;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    // === Public API ===

    /**
     * Get notification subscriptions for devices
     */
    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDevices(
            String tenant, String deviceId, String deviceSubscription) {

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        filter = filter.bySubscription(deviceSubscription != null ? deviceSubscription : Utils.STATIC_DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            List<NotificationSubscriptionRepresentation> deviceSubList = new ArrayList<>();
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    if (!"tenant".equals(nsr.getContext())) {
                        log.debug("{} - Retrieved device subscription: {}", tenant, nsr.getId().getValue());
                        deviceSubList.add(nsr);
                    }
                }
            } catch (Exception e) {
                log.error("{} - Error retrieving device subscriptions: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to retrieve device subscriptions: " + e.getMessage(), e);
            }
            return deviceSubList;
        }));
    }

    /**
     * Get notification subscriptions for device groups
     */
    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDeviceGroup(
            String tenant, String deviceId, String deviceSubscription) {

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        filter = filter.bySubscription(deviceSubscription != null ? deviceSubscription : Utils.MANAGEMENT_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            List<NotificationSubscriptionRepresentation> managementSubList = new ArrayList<>();
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    if (!"tenant".equals(nsr.getContext())) {
                        log.debug("{} - Retrieved management subscription: {}", tenant, nsr.getId().getValue());
                        managementSubList.add(nsr);
                    }
                }
            } catch (Exception e) {
                log.error("{} - Error retrieving management subscriptions: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to retrieve management subscriptions: " + e.getMessage(), e);
            }
            return managementSubList;
        }));
    }

    /**
     * Get notification subscription for device type
     */
    public Future<NotificationSubscriptionRepresentation> getNotificationSubscriptionForDeviceType(String tenant) {

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(Utils.MANAGEMENT_SUBSCRIPTION)
                .byContext("tenant");

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(filter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    if ("tenant".equals(nsr.getContext())) {
                        log.debug("{} - Retrieved type subscription: {}", tenant, nsr.getId().getValue());
                        return nsr;
                    }
                }
                return null;
            } catch (Exception e) {
                log.error("{} - Error retrieving type subscriptions: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to retrieve type subscriptions: " + e.getMessage(), e);
            }
        }));
    }

    /**
     * Get subscription response for devices
     */
    public NotificationSubscriptionResponse getSubscriptionsDevices(String tenant, String deviceId,
            String deviceSubscription) {

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(deviceSubscription != null ? deviceSubscription : Utils.STATIC_DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;
        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = 
                NotificationSubscriptionResponse.builder();
        List<Device> devices = new ArrayList<>();

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    if (!"tenant".equals(nsr.getContext())) {
                        processDeviceSubscription(tenant, nsr, devices, responseBuilder);
                    }
                }
            } catch (Exception e) {
                log.error("{} - Error getting device subscriptions: {}", tenant, e.getMessage(), e);
            }
        });

        return responseBuilder.devices(devices).build();
    }

    /**
     * Get subscriptions by device group
     */
    public NotificationSubscriptionResponse getSubscriptionsByDeviceGroup(String tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(Utils.MANAGEMENT_SUBSCRIPTION)
                .byContext("mo");

        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = 
                NotificationSubscriptionResponse.builder();
        List<Device> devices = new ArrayList<>();

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(filter).get().allPages().iterator();

                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    if (!"tenant".equals(nsr.getContext())) {
                        processDeviceSubscription(tenant, nsr, devices, responseBuilder);
                    }
                }
            } catch (Exception e) {
                log.error("{} - Error getting group subscriptions: {}", tenant, e.getMessage(), e);
            }
        });

        return responseBuilder.devices(devices).build();
    }

    /**
     * Get subscriptions by device type
     */
    public NotificationSubscriptionResponse getSubscriptionsByDeviceType(String tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionResponse response = NotificationSubscriptionResponse.builder().build();

        try {
            Future<NotificationSubscriptionRepresentation> future = getNotificationSubscriptionForDeviceType(tenant);
            NotificationSubscriptionRepresentation nsr = future.get(30, TimeUnit.SECONDS);

            if (nsr != null && nsr.getSubscriptionFilter() != null) {
                String filterString = nsr.getSubscriptionFilter().getTypeFilter();
                log.debug("{} - Retrieved type subscription with filter: {}", tenant, filterString);

                if (filterString != null) {
                    List<String> types = new ArrayList<>(dynamic.mapper.notification.Utils.parseTypesFromFilter(filterString));
                    response = NotificationSubscriptionResponse.builder()
                            .types(types)
                            .subscriptionName(nsr.getSubscription())
                            .subscriptionId(nsr.getId().getValue())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE)
                            .build();
                }
            } else {
                log.info("{} - No type subscription found", tenant);
                response = NotificationSubscriptionResponse.builder()
                        .types(new ArrayList<>())
                        .status(NotificationSubscriptionResponse.SubscriptionStatus.INACTIVE)
                        .build();
            }
        } catch (Exception e) {
            log.error("{} - Error retrieving type subscriptions: {}", tenant, e.getMessage(), e);
            response = NotificationSubscriptionResponse.builder()
                    .types(new ArrayList<>())
                    .status(NotificationSubscriptionResponse.SubscriptionStatus.ERROR)
                    .build();
        }

        return response;
    }

    // === Private Helper Methods ===

    private void processDeviceSubscription(String tenant, NotificationSubscriptionRepresentation nsr,
            List<Device> devices,
            NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder) {

        if (!isValidSubscription(nsr)) {
            log.warn("{} - Invalid subscription representation", tenant);
            return;
        }

        log.debug("{} - Processing subscription {}", tenant, nsr.getId().getValue());

        Device device = new Device();
        device.setId(nsr.getSource().getId().getValue());

        try {
            ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent()
                    .getManagedObjectForId(tenant, device.getId(), false);
            if (mor != null) {
                device.setName(mor.getName());
                device.setType(mor.getType());
            } else {
                log.warn("{} - Device {} in subscription does not exist", tenant, device.getId());
            }
        } catch (Exception e) {
            log.warn("{} - Error retrieving device {}: {}", tenant, device.getId(), e.getMessage());
        }

        devices.add(device);

        // Set API and subscription name from first valid subscription
        if (nsr.getSubscriptionFilter() != null &&
                nsr.getSubscriptionFilter().getApis() != null &&
                !nsr.getSubscriptionFilter().getApis().isEmpty()) {
            try {
                API api = API.fromString(nsr.getSubscriptionFilter().getApis().get(0));
                responseBuilder.api(api);
                responseBuilder.subscriptionName(nsr.getSubscription());
            } catch (Exception e) {
                log.warn("{} - Error parsing API from subscription filter: {}", tenant, e.getMessage());
            }
        }
    }

    private boolean isValidSubscription(NotificationSubscriptionRepresentation sub) {
        return sub != null &&
                sub.getSource() != null &&
                sub.getSource().getId() != null;
    }
}
