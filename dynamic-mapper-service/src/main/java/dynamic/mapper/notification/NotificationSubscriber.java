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

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;

import dynamic.mapper.configuration.ConnectorConfiguration;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Device;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.notification.service.*;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Facade/Orchestrator for notification subscription management.
 * Delegates to specialized service components.
 */
@Slf4j
@Component
public class NotificationSubscriber {

    @Autowired
    private SubscriptionManager subscriptionManager;

    @Autowired
    private NotificationConnectionManager connectionManager;

    @Autowired
    private MqttPushManager mqttPushManager;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private MessagingManagementService messagingManagementService;

    @Autowired
    private ConnectorRegistry connectorRegistry;

    @Autowired
    private DeviceDiscoveryService deviceDiscoveryService;

    @Autowired
    @Getter
    private SubscriptionQueryService queryService;

    // === Lifecycle Methods ===

    public void addSubscriber(String tenant, String identifier, CamelDispatcherOutbound dispatcherOutbound) {
        connectorRegistry.addSubscriber(tenant, identifier, dispatcherOutbound);
    }

    public void initializeDeviceClient(String tenant) {
        connectionManager.initializeDynamicDeviceClient(tenant);
        connectionManager.initializeStaticDeviceClient(tenant);
    }

    public void initializeManagementClient(String tenant) {
        connectionManager.initializeManagementClient(tenant);
    }

    public void notificationSubscriberReconnect(String tenant) {
        connectionManager.reconnect(tenant);
    }

    public boolean isNotificationServiceAvailable(String tenant) {
        return subscriptionManager.isNotificationServiceAvailable(tenant);
    }

    /**
     * Disconnect all connections for a tenant
     */
    public void disconnect(String tenant) {
        connectionManager.disconnect(tenant);
    }

    // === Device Subscription Methods ===

    public Future<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(
            String tenant, ManagedObjectRepresentation mor, API api, String subscriptionName) {
        return subscriptionManager.subscribeDeviceAndConnect(tenant, mor, api, subscriptionName);
    }

    public Future<NotificationSubscriptionRepresentation> subscribeByDeviceGroup(
            String tenant, ManagedObjectRepresentation mor) {
        return subscriptionManager.subscribeByDeviceGroup(tenant, mor);
    }

    public Future<NotificationSubscriptionRepresentation> subscribeMOForInventoryCacheUpdates(
            String tenant, ManagedObjectRepresentation mor) {
        return subscriptionManager.subscribeMOForInventoryCacheUpdates(tenant, mor);
    }

    public boolean unsubscribeMOForInventoryCacheUpdates(String tenant, ManagedObjectRepresentation mor) {
        return subscriptionManager.unsubscribeMOForInventoryCacheUpdates(tenant, mor);
    }

    public void unsubscribeAllMOForInventoryCacheUpdates(String tenant) {
        subscriptionManager.unsubscribeAllMOForInventoryCacheUpdates(tenant);
    }

    public void unsubscribeDeviceAndDisconnect(String tenant, ManagedObjectRepresentation mor, String subscription) {
        subscriptionManager.unsubscribeDeviceAndDisconnect(tenant, mor, subscription);
    }

    public void unsubscribeByDeviceGroup(String tenant, ManagedObjectRepresentation mor) {
        subscriptionManager.unsubscribeByDeviceGroup(tenant, mor);
    }

    public int deleteSubscriptionsForDevice(String tenant, String deviceId, String subscriptionName) {
        return subscriptionManager.deleteSubscriptionsForDevice(tenant, deviceId, subscriptionName);
    }

    public void unsubscribeAllDevices(String tenant) {
        subscriptionManager.unsubscribeAllDevices(tenant);
    }

    // === Token/Subscriber Management Methods ===

    /**
     * Unsubscribe device subscriber tokens
     */
    public void unsubscribeDeviceSubscriber(String tenant) {
        tokenManager.unsubscribeDeviceSubscriber(tenant);
    }

    /**
     * Unsubscribe device group subscriber tokens
     */
    public void unsubscribeDeviceGroupSubscriber(String tenant) {
        tokenManager.unsubscribeDeviceGroupSubscriber(tenant);
    }

