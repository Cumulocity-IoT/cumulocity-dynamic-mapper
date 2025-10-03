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

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionFilterRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationTokenRequestRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.Token;
import com.cumulocity.sdk.client.messaging.notifications.TokenApi;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.model.API;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.model.Device;
import dynamic.mapper.notification.websocket.CustomWebSocketClient;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import dynamic.mapper.util.Utils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class NotificationSubscriber {

    // Constants
    private static final String WEBSOCKET_PATH = "/notification2/consumer/?token=";
    private static final Integer TOKEN_REFRESH_INTERVAL_HOURS = 12;
    private static final Integer RECONNECT_INTERVAL_SECONDS = 60;
    private static final String DEVICE_SUBSCRIBER = "DynamicMapperDeviceSubscriber";
    private static final String DEVICE_SUBSCRIPTION = "DynamicMapperDeviceSubscription";
    private static final String MANAGEMENT_SUBSCRIBER = "DynamicMapperManagementSubscriber";
    private static final String MANAGEMENT_SUBSCRIPTION = "DynamicMapperManagementSubscription";
    private static final String CACHE_INVENTORY_SUBSCRIBER = "DynamicMapperCacheInventorySubscriber";
    private static final String CACHE_INVENTORY_SUBSCRIPTION = "DynamicMapperCacheInventorySubscription";
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;

    // Dependencies
    @Autowired
    private TokenApi tokenApi;

    @Autowired
    private NotificationSubscriptionApi subscriptionAPI;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Getter
    private ConfigurationRegistry configurationRegistry;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    @Autowired
    @Qualifier("virtualThreadPool")
    private ExecutorService virtualThreadPool;

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    @Value("${APP.additionalSubscriptionIdTest:}")
    private String additionalSubscriptionIdTest;

    // Thread-safe collections
    @Getter
    private final Map<String, Map<String, CamelDispatcherOutbound>> dispatcherOutboundMaps = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CustomWebSocketClient>> deviceClients = new ConcurrentHashMap<>();
    private final Map<String, CustomWebSocketClient> managementClients = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> managementCallbacks = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> cacheInventoryCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Integer> deviceWSStatusCodes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Mqtt3Client>> activePushConnections = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> deviceTokens = new ConcurrentHashMap<>();
    private final Map<String, String> managementTokens = new ConcurrentHashMap<>();
    private final Map<String, String> cacheInboundTokens = new ConcurrentHashMap<>();

    // Thread-safe executor services
    private volatile ScheduledExecutorService reconnectExecutor;
    private volatile ScheduledExecutorService tokenRefreshExecutor;

    // Circuit breaker for preventing infinite recursion
    private final Set<String> processingDevices = Collections.synchronizedSet(new HashSet<>());
    private final ThreadLocal<Integer> recursionDepth = ThreadLocal.withInitial(() -> 0);

    // Helper methods for null checks
    private boolean isValidSubscription(NotificationSubscriptionRepresentation sub) {
        return sub != null &&
                sub.getSource() != null &&
                sub.getSource().getId() != null;
    }

    private boolean isValidManagedObjectRef(ManagedObjectReferenceRepresentation ref) {
        return ref != null &&
                ref.getManagedObject() != null &&
                ref.getManagedObject().getId() != null;
    }

    private boolean isValidManagedObject(ManagedObjectRepresentation mor) {
        return mor != null && mor.getId() != null;
    }

    // Lifecycle Methods

    public void addSubscriber(String tenant, String identifier, CamelDispatcherOutbound dispatcherOutbound) {
        if (tenant == null || identifier == null || dispatcherOutbound == null) {
            log.warn("Cannot add subscriber with null parameters: tenant={}, identifier={}", tenant, identifier);
            return;
        }

        dispatcherOutboundMaps.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(identifier, dispatcherOutbound);
        log.debug("{} - Added subscriber {} for tenant", tenant, identifier);
    }

    public void initializeDeviceClient() {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            log.warn("Cannot initialize device client: tenant is null");
            return;
        }

        deviceClients.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        try {
            List<NotificationSubscriptionRepresentation> deviceSubs = getNotificationSubscriptionForDevices(null,
                    DEVICE_SUBSCRIPTION).get(30, TimeUnit.SECONDS);
            log.info("{} - Phase II: Notification 2.0, initializing {} device subscriptions", tenant,
                    deviceSubs.size());

            if (!deviceSubs.isEmpty()) {
                initializeDeviceConnections(tenant);
                activateDeviceConnections(tenant, deviceSubs);
            } else {
                log.info("{} - No existing device subscriptions found", tenant);
            }
        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing device client", tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing device client: {}", tenant, e.getMessage(), e);
        }
    }

    private void initializeDeviceConnections(String tenant) throws URISyntaxException {
        Map<String, CamelDispatcherOutbound> dispatchers = dispatcherOutboundMaps.get(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.warn("{} - No outbound dispatchers registered", tenant);
            return;
        }

        Map<String, String> tenantDeviceTokens = deviceTokens.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        for (CamelDispatcherOutbound dispatcher : dispatchers.values()) {
            if (dispatcher == null ||
                    dispatcher.getConnectorClient() == null ||
                    dispatcher.getConnectorClient().getConnectorConfiguration() == null ||
                    !dispatcher.getConnectorClient().getConnectorConfiguration().isEnabled()) {

                String connectorName = dispatcher != null && dispatcher.getConnectorClient() != null
                        ? dispatcher.getConnectorClient().getConnectorName()
                        : "unknown";
                log.debug("{} - Skipping disabled or invalid connector: {}", tenant, connectorName);
                continue;
            }

            String connectorId = dispatcher.getConnectorClient().getConnectorIdentifier();
            String tokenSeed = DEVICE_SUBSCRIBER + connectorId + additionalSubscriptionIdTest;

            try {
                String token = createToken(DEVICE_SUBSCRIPTION, tokenSeed);
                tenantDeviceTokens.put(connectorId, token);

                ConnectorId connectorInfo = new ConnectorId(
                        dispatcher.getConnectorClient().getConnectorName(),
                        connectorId);

                CustomWebSocketClient client = connect(token, dispatcher, connectorInfo);
                if (client != null) {
                    deviceClients.get(tenant).put(connectorId, client);
                    log.info("{} - Initialized device connection for connector: {}", tenant, connectorId);
                }
            } catch (Exception e) {
                log.error("{} - Failed to initialize device connection for connector {}: {}",
                        tenant, connectorId, e.getMessage(), e);
            }
        }
    }

    private void activateDeviceConnections(String tenant, List<NotificationSubscriptionRepresentation> deviceSubs) {
        int activatedCount = 0;
        for (NotificationSubscriptionRepresentation sub : deviceSubs) {
            try {
                if (isValidSubscription(sub)) {
                    ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                            .resolveGlobalId2ExternalId(tenant, sub.getSource().getId(), null, null);

                    if (extId != null) {
                        activatePushConnectivityStatus(tenant, extId.getExternalId());
                        activatedCount++;
                    } else {
                        log.debug("{} - Could not resolve external ID for device {}",
                                tenant, sub.getSource().getId());
                    }
                } else {
                    log.warn("{} - Invalid subscription representation: {}", tenant, sub);
                }
            } catch (Exception e) {
                log.warn("{} - Error activating device connection: {}", tenant, e.getMessage());
            }
        }
        log.info("{} - Activated {} device push connections", tenant, activatedCount);
    }

    public void initializeManagementClient() {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            log.warn("Cannot initialize management client: tenant is null");
            return;
        }

        NotificationCallback managementCallback = managementCallbacks.computeIfAbsent(tenant,
                k -> new ManagementSubscriptionClient(configurationRegistry, tenant));

        NotificationCallback cacheInventoryCallback = cacheInventoryCallbacks.computeIfAbsent(tenant,
                k -> new CacheInventorySubscriptionClient(configurationRegistry, tenant));

        try {
            List<NotificationSubscriptionRepresentation> managementSubs = getNotificationSubscriptionForDeviceGroup(
                    null, null).get(30, TimeUnit.SECONDS);
            log.info("{} - Phase II: Notification 2.0, initializing {} management subscriptions",
                    tenant, managementSubs.size());

            // Cache monitored groups
            cacheMonitoredGroups(tenant, managementSubs, managementCallback);

            // Create management connection
            createManagementConnection(tenant, managementCallback);

            // Create cache inventory connection
            createCacheInventoryConnection(tenant, cacheInventoryCallback);

        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing management client", tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing management client: {}", tenant, e.getMessage(), e);
        }
    }

    private void cacheMonitoredGroups(String tenant, List<NotificationSubscriptionRepresentation> subs,
            NotificationCallback callback) {
        int cachedCount = 0;
        for (NotificationSubscriptionRepresentation sub : subs) {
            try {
                if (isValidSubscription(sub)) {
                    ManagedObjectRepresentation groupMO = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, sub.getSource().getId().getValue());
                    if (groupMO != null && callback instanceof ManagementSubscriptionClient) {
                        ((ManagementSubscriptionClient) callback).addGroupToCache(groupMO);
                        cachedCount++;
                    }
                } else {
                    log.warn("{} - Invalid subscription for caching: {}", tenant, sub);
                }
            } catch (Exception e) {
                log.warn("{} - Error caching group: {}", tenant, e.getMessage());
            }
        }
        log.info("{} - Cached {} monitored groups", tenant, cachedCount);
    }

    private void createManagementConnection(String tenant, NotificationCallback callback)
            throws URISyntaxException {
        String tokenSeed = MANAGEMENT_SUBSCRIBER + additionalSubscriptionIdTest;
        String token = createToken(MANAGEMENT_SUBSCRIPTION, tokenSeed);
        ConnectorId connectorId = new ConnectorId(
                ManagementSubscriptionClient.CONNECTOR_NAME,
                ManagementSubscriptionClient.CONNECTOR_ID);

        managementTokens.put(tenant, token);
        CustomWebSocketClient client = connect(token, callback, connectorId);
        if (client != null) {
            managementClients.put(tenant, client);
            log.info("{} - Created management connection", tenant);
        }
    }

    private void createCacheInventoryConnection(String tenant, NotificationCallback callback)
            throws URISyntaxException {
        String tokenSeed = CACHE_INVENTORY_SUBSCRIBER + additionalSubscriptionIdTest;
        String token = createToken(CACHE_INVENTORY_SUBSCRIPTION, tokenSeed);
        ConnectorId connectorId = new ConnectorId(
                CacheInventorySubscriptionClient.CONNECTOR_NAME,
                CacheInventorySubscriptionClient.CONNECTOR_ID);

        cacheInboundTokens.put(tenant, token);
        CustomWebSocketClient client = connect(token, callback, connectorId);
        if (client != null) {
            managementClients.put(tenant, client);
            log.info("{} - Created cache inbound connection", tenant);
        }
    }

    public void notificationSubscriberReconnect(String tenant) {
        if (tenant == null) {
            log.warn("Cannot reconnect: tenant is null");
            return;
        }

        log.info("{} - Reconnecting notification subscriber", tenant);
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                disconnect(tenant);
                initializeDeviceClient();
                initializeManagementClient();
                log.info("{} - Successfully reconnected notification subscriber", tenant);
            } catch (Exception e) {
                log.error("{} - Error during reconnection: {}", tenant, e.getMessage(), e);
            }
        });
    }

    public boolean isNotificationServiceAvailable(String tenant) {
        if (tenant == null) {
            log.warn("Cannot check notification service: tenant is null");
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

    // Device Subscription Methods

    public Future<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(
            String tenant, ManagedObjectRepresentation mor, API api) {

        if (!isValidManagedObject(mor)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ManagedObject cannot be null"));
        }

        if (tenant == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tenant cannot be null"));
        }

        if (api == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("API cannot be null"));
        }

        String deviceId = mor.getId().getValue();
        if (processingDevices.contains(deviceId)) {
            log.debug("{} - Device {} is already being processed, skipping", tenant, deviceId);
            return CompletableFuture.completedFuture(null);
        }

        processingDevices.add(deviceId);

        return virtualThreadPool.submit(() -> {
            try {
                return subscriptionsService.callForTenant(tenant, () -> {
                    log.info("{} - Creating subscription for device: {} {}",
                            tenant, mor.getName() == null ? "" : mor.getName() == null, deviceId);

                    NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(mor, api, DEVICE_SUBSCRIPTION);

                    // Initialize connections if needed
                    if (shouldInitializeConnections(tenant)) {
                        try {
                            initializeDeviceConnections(tenant);
                        } catch (URISyntaxException e) {
                            log.error("{} - Error initializing device connections: {}", tenant, e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }

                    // Activate push connectivity
                    activateDevicePushConnectivity(tenant, mor);

                    log.info("{} - Successfully created subscription for device {}", tenant, deviceId);
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

    private void activateDevicePushConnectivity(String tenant, ManagedObjectRepresentation mor) {
        try {
            ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                    .resolveGlobalId2ExternalId(tenant, mor.getId(), null, null);
            if (extId != null) {
                activatePushConnectivityStatus(tenant, extId.getExternalId());
            } else {
                log.debug("{} - No external ID found for device {}, using internal ID",
                        tenant, mor.getId().getValue());
                // Use internal ID as fallback
                activatePushConnectivityStatus(tenant, mor.getId().getValue());
            }
        } catch (Exception e) {
            log.warn("{} - Error activating push connectivity for device {}: {}",
                    tenant, mor.getId().getValue(), e.getMessage());
        }
    }

    private boolean shouldInitializeConnections(String tenant) {
        Integer status = deviceWSStatusCodes.get(tenant);
        return status == null || status != 200;
    }

    public Future<NotificationSubscriptionRepresentation> subscribeByDeviceGroup(
            ManagedObjectRepresentation mor) {

        if (!isValidManagedObject(mor)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ManagedObject cannot be null"));
        }

        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tenant cannot be determined"));
        }

        log.info("{} - Creating subscription by device group {} ({})", tenant, mor.getName(), mor.getId().getValue());

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            try {
                NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(mor, API.INVENTORY,
                        MANAGEMENT_SUBSCRIPTION);
                log.info("{} - Successfully created group subscription for {}", tenant, mor.getId().getValue());
                return nsr;
            } catch (Exception e) {
                log.error("{} - Error creating group subscription for {}: {}",
                        tenant, mor.getId().getValue(), e.getMessage(), e);
                throw new RuntimeException("Failed to create group subscription: " + e.getMessage(), e);
            }
        }));
    }

    public Future<NotificationSubscriptionRepresentation> subscribeMOForInventoryCacheUpdates(
            ManagedObjectRepresentation mor) {

        if (!isValidManagedObject(mor)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ManagedObject cannot be null"));
        }

        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tenant cannot be determined"));
        }

        log.info("{} - Creating subscription for inventory cache updates {} ({})", tenant, mor.getName(),
                mor.getId().getValue());

        return virtualThreadPool.submit(() -> subscriptionsService.callForTenant(tenant, () -> {
            try {
                NotificationSubscriptionRepresentation nsr = createSubscriptionByMO(mor, API.INVENTORY,
                        CACHE_INVENTORY_SUBSCRIPTION);
                log.info("{} - Successfully created subscription for inventory cache updates for {}", tenant,
                        mor.getId().getValue());
                return nsr;
            } catch (Exception e) {
                log.error("{} - Error creating subscription for inventory cache updates for {}: {}",
                        tenant, mor.getId().getValue(), e.getMessage(), e);
                throw new RuntimeException(
                        "Failed to create subscription for inventory cache updates: " + e.getMessage(), e);
            }
        }));
    }

    public boolean unsubscribeMOForInventoryCacheUpdates(ManagedObjectRepresentation mor) {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be determined");
        }

        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                // Get existing subscription for MO
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
                        return true;
                    }
                }

                return false;
            } catch (Exception e) {
                log.error("{} - Error unsubscribing MO from inventory cache updates (removing subscription): {}",
                        tenant, e.getMessage(), e);
                throw new RuntimeException(
                        "Failed to unsubscribing MO from inventory cache updates (removing subscription): "
                                + e.getMessage(),
                        e);
            }
        });
    }

    public void unsubscribeAllMOForInventoryCacheUpdates() {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be determined");
        }

        subscriptionsService.callForTenant(tenant, () -> {
            try {
                // Get existing subscription for MO
                Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                        .getSubscriptionsByFilter(
                                new NotificationSubscriptionFilter()
                                        .bySubscription(CACHE_INVENTORY_SUBSCRIPTION))
                        .get().allPages().iterator();
                while (subIt.hasNext()) {
                    NotificationSubscriptionRepresentation nsr = subIt.next();
                    subscriptionAPI.delete(nsr);
                }

                return true;
            } catch (Exception e) {
                log.error("{} - Error unsubscribing all MOs from inventory cache updates (removing subscription): {}",
                        tenant, e.getMessage(), e);
                throw new RuntimeException(
                        "Failed to unsubscribing all MOs from inventory cache updates (removing subscription): "
                                + e.getMessage(),
                        e);
            }
        });
    }

    private NotificationSubscriptionRepresentation createSubscriptionByMO(
            ManagedObjectRepresentation mor, API api, String subscriptionName) {

        if (!isValidManagedObject(mor)) {
            throw new IllegalArgumentException("ManagedObject or its ID cannot be null");
        }

        String tenant = subscriptionsService.getTenant();

        // Check if subscription already exists
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
                    log.debug("{} - Subscription {} already exists for source {}",
                            tenant, existing.getId().getValue(), mor.getId().getValue());
                    return existing;
                }
            }
        } catch (Exception e) {
            log.warn("{} - Error checking existing subscriptions: {}", tenant, e.getMessage());
        }

        // Create new subscription
        try {
            NotificationSubscriptionRepresentation nsr = new NotificationSubscriptionRepresentation();
            nsr.setSource(mor);
            nsr.setContext("mo");
            nsr.setSubscription(subscriptionName);

            NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
            filter.setApis(List.of(api.notificationFilter));
            nsr.setSubscriptionFilter(filter);

            NotificationSubscriptionRepresentation result = subscriptionAPI.subscribe(nsr);
            log.debug("{} - Created new subscription {} for source {}",
                    tenant, result.getId().getValue(), mor.getId().getValue());
            return result;
        } catch (Exception e) {
            log.error("{} - Error creating subscription for {}: {}", tenant, mor.getId().getValue(), e.getMessage(), e);
            throw new RuntimeException("Failed to create subscription: " + e.getMessage(), e);
        }
    }

    // MQTT Push Connectivity Methods

    public void activatePushConnectivityStatus(String tenant, String deviceId) {
        if (tenant == null || deviceId == null || deviceId.trim().isEmpty()) {
            log.warn("{} - Cannot activate push connectivity: invalid parameters (tenant={}, deviceId={})",
                    tenant, deviceId);
            return;
        }

        // Check if already connected
        Map<String, Mqtt3Client> tenantConnections = activePushConnections.get(tenant);
        if (tenantConnections != null && tenantConnections.containsKey(deviceId)) {
            Mqtt3Client existing = tenantConnections.get(deviceId);
            if (existing != null && existing.getState().isConnected()) {
                log.debug("{} - MQTT already connected for device {}", tenant, deviceId);
                return;
            }
        }

        try {
            String mqttHost = extractMqttHost(baseUrl);
            log.info("{} - Activating MQTT push connectivity for device {} at host {}",
                    tenant, deviceId, mqttHost);
            Optional<MicroserviceCredentials> credentialsOpt = subscriptionsService.getCredentials(tenant);

            if (credentialsOpt.isEmpty()) {
                log.warn("{} - No credentials found for tenant, cannot activate MQTT for device {}", tenant, deviceId);
                return;
            }

            MicroserviceCredentials credentials = credentialsOpt.get();
            Mqtt3SimpleAuth auth = Mqtt3SimpleAuth.builder()
                    .username(credentials.getTenant() + "/" + credentials.getUsername())
                    .password(credentials.getPassword().getBytes(StandardCharsets.UTF_8))
                    .build();

            Mqtt3AsyncClient client = Mqtt3Client.builder()
                    .serverHost(mqttHost)
                    .serverPort(8883)
                    .sslWithDefaultConfig()
                    .identifier(deviceId)
                    .automaticReconnectWithDefaultConfig()
                    .simpleAuth(auth)
                    .buildAsync();

            CompletableFuture<Void> connectionFuture = client.connectWith()
                    .cleanSession(true)
                    .keepAlive(60)
                    .send()
                    .thenRun(() -> {
                        log.info("{} - MQTT connected for device {}", tenant, deviceId);

                        // Subscribe to device messages
                        client.toAsync().subscribeWith()
                                .topicFilter("s/ds")
                                .qos(MqttQos.AT_LEAST_ONCE)
                                .callback(publish -> {
                                    if (log.isDebugEnabled()) {
                                        log.debug("{} - MQTT message received from device {}: {}",
                                                tenant, deviceId, new String(publish.getPayloadAsBytes()));
                                    }
                                })
                                .send()
                                .whenComplete((ack, throwable) -> {
                                    if (throwable != null) {
                                        log.warn("{} - Failed to subscribe to topic for device {}: {}",
                                                tenant, deviceId, throwable.getMessage());
                                    }
                                });
                    });

            // Handle connection errors
            connectionFuture.exceptionally(throwable -> {
                // Get meaningful error information
                String errorClass = throwable.getClass().getSimpleName();
                String errorMessage = throwable.getMessage();
                String causeInfo = "";

                if (throwable.getCause() != null) {
                    Throwable cause = throwable.getCause();
                    causeInfo = String.format(" | Caused by %s: %s",
                            cause.getClass().getSimpleName(),
                            cause.getMessage() != null ? cause.getMessage() : "No cause message");
                }

                log.error("{} - MQTT connection failed for device {}: {}{}{}",
                        tenant, deviceId, errorClass,
                        errorMessage != null ? ": " + errorMessage : " (no message)",
                        causeInfo);

                // Optionally log full stack trace at debug level
                log.debug("{} - Full stack trace for connection failure:", tenant, throwable);

                return null;
            });

            // Add timeout for connection
            connectionFuture.orTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((result, throwable) -> {
                        if (throwable instanceof TimeoutException) {
                            log.warn("{} - MQTT connection timeout for device {}", tenant, deviceId);
                            client.disconnect();
                        }
                    });

            activePushConnections.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                    .put(deviceId, client);

        } catch (Exception e) {
            log.error("{} - Error activating push connectivity for device {}: {}",
                    tenant, deviceId, e.getMessage(), e);
        }
    }

    private String extractMqttHost(String baseUrl) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("Base URL cannot be null");
        }
        return baseUrl.replace("http://", "")
                .replace("https://", "")
                .replace(":8111", "")
                .replace(":8111/", "");
    }

    public void deactivatePushConnectivityStatus(String tenant, String deviceId) {
        if (tenant == null || deviceId == null) {
            log.warn("Cannot deactivate push connectivity: invalid parameters (tenant={}, deviceId={})", tenant,
                    deviceId);
            return;
        }

        Map<String, Mqtt3Client> clients = activePushConnections.get(tenant);
        if (clients != null) {
            Mqtt3Client client = clients.remove(deviceId);
            if (client != null && client.getState().isConnected()) {
                try {
                    client.toBlocking().disconnect();
                    log.info("{} - MQTT disconnected for device {}", tenant, deviceId);
                } catch (Exception e) {
                    log.warn("{} - Error disconnecting MQTT for device {}: {}",
                            tenant, deviceId, e.getMessage());
                }
            }
        }
    }

    // Query Methods

    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDevices(
            String deviceId, String deviceSubscription) {

        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tenant cannot be determined"));
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        filter = filter.bySubscription(deviceSubscription != null ? deviceSubscription : DEVICE_SUBSCRIPTION);

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

    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDeviceGroup(
            String deviceId, String deviceSubscription) {

        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tenant cannot be determined"));
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        filter = filter.bySubscription(deviceSubscription != null ? deviceSubscription : MANAGEMENT_SUBSCRIPTION);

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

    private Future<NotificationSubscriptionRepresentation> getNotificationSubscriptionForDeviceType() {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tenant cannot be determined"));
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(MANAGEMENT_SUBSCRIPTION)
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

    // Response Methods

    public NotificationSubscriptionResponse getSubscriptionsDevices(String tenant, String deviceId,
            String deviceSubscription) {

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(deviceSubscription != null ? deviceSubscription : DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");

        NotificationSubscriptionFilter finalFilter = filter;
        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = NotificationSubscriptionResponse
                .builder();
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
                    .getManagedObjectForId(tenant, device.getId());
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

    public NotificationSubscriptionResponse getSubscriptionsByDeviceGroup(String tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter()
                .bySubscription(MANAGEMENT_SUBSCRIPTION)
                .byContext("mo");

        NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = NotificationSubscriptionResponse
                .builder();
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

    public NotificationSubscriptionResponse getSubscriptionsByDeviceType(String tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be null");
        }

        NotificationSubscriptionResponse response = NotificationSubscriptionResponse.builder().build();

        try {
            Future<NotificationSubscriptionRepresentation> future = getNotificationSubscriptionForDeviceType();
            NotificationSubscriptionRepresentation nsr = future.get(30, TimeUnit.SECONDS);

            if (nsr != null && nsr.getSubscriptionFilter() != null) {
                String filterString = nsr.getSubscriptionFilter().getTypeFilter();
                log.debug("{} - Retrieved type subscription with filter: {}", tenant, filterString);

                if (filterString != null) {
                    List<String> types = new ArrayList<>(Utils.parseTypesFromFilter(filterString));
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

    // Type Subscription Methods

    public NotificationSubscriptionResponse updateSubscriptionByType(List<String> types) {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant cannot be determined");
        }

        return subscriptionsService.callForTenant(tenant, () -> {
            try {
                // Get existing subscription
                NotificationSubscriptionRepresentation existing = findExistingTypeSubscription();
                String existingTypeFilter = null;
                if (existing != null && existing.getSubscriptionFilter() != null) {
                    existingTypeFilter = existing.getSubscriptionFilter().getTypeFilter();
                }

                // Delete existing if found
                if (existing != null) {
                    subscriptionAPI.delete(existing);
                    log.info("{} - Deleted existing type subscription", tenant);
                }

                // Create new subscription with updated types
                String newTypeFilter = Utils.createChangedTypeFilter(types, existingTypeFilter);
                NotificationSubscriptionResponse.NotificationSubscriptionResponseBuilder responseBuilder = NotificationSubscriptionResponse
                        .builder()
                        .subscriptionName(MANAGEMENT_SUBSCRIPTION);

                if (newTypeFilter != null && !newTypeFilter.trim().isEmpty()) {
                    NotificationSubscriptionRepresentation nsr = createTypeSubscription(newTypeFilter);
                    responseBuilder.types(new ArrayList<>(Utils.parseTypesFromFilter(newTypeFilter)))
                            .subscriptionId(nsr.getId().getValue())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE);
                    log.info("{} - Created new type subscription with {} types", tenant, types.size());
                } else {
                    responseBuilder.types(new ArrayList<>())
                            .status(NotificationSubscriptionResponse.SubscriptionStatus.INACTIVE);
                    log.info("{} - No type filter created, subscription inactive", tenant);
                }

                return responseBuilder.build();
            } catch (Exception e) {
                log.error("{} - Error updating type subscription: {}", tenant, e.getMessage(), e);
                throw new RuntimeException("Failed to update type subscription: " + e.getMessage(), e);
            }
        });
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

    // Unsubscribe Methods

    public void unsubscribeDeviceAndDisconnect(String tenant, ManagedObjectRepresentation mor) {
        if (tenant == null || !isValidManagedObject(mor)) {
            log.warn("Cannot unsubscribe device: invalid parameters (tenant={}, mor={})", tenant, mor);
            return;
        }

        String deviceId = mor.getId().getValue();
        log.info("{} - Unsubscribing device {}", tenant, deviceId);

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                // Remove device subscriptions
                Future<List<NotificationSubscriptionRepresentation>> future = getNotificationSubscriptionForDevices(
                        deviceId, DEVICE_SUBSCRIPTION);

                List<NotificationSubscriptionRepresentation> subscriptions = future.get(30, TimeUnit.SECONDS);
                int deletedCount = 0;

                for (NotificationSubscriptionRepresentation sub : subscriptions) {
                    try {
                        subscriptionAPI.delete(sub);
                        deletedCount++;
                        log.debug("{} - Deleted subscription {} for device {}",
                                tenant, sub.getId().getValue(), deviceId);
                    } catch (Exception e) {
                        log.warn("{} - Error deleting subscription {}: {}",
                                tenant, sub.getId().getValue(), e.getMessage());
                    }
                }

                log.info("{} - Deleted {} subscriptions for device {}", tenant, deletedCount, deviceId);

                // Check if we should disconnect WebSocket
                if (shouldDisconnectAfterUnsubscribe(tenant)) {
                    disconnect(tenant);
                }

                // Deactivate push connectivity
                deactivatePushConnectivity(tenant, mor);

            } catch (Exception e) {
                log.error("{} - Error unsubscribing device {}: {}", tenant, deviceId, e.getMessage(), e);
            }
        });
    }

    private boolean shouldDisconnectAfterUnsubscribe(String tenant) {
        try {
            Future<List<NotificationSubscriptionRepresentation>> future = getNotificationSubscriptionForDevices(null,
                    DEVICE_SUBSCRIPTION);
            List<NotificationSubscriptionRepresentation> remainingSubscriptions = future.get(10, TimeUnit.SECONDS);
            return remainingSubscriptions.isEmpty();
        } catch (Exception e) {
            log.warn("{} - Error checking remaining subscriptions: {}", tenant, e.getMessage());
            return false;
        }
    }

    private void deactivatePushConnectivity(String tenant, ManagedObjectRepresentation mor) {
        try {
            ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                    .resolveGlobalId2ExternalId(tenant, mor.getId(), null, null);

            String deviceId = extId != null ? extId.getExternalId() : mor.getId().getValue();
            deactivatePushConnectivityStatus(tenant, deviceId);
        } catch (Exception e) {
            log.warn("{} - Error deactivating push connectivity for device {}: {}",
                    tenant, mor.getId().getValue(), e.getMessage());
        }
    }

    public void unsubscribeByDeviceGroup(ManagedObjectRepresentation mor) {
        if (!isValidManagedObject(mor)) {
            log.warn("Cannot unsubscribe group: ManagedObject or ID is null");
            return;
        }

        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            log.warn("Cannot unsubscribe group: tenant is null");
            return;
        }

        String groupId = mor.getId().getValue();
        log.info("{} - Unsubscribing device group {}", tenant, groupId);

        NotificationCallback managementCallback = managementCallbacks.get(tenant);

        subscriptionsService.runForTenant(tenant, () -> {
            try {
                // Remove group subscriptions
                Future<List<NotificationSubscriptionRepresentation>> future = getNotificationSubscriptionForDeviceGroup(
                        groupId, MANAGEMENT_SUBSCRIPTION);

                List<NotificationSubscriptionRepresentation> subscriptions = future.get(30, TimeUnit.SECONDS);
                int deletedCount = 0;

                for (NotificationSubscriptionRepresentation sub : subscriptions) {
                    try {
                        subscriptionAPI.delete(sub);
                        deletedCount++;
                        log.debug("{} - Deleted group subscription {} for group {}",
                                tenant, sub.getId().getValue(), groupId);
                    } catch (Exception e) {
                        log.warn("{} - Error deleting group subscription {}: {}",
                                tenant, sub.getId().getValue(), e.getMessage());
                    }
                }

                log.info("{} - Deleted {} subscriptions for group {}", tenant, deletedCount, groupId);

                // Remove group from cache
                if (managementCallback instanceof ManagementSubscriptionClient) {
                    ((ManagementSubscriptionClient) managementCallback).removeGroupFromCache(mor);
                }

            } catch (Exception e) {
                log.error("{} - Error unsubscribing group {}: {}", tenant, groupId, e.getMessage(), e);
            }
        });
    }

    public void unsubscribeAllDevices() {
        String tenant = subscriptionsService.getTenant();
        if (tenant == null) {
            log.warn("Cannot unsubscribe all devices: tenant is null");
            return;
        }

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

    // Token Management Methods

    public void unsubscribeDeviceSubscriber(String tenant) {
        if (tenant == null) {
            log.warn("Cannot unsubscribe device subscriber: tenant is null");
            return;
        }

        String token = managementTokens.remove(tenant);
        if (token != null) {
            try {
                tokenApi.unsubscribe(new Token(token));
                log.info("{} - Unsubscribed device subscriber", tenant);
            } catch (Exception e) {
                log.warn("{} - Error unsubscribing device subscriber: {}", tenant, e.getMessage());
            }
        }
    }

    public void unsubscribeDeviceGroupSubscriber(String tenant) {
        if (tenant == null) {
            log.warn("Cannot unsubscribe device group subscriber: tenant is null");
            return;
        }

        Map<String, String> tenantTokens = deviceTokens.remove(tenant);
        if (tenantTokens != null) {
            int unsubscribedCount = 0;
            for (String token : tenantTokens.values()) {
                try {
                    tokenApi.unsubscribe(new Token(token));
                    unsubscribedCount++;
                } catch (Exception e) {
                    log.warn("{} - Error unsubscribing token: {}", tenant, e.getMessage());
                }
            }
            log.info("{} - Unsubscribed {} device group subscribers", tenant, unsubscribedCount);
        }
    }

    public void unsubscribeDeviceSubscriberByConnector(String tenant, String connectorIdentifier) {
        if (tenant == null || connectorIdentifier == null) {
            log.warn("Cannot unsubscribe connector: invalid parameters (tenant={}, connector={})",
                    tenant, connectorIdentifier);
            return;
        }

        Map<String, String> tenantTokens = deviceTokens.get(tenant);
        if (tenantTokens != null) {
            String token = tenantTokens.remove(connectorIdentifier);
            if (token != null) {
                try {
                    tokenApi.unsubscribe(new Token(token));
                    log.info("{} - Unsubscribed connector {} from Notification 2.0", tenant, connectorIdentifier);
                } catch (SDKException e) {
                    log.error("{} - Could not unsubscribe connector {}: {}",
                            tenant, connectorIdentifier, e.getMessage(), e);
                }
            }
        }
    }

    // Utility Methods

    public String createToken(String subscription, String subscriber) {
        if (subscription == null || subscriber == null) {
            throw new IllegalArgumentException("Subscription and subscriber cannot be null");
        }

        try {
            NotificationTokenRequestRepresentation tokenRequest = new NotificationTokenRequestRepresentation(
                    subscriber, subscription, 1440, false);
            return tokenApi.create(tokenRequest).getTokenString();
        } catch (Exception e) {
            log.error("Error creating token for subscription {} and subscriber {}: {}",
                    subscription, subscriber, e.getMessage(), e);
            throw new RuntimeException("Failed to create token: " + e.getMessage(), e);
        }
    }

    public void setDeviceConnectionStatus(String tenant, Integer status) {
        if (tenant != null) {
            if (status != null) {
                deviceWSStatusCodes.put(tenant, status);
            } else {
                deviceWSStatusCodes.remove(tenant);
            }
        }
    }

    public Integer getDeviceConnectionStatus(String tenant) {
        return tenant != null ? deviceWSStatusCodes.get(tenant) : null;
    }

    // Connector Management Methods

    public void removeConnector(String tenant, String connectorIdentifier) {
        if (tenant == null || connectorIdentifier == null) {
            log.warn("Cannot remove connector: invalid parameters (tenant={}, connector={})",
                    tenant, connectorIdentifier);
            return;
        }

        log.info("{} - Removing connector {}", tenant, connectorIdentifier);

        // Remove from dispatcher map
        Map<String, CamelDispatcherOutbound> dispatchers = dispatcherOutboundMaps.get(tenant);
        if (dispatchers != null) {
            dispatchers.remove(connectorIdentifier);
        }

        // Close WebSocket connection
        Map<String, CustomWebSocketClient> tenantClients = deviceClients.get(tenant);
        if (tenantClients != null) {
            CustomWebSocketClient client = tenantClients.remove(connectorIdentifier);
            if (client != null) {
                try {
                    client.close();
                    log.info("{} - Closed WebSocket connection for connector {}", tenant, connectorIdentifier);
                } catch (Exception e) {
                    log.warn("{} - Error closing WebSocket for connector {}: {}",
                            tenant, connectorIdentifier, e.getMessage());
                }
            }
        }

        // Disconnect if no more dispatchers
        if (dispatchers != null && dispatchers.isEmpty()) {
            log.info("{} - No more connectors, disconnecting", tenant);
            disconnect(tenant);
        }

        log.info("{} - Successfully removed connector {}", tenant, connectorIdentifier);
    }

    public void addConnector(String tenant, String connectorIdentifier, CamelDispatcherOutbound dispatcherOutbound) {
        if (tenant == null || connectorIdentifier == null || dispatcherOutbound == null) {
            log.warn("Cannot add connector: invalid parameters");
            return;
        }

        log.info("{} - Adding connector {}", tenant, connectorIdentifier);

        // Add to dispatcher map
        dispatcherOutboundMaps.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(connectorIdentifier, dispatcherOutbound);

        // Initialize token map
        deviceTokens.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        log.info("{} - Successfully added connector {}", tenant, connectorIdentifier);
    }

    // Device Discovery Methods

    public List<Device> findAllRelatedDevicesByMO(ManagedObjectRepresentation mor,
            List<Device> devices, boolean isChildDevice) {

        if (mor == null) {
            return devices != null ? devices : new ArrayList<>();
        }

        // Check recursion depth to prevent stack overflow
        Integer depth = recursionDepth.get();
        if (depth >= MAX_RECURSION_DEPTH) {
            log.warn("Maximum recursion depth reached for device discovery, stopping at device {}",
                    mor.getId().getValue());
            return devices != null ? devices : new ArrayList<>();
        }

        recursionDepth.set(depth + 1);

        try {
            if (devices == null) {
                devices = new ArrayList<>();
            }

            String deviceId = mor.getId().getValue();
            String tenant = subscriptionsService.getTenant();

            // Prevent infinite recursion with circular references
            if (processingDevices.contains(deviceId)) {
                log.debug("{} - Circular reference detected for device {}, skipping", tenant, deviceId);
                return devices;
            }

            processingDevices.add(deviceId);

            try {
                if (mor.hasProperty("c8y_IsDevice") || isChildDevice) {
                    addDeviceIfNotExists(devices, mor);
                    processChildDevices(tenant, mor, devices);
                } else if (mor.hasProperty("c8y_IsDeviceGroup")) {
                    processGroupAssets(tenant, mor, devices);
                } else {
                    log.debug("{} - ManagedObject {} is neither device nor group, skipping", tenant, deviceId);
                }
            } finally {
                processingDevices.remove(deviceId);
            }
        } finally {
            recursionDepth.set(depth);
        }

        return devices;
    }

    private void addDeviceIfNotExists(List<Device> devices, ManagedObjectRepresentation mor) {
        Device device = new Device();
        device.setId(mor.getId().getValue());
        device.setName(mor.getName());
        device.setType(mor.getType());

        if (!devices.contains(device)) {
            devices.add(device);
            log.debug("Added device {} to discovery list", device.getId());
        }
    }

    private void processChildDevices(String tenant, ManagedObjectRepresentation mor, List<Device> devices) {
        if (mor.getChildDevices() == null || mor.getChildDevices().getReferences() == null) {
            return;
        }

        for (ManagedObjectReferenceRepresentation childRef : mor.getChildDevices().getReferences()) {
            try {
                if (isValidManagedObjectRef(childRef)) {
                    ManagedObjectRepresentation child = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, childRef.getManagedObject().getId().getValue());
                    if (child != null) {
                        findAllRelatedDevicesByMO(child, devices, true);
                    }
                }
            } catch (Exception e) {
                String deviceId = null;
                if (isValidManagedObjectRef(childRef)) {
                    deviceId = childRef.getManagedObject().getId().getValue();
                }
                log.warn("{} - Error processing child device {}: {}", tenant, deviceId, e.getMessage());
            }
        }
    }

    private void processGroupAssets(String tenant, ManagedObjectRepresentation mor, List<Device> devices) {
        if (mor.getChildAssets() == null || mor.getChildAssets().getReferences() == null) {
            return;
        }

        for (ManagedObjectReferenceRepresentation assetRef : mor.getChildAssets().getReferences()) {
            try {
                if (isValidManagedObjectRef(assetRef)) {
                    ManagedObjectRepresentation asset = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, assetRef.getManagedObject().getId().getValue());
                    if (asset != null) {
                        findAllRelatedDevicesByMO(asset, devices, false);
                    }
                }
            } catch (Exception e) {
                String assetId = null;
                if (isValidManagedObjectRef(assetRef)) {
                    assetId = assetRef.getManagedObject().getId().getValue();
                }
                log.warn("{} - Error processing group asset {}: {}", tenant, assetId, e.getMessage());
            }
        }
    }

    // Connection Management Methods

    public void disconnect(String tenant) {
        if (tenant == null) {
            log.warn("Cannot disconnect: tenant is null");
            return;
        }

        log.info("{} - Disconnecting notification subscriber", tenant);

        // Stop executors
        stopReconnectExecutor();
        stopTokenRefreshExecutor();

        // Close device clients
        disconnectDeviceClients(tenant);

        // Close management client
        disconnectManagementClient(tenant);

        // Close MQTT connections
        disconnectMqttConnections(tenant);

        // Clear status
        deviceWSStatusCodes.remove(tenant);

        // Send notification
        try {
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.DISCONNECTED, null);
        } catch (Exception e) {
            log.warn("{} - Error sending disconnect notification: {}", tenant, e.getMessage());
        }

        log.info("{} - Successfully disconnected notification subscriber", tenant);
    }

    private void stopReconnectExecutor() {
        ScheduledExecutorService executor = reconnectExecutor;
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                reconnectExecutor = null;
                log.debug("Stopped reconnect executor");
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping reconnect executor");
            }
        }
    }

    private void stopTokenRefreshExecutor() {
        ScheduledExecutorService executor = tokenRefreshExecutor;
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                tokenRefreshExecutor = null;
                log.debug("Stopped token refresh executor");
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping token refresh executor");
            }
        }
    }

    private void disconnectDeviceClients(String tenant) {
        Map<String, CustomWebSocketClient> tenantClients = deviceClients.get(tenant);
        if (tenantClients != null) {
            int disconnectedCount = 0;
            for (CustomWebSocketClient client : tenantClients.values()) {
                if (client != null && client.isOpen()) {
                    try {
                        client.close();
                        disconnectedCount++;
                    } catch (Exception e) {
                        log.warn("{} - Error closing device client: {}", tenant, e.getMessage());
                    }
                }
            }
            tenantClients.clear();
            log.info("{} - Disconnected {} device WebSocket clients", tenant, disconnectedCount);
        }
    }

    private void disconnectManagementClient(String tenant) {
        CustomWebSocketClient managementClient = managementClients.remove(tenant);
        if (managementClient != null) {
            try {
                managementClient.close();
                log.info("{} - Disconnected management WebSocket client", tenant);
            } catch (Exception e) {
                log.warn("{} - Error closing management client: {}", tenant, e.getMessage());
            }
        }
    }

    private void disconnectMqttConnections(String tenant) {
        Map<String, Mqtt3Client> tenantConnections = activePushConnections.remove(tenant);
        if (tenantConnections != null) {
            int disconnectedCount = 0;
            for (Map.Entry<String, Mqtt3Client> entry : tenantConnections.entrySet()) {
                try {
                    Mqtt3Client client = entry.getValue();
                    if (client != null && client.getState().isConnected()) {
                        client.toBlocking().disconnect();
                        disconnectedCount++;
                    }
                } catch (Exception e) {
                    log.warn("{} - Error disconnecting MQTT for device {}: {}",
                            tenant, entry.getKey(), e.getMessage());
                }
            }
            log.info("{} - Disconnected {} MQTT connections", tenant, disconnectedCount);
        }
    }

    public CustomWebSocketClient connect(String token, NotificationCallback callback, ConnectorId connectorId)
            throws URISyntaxException {

        if (token == null || callback == null || connectorId == null) {
            log.warn("Cannot connect: invalid parameters");
            return null;
        }

        String tenant = subscriptionsService.getTenant();

        try {
            // Send connecting status
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);

            String webSocketBaseUrl = baseUrl.replace("http", "ws");
            URI webSocketUrl = new URI(webSocketBaseUrl + WEBSOCKET_PATH + token);

            CustomWebSocketClient client = new CustomWebSocketClient(tenant, configurationRegistry,
                    webSocketUrl, callback, connectorId);
            client.setConnectionLostTimeout(CONNECTION_TIMEOUT_SECONDS);

            // Connect with timeout
            boolean connected = client.connectBlocking(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!connected) {
                log.error("{} - WebSocket connection timeout for connector {}", tenant, connectorId.getName());
                return null;
            }

            // Start executors if this is the first connection
            startExecutorsIfNeeded();

            log.info("{} - Successfully connected WebSocket for connector {}", tenant, connectorId.getName());
            return client;

        } catch (Exception e) {
            log.error("{} - Error connecting WebSocket for connector {}: {}",
                    tenant, connectorId.getName(), e.getMessage(), e);
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant,
                    ConnectorStatus.FAILED, e.getLocalizedMessage());
            return null;
        }
    }

    private void startExecutorsIfNeeded() {
        // Start reconnect executor if not already running
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "websocket-reconnect");
                t.setDaemon(true);
                return t;
            });
            reconnectExecutor.scheduleAtFixedRate(this::reconnect,
                    120, RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
            log.debug("Started reconnect executor");
        }

        // Start token refresh executor if not already running
        if (tokenRefreshExecutor == null || tokenRefreshExecutor.isShutdown()) {
            tokenRefreshExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "token-refresh");
                t.setDaemon(true);
                return t;
            });
            tokenRefreshExecutor.scheduleAtFixedRate(this::refreshTokens,
                    TOKEN_REFRESH_INTERVAL_HOURS, TOKEN_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS);
            log.debug("Started token refresh executor");
        }
    }

    public void refreshTokens() {
        log.debug("Starting token refresh cycle");

        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            Map<String, String> tenantTokens = deviceTokens.get(tenant);

            if (tenantTokens == null || tenantTokens.isEmpty()) {
                log.debug("{} - No device tokens to refresh", tenant);
                return;
            }

            int refreshedCount = 0;
            int failedCount = 0;

            for (Map.Entry<String, String> entry : tenantTokens.entrySet()) {
                String connectorId = entry.getKey();
                String token = entry.getValue();

                try {
                    String newToken = tokenApi.refresh(new Token(token)).getTokenString();
                    tenantTokens.put(connectorId, newToken);
                    refreshedCount++;
                    log.debug("{} - Refreshed token for connector {}", tenant, connectorId);
                } catch (IllegalArgumentException e) {
                    failedCount++;
                    log.warn("{} - Could not refresh token for connector {}: {}", tenant, connectorId, e.getMessage());
                } catch (Exception e) {
                    failedCount++;
                    log.error("{} - Error refreshing token for connector {}: {}", tenant, connectorId, e.getMessage());
                }
            }

            if (refreshedCount > 0 || failedCount > 0) {
                log.info("{} - Token refresh completed: {} successful, {} failed",
                        tenant, refreshedCount, failedCount);
            }
        });
    }

    public void reconnect() {
        log.debug("Starting reconnection cycle");

        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();

            try {
                reconnectDeviceClients(tenant);
                reconnectManagementClient(tenant);

                // Send connected status if all is well
                configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTED, null);

            } catch (Exception e) {
                log.error("{} - Error during reconnection: {}", tenant, e.getMessage(), e);
                configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant,
                        ConnectorStatus.FAILED, e.getLocalizedMessage());
            }
        });
    }

    private void reconnectDeviceClients(String tenant) {
        Map<String, CustomWebSocketClient> tenantClients = deviceClients.get(tenant);
        if (tenantClients == null) {
            return;
        }

        int reconnectedCount = 0;
        for (Map.Entry<String, CustomWebSocketClient> entry : tenantClients.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (client != null && shouldReconnectClient(tenant, client)) {
                try {
                    if (client.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                            (deviceWSStatusCodes.get(tenant) != null && deviceWSStatusCodes.get(tenant) == 401)) {
                        // Re-initialize if not connected or unauthorized
                        log.info("{} - Re-initializing device client for connector {}",
                                tenant, client.getConnectorId().getName());
                        initializeDeviceClient();
                        initializeManagementClient();
                        break; // Exit loop as we're re-initializing all clients
                    } else {
                        // Simple reconnect
                        client.reconnect();
                        reconnectedCount++;
                        log.info("{} - Reconnected device client for connector {}",
                                tenant, client.getConnectorId().getName());
                    }
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting device client for connector {}: {}",
                            tenant, client.getConnectorId().getName(), e.getMessage());
                }
            }
        }

        if (reconnectedCount > 0) {
            log.info("{} - Reconnected {} device clients", tenant, reconnectedCount);
        }
    }

    private void reconnectManagementClient(String tenant) {
        CustomWebSocketClient managementClient = managementClients.get(tenant);
        if (managementClient != null && !managementClient.isOpen()) {
            try {
                managementClient.reconnect();
                log.info("{} - Reconnected management client", tenant);
            } catch (Exception e) {
                log.warn("{} - Error reconnecting management client: {}", tenant, e.getMessage());
            }
        }
    }

    private boolean shouldReconnectClient(String tenant, CustomWebSocketClient client) {
        if (client == null) {
            return false;
        }

        ReadyState state = client.getReadyState();
        Integer statusCode = deviceWSStatusCodes.get(tenant);

        return !client.isOpen() &&
                (state == ReadyState.CLOSING ||
                        state == ReadyState.CLOSED ||
                        state == ReadyState.NOT_YET_CONNECTED ||
                        (statusCode != null && statusCode == 401));
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up C8YNotificationSubscriber resources");

        // Shutdown executors first
        stopReconnectExecutor();
        stopTokenRefreshExecutor();

        // Disconnect all tenants
        Set<String> tenants = new HashSet<>();
        tenants.addAll(deviceClients.keySet());
        tenants.addAll(managementClients.keySet());
        tenants.addAll(activePushConnections.keySet());

        for (String tenant : tenants) {
            try {
                disconnect(tenant);
            } catch (Exception e) {
                log.warn("Error disconnecting tenant {} during cleanup: {}", tenant, e.getMessage());
            }
        }

        // Clear all collections
        dispatcherOutboundMaps.clear();
        deviceClients.clear();
        managementClients.clear();
        managementCallbacks.clear();
        deviceWSStatusCodes.clear();
        activePushConnections.clear();
        deviceTokens.clear();
        managementTokens.clear();
        processingDevices.clear();

        // Clear thread local
        recursionDepth.remove();

        log.info("C8YNotificationSubscriber cleanup completed");
    }
}