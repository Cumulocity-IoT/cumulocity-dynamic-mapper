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
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.ConnectorStatus;
import dynamic.mapper.notification.CacheInventoryUpdateClient;
import dynamic.mapper.notification.ManagementSubscriptionClient;
import dynamic.mapper.notification.websocket.CustomWebSocketClient;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.outbound.CamelDispatcherOutbound;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String WEBSOCKET_PATH = "/notification2/consumer/?token=";
    private static final String DEVICE_SUBSCRIBER = "DynamicMapperDeviceSubscriber";
    private static final String DEVICE_SUBSCRIPTION = "DynamicMapperDeviceSubscription";
    private static final String MANAGEMENT_SUBSCRIBER = "DynamicMapperManagementSubscriber";
    private static final String MANAGEMENT_SUBSCRIPTION = "DynamicMapperManagementSubscription";
    private static final String CACHE_INVENTORY_SUBSCRIBER = "DynamicMapperCacheInventorySubscriber";
    private static final String CACHE_INVENTORY_SUBSCRIPTION = "DynamicMapperCacheInventorySubscription";
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;
    private static final int RECONNECT_INTERVAL_SECONDS = 60;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private MqttPushManager mqttPushManager;

    @Autowired
    private ConnectorRegistry connectorRegistry;

    @Autowired
    private SubscriptionQueryService queryService;

    private ConfigurationRegistry configurationRegistry;

    @Autowired
    public void setConfigurationRegistry(@Lazy ConfigurationRegistry configurationRegistry) {
        this.configurationRegistry = configurationRegistry;
    }

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    @Value("${APP.additionalSubscriptionIdTest:}")
    private String additionalSubscriptionIdTest;

    // Thread-safe collections
    private final Map<String, Map<String, CustomWebSocketClient>> deviceClients = new ConcurrentHashMap<>();
    private final Map<String, CustomWebSocketClient> managementClients = new ConcurrentHashMap<>();
    private final Map<String, CustomWebSocketClient> cacheInventoryClients = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> managementCallbacks = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> cacheInventoryCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Integer> deviceWSStatusCodes = new ConcurrentHashMap<>();

    // Scheduled executor for reconnection
    private volatile ScheduledExecutorService reconnectExecutor;

    // === Public API ===

    public void initializeDeviceClient(String tenant) {
        deviceClients.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        try {
            List<NotificationSubscriptionRepresentation> deviceSubs = queryService
                    .getNotificationSubscriptionForDevices(tenant, null, DEVICE_SUBSCRIPTION)
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Initializing {} device subscriptions", tenant, deviceSubs.size());

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

    public void initializeManagementClient(String tenant) {
        NotificationCallback managementCallback = managementCallbacks.computeIfAbsent(tenant,
                k -> new ManagementSubscriptionClient(configurationRegistry, tenant));

        NotificationCallback cacheInventoryCallback = cacheInventoryCallbacks.computeIfAbsent(tenant,
                k -> new CacheInventoryUpdateClient(configurationRegistry, tenant));

        try {
            List<NotificationSubscriptionRepresentation> managementSubs = queryService
                    .getNotificationSubscriptionForDeviceGroup(tenant, null, null)
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Initializing {} management subscriptions", tenant, managementSubs.size());

            // Cache monitored groups
            cacheMonitoredGroups(tenant, managementSubs, managementCallback);

            // Create connections
            createManagementConnection(tenant, managementCallback);
            createCacheInventoryConnection(tenant, cacheInventoryCallback);

        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing management client", tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing management client: {}", tenant, e.getMessage(), e);
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
                initializeDeviceClient(tenant);
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
        Integer status = deviceWSStatusCodes.get(tenant);
        if (status == null || status != 200) {
            try {
                initializeDeviceConnections(tenant);
            } catch (URISyntaxException e) {
                log.error("{} - Error initializing device connections: {}", tenant, e.getMessage());
            }
        }
    }

    public void handleConnectorRemoval(String tenant, String connectorIdentifier) {
        if (tenant == null || connectorIdentifier == null) {
            log.warn("Cannot handle connector removal: invalid parameters");
            return;
        }

        log.info("{} - Handling removal of connector {}", tenant, connectorIdentifier);

        // Close WebSocket connection for this connector
        Map<String, CustomWebSocketClient> tenantClients = deviceClients.get(tenant);
        if (tenantClients != null) {
            CustomWebSocketClient client = tenantClients.remove(connectorIdentifier);
            if (client != null) {
                try {
                    client.close();
                    log.info("{} - Closed WebSocket for connector {}", tenant, connectorIdentifier);
                } catch (Exception e) {
                    log.warn("{} - Error closing WebSocket for connector {}: {}",
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

    public Integer getDeviceConnectionStatus(String tenant) {
        return tenant != null ? deviceWSStatusCodes.get(tenant) : null;
    }

    public void startReconnectScheduler() {
        if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
            reconnectExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "websocket-reconnect");
                t.setDaemon(true);
                return t;
            });
            reconnectExecutor.scheduleAtFixedRate(this::reconnectAll,
                    120, RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
            log.debug("Started reconnect scheduler");
        }
    }

    // === Private Helper Methods ===

    private void initializeDeviceConnections(String tenant) throws URISyntaxException {
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
            String tokenSeed = DEVICE_SUBSCRIBER + connectorId + additionalSubscriptionIdTest;

            try {
                String token = tokenManager.createToken(DEVICE_SUBSCRIPTION, tokenSeed);
                tokenManager.storeDeviceToken(tenant, connectorId, token);

                ConnectorId connectorInfo = new ConnectorId(
                        dispatcher.getConnectorClient().getConnectorName(),
                        connectorId);

                CustomWebSocketClient client = connect(tenant, token, dispatcher, connectorInfo);
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
        String tokenSeed = MANAGEMENT_SUBSCRIBER + additionalSubscriptionIdTest;
        String token = tokenManager.createToken(MANAGEMENT_SUBSCRIPTION, tokenSeed);
        tokenManager.storeManagementToken(tenant, token);

        ConnectorId connectorId = new ConnectorId(
                ManagementSubscriptionClient.CONNECTOR_NAME,
                ManagementSubscriptionClient.CONNECTOR_ID);

        CustomWebSocketClient client = connect(tenant, token, callback, connectorId);
        if (client != null) {
            managementClients.put(tenant, client);
            log.info("{} - Created management connection", tenant);
        }
    }

    private void createCacheInventoryConnection(String tenant, NotificationCallback callback)
            throws URISyntaxException {
        String tokenSeed = CACHE_INVENTORY_SUBSCRIBER + additionalSubscriptionIdTest;
        String token = tokenManager.createToken(CACHE_INVENTORY_SUBSCRIPTION, tokenSeed);
        tokenManager.storeCacheInventoryToken(tenant, token);

        ConnectorId connectorId = new ConnectorId(
                CacheInventoryUpdateClient.CONNECTOR_NAME,
                CacheInventoryUpdateClient.CONNECTOR_ID);

        CustomWebSocketClient client = connect(tenant, token, callback, connectorId);
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

            String webSocketBaseUrl = baseUrl.replace("http", "ws");
            URI webSocketUrl = new URI(webSocketBaseUrl + WEBSOCKET_PATH + token);

            CustomWebSocketClient client = new CustomWebSocketClient(
                    tenant, configurationRegistry, webSocketUrl, callback, connectorId);
            client.setConnectionLostTimeout(CONNECTION_TIMEOUT_SECONDS);

            boolean connected = client.connectBlocking(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!connected) {
                log.error("{} - WebSocket connection timeout for connector {}", tenant, connectorId.getName());
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
                reconnectManagementClient(tenant);
                reconnectCacheInventoryClient(tenant);

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
        Map<String, CustomWebSocketClient> tenantClients = deviceClients.get(tenant);
        if (tenantClients == null) {
            return;
        }

        int reconnectedCount = 0;
        for (Map.Entry<String, CustomWebSocketClient> entry : tenantClients.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (shouldReconnectClient(tenant, client)) {
                try {
                    if (client.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                            (deviceWSStatusCodes.get(tenant) != null && deviceWSStatusCodes.get(tenant) == 401)) {
                        log.info("{} - Re-initializing device client", tenant);
                        initializeDeviceClient(tenant);
                        initializeManagementClient(tenant);
                        break;
                    } else {
                        client.reconnect();
                        reconnectedCount++;
                        log.info("{} - Reconnected device client", tenant);
                    }
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting device client: {}", tenant, e.getMessage());
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

    private void reconnectCacheInventoryClient(String tenant) {
        CustomWebSocketClient cacheClient = cacheInventoryClients.get(tenant);
        if (cacheClient != null && !cacheClient.isOpen()) {
            try {
                cacheClient.reconnect();
                log.info("{} - Reconnected cache inventory client", tenant);
            } catch (Exception e) {
                log.warn("{} - Error reconnecting cache inventory client: {}", tenant, e.getMessage());
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

    private boolean isValidDispatcher(CamelDispatcherOutbound dispatcher) {
        return dispatcher != null &&
                dispatcher.getConnectorClient() != null &&
                dispatcher.getConnectorClient().getConnectorConfiguration() != null &&
                dispatcher.getConnectorClient().getConnectorConfiguration().isEnabled();
    }

    private boolean isValidSubscription(NotificationSubscriptionRepresentation sub) {
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
        tenants.addAll(deviceClients.keySet());
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
        deviceClients.clear();
        managementClients.clear();
        cacheInventoryClients.clear();
        managementCallbacks.clear();
        cacheInventoryCallbacks.clear();
        deviceWSStatusCodes.clear();

        log.info("ConnectionManager cleanup completed");
    }
}
