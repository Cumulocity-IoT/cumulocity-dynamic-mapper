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
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import dynamic.mapper.connector.core.client.ConnectorType;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.ConnectorStatus;
import dynamic.mapper.notification.CacheInventoryUpdateClient;
import dynamic.mapper.notification.ManagementSubscriptionClient;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.notification.websocket.CustomWebSocketClient;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages WebSocket connections for device and management subscriptions.
 */
@Slf4j
@Service
public class NotificationConnectionManager {

    private final MicroserviceSubscriptionsService subscriptionsService;
    private final TokenManager tokenManager;
    private final MqttPushManager mqttPushManager;
    private final ConnectorRegistry connectorRegistry;
    private final SubscriptionQueryService queryService;
    private final ConfigurationRegistry configurationRegistry;

    public NotificationConnectionManager(MicroserviceSubscriptionsService subscriptionsService,
                                          TokenManager tokenManager,
                                          MqttPushManager mqttPushManager,
                                          ConnectorRegistry connectorRegistry,
                                          SubscriptionQueryService queryService,
                                          @Lazy ConfigurationRegistry configurationRegistry) {
        this.subscriptionsService = subscriptionsService;
        this.tokenManager = tokenManager;
        this.mqttPushManager = mqttPushManager;
        this.connectorRegistry = connectorRegistry;
        this.queryService = queryService;
        this.configurationRegistry = configurationRegistry;
    }

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    @Value("${APP.additionalSubscriptionIdTest:}")
    private String additionalSubscriptionIdTest;

    // Guards against concurrent management client initialization per tenant
    private final Map<String, Semaphore> managementInitLocks = new ConcurrentHashMap<>();

    // Thread-safe collections
    private final Map<String, Map<String, CustomWebSocketClient>> staticDeviceClients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CustomWebSocketClient>> dynamicDeviceClients = new ConcurrentHashMap<>();
    private final Map<String, CustomWebSocketClient> managementClients = new ConcurrentHashMap<>();
    private final Map<String, CustomWebSocketClient> cacheInventoryClients = new ConcurrentHashMap<>();
    // Explorer: one WebSocket per session (keyed by sessionId)
    private final Map<String, CustomWebSocketClient> explorerDeviceClients = new ConcurrentHashMap<>();
    // Explorer session metadata: sessionId → [tenant, subscriberName] for cleanup
    private final Map<String, String[]> explorerDeviceSessionMeta = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> managementCallbacks = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> cacheInventoryCallbacks = new ConcurrentHashMap<>();
    //FIXME As we have multiple WS connections the statusCode will only reflect the last connection attempt. We should consider a more granular status tracking if needed.
    private final Map<String, Integer> deviceWSStatusCodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> managementWSStatusCodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> cacheInventoryWSStatusCodes = new ConcurrentHashMap<>();

    // Scheduled executor for reconnection
    private volatile ScheduledExecutorService reconnectExecutor;

    /**
     * Create a subscriber token for EXPLORER_DEVICE_SUBSCRIPTION and open a WebSocket
     * so the explorer session receives Notification 2.0 events for the subscribed device.
     * Prefers a non-TEST dispatcher so that the WebSocket callback processes notifications
     * normally — TEST connector dispatchers skip live notifications (early-return guard in
     * CamelDispatcherOutbound.onNotification) and would silently drop all events.
     */
    public void initializeExplorerDeviceClient(String tenant, String sessionId) {
        Map<String, CamelDispatcherOutbound> dispatchers = connectorRegistry.getDispatchers(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.warn("{} - No outbound dispatchers available for explorer session {}", tenant, sessionId);
            return;
        }
        // Prefer a non-TEST dispatcher: CamelDispatcherOutbound.onNotification returns early
        // for TEST connectors without calling processNotification, so notifyOutboundExplorerListeners
        // would never fire and no messages would appear in the explorer.
        CamelDispatcherOutbound dispatcher = dispatchers.values().stream()
                .filter(d -> isValidDispatcher(d)
                        && d.getConnectorClient().getConnectorType() != ConnectorType.TEST)
                .findFirst()
                .orElseGet(() -> dispatchers.values().stream()
                        .filter(this::isValidDispatcher)
                        .findFirst()
                        .orElse(null));
        if (dispatcher == null) {
            log.warn("{} - No valid dispatcher for explorer session {}", tenant, sessionId);
            return;
        }
        String tokenSeed = Utils.EXPLORER_DEVICE_SUBSCRIBER + sessionId.replace("-", "") + additionalSubscriptionIdTest;
        try {
            String token = subscriptionsService.callForTenant(tenant,
                    () -> tokenManager.createToken(Utils.EXPLORER_DEVICE_SUBSCRIPTION, tokenSeed));
            ConnectorId connectorInfo = new ConnectorId(
                    dispatcher.getConnectorClient().getConnectorName(),
                    dispatcher.getConnectorClient().getConnectorIdentifier());
            CustomWebSocketClient client = connect(tenant, token, dispatcher, connectorInfo);
            if (client != null) {
                CustomWebSocketClient old = explorerDeviceClients.put(sessionId, client);
                if (old != null) { try { old.close(); } catch (Exception ignored) {} }
                explorerDeviceSessionMeta.put(sessionId, new String[]{tenant, tokenSeed});
                log.info("{} - Explorer device WebSocket opened for session {}", tenant, sessionId);
            }
        } catch (Exception e) {
            log.error("{} - Failed to open explorer device WebSocket for session {}: {}",
                    tenant, sessionId, e.getMessage(), e);
        }
    }