    /**
     * Unsubscribe a specific connector
     */
    public void unsubscribeDeviceSubscriberByConnector(String tenant, String connectorIdentifier) {
        tokenManager.unsubscribeDeviceSubscriberByConnector(tenant, connectorIdentifier);
    }

    // === Type Subscription Methods ===

    public NotificationSubscriptionResponse updateSubscriptionByType(String tenant, List<String> types) {
        return subscriptionManager.updateSubscriptionByType(tenant, types);
    }

    // === Query Methods ===

    public NotificationSubscriptionResponse getSubscriptionsDevices(String tenant, String deviceId,
            String deviceSubscription) {
        return queryService.getSubscriptionsDevices(tenant, deviceId, deviceSubscription);
    }

    public NotificationSubscriptionResponse getSubscriptionsByDeviceGroup(String tenant) {
        return queryService.getSubscriptionsByDeviceGroup(tenant);
    }

    public NotificationSubscriptionResponse getSubscriptionsByDeviceType(String tenant) {
        return queryService.getSubscriptionsByDeviceType(tenant);
    }

    // === Push Connectivity Methods ===

    public void activatePushConnectivityStatus(String tenant, String deviceId) {
        mqttPushManager.activatePushConnectivity(tenant, deviceId);
    }

    public void deactivatePushConnectivityStatus(String tenant, String deviceId) {
        mqttPushManager.deactivatePushConnectivity(tenant, deviceId);
    }

    // === Connection Status Methods ===

    public void setDeviceConnectionStatus(String tenant, Integer status) {
        connectionManager.setDeviceConnectionStatus(tenant, status);
    }

    public Integer getDeviceConnectionStatus(String tenant) {
        return connectionManager.getDeviceConnectionStatus(tenant);
    }

    // === Startup Cleanup ===

    /**
     * Unsubscribe orphaned Notifications 2.0 subscribers at startup.
     * Queries existing subscribers from the Cumulocity messaging-management API for
     * each of the four known subscriptions, then deletes any subscriber whose name
     * does not match a current valid pattern (known connector ID + current suffix).
     */
    public void cleanupOrphanedSubscribers(String tenant, List<ConnectorConfiguration> allConfigs,
            String currentSuffix) {
        if (!isNotificationServiceAvailable(tenant)) {
            log.debug("{} - Notification service not available, skipping orphaned subscriber cleanup", tenant);
            return;
        }

        log.info("{} - Starting orphaned subscriber cleanup", tenant);

        String suffix = currentSuffix != null ? currentSuffix : "";

        Set<String> currentConnectorIds = allConfigs != null
                ? allConfigs.stream().map(ConnectorConfiguration::getIdentifier).collect(Collectors.toSet())
                : Set.of();

        int cleaned = 0;

        // Tenant-level subscriptions: valid subscriber = base + suffix (no connector ID)
        cleaned += cleanupTenantSubscribers(tenant, Utils.MANAGEMENT_SUBSCRIPTION,
                Utils.MANAGEMENT_SUBSCRIBER, suffix);
        cleaned += cleanupTenantSubscribers(tenant, Utils.CACHE_INVENTORY_SUBSCRIPTION,
                Utils.CACHE_INVENTORY_SUBSCRIBER, suffix);

        // Per-connector subscriptions: valid subscriber = base + connectorId + suffix
        cleaned += cleanupDeviceSubscribers(tenant, Utils.STATIC_DEVICE_SUBSCRIPTION,
                Utils.STATIC_DEVICE_SUBSCRIBER, currentConnectorIds, suffix);
        cleaned += cleanupDeviceSubscribers(tenant, Utils.DYNAMIC_DEVICE_SUBSCRIPTION,
                Utils.DYNAMIC_DEVICE_SUBSCRIBER, currentConnectorIds, suffix);

        log.info("{} - Orphaned subscriber cleanup completed, {} subscriber(s) cleaned", tenant, cleaned);
    }

    private int cleanupTenantSubscribers(String tenant, String subscriptionName,
            String subscriberBase, String currentSuffix) {
        String validName = subscriberBase + currentSuffix;
        int cleaned = 0;
        for (String subscriber : messagingManagementService.getSubscribers(tenant, subscriptionName)) {
            if (!subscriber.isEmpty() && !subscriber.equals(validName)) {
                log.info("{} - Deleting orphaned Tenant subscriber '{}' from '{}'", tenant, subscriber, subscriptionName);
                try {
                    messagingManagementService.deleteSubscriber(tenant, subscriptionName, subscriber);
                    cleaned++;
                } catch (Exception e) {
                    log.warn("{} - Could not delete subscriber '{}' from subscription '{}': {}",
                            tenant, subscriber, subscriptionName, e.getMessage());
                }
            }
        }
        return cleaned;
    }

    private int cleanupDeviceSubscribers(String tenant, String subscriptionName,
            String subscriberBase, Set<String> currentConnectorIds, String currentSuffix) {
        int cleaned = 0;
        for (String subscriber : messagingManagementService.getSubscribers(tenant, subscriptionName)) {
            if (!subscriber.startsWith(subscriberBase)) {
                continue;
            }
            String rest = subscriber.substring(subscriberBase.length());
            boolean isValid = currentConnectorIds.stream()
                    .anyMatch(connId -> rest.equals(connId + currentSuffix));
            if (!isValid) {
                //log.info("{} - Deleting orphaned Device subscriber '{}' from '{}'", tenant, subscriber, subscriptionName);
                try {
                    messagingManagementService.deleteSubscriber(tenant, subscriptionName, subscriber);
                    cleaned++;
                } catch (Exception e) {
                    log.warn("{} - Could not delete subscriber '{}' from subscription '{}': {}",
                            tenant, subscriber, subscriptionName, e.getMessage());
                }
            }
        }
        return cleaned;
    }

    // === Connector Management Methods ===

    /**
     * Remove a connector and handle cleanup
     */
    public void removeConnector(String tenant, String connectorIdentifier) {
        log.info("{} - Removing connector {}", tenant, connectorIdentifier);
        
        // Remove subscriber from registry
        connectorRegistry.removeSubscriber(tenant, connectorIdentifier);
        
        // Handle connection cleanup
        connectionManager.handleConnectorRemoval(tenant, connectorIdentifier);
        
        log.info("{} - Successfully removed connector {}", tenant, connectorIdentifier);
    }

    /**
     * Add a connector
     */
    public void addConnector(String tenant, String connectorIdentifier, CamelDispatcherOutbound dispatcherOutbound) {
        log.info("{} - Adding connector {}", tenant, connectorIdentifier);
        
        // Add subscriber to registry
        connectorRegistry.addSubscriber(tenant, connectorIdentifier, dispatcherOutbound);
        
        log.info("{} - Successfully added connector {}", tenant, connectorIdentifier);
    }

    // === Device Discovery Methods ===

    public List<Device> findAllRelatedDevicesByMO(String tenant, ManagedObjectRepresentation mor,
            List<Device> devices, boolean isChildDevice) {
        return deviceDiscoveryService.findAllRelatedDevicesByMO(tenant, mor, devices, isChildDevice);
    }

    // === Cleanup ===

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up NotificationSubscriber");
        
        try {
            connectionManager.cleanup();
        } catch (Exception e) {
            log.error("Error cleaning up ConnectionManager: {}", e.getMessage(), e);
        }
        
        try {
            tokenManager.cleanup();
        } catch (Exception e) {
            log.error("Error cleaning up TokenManager: {}", e.getMessage(), e);
        }
        
        try {
            mqttPushManager.cleanup();
        } catch (Exception e) {
            log.error("Error cleaning up MqttPushManager: {}", e.getMessage(), e);
        }
        
        try {
            connectorRegistry.cleanup();
        } catch (Exception e) {
            log.error("Error cleaning up ConnectorRegistry: {}", e.getMessage(), e);
        }
        
        log.info("NotificationSubscriber cleanup completed");
    }
}