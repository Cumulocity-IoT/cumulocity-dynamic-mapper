/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;

import dynamic.mapper.configuration.ConnectorId;
import dynamic.mapper.connector.core.client.ConnectorType;
import dynamic.mapper.core.ConnectorStatusEventService;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.status.ConnectorStatus;
import dynamic.mapper.notification.CacheInventoryUpdateClient;
import dynamic.mapper.notification.ManagementSubscriptionClient;
import dynamic.mapper.notification.NotificationClientOperations;
import dynamic.mapper.notification.OutboundDispatcherRegistry;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.notification.websocket.CustomWebSocketClient;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.outbound.OutboundMessageDispatcher;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages WebSocket connections for device and management subscriptions.
 */
@Slf4j
@Service
public class NotificationConnectionManager {

    private final MicroserviceSubscriptionsService subscriptionsService;
    private final TokenManager tokenManager;
    private final MqttPushManager mqttPushManager;
    private final OutboundDispatcherRegistry dispatcherRegistry;
    private final SubscriptionQueryService queryService;
    private final ServiceRegistry serviceRegistry;
    // set by NotificationSubscriber after construction: it owns this manager, so constructor injection would be a cycle
    private volatile NotificationClientOperations clientOperations;
    private final ConnectorStatusEventService connectorStatusEventService;

    public NotificationConnectionManager(MicroserviceSubscriptionsService subscriptionsService,
                                          TokenManager tokenManager,
                                          MqttPushManager mqttPushManager,
                                          OutboundDispatcherRegistry dispatcherRegistry,
                                          SubscriptionQueryService queryService,
                                          ServiceRegistry serviceRegistry,
                                          ConnectorStatusEventService connectorStatusEventService) {
        this.connectorStatusEventService = connectorStatusEventService;
        this.subscriptionsService = subscriptionsService;
        this.tokenManager = tokenManager;
        this.mqttPushManager = mqttPushManager;
        this.dispatcherRegistry = dispatcherRegistry;
        this.queryService = queryService;
        this.serviceRegistry = serviceRegistry;
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

    /**
     * The two kinds of device WebSocket a connector has: the one for devices subscribed one by one (static) and the one
     * for groups and types that resolve to devices (dynamic). They differ only in the subscription they read, the
     * subscriber name their token is made for, and the key their token and failed connects are kept under.
     */
    private enum DeviceBucket {
        STATIC("static", Utils.STATIC_DEVICE_SUBSCRIPTION, Utils.STATIC_DEVICE_SUBSCRIBER),
        DYNAMIC("dynamic", Utils.DYNAMIC_DEVICE_SUBSCRIPTION, Utils.DYNAMIC_DEVICE_SUBSCRIBER);

        final String label;
        final String subscription;
        final String subscriberPrefix;

        DeviceBucket(String label, String subscription, String subscriberPrefix) {
            this.label = label;
            this.subscription = subscription;
            this.subscriberPrefix = subscriberPrefix;
        }

        /** The key the connector's token is stored under. */
        String tokenKey(String connectorId) {
            return connectorId + "_" + label;
        }
    }

    private Map<String, Map<String, CustomWebSocketClient>> clientsOf(DeviceBucket bucket) {
        return bucket == DeviceBucket.STATIC ? staticDeviceClients : dynamicDeviceClients;
    }
    private final Map<String, CustomWebSocketClient> managementClients = new ConcurrentHashMap<>();
    private final Map<String, CustomWebSocketClient> cacheInventoryClients = new ConcurrentHashMap<>();
    // Explorer: one WebSocket per session (keyed by sessionId)
    private final Map<String, CustomWebSocketClient> explorerDeviceClients = new ConcurrentHashMap<>();
    // Explorer session metadata: sessionId → [tenant, subscriberName] for cleanup
    private final Map<String, String[]> explorerDeviceSessionMeta = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> managementCallbacks = new ConcurrentHashMap<>();
    private final Map<String, NotificationCallback> cacheInventoryCallbacks = new ConcurrentHashMap<>();
    //FIXME As we have multiple WS connections the statusCode will only reflect the last connection attempt. We should consider a more granular status tracking if needed.
    // Tenant-level summary of the device WebSockets (what the connector lifecycle reports) ...
    private final Map<String, Integer> deviceWSStatusCodes = new ConcurrentHashMap<>();
    // ... derived from the status of each connector's WebSocket ("tenant|connectorId"). One shared
    // value per tenant let a healthy connector's "open" overwrite another connector's 401, so the
    // expired token of the latter was never replaced.
    private final Map<String, Integer> deviceWSStatusByConnector = new ConcurrentHashMap<>();
    private final Map<String, Integer> managementWSStatusCodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> cacheInventoryWSStatusCodes = new ConcurrentHashMap<>();

    // Scheduled executor for reconnection
    private volatile ScheduledExecutorService reconnectExecutor;

    /** Connectors (as {@code tenant|connectorId}) whose device WebSockets are closed because the broker is down. */
    private final Set<String> pausedConnectors = ConcurrentHashMap.newKeySet();

    /**
     * Device WebSockets that should be open but whose connect failed ("tenant|connectorId|static" or
     * "...|dynamic"). A failed connect leaves no client in the maps, so the periodic reconnect has
     * nothing to reconnect: without this marker the connector stays without a consumer until the
     * next restart. Cleared on success and when the clients are closed on purpose.
     */
    private final Set<String> failedDeviceConnects = ConcurrentHashMap.newKeySet();

    /**
     * Create a subscriber token for EXPLORER_DEVICE_SUBSCRIPTION and open a WebSocket
     * so the explorer session receives Notification 2.0 events for the subscribed device.
     * Prefers a non-TEST dispatcher so that the WebSocket callback processes notifications
     * normally — TEST connector dispatchers skip live notifications (early-return guard in
     * OutboundMessageDispatcher.onNotification) and would silently drop all events.
     */
    public void initializeExplorerDeviceClient(String tenant, String sessionId) {
        Map<String, OutboundMessageDispatcher> dispatchers = dispatcherRegistry.getAll(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.warn("{} - No outbound dispatchers available for explorer session {}", tenant, sessionId);
            return;
        }
        // Prefer a non-TEST dispatcher: OutboundMessageDispatcher.onNotification returns early
        // for TEST connectors without calling processNotification, so notifyOutboundExplorerListeners
        // would never fire and no messages would appear in the explorer.
        OutboundMessageDispatcher dispatcher = dispatchers.values().stream()
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
        // Recorded before the token is created: the subscriber exists on the platform from then on, even
        // if the WebSocket never opens, so closeExplorerDeviceClient must be able to delete it, and the
        // sweep of orphaned explorer subscribers must not take it for an orphan.
        explorerDeviceSessionMeta.put(sessionId, new String[]{tenant, tokenSeed});
        try {
            String token = subscriptionsService.callForTenant(tenant,
                    () -> tokenManager.createToken(Utils.EXPLORER_DEVICE_SUBSCRIPTION, tokenSeed));
            ConnectorId connectorInfo = new ConnectorId(
                    dispatcher.getConnectorClient().getConnectorName(),
                    dispatcher.getConnectorClient().getConnectorIdentifier());
            CustomWebSocketClient client = connect(tenant, token, dispatcher, connectorInfo);
            if (client != null) {
                CustomWebSocketClient old = explorerDeviceClients.put(sessionId, client);
                if (old != null) { closeQuietly(old, sessionId); }
                log.info("{} - Explorer device WebSocket opened for session {}", tenant, sessionId);
            }
        } catch (Exception e) {
            log.error("{} - Failed to open explorer device WebSocket for session {}: {}",
                    tenant, sessionId, e.getMessage(), e);
        }
    }

    private void closeQuietly(CustomWebSocketClient client, String sessionId) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Error closing explorer device WebSocket for session {}: {}", sessionId, e.getMessage());
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
            closeQuietly(client, sessionId);
            log.info("Explorer device WebSocket closed for session {}", sessionId);
        }
        String[] meta = explorerDeviceSessionMeta.remove(sessionId);
        if (meta != null) {
            String tenant = meta[0];
            String subscriberName = meta[1];
            try {
                Boolean deleted = subscriptionsService.callForTenant(tenant,
                        () -> tokenManager.unsubscribeBySubscriberName(Utils.EXPLORER_DEVICE_SUBSCRIPTION, subscriberName));
                if (Boolean.TRUE.equals(deleted)) {
                    log.info("{} - Explorer device subscriber '{}' deleted from C8Y", tenant, subscriberName);
                } else {
                    log.warn("{} - Explorer device subscriber '{}' could not be deleted from C8Y; "
                            + "the next orphan sweep or service restart removes it", tenant, subscriberName);
                }
            } catch (Exception e) {
                log.warn("{} - Could not delete explorer device subscriber '{}': {}", tenant, subscriberName, e.getMessage());
            }
        }
    }

    /** Runs the action with the tenant's service credentials: callers on the session watchdog thread have none. */
    public void runAsTenant(String tenant, Runnable action) {
        subscriptionsService.callForTenant(tenant, () -> { action.run(); return null; });
    }

    /**
     * Whether a subscriber name is an explorer subscriber of this instance: base + 32 hex session id +
     * this instance's suffix. Another instance on the same tenant (for example a local backend next to the
     * deployed service) uses a different suffix, or none; its live subscribers must not be taken for orphans.
     */
    public boolean isOwnExplorerSubscriberName(String name) {
        String base = Utils.EXPLORER_DEVICE_SUBSCRIBER;
        String suffix = additionalSubscriptionIdTest != null ? additionalSubscriptionIdTest : "";
        return name != null && name.length() == base.length() + 32 + suffix.length()
                && name.startsWith(base) && name.endsWith(suffix);
    }

    /** Subscriber names of the explorer sessions that are open now (including ones still connecting). */
    public Set<String> getActiveExplorerSubscriberNames() {
        return explorerDeviceSessionMeta.values().stream().map(m -> m[1]).collect(java.util.stream.Collectors.toSet());
    }

    public void initializeStaticDeviceClient(String tenant) {
        initializeDeviceClient(tenant, DeviceBucket.STATIC);
    }

    public void initializeDynamicDeviceClient(String tenant) {
        initializeDeviceClient(tenant, DeviceBucket.DYNAMIC);
    }

    private void initializeDeviceClient(String tenant, DeviceBucket bucket) {
        clientsOf(bucket).computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());

        try {
            List<NotificationSubscriptionRepresentation> deviceSubs = queryService
                    .getNotificationSubscriptionForDevices(tenant, null, bucket.subscription)
                    .get(30, TimeUnit.SECONDS);

            log.info("{} - Initializing {} {} device subscriptions", tenant, deviceSubs.size(), bucket.label);

            if (!deviceSubs.isEmpty()) {
                initializeDeviceConnections(tenant, bucket);
                activateDeviceConnections(tenant, deviceSubs);
            } else {
                log.info("{} - No existing {} device subscriptions found", tenant, bucket.label);
            }
        } catch (InterruptedException e) {
            log.error("{} - Interrupted while initializing device client", tenant);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | URISyntaxException e) {
            log.error("{} - Error initializing device client: {}", tenant, e.getMessage(), e);
        }
    }

    /** Registers the callbacks that the Management and cache-inventory clients use; see {@link NotificationClientOperations}. */
    public void setClientOperations(NotificationClientOperations clientOperations) {
        this.clientOperations = clientOperations;
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
                    k -> new ManagementSubscriptionClient(serviceRegistry, clientOperations, tenant));

            NotificationCallback cacheInventoryCallback = cacheInventoryCallbacks.computeIfAbsent(tenant,
                    k -> new CacheInventoryUpdateClient(clientOperations, serviceRegistry, tenant));

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
        reconnectDeviceClientsForNewSubscription(tenant, DeviceBucket.STATIC);
    }

    /**
     * Reconnects existing dynamic device WebSocket clients so they pick up pending
     * notifications from a newly created device subscription (e.g. a group or type
     * resolving to this device) without waiting for the 60-second {@link #reconnectAll()}
     * cycle. See {@link #reconnectStaticDeviceClientsForNewSubscription(String)} for why an
     * immediate reconnect is needed: without it, a dynamic client that is already open from
     * an earlier subscription never learns about devices added afterward, since
     * {@code initializeDeviceConnections} skips any client that is already open.
     */
    public void reconnectDynamicDeviceClientsForNewSubscription(String tenant) {
        reconnectDeviceClientsForNewSubscription(tenant, DeviceBucket.DYNAMIC);
    }

    private void reconnectDeviceClientsForNewSubscription(String tenant, DeviceBucket bucket) {
        if (tenant == null) {
            return;
        }
        Map<String, CustomWebSocketClient> clients = clientsOf(bucket).get(tenant);
        if (clients == null || clients.isEmpty()) {
            // No existing clients yet — initialize from scratch
            try {
                initializeDeviceConnections(tenant, bucket);
            } catch (URISyntaxException e) {
                log.error("{} - Error initializing {} device connections after subscription: {}", tenant,
                        bucket.label, e.getMessage(), e);
            }
            return;
        }
        for (Map.Entry<String, CustomWebSocketClient> entry : clients.entrySet()) {
            CustomWebSocketClient client = entry.getValue();
            if (client != null && client.isOpen()) {
                try {
                    client.reconnect();
                    log.info("{} - Triggered {} device WebSocket reconnect for connector {} after new subscription",
                            tenant, bucket.label, entry.getKey());
                } catch (Exception e) {
                    log.warn("{} - Error reconnecting {} device client after subscription: {}", tenant,
                            bucket.label, e.getMessage());
                }
            }
        }
    }

    /**
     * @return true if reconnection completed successfully, false if it failed
     *         (the failure is logged here; callers that need to surface the
     *         failure to a caller/operation-status should check the result).
     */
    public boolean reconnect(String tenant) {
        if (tenant == null) {
            log.warn("Cannot reconnect: tenant is null");
            return false;
        }

        log.info("{} - Reconnecting notification subscriber", tenant);
        AtomicBoolean success = new AtomicBoolean(false);
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                disconnect(tenant);
                initializeStaticDeviceClient(tenant);
                initializeDynamicDeviceClient(tenant);
                initializeManagementClient(tenant);
                log.info("{} - Successfully reconnected", tenant);
                success.set(true);
            } catch (Exception e) {
                log.error("{} - Error during reconnection: {}", tenant, e.getMessage(), e);
            }
        });
        return success.get();
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
        clearDeviceStatus(tenant);

        // Send notification
        try {
            connectorStatusEventService.sendNotificationLifecycle(
                    tenant, ConnectorStatus.DISCONNECTED, null);
        } catch (Exception e) {
            log.warn("{} - Error sending disconnect notification: {}", tenant, e.getMessage());
        }

        log.info("{} - Successfully disconnected", tenant);
    }

    /**
     * Drops all remaining per-tenant state after {@link #disconnect(String)}: the (now empty)
     * client maps, registered callbacks (the management callback owns a group cache with its own
     * cleanup thread), the init lock and the WebSocket status codes. Only for tenant removal;
     * {@link #disconnect(String)} alone is also used for reconnects.
     */
    public void removeTenant(String tenant) {
        if (tenant == null) {
            return;
        }
        NotificationCallback managementCallback = managementCallbacks.remove(tenant);
        if (managementCallback instanceof ManagementSubscriptionClient msc) {
            try {
                msc.cleanup();
            } catch (Exception e) {
                log.warn("{} - Error cleaning up management callback: {}", tenant, e.getMessage());
            }
        }
        cacheInventoryCallbacks.remove(tenant);
        staticDeviceClients.remove(tenant);
        dynamicDeviceClients.remove(tenant);
        managementClients.remove(tenant);
        cacheInventoryClients.remove(tenant);
        managementInitLocks.remove(tenant);
        clearDeviceStatus(tenant);
        managementWSStatusCodes.remove(tenant);
        cacheInventoryWSStatusCodes.remove(tenant);
        failedDeviceConnects.removeIf(k -> k.startsWith(tenant + "|"));
        pausedConnectors.removeIf(k -> k.startsWith(tenant + "|"));
    }

    /**
     * Closes only the per-connector device WebSockets (static + dynamic). The management client
     * (type/group watcher) and cache-inventory client stay up, so type/group subscriptions that
     * currently match no devices keep discovering new ones.
     */
    public void disconnectDeviceSubscribers(String tenant) {
        if (tenant == null) {
            log.warn("Cannot disconnect device clients: tenant is null");
            return;
        }
        disconnectDeviceClients(tenant);
        clearDeviceStatus(tenant);
    }

    public void initializeConnectionsIfNeeded(String tenant) {
        try {
            for (DeviceBucket bucket : DeviceBucket.values()) {
                // Initialize only what is needed: a bucket with no client, or none that is connected
                Map<String, CustomWebSocketClient> clientsForTenant = clientsOf(bucket).get(tenant);
                boolean needInit = clientsForTenant == null || clientsForTenant.isEmpty()
                        || clientsForTenant.values().stream().noneMatch(client -> client != null && client.isOpen());
                if (needInit) {
                    log.info("{} - Initializing {} device connections", tenant, bucket.label);
                    initializeDeviceConnections(tenant, bucket);
                }
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
        for (DeviceBucket bucket : DeviceBucket.values()) {
            Map<String, CustomWebSocketClient> clientsForTenant = clientsOf(bucket).get(tenant);
            if (clientsForTenant == null) {
                continue;
            }
            CustomWebSocketClient client = clientsForTenant.remove(connectorIdentifier);
            if (client != null) {
                try {
                    client.close();
                    log.info("{} - Closed WebSocket associated {} device subscriptions for connector {}", tenant,
                            bucket.label, connectorIdentifier);
                } catch (Exception e) {
                    log.warn("{} - Error closing WebSocket associated {} device subscriptions for connector {}: {}",
                            tenant, bucket.label, connectorIdentifier, e.getMessage());
                }
            }
        }

        // Disconnect if no more dispatchers
        Map<String, OutboundMessageDispatcher> dispatchers = dispatcherRegistry.getAll(tenant);
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

    /** Records the status of one connector's device WebSocket and refreshes the tenant summary. */
    public void setDeviceConnectionStatus(String tenant, String connectorId, Integer status) {
        if (tenant == null) {
            return;
        }
        if (connectorId == null) {
            setDeviceConnectionStatus(tenant, status);
            return;
        }
        String key = pauseKey(tenant, connectorId);
        if (status != null) {
            deviceWSStatusByConnector.put(key, status);
        } else {
            deviceWSStatusByConnector.remove(key);
        }
        // Summary: any connector unauthorized -> 401 (it needs a new token); else any open -> 200
        String prefix = tenant + "|";
        Integer summary = null;
        for (Map.Entry<String, Integer> e : deviceWSStatusByConnector.entrySet()) {
            if (!e.getKey().startsWith(prefix) || e.getValue() == null) {
                continue;
            }
            if (e.getValue() == 401) {
                summary = 401;
                break;
            }
            summary = e.getValue();
        }
        setDeviceConnectionStatus(tenant, summary);
    }

    private boolean isUnauthorized(String tenant, String connectorId) {
        Integer status = deviceWSStatusByConnector.get(pauseKey(tenant, connectorId));
        return status != null && status == 401;
    }

    private void clearDeviceStatus(String tenant) {
        deviceWSStatusCodes.remove(tenant);
        deviceWSStatusByConnector.keySet().removeIf(k -> k.startsWith(tenant + "|"));
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
            reconnectExecutor.scheduleWithFixedDelay(this::syncConnectorAvailabilityAllTenants,
                    30, Utils.CONNECTOR_AVAILABILITY_CHECK_SECONDS, TimeUnit.SECONDS);
            log.debug("Started reconnect scheduler");
        }
    }

    // === Private Helper Methods ===

    private void initializeDeviceConnections(String tenant, DeviceBucket bucket) throws URISyntaxException {
        Map<String, OutboundMessageDispatcher> dispatchers = dispatcherRegistry.getAll(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            log.warn("{} - No outbound dispatchers registered", tenant);
            return;
        }

        for (OutboundMessageDispatcher dispatcher : dispatchers.values()) {
            if (!isValidDispatcher(dispatcher) || isTestConnector(dispatcher)) {
                continue;
            }
            if (isHeldWhileDisconnected(dispatcher)) {
                // Do not consume while the broker connection is down: the unacked messages stay queued in
                // Cumulocity and are delivered once syncConnectorAvailability() sees the connector connected.
                pausedConnectors.add(pauseKey(tenant, dispatcher.getConnectorClient().getConnectorIdentifier()));
                log.info("{} - Not opening device WebSocket for connector {}: broker connection is down",
                        tenant, dispatcher.getConnectorClient().getConnectorIdentifier());
                continue;
            }

            String connectorId = dispatcher.getConnectorClient().getConnectorIdentifier();

            Map<String, CustomWebSocketClient> clientsForTenant = clientsOf(bucket).get(tenant);
            if (clientsForTenant != null) {
                CustomWebSocketClient existingClient = clientsForTenant.get(connectorId);
                if (existingClient != null && existingClient.isOpen()) {
                    log.debug("{} - {} device client already connected for connector {}, skipping", tenant,
                            bucket.label, connectorId);
                    continue;
                }
            }

            String tokenSeed = bucket.subscriberPrefix + connectorId + additionalSubscriptionIdTest;
            String tokenKey = bucket.tokenKey(connectorId);

            try {
                String storedToken = tokenManager.getDeviceToken(tenant, tokenKey);
                boolean usingStoredToken = storedToken != null;
                String token = usingStoredToken
                        ? storedToken
                        : tokenManager.createToken(bucket.subscription, tokenSeed);
                if (!usingStoredToken) {
                    tokenManager.storeDeviceToken(tenant, tokenKey, token);
                } else {
                    log.debug("{} - Reusing refreshed {} device token for connector {}", tenant, bucket.label,
                            connectorId);
                }

                ConnectorId connectorInfo = new ConnectorId(
                        dispatcher.getConnectorClient().getConnectorName(),
                        connectorId);

                CustomWebSocketClient client = connect(tenant, token, dispatcher, connectorInfo);
                if (client == null && usingStoredToken) {
                    log.warn("{} - Stored {} device token failed to connect for connector {}, retrying with fresh token",
                            tenant, bucket.label, connectorId);
                    tokenManager.storeDeviceToken(tenant, tokenKey, null);
                    token = tokenManager.createToken(bucket.subscription, tokenSeed);
                    tokenManager.storeDeviceToken(tenant, tokenKey, token);
                    client = connect(tenant, token, dispatcher, connectorInfo);
                }
                if (client != null) {
                    clientsOf(bucket).get(tenant).put(connectorId, client);
                    failedDeviceConnects.remove(connectKey(tenant, connectorId, bucket.label));
                    log.info("{} - Initialized {} device connection for connector: {}", tenant, bucket.label,
                            connectorId);
                } else {
                    failedDeviceConnects.add(connectKey(tenant, connectorId, bucket.label));
                }
            } catch (Exception e) {
                failedDeviceConnects.add(connectKey(tenant, connectorId, bucket.label));
                log.error("{} - Failed to initialize {} device connection for connector {}: {}",
                        tenant, bucket.label, connectorId, e.getMessage(), e);
            }
        }
    }

    private void activateDeviceConnections(String tenant, List<NotificationSubscriptionRepresentation> deviceSubs) {
        int activatedCount = 0;
        for (NotificationSubscriptionRepresentation sub : deviceSubs) {
            try {
                if (isValidSubscription(sub)) {
                    ExternalIDRepresentation extId = serviceRegistry.getC8yAgent()
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
                    ManagedObjectRepresentation groupMO = serviceRegistry.getC8yAgent()
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
            log.warn("{} - Cannot connect: invalid parameters", tenant);
            return null;
        }

        try {
            connectorStatusEventService.sendNotificationLifecycle(
                    tenant, ConnectorStatus.CONNECTING, null);

            // L3: replace("http","ws") corrupts any hostname that contains "http" as a substring;
            // only the scheme prefix must be replaced
            String webSocketBaseUrl = baseUrl.startsWith("https://")
                    ? "wss://" + baseUrl.substring("https://".length())
                    : "ws://" + baseUrl.substring("http://".length());
            URI webSocketUrl = new URI(webSocketBaseUrl + Utils.WEBSOCKET_PATH + token);

            CustomWebSocketClient client = new CustomWebSocketClient(
                    tenant, serviceRegistry, webSocketUrl, callback, connectorId);
            client.setConnectionLostTimeout(Utils.CONNECTION_TIMEOUT_SECONDS);

            boolean connected = client.connectBlocking(Utils.CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!connected) {
                // After an ungraceful microservice restart, Cumulocity still considers the
                // previous consumer active and rejects the new connection with HTTP 409.
                // Wait and retry — do NOT unsubscribe, as that would drop any backlogged messages.
                if (client.isConflict()) {
                    for (int attempt = 1; attempt <= Utils.CONFLICT_RETRY_COUNT; attempt++) {
                        int delaySeconds = Utils.CONFLICT_RETRY_DELAYS_SECONDS[attempt - 1];
                        log.warn("{} - WebSocket 409 Conflict for connector {} — waiting {}s before retry {}/{}",
                                tenant, connectorId.getName(), delaySeconds,
                                attempt, Utils.CONFLICT_RETRY_COUNT);
                        try {
                            TimeUnit.SECONDS.sleep(delaySeconds);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        CustomWebSocketClient retryClient = new CustomWebSocketClient(
                                tenant, serviceRegistry, webSocketUrl, callback, connectorId);
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
            connectorStatusEventService.sendNotificationLifecycle(
                    tenant, ConnectorStatus.FAILED, e.getLocalizedMessage());
            return null;
        }
    }

    private void disconnectDeviceClients(String tenant) {
        failedDeviceConnects.removeIf(k -> k.startsWith(tenant + "|"));
        for (DeviceBucket bucket : DeviceBucket.values()) {
            Map<String, CustomWebSocketClient> clientsForTenant = clientsOf(bucket).get(tenant);
            if (clientsForTenant == null) {
                continue;
            }
            int disconnectedCount = 0;
            for (CustomWebSocketClient client : clientsForTenant.values()) {
                if (client != null && client.isOpen()) {
                    try {
                        client.close();
                        disconnectedCount++;
                    } catch (Exception e) {
                        log.warn("{} - Error closing {} device client: {}", tenant, bucket.label, e.getMessage());
                    }
                }
            }
            clientsForTenant.clear();
            log.info("{} - Disconnected {} device {} WebSocket clients", tenant, disconnectedCount, bucket.label);
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

    private static String pauseKey(String tenant, String connectorId) {
        return tenant + "|" + connectorId;
    }

    private static String connectKey(String tenant, String connectorId, String bucket) {
        return tenant + "|" + connectorId + "|" + bucket;
    }

    /** A connector that holds outbound messages while its broker connection is down, and is down now. */
    private boolean isHeldWhileDisconnected(OutboundMessageDispatcher dispatcher) {
        return dispatcher.getConnectorClient().holdsOutboundWhileDisconnected()
                && !dispatcher.getConnectorClient().isConnected();
    }

    private void syncConnectorAvailabilityAllTenants() {
        // A scheduled task that throws is never run again: nothing may escape this method, an Error included, or
        // the outbound pause would silently stop for good (reconnectAll is guarded the same way)
        try {
            subscriptionsService.runForEachTenant(() -> syncConnectorAvailability(subscriptionsService.getTenant()));
        } catch (Throwable t) {
            log.error("Error while syncing connector availability: {}", t.getMessage(), t);
        }
    }

    /**
     * Keeps the device WebSockets in step with the broker connections, so outbound messages are not consumed
     * (and lost) while a broker is down. Cumulocity keeps what was not acknowledged and delivers it again when
     * the WebSocket is opened. Nothing is buffered here and the subscription is kept: unsubscribing would drop
     * the queued messages.
     * <ul>
     *   <li>connector down: close its static and dynamic device WebSocket, without unsubscribing;</li>
     *   <li>connector up again: open them again.</li>
     * </ul>
     */
    void syncConnectorAvailability(String tenant) {
        Map<String, OutboundMessageDispatcher> dispatchers = dispatcherRegistry.getAll(tenant);
        if (dispatchers == null || dispatchers.isEmpty()) {
            return;
        }
        boolean resume = false;
        for (OutboundMessageDispatcher dispatcher : dispatchers.values()) {
            if (!isValidDispatcher(dispatcher) || isTestConnector(dispatcher)
                    || !dispatcher.getConnectorClient().holdsOutboundWhileDisconnected()) {
                continue;
            }
            String connectorId = dispatcher.getConnectorClient().getConnectorIdentifier();
            String key = pauseKey(tenant, connectorId);
            if (!dispatcher.getConnectorClient().isConnected()) {
                if (closeDeviceClientsForConnector(tenant, connectorId) | pausedConnectors.add(key)) {
                    log.warn("{} - Broker connection of connector {} is down: paused its device WebSockets, "
                            + "unacknowledged outbound messages stay queued in Cumulocity", tenant, connectorId);
                }
            } else if (pausedConnectors.remove(key)) {
                log.info("{} - Broker connection of connector {} is up again: resuming its device WebSockets",
                        tenant, connectorId);
                resume = true;
            }
        }
        if (resume) {
            try {
                initializeDeviceConnections(tenant, DeviceBucket.STATIC);
                initializeDeviceConnections(tenant, DeviceBucket.DYNAMIC);
            } catch (URISyntaxException e) {
                log.error("{} - Error resuming device connections: {}", tenant, e.getMessage(), e);
            }
        }
    }

    /** Closes the connector's device WebSockets without unsubscribing. @return whether one was open */
    private boolean closeDeviceClientsForConnector(String tenant, String connectorId) {
        failedDeviceConnects.remove(connectKey(tenant, connectorId, "static"));
        failedDeviceConnects.remove(connectKey(tenant, connectorId, "dynamic"));
        boolean closed = false;
        for (Map<String, Map<String, CustomWebSocketClient>> clientsByTenant
                : List.of(staticDeviceClients, dynamicDeviceClients)) {
            Map<String, CustomWebSocketClient> clients = clientsByTenant.get(tenant);
            CustomWebSocketClient client = clients == null ? null : clients.remove(connectorId);
            if (client != null) {
                closed |= client.isOpen();
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("{} - Error closing device WebSocket of connector {}: {}", tenant, connectorId,
                            e.getMessage());
                }
            }
        }
        return closed;
    }

    private void reconnectAll() {
        log.debug("Starting reconnection cycle");

        // A scheduleAtFixedRate task that throws is never run again: nothing may escape this method
        try {
            reconnectAllTenants();
        } catch (Throwable t) {
            log.error("Reconnection cycle failed: {}", t.getMessage(), t);
        }
    }

    private void reconnectAllTenants() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();

            try {
                reconnectDeviceClients(tenant);
                retryFailedDeviceConnects(tenant);
                reconnectManagementClients(tenant);

                connectorStatusEventService.sendNotificationLifecycle(
                        tenant, ConnectorStatus.CONNECTED, null);

            } catch (Exception e) {
                log.error("{} - Error during reconnection: {}", tenant, e.getMessage(), e);
                connectorStatusEventService.sendNotificationLifecycle(
                        tenant, ConnectorStatus.FAILED, e.getLocalizedMessage());
            }
        });
    }

    /**
     * Opens the device WebSockets whose earlier connect failed. {@link #reconnectDeviceClients} only
     * revisits clients that exist, and a failed connect leaves none.
     */
    void retryFailedDeviceConnects(String tenant) {
        String prefix = tenant + "|";
        boolean staticFailed = failedDeviceConnects.stream().anyMatch(k -> k.startsWith(prefix) && k.endsWith("|static"));
        boolean dynamicFailed = failedDeviceConnects.stream().anyMatch(k -> k.startsWith(prefix) && k.endsWith("|dynamic"));
        if (staticFailed) {
            log.info("{} - Retrying static device WebSocket connect that failed earlier", tenant);
            initializeStaticDeviceClient(tenant);
        }
        if (dynamicFailed) {
            log.info("{} - Retrying dynamic device WebSocket connect that failed earlier", tenant);
            initializeDynamicDeviceClient(tenant);
        }
    }

    private void reconnectDeviceClients(String tenant) {
        for (DeviceBucket bucket : DeviceBucket.values()) {
            Map<String, CustomWebSocketClient> clientsForTenant = clientsOf(bucket).get(tenant);
            if (clientsForTenant == null) {
                continue;
            }

            int reconnectedCount = 0;
            for (Map.Entry<String, CustomWebSocketClient> entry : clientsForTenant.entrySet()) {
                CustomWebSocketClient client = entry.getValue();
                if (shouldReconnectClient(tenant, entry.getKey(), client)) {
                    try {
                        if (client.getReadyState() == ReadyState.NOT_YET_CONNECTED ||
                                isUnauthorized(tenant, entry.getKey())) {
                            log.info("{} - Re-initializing {} device client", tenant, bucket.label);
                            initializeDeviceClient(tenant, bucket);
                            // Don't break: it covers all connectors in one call, but continue so the
                            // remaining closed or stale clients are also inspected
                        } else {
                            client.reconnect();
                            reconnectedCount++;
                            log.info("{} - Reconnected {} device client", tenant, bucket.label);
                        }
                    } catch (Exception e) {
                        log.warn("{} - Error reconnecting {} device client: {}", tenant, bucket.label,
                                e.getMessage());
                    }
                }
            }

            if (reconnectedCount > 0) {
                log.info("{} - Reconnected {} {} device clients", tenant, reconnectedCount, bucket.label);
            }
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

    private Boolean shouldReconnectClient(String tenant, String connectorId, CustomWebSocketClient client) {
        if (client == null) {
            return false;
        }

        ReadyState state = client.getReadyState();
        boolean unauthorized = isUnauthorized(tenant, connectorId);

        return !client.isOpen() &&
                (state == ReadyState.CLOSING ||
                        state == ReadyState.CLOSED ||
                        state == ReadyState.NOT_YET_CONNECTED ||
                        unauthorized);
    }

    /**
     * The TEST connector is driven via TestController only; OutboundMessageDispatcher.onNotification
     * drops live notifications for it, so a Notification 2.0 WebSocket would be pure overhead.
     */
    private boolean isTestConnector(OutboundMessageDispatcher dispatcher) {
        return dispatcher.getConnectorClient().getConnectorType() == ConnectorType.TEST;
    }

    private Boolean isValidDispatcher(OutboundMessageDispatcher dispatcher) {
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
        deviceWSStatusByConnector.clear();
        managementWSStatusCodes.clear();
        cacheInventoryWSStatusCodes.clear();

        log.info("ConnectionManager cleanup completed");
    }
}