    /**
     * Close and remove the explorer device WebSocket for the given session,
     * and delete the subscriber from C8Y so it no longer appears in the
     * Notification 2.0 subscriber list.
     */
    public void closeExplorerDeviceClient(String sessionId) {
        CustomWebSocketClient client = explorerDeviceClients.remove(sessionId);
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            log.info("Explorer device WebSocket closed for session {}", sessionId);
        }
        String[] meta = explorerDeviceSessionMeta.remove(sessionId);
        if (meta != null) {
            String tenant = meta[0];
            String subscriberName = meta[1];
            try {
                subscriptionsService.callForTenant(tenant,
                        () -> { tokenManager.unsubscribeBySubscriberName(Utils.EXPLORER_DEVICE_SUBSCRIPTION, subscriberName); return null; });
                log.info("{} - Explorer device subscriber '{}' deleted from C8Y", tenant, subscriberName);
            } catch (Exception e) {
                log.warn("{} - Could not delete explorer device subscriber '{}': {}", tenant, subscriberName, e.getMessage());
            }
        }
    }

    public void initializeStaticDeviceClient(String tenant) {
        staticDeviceClients.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        try {
            List<NotificationSubscriptionRepresentation> staticDeviceSubs = queryService
                    .getNotificationSubscriptionForDevices(tenant, null, Utils.STATIC_DEVICE_SUBSCRIPTION)
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Initializing {} static device subscriptions", tenant, staticDeviceSubs.size());

            if (!staticDeviceSubs.isEmpty()) {
                initializeStaticDeviceConnections(tenant);
                activateDeviceConnections(tenant, staticDeviceSubs);
            } else {
                log.info("{} - No existing static device subscriptions found", tenant);
            }
        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing device client", tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing device client: {}", tenant, e.getMessage(), e);
        }
    }

    public void initializeDynamicDeviceClient(String tenant) {
        dynamicDeviceClients.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        try {
            List<NotificationSubscriptionRepresentation> dynamicDeviceSubs = queryService
                    .getNotificationSubscriptionForDevices(tenant, null, Utils.DYNAMIC_DEVICE_SUBSCRIPTION)
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Initializing {} dynamic device subscriptions", tenant, dynamicDeviceSubs.size());

            if (!dynamicDeviceSubs.isEmpty()) {
                initializeDynamicDeviceConnections(tenant);
                activateDeviceConnections(tenant, dynamicDeviceSubs);
            } else {
                log.info("{} - No existing dynamic device subscriptions found", tenant);
            }
        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing device client", tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing device client: {}", tenant, e.getMessage(), e);
        }
    }

    public void initializeManagementClient(String tenant) {
        CustomWebSocketClient existingManagement = managementClients.get(tenant);
        CustomWebSocketClient existingCache = cacheInventoryClients.get(tenant);

        // Only skip initialization if BOTH clients are connected
        if (existingManagement != null && existingManagement.isOpen() &&
            existingCache != null && existingCache.isOpen()) {
            log.debug("{} - Management and cache inventory clients already connected, skipping initialization", tenant);
            return;
        }

        Semaphore lock = managementInitLocks.computeIfAbsent(tenant, k -> new Semaphore(1));
        if (!lock.tryAcquire()) {
            log.info("{} - Management client initialization already in progress, skipping", tenant);
            return;
        }

        try {
            NotificationCallback managementCallback = managementCallbacks.computeIfAbsent(tenant,
                    k -> new ManagementSubscriptionClient(configurationRegistry, tenant));

            NotificationCallback cacheInventoryCallback = cacheInventoryCallbacks.computeIfAbsent(tenant,
                    k -> new CacheInventoryUpdateClient(configurationRegistry, tenant));

            List<NotificationSubscriptionRepresentation> managementSubs = queryService
                    .getNotificationSubscriptionForDeviceGroup(tenant, null, null)
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Initializing {} management subscriptions", tenant, managementSubs.size());

            // Cache monitored groups
            cacheMonitoredGroups(tenant, managementSubs, managementCallback);

            // Create connections - only if not already connected
            if (existingManagement == null || !existingManagement.isOpen()) {
                createManagementConnection(tenant, managementCallback);
            } else {
                log.debug("{} - Management client already connected, skipping creation", tenant);
            }

            if (existingCache == null || !existingCache.isOpen()) {
                createCacheInventoryConnection(tenant, cacheInventoryCallback);
            } else {
                log.debug("{} - Cache inventory client already connected, skipping creation", tenant);
            }

        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing management client", tenant);
            // M3: clean up pre-registered callbacks so they don't leak on failure
            managementCallbacks.remove(tenant);
            cacheInventoryCallbacks.remove(tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing management client: {}", tenant, e.getMessage(), e);
            // M3: clean up pre-registered callbacks so they don't leak on failure
            managementCallbacks.remove(tenant);
            cacheInventoryCallbacks.remove(tenant);
        } finally {
            lock.release();
        }
    }

    /**
     * Reconnects existing static device WebSocket clients so they pick up pending
     * notifications from a newly created device subscription without waiting for the
     * 60-second {@link #reconnectAll()} cycle.  Calls {@code client.reconnect()} on
     * each open client — the same lightweight path used by the scheduler — so C8Y
     * delivers any queued messages (including the new subscription's events) as
     * initial messages after the reconnect completes.
     */
    public void reconnectStaticDeviceClientsForNewSubscription(String tenant) {
        if (tenant == null) {
            return;
        }
        Map<String, CustomWebSocketClient> clients = staticDeviceClients.get(tenant);
        if (clients == null || clients.isEmpty()) {
            // No existing clients yet — initialize from scratch
            try {
                initializeStaticDeviceConnections(tenant);
            } catch (URISyntaxException e) {
                log.error("{} - Error initializing static device connections after subscription: {}", tenant,
                        e.getMessage(), e);
            }
            return;
        }
        for (Map.Entry<String, CustomWebSocketClient> entry : clients.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (client != null && client.isOpen()) {
                try {
                    client.reconnect();
                    log.info("{} - Triggered static device WebSocket reconnect for connector {} after new subscription",
                            tenant, entry.getKey());
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting static device client after subscription: {}", tenant,
                            e.getMessage());
                }
            }
        }
    }

    /**
     * Reconnects existing dynamic device WebSocket clients so they pick up pending
     * notifications from a newly created device subscription (e.g. a group or type
     * resolving to this device) without waiting for the 60-second {@link #reconnectAll()}
     * cycle. Mirrors {@link #reconnectStaticDeviceClientsForNewSubscription(String)} —
     * see that method's Javadoc for why an immediate reconnect is needed: without it, a
     * dynamic client that is already open from an earlier subscription never learns about
     * devices added afterward, since {@link #initializeDynamicDeviceConnections(String)}
     * skips any client that is already open.
     */
    public void reconnectDynamicDeviceClientsForNewSubscription(String tenant) {
        if (tenant == null) {
            return;
        }
        Map<String, CustomWebSocketClient> clients = dynamicDeviceClients.get(tenant);
        if (clients == null || clients.isEmpty()) {
            // No existing clients yet — initialize from scratch
            try {
                initializeDynamicDeviceConnections(tenant);
            } catch (URISyntaxException e) {
                log.error("{} - Error initializing dynamic device connections after subscription: {}", tenant,
                        e.getMessage(), e);
            }
            return;
        }
        for (Map.Entry<String, CustomWebSocketClient> entry : clients.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (client != null && client.isOpen()) {
                try {
                    client.reconnect();
                    log.info("{} - Triggered dynamic device WebSocket reconnect for connector {} after new subscription",
                            tenant, entry.getKey());
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting dynamic device client after subscription: {}", tenant,
                            e.getMessage());
                }
            }
        }
    }

    public void reconnect(String tenant) {
        if (tenant == null) {
            log.warn("Cannot reconnect: tenant is null");
            return;
        }

        log.info("{} - Reconnecting notification subscriber", tenant);
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                disconnect(tenant);
                initializeStaticDeviceClient(tenant);
                initializeDynamicDeviceClient(tenant);
                initializeManagementClient(tenant);
                log.info("{} - Successfully reconnected", tenant);
            } catch (Exception e) {
                log.error("{} - Error during reconnection: {}", tenant, e.getMessage(), e);
            }
        });
    }

    public void disconnect(String tenant) {
        if (tenant == null) {
            log.warn("Cannot disconnect: tenant is null");
            return;
        }

        log.info("{} - Disconnecting notification subscriber", tenant);

        // Close device clients
        disconnectDeviceClients(tenant);

        // Close management client
        disconnectManagementClient(tenant);

        // Close cache inventory client
        disconnectCacheInventoryClient(tenant);

        // Close MQTT connections
        mqttPushManager.disconnectAll(tenant);

        // Clear status
        deviceWSStatusCodes.remove(tenant);

        // Send notification
        try {
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(
                    tenant, ConnectorStatus.DISCONNECTED, null);
        } catch (Exception e) {
            log.warn("{} - Error sending disconnect notification: {}", tenant, e.getMessage());
        }

        log.info("{} - Successfully disconnected", tenant);
    }

    public void initializeConnectionsIfNeeded(String tenant) {
        try {
            // Check if static device connections need initialization
            boolean needStaticInit = false;
            Map<String, CustomWebSocketClient> staticClientsForTenant = staticDeviceClients.get(tenant);
            if (staticClientsForTenant == null || staticClientsForTenant.isEmpty()) {
                needStaticInit = true;
            } else {
                // Check if any static client is connected
                boolean hasConnectedStatic = staticClientsForTenant.values().stream()
                        .anyMatch(client -> client != null && client.isOpen());
                if (!hasConnectedStatic) {
                    needStaticInit = true;
                }
            }

            // Check if dynamic device connections need initialization
            boolean needDynamicInit = false;
            Map<String, CustomWebSocketClient> dynamicClientsForTenant = dynamicDeviceClients.get(tenant);
            if (dynamicClientsForTenant == null || dynamicClientsForTenant.isEmpty()) {
                needDynamicInit = true;
            } else {
                // Check if any dynamic client is connected
                boolean hasConnectedDynamic = dynamicClientsForTenant.values().stream()
                        .anyMatch(client -> client != null && client.isOpen());
                if (!hasConnectedDynamic) {
                    needDynamicInit = true;
                }
            }

            // Initialize only what's needed
            if (needStaticInit) {
                log.info("{} - Initializing static device connections", tenant);
                initializeStaticDeviceConnections(tenant);
            }

            if (needDynamicInit) {
                log.info("{} - Initializing dynamic device connections", tenant);
                initializeDynamicDeviceConnections(tenant);
            }

        } catch (URISyntaxException e) {
            log.error("{} - Error initializing device connections: {}", tenant, e.getMessage(), e);
        }
    }

    public void handleConnectorRemoval(String tenant, String connectorIdentifier) {
        if (tenant == null || connectorIdentifier == null) {
            log.warn("Cannot handle connector removal: invalid parameters");
            return;
        }

        log.info("{} - Handling removal of connector {}", tenant, connectorIdentifier);

        // Close WebSocket connection for this connector
        Map<String, CustomWebSocketClient> staticDeviceClientsForTenant = staticDeviceClients.get(tenant);
        if (staticDeviceClientsForTenant != null) {
            CustomWebSocketClient client = staticDeviceClientsForTenant.remove(connectorIdentifier);
            if (client != null) {
                try {
                    client.close();
                    log.info("{} - Closed WebSocket associated static device subscriptions for connector {}", tenant,
                            connectorIdentifier);
                } catch (Exception e) {
                    log.warn("{} - Error closing WebSocket associated static device subscriptions for connector {}: {}",
                            tenant, connectorIdentifier, e.getMessage());
                }
            }
        }

        Map<String, CustomWebSocketClient> dynamicDeviceClientsForTenant = dynamicDeviceClients.get(tenant);
        if (dynamicDeviceClientsForTenant != null) {
            CustomWebSocketClient client = dynamicDeviceClientsForTenant.remove(connectorIdentifier);
            if (client != null) {
                try {
                    client.close();
                    log.info("{} - Closed WebSocket associated dynamic device subscriptions for connector {}", tenant,
                            connectorIdentifier);
                } catch (Exception e) {
                    log.warn(
                            "{} - Error closing WebSocket associated dynamic device subscriptions for connector {}: {}",
                            tenant, connectorIdentifier, e.getMessage());
                }
            }
        }

        // Disconnect if no more dispatchers
        Map<String, CamelDispatcherOutbound> dispatchers = connectorRegistry.getDispatchers(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.info("{} - No more connectors, disconnecting", tenant);
            disconnect(tenant);
        }
    }

    public void addGroupToCache(String tenant, ManagedObjectRepresentation mor) {
        NotificationCallback callback = managementCallbacks.get(tenant);
        if (callback instanceof ManagementSubscriptionClient) {
            ((ManagementSubscriptionClient) callback).addGroupToCache(mor);
        }
    }

    public void removeGroupFromCache(String tenant, ManagedObjectRepresentation mor) {
        NotificationCallback callback = managementCallbacks.get(tenant);
        if (callback instanceof ManagementSubscriptionClient) {
            ((ManagementSubscriptionClient) callback).removeGroupFromCache(mor);
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

    public void setManagementConnectionStatus(String tenant, Integer status) {
        if (tenant != null) {
            if (status != null) {
                managementWSStatusCodes.put(tenant, status);
            } else {
                managementWSStatusCodes.remove(tenant);
            }
        }
    }

    public void setCacheInventoryConnectionStatus(String tenant, Integer status) {
        if (tenant != null) {
            if (status != null) {
                cacheInventoryWSStatusCodes.put(tenant, status);
            } else {
                cacheInventoryWSStatusCodes.remove(tenant);
            }
        }
    }

    public Integer getDeviceConnectionStatus(String tenant) {
        return tenant != null ? deviceWSStatusCodes.get(tenant) : null;
    }

    public Integer getManagementConnectionStatus(String tenant) {
        return tenant != null ? managementWSStatusCodes.get(tenant) : null;
    }

    public Integer getCacheInventoryConnectionStatus(String tenant) {
        return tenant != null ? cacheInventoryWSStatusCodes.get(tenant) : null;
    }

    // H5: synchronized so concurrent callers can't each pass the null-check and create duplicate schedulers
    public synchronized void startReconnectScheduler() {
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "websocket-reconnect");
                t.setDaemon(true);
                return t;
            });
            reconnectExecutor.scheduleAtFixedRate(this::reconnectAll,
                    120, Utils.RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
            log.debug("Started reconnect scheduler");
        }
    }

    // === Private Helper Methods ===

    private void initializeStaticDeviceConnections(String tenant) throws URISyntaxException {
        Map<String, CamelDispatcherOutbound> dispatchers = connectorRegistry.getDispatchers(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.warn("{} - No outbound dispatchers registered", tenant);
            return;
        }

        for (CamelDispatcherOutbound dispatcher : dispatchers.values()) {
            if (!isValidDispatcher(dispatcher)) {
                continue;
            }

            String connectorId = dispatcher.getConnectorClient().getConnectorIdentifier();

            Map<String, CustomWebSocketClient> staticClientsForTenant = staticDeviceClients.get(tenant);
            if (staticClientsForTenant != null) {
                CustomWebSocketClient existingClient = staticClientsForTenant.get(connectorId);
                if (existingClient != null && existingClient.isOpen()) {
                    log.debug("{} - Static device client already connected for connector {}, skipping", tenant, connectorId);
                    continue;
                }
            }

            String tokenSeedForStatic = Utils.STATIC_DEVICE_SUBSCRIBER + connectorId + additionalSubscriptionIdTest;

            try {
                String storedToken = tokenManager.getDeviceToken(tenant, connectorId + "_static");
                boolean usingStoredToken = storedToken != null;
                String token = usingStoredToken
                        ? storedToken
                        : tokenManager.createToken(Utils.STATIC_DEVICE_SUBSCRIPTION, tokenSeedForStatic);
                if (!usingStoredToken) {
                    tokenManager.storeDeviceToken(tenant, connectorId + "_static", token);
                } else {
                    log.debug("{} - Reusing refreshed static device token for connector {}", tenant, connectorId);
                }

                ConnectorId connectorInfo = new ConnectorId(
                        dispatcher.getConnectorClient().getConnectorName(),
                        connectorId);

                CustomWebSocketClient client = connect(tenant, token, dispatcher, connectorInfo);
                if (client == null && usingStoredToken) {
                    log.warn("{} - Stored static device token failed to connect for connector {}, retrying with fresh token",
                            tenant, connectorId);
                    tokenManager.storeDeviceToken(tenant, connectorId + "_static", null);
                    token = tokenManager.createToken(Utils.STATIC_DEVICE_SUBSCRIPTION, tokenSeedForStatic);
                    tokenManager.storeDeviceToken(tenant, connectorId + "_static", token);
                    client = connect(tenant, token, dispatcher, connectorInfo);
                }
                if (client != null) {
                    staticDeviceClients.get(tenant).put(connectorId, client);
                    log.info("{} - Initialized device connection for connector: {}", tenant, connectorId);
                }
            } catch (Exception e) {
                log.error("{} - Failed to initialize static device connection for connector {}: {}",
                        tenant, connectorId, e.getMessage(), e);
            }
        }
    }

        private void initializeDynamicDeviceConnections(String tenant) throws URISyntaxException {
        Map<String, CamelDispatcherOutbound> dispatchers = connectorRegistry.getDispatchers(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.warn("{} - No outbound dispatchers registered", tenant);
            return;
        }

        for (CamelDispatcherOutbound dispatcher : dispatchers.values()) {
            if (!isValidDispatcher(dispatcher)) {
                continue;
            }

            String connectorId = dispatcher.getConnectorClient().getConnectorIdentifier();

            Map<String, CustomWebSocketClient> dynamicClientsForTenant = dynamicDeviceClients.get(tenant);
            if (dynamicClientsForTenant != null) {
                CustomWebSocketClient existingClient = dynamicClientsForTenant.get(connectorId);
                if (existingClient != null && existingClient.isOpen()) {
                    log.debug("{} - Dynamic device client already connected for connector {}, skipping", tenant, connectorId);
                    continue;
                }
            }

            String tokenSeedForDynamic = Utils.DYNAMIC_DEVICE_SUBSCRIBER + connectorId + additionalSubscriptionIdTest;

            try {
                String storedToken = tokenManager.getDeviceToken(tenant, connectorId + "_dynamic");
                boolean usingStoredToken = storedToken != null;
                String token = usingStoredToken
                        ? storedToken
                        : tokenManager.createToken(Utils.DYNAMIC_DEVICE_SUBSCRIPTION, tokenSeedForDynamic);
                if (!usingStoredToken) {
                    tokenManager.storeDeviceToken(tenant, connectorId + "_dynamic", token);
                } else {
                    log.debug("{} - Reusing refreshed dynamic device token for connector {}", tenant, connectorId);
                }

                ConnectorId connectorInfo = new ConnectorId(
                        dispatcher.getConnectorClient().getConnectorName(),
                        connectorId);

                CustomWebSocketClient client = connect(tenant, token, dispatcher, connectorInfo);
                if (client == null && usingStoredToken) {
                    log.warn("{} - Stored dynamic device token failed to connect for connector {}, retrying with fresh token",
                            tenant, connectorId);
                    tokenManager.storeDeviceToken(tenant, connectorId + "_dynamic", null);
                    token = tokenManager.createToken(Utils.DYNAMIC_DEVICE_SUBSCRIPTION, tokenSeedForDynamic);
                    tokenManager.storeDeviceToken(tenant, connectorId + "_dynamic", token);
                    client = connect(tenant, token, dispatcher, connectorInfo);
                }
                if (client != null) {
                    dynamicDeviceClients.get(tenant).put(connectorId, client);
                    log.info("{} - Initialized dynamic connection for connector: {}", tenant, connectorId);
                }
            } catch (Exception e) {
                log.error("{} - Failed to initialize dynamic device connection for connector {}: {}",
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
                            .resolveGlobalId2ExternalId(tenant, sub.getSource().getId(), null, false);

                    if (extId != null) {
                        mqttPushManager.activatePushConnectivity(tenant, extId.getExternalId());
                        activatedCount++;
                    }
                }
            } catch (Exception e) {
                log.warn("{} - Error activating device connection: {}", tenant, e.getMessage());
            }
        }
        log.info("{} - Activated {} device push connections", tenant, activatedCount);
    }

    private void cacheMonitoredGroups(String tenant, List<NotificationSubscriptionRepresentation> subs,
            NotificationCallback callback) {
        int cachedCount = 0;
        for (NotificationSubscriptionRepresentation sub : subs) {
            try {
                if (isValidSubscription(sub)) {
                    ManagedObjectRepresentation groupMO = configurationRegistry.getC8yAgent()
                            .getManagedObjectForId(tenant, sub.getSource().getId().getValue(), false);
                    if (groupMO != null && callback instanceof ManagementSubscriptionClient) {
                        ((ManagementSubscriptionClient) callback).addGroupToCache(groupMO);
                        cachedCount++;
                    }
                }
            } catch (Exception e) {
                log.warn("{} - Error caching group: {}", tenant, e.getMessage());
            }
        }
        log.info("{} - Cached {} monitored groups", tenant, cachedCount);
    }

    private void createManagementConnection(String tenant, NotificationCallback callback)
            throws URISyntaxException {
        // Prefer the stored (periodically refreshed) token so that a background refresh
        // is picked up on the next reconnect without an extra round-trip to C8Y.
        // Fall back to creating a brand-new token when none is stored yet.
        String tokenSeed = Utils.MANAGEMENT_SUBSCRIBER + additionalSubscriptionIdTest;
        String token = tokenManager.getManagementToken(tenant);
        boolean usingStoredToken = token != null;
        if (!usingStoredToken) {
            token = tokenManager.createToken(Utils.MANAGEMENT_SUBSCRIPTION, tokenSeed);
            tokenManager.storeManagementToken(tenant, token);
        } else {
            log.debug("{} - Reusing refreshed management token for reconnect", tenant);
        }

        ConnectorId connectorId = new ConnectorId(
                ManagementSubscriptionClient.CONNECTOR_NAME,
                ManagementSubscriptionClient.CONNECTOR_ID);

        CustomWebSocketClient client = connect(tenant, token, callback, connectorId);
        if (client == null && usingStoredToken) {
            // Stored token was invalid (e.g. expired before we could use it); create a fresh one.
            log.warn("{} - Stored management token failed to connect, retrying with a fresh token", tenant);
            tokenManager.storeManagementToken(tenant, null);
            token = tokenManager.createToken(Utils.MANAGEMENT_SUBSCRIPTION, tokenSeed);
            tokenManager.storeManagementToken(tenant, token);
            client = connect(tenant, token, callback, connectorId);
        }
        if (client != null) {
            managementClients.put(tenant, client);
            log.info("{} - Created management connection", tenant);
        }
    }

    private void createCacheInventoryConnection(String tenant, NotificationCallback callback)
            throws URISyntaxException {
        // Same pattern: prefer stored refreshed token, fall back to creating a new one.
        String tokenSeed = Utils.CACHE_INVENTORY_SUBSCRIBER + additionalSubscriptionIdTest;
        String token = tokenManager.getCacheInventoryToken(tenant);
        boolean usingStoredToken = token != null;
        if (!usingStoredToken) {
            token = tokenManager.createToken(Utils.CACHE_INVENTORY_SUBSCRIPTION, tokenSeed);
            tokenManager.storeCacheInventoryToken(tenant, token);
        } else {
            log.debug("{} - Reusing refreshed cache inventory token for reconnect", tenant);
        }

        ConnectorId connectorId = new ConnectorId(
                CacheInventoryUpdateClient.CONNECTOR_NAME,
                CacheInventoryUpdateClient.CONNECTOR_ID);

        CustomWebSocketClient client = connect(tenant, token, callback, connectorId);
        if (client == null && usingStoredToken) {
            // Stored token was invalid (e.g. expired before we could use it); create a fresh one.
            log.warn("{} - Stored cache inventory token failed to connect, retrying with a fresh token", tenant);
            tokenManager.storeCacheInventoryToken(tenant, null);
            token = tokenManager.createToken(Utils.CACHE_INVENTORY_SUBSCRIPTION, tokenSeed);
            tokenManager.storeCacheInventoryToken(tenant, token);
            client = connect(tenant, token, callback, connectorId);
        }
        if (client != null) {
            cacheInventoryClients.put(tenant, client);
            log.info("{} - Created cache inventory connection", tenant);
        }
    }

    private CustomWebSocketClient connect(String tenant, String token, NotificationCallback callback,
            ConnectorId connectorId) throws URISyntaxException {

        if (token == null || callback == null || connectorId == null) {
            log.warn("Cannot connect: invalid parameters");
            return null;
        }

        try {
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(
                    tenant, ConnectorStatus.CONNECTING, null);

            // L3: replace("http","ws") corrupts any hostname that contains "http" as a substring;
            // only the scheme prefix must be replaced
            String webSocketBaseUrl = baseUrl.startsWith("https://")
                    ? "wss://" + baseUrl.substring("https://".length())
                    : "ws://" + baseUrl.substring("http://".length());
            URI webSocketUrl = new URI(webSocketBaseUrl + Utils.WEBSOCKET_PATH + token);

            CustomWebSocketClient client = new CustomWebSocketClient(
                    tenant, configurationRegistry, webSocketUrl, callback, connectorId);
            client.setConnectionLostTimeout(Utils.CONNECTION_TIMEOUT_SECONDS);

            boolean connected = client.connectBlocking(Utils.CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!connected) {
                // After an ungraceful microservice restart, Cumulocity still considers the
                // previous consumer active and rejects the new connection with HTTP 409.
                // Wait and retry — do NOT unsubscribe, as that would drop any backlogged messages.
                if (client.isConflict()) {
                    for (int attempt = 1; attempt <= Utils.CONFLICT_RETRY_COUNT; attempt++) {
                        log.warn("{} - WebSocket 409 Conflict for connector {} — waiting {}s before retry {}/{}",
                                tenant, connectorId.getName(), Utils.CONFLICT_RETRY_DELAY_SECONDS,
                                attempt, Utils.CONFLICT_RETRY_COUNT);
                        try {
                            TimeUnit.SECONDS.sleep(Utils.CONFLICT_RETRY_DELAY_SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        CustomWebSocketClient retryClient = new CustomWebSocketClient(
                                tenant, configurationRegistry, webSocketUrl, callback, connectorId);
                        retryClient.setConnectionLostTimeout(Utils.CONNECTION_TIMEOUT_SECONDS);
                        boolean retryConnected = retryClient.connectBlocking(Utils.CONNECTION_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS);
                        if (retryConnected) {
                            log.info("{} - Successfully connected WebSocket for connector {} on retry {}/{}",
                                    tenant, connectorId.getName(), attempt, Utils.CONFLICT_RETRY_COUNT);
                            startReconnectScheduler();
                            return retryClient;
                        }
                        if (!retryClient.isConflict()) {
                            // L4: tenant must be first arg to match the "{}" prefix slot
                            log.error("{} - WebSocket retry {}/{} failed for connector {} (not a conflict)",
                                    tenant, attempt, Utils.CONFLICT_RETRY_COUNT, connectorId.getName());
                            return null;
                        }
                    }
                    log.error("{} - WebSocket still getting 409 for connector {} after {} retries",
                            tenant, connectorId.getName(), Utils.CONFLICT_RETRY_COUNT);
                } else {
                    log.error("{} - WebSocket connection failed for connector {}", tenant, connectorId.getName());
                }
                return null;
            }

            startReconnectScheduler();

            log.info("{} - Successfully connected WebSocket for connector {}", tenant, connectorId.getName());
            return client;

        } catch (Exception e) {
            log.error("{} - Error connecting WebSocket for connector {}: {}",
                    tenant, connectorId.getName(), e.getMessage(), e);
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(
                    tenant, ConnectorStatus.FAILED, e.getLocalizedMessage());
            return null;
        }
    }

    private void disconnectDeviceClients(String tenant) {
        Map<String, CustomWebSocketClient> staticDeviceClientsForTenant = staticDeviceClients.get(tenant);
        if (staticDeviceClientsForTenant != null) {
            int disconnectedCount = 0;
            for (CustomWebSocketClient client : staticDeviceClientsForTenant.values()) {
                if (client != null && client.isOpen()) {
                    try {
                        client.close();
                        disconnectedCount++;
                    } catch (Exception e) {
                        log.warn("{} - Error closing static device client: {}", tenant, e.getMessage());
                    }
                }
            }
            staticDeviceClientsForTenant.clear();
            log.info("{} - Disconnected {} device static WebSocket clients", tenant, disconnectedCount);
        }

        Map<String, CustomWebSocketClient> dynamicDeviceClientsForTenant = dynamicDeviceClients.get(tenant);
        if (dynamicDeviceClientsForTenant != null) {
            int disconnectedCount = 0;
            for (CustomWebSocketClient client : dynamicDeviceClientsForTenant.values()) {
                if (client != null && client.isOpen()) {
                    try {
                        client.close();
                        disconnectedCount++;
                    } catch (Exception e) {
                        log.warn("{} - Error closing dynamic device client: {}", tenant, e.getMessage());
                    }
                }
            }
            dynamicDeviceClientsForTenant.clear();
            log.info("{} - Disconnected {} device dynamic WebSocket clients", tenant, disconnectedCount);
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

    private void disconnectCacheInventoryClient(String tenant) {
        CustomWebSocketClient cacheClient = cacheInventoryClients.remove(tenant);
        if (cacheClient != null) {
            try {
                cacheClient.close();
                log.info("{} - Disconnected cache inventory WebSocket client", tenant);
            } catch (Exception e) {
                log.warn("{} - Error closing cache inventory client: {}", tenant, e.getMessage());
            }
        }
    }

    private void reconnectAll() {
        log.debug("Starting reconnection cycle");

        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();

            try {
                reconnectDeviceClients(tenant);
                reconnectManagementClients(tenant);

                configurationRegistry.getC8yAgent().sendNotificationLifecycle(
                        tenant, ConnectorStatus.CONNECTED, null);

            } catch (Exception e) {
                log.error("{} - Error during reconnection: {}", tenant, e.getMessage(), e);
                configurationRegistry.getC8yAgent().sendNotificationLifecycle(
                        tenant, ConnectorStatus.FAILED, e.getLocalizedMessage());
            }
        });
    }

    private void reconnectDeviceClients(String tenant) {
        Map<String, CustomWebSocketClient> staticDeviceClientsForTenant = staticDeviceClients.get(tenant);
        if (staticDeviceClientsForTenant == null) {
            return;
        }

        int reconnectedCount = 0;
        for (Map.Entry<String, CustomWebSocketClient> entry : staticDeviceClientsForTenant.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (shouldReconnectClient(tenant, client)) {
                try {
                    if (client.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                            (deviceWSStatusCodes.get(tenant) != null && deviceWSStatusCodes.get(tenant) == 401)) {
                        log.info("{} - Re-initializing static device client", tenant);
                        initializeStaticDeviceClient(tenant);
                        // M6: don't break — continue so remaining CLOSED clients are also reconnected
                    } else {
                        client.reconnect();
                        reconnectedCount++;
                        log.info("{} - Reconnected static device client", tenant);
                    }
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting static device client: {}", tenant, e.getMessage());
                }
            }
        }

        if (reconnectedCount > 0) {
            log.info("{} - Reconnected {} static device clients", tenant, reconnectedCount);
        }

        Map<String, CustomWebSocketClient> dynamicDeviceClientsForTenant = dynamicDeviceClients.get(tenant);
        if (dynamicDeviceClientsForTenant == null) {
            return;
        }

        reconnectedCount = 0;
        for (Map.Entry<String, CustomWebSocketClient> entry : dynamicDeviceClientsForTenant.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (shouldReconnectClient(tenant, client)) {
                try {
                    if (client.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                            (deviceWSStatusCodes.get(tenant) != null && deviceWSStatusCodes.get(tenant) == 401)) {
                        log.info("{} - Re-initializing dynamic device client", tenant);
                        initializeDynamicDeviceClient(tenant);
                        // L5: don't break — initializeDynamic covers all connectors in one call,
                        // but continue so any remaining stale-state clients are also inspected
                    } else {
                        client.reconnect();
                        reconnectedCount++;
                        log.info("{} - Reconnected dynamic device client", tenant);
                    }
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting dynamic device client: {}", tenant, e.getMessage());
                }
            }
        }
        if (reconnectedCount > 0) {
            log.info("{} - Reconnected {} dynamic device clients", tenant, reconnectedCount);
        }

    }

    private void reconnectManagementClients(String tenant) {
        CustomWebSocketClient managementClient = managementClients.get(tenant);
        int reconnectedCount = 0;
        boolean reinitializing = false;
        if (managementClient != null && !managementClient.isOpen()) {
            try {
                if (managementClient.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                        (managementWSStatusCodes.get(tenant) != null && managementWSStatusCodes.get(tenant) == 401)) {
                    log.info("{} - Re-initializing management WS client", tenant);
                    initializeManagementClient(tenant);
                    reinitializing = true;
                } else {
                    managementClient.reconnect();
                    reconnectedCount++;
                    log.info("{} - Reconnected management WS client", tenant);
                }
            } catch (Exception e) {
                log.warn("{} - Error reconnecting management WS client: {}", tenant, e.getMessage());
            }
        }
        CustomWebSocketClient cacheClient = cacheInventoryClients.get(tenant);
        if (cacheClient != null && !cacheClient.isOpen()) {
            try {
                if (cacheClient.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                        (cacheInventoryWSStatusCodes.get(tenant) != null && cacheInventoryWSStatusCodes.get(tenant) == 401)) {
                    log.info("{} - Re-initializing cache inventory WS", tenant);
                    if(!reinitializing)
                        initializeManagementClient(tenant);
                } else {
                    cacheClient.reconnect();
                    reconnectedCount++;
                    log.info("{} - Reconnected cache inventory WS client", tenant);
                }
            } catch (Exception e) {
                log.warn("{} - Error reconnecting cache inventory WS client: {}", tenant, e.getMessage());
            }
        }
        if (reconnectedCount > 0) {
            log.info("{} - Reconnected {} management WS client", tenant, reconnectedCount);
        }
    }

    private Boolean shouldReconnectClient(String tenant, CustomWebSocketClient client) {
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

    private Boolean isValidDispatcher(CamelDispatcherOutbound dispatcher) {
        return dispatcher != null &&
                dispatcher.getConnectorClient() != null &&
                dispatcher.getConnectorClient().getConnectorConfiguration() != null &&
                dispatcher.getConnectorClient().getConnectorConfiguration().getEnabled();
    }

    private Boolean isValidSubscription(NotificationSubscriptionRepresentation sub) {
        return sub != null &&
                sub.getSource() != null &&
                sub.getSource().getId() != null;
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up ConnectionManager");

        // Stop reconnect executor
        if (reconnectExecutor != null && !reconnectExecutor.isShutdown()) {
            try {
                reconnectExecutor.shutdown();
                if (!reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                reconnectExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Disconnect all tenants
        Set<String> tenants = new HashSet<>();
        tenants.addAll(staticDeviceClients.keySet());
        tenants.addAll(dynamicDeviceClients.keySet());
        tenants.addAll(managementClients.keySet());
        tenants.addAll(cacheInventoryClients.keySet());

        for (String tenant : tenants) {
            try {
                disconnect(tenant);
            } catch (Exception e) {
                log.warn("Error disconnecting tenant {} during cleanup: {}", tenant, e.getMessage());
            }
        }

        // Clear collections
        staticDeviceClients.clear();
        dynamicDeviceClients.clear();
        managementClients.clear();
        cacheInventoryClients.clear();
        managementCallbacks.clear();
        cacheInventoryCallbacks.clear();
        deviceWSStatusCodes.clear();
        managementWSStatusCodes.clear();
        cacheInventoryWSStatusCodes.clear();

        log.info("ConnectionManager cleanup completed");
    }
}
