/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package dynamic.mapping.notification;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionFilterRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationTokenRequestRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.Token;
import com.cumulocity.sdk.client.messaging.notifications.TokenApi;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.model.C8YAPISubscription;
import dynamic.mapping.model.Device;
import dynamic.mapping.notification.websocket.CustomWebSocketClient;
import dynamic.mapping.notification.websocket.NotificationCallback;
import dynamic.mapping.processor.outbound.AsynchronousDispatcherOutbound;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.model.API;
import dynamic.mapping.notification.websocket.Notification;
import org.apache.commons.collections.ArrayStack;
import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class C8YAPISubscriber {
    private final static String WEBSOCKET_PATH = "/notification2/consumer/?token=";

    @Autowired
    private TokenApi tokenApi;

    @Autowired
    private NotificationSubscriptionApi subscriptionApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private C8YAgent c8YAgent;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    // structure: <tenant, <connectorIdent, asynchronousDispatcherOutbound>>
    @Getter
    private Map<String, Map<String, AsynchronousDispatcherOutbound>> dispatcherOutboundMaps = new HashMap<>();

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    @Value("${APP.additionalSubscriptionIdTest}")
    private String additionalSubscriptionIdTest;

    @Value("${APP.outputMappingEnabled}")
    private boolean outputMappingEnabled;

    private ScheduledExecutorService executorService = null;
    private final String DEVICE_SUBSCRIBER = "DynamicMapperDeviceSubscriber";
    private final String DEVICE_SUBSCRIPTION = "DynamicMapperDeviceSubscription";
    private final String TENANT_SUBSCRIBER = "DynamicMapperTenantSubscriber";
    private final String TENANT_SUBSCRIPTION = "DynamicMapperTenantSubscription";

    // List<CustomWebSocketClient> wsClientList = new ArrayList<>();
    private Map<String, Map<String, CustomWebSocketClient>> deviceClientMap = new HashMap<>();
    private Map<String, CustomWebSocketClient> tenantClientMap = new HashMap<>();
    private Map<String, Integer> tenantWSStatusCode = new HashMap<>();
    private Map<String, Integer> deviceWSStatusCode = new HashMap<>();

    private Map<String, String> tenantDeviceToken = new HashMap<>();
    private Map<String, String> tenantToken = new HashMap<>();
    // private int deviceWSStatusCode = 0;

    // public void init() {
    // //Assuming this can be only changed for all tenants!
    // String tenant = subscriptionsService.getTenant();
    // if(deviceClientMap.get(tenant) == null)
    // deviceClientMap.put(tenant, new HashMap<>());
    // try {
    // HashMap<String, AConnectorClient> connectorMap =
    // connectorRegistry.getClientsForTenant(tenant);
    // //For multiple connectors register for each a separate dispatcher
    // if(connectorMap != null) {
    // // for (AConnectorClient connectorClient : connectorMap.values()) {
    // // AsynchronousDispatcherOutbound dispatcherOutbound = new
    // AsynchronousDispatcherOutbound(objectMapper, c8YAgent, mappingComponent,
    // cachedThreadPool, connectorClient, payloadProcessor);
    // // dispatcherOutboundMap.get(tenant).put(connectorClient.getConnectorIdent(),
    // dispatcherOutbound);
    // // }
    // log.info("Tenant {} - OutputMapping Config Enabled: {}", tenant,
    // outputMappingEnabled);
    // if (outputMappingEnabled) {
    // initTenantClient();
    // initDeviceClient();
    // }
    // }
    // } catch (ConnectorRegistryException e) {
    // throw new RuntimeException(e);
    // }

    // }

    public void removeConnector(String tenant, String ident) {
        // Remove Dispatcher from list
        this.dispatcherOutboundMaps.get(tenant).remove(ident);
        // Close WS connection for connector
        this.deviceClientMap.get(tenant).get(ident).close();
        // Remove client from client Map
        this.deviceClientMap.get(tenant).remove(ident);
        if (dispatcherOutboundMaps.get(tenant).keySet().isEmpty())
            disconnect(tenant, false);
    }

    public void addConnector(String tenant, String ident, AsynchronousDispatcherOutbound dispatcherOutbound) {
        Map<String, AsynchronousDispatcherOutbound> dispatcherOutboundMap = getDispatcherOutboundMaps().get(tenant);
        if (dispatcherOutboundMap == null) {
            dispatcherOutboundMap = new HashMap<>();
            getDispatcherOutboundMaps().put(tenant, dispatcherOutboundMap);
        }
        dispatcherOutboundMap.put(ident, dispatcherOutbound);
    }

    public void initTenantClient() {
        // Subscribe on Tenant do get informed when devices get deleted/added
        String tenant = subscriptionsService.getTenant();
        log.info("Tenant {} - Initializing Operation Subscriptions...", tenant);
        subscribeTenant(subscriptionsService.getTenant());
    }

    public void initDeviceClient() {
        String tenant = subscriptionsService.getTenant();
        if (deviceClientMap.get(tenant) == null)
            deviceClientMap.put(tenant, new HashMap<>());
        List<NotificationSubscriptionRepresentation> deviceSubList = null;

        try {
            // Getting existing subscriptions
            deviceSubList = getNotificationSubscriptions(null, DEVICE_SUBSCRIPTION).get();
            log.info("Tenant {} - Subscribing to devices {}", tenant, deviceSubList);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        // When one subscription exists, connect...
        if (deviceSubList.size() > 0) {
            try {
                // For each dispatcher/connector create a new connection
                for (AsynchronousDispatcherOutbound dispatcherOutbound : dispatcherOutboundMaps.get(tenant).values()) {
                    String token = createToken(DEVICE_SUBSCRIPTION,
                            DEVICE_SUBSCRIBER + dispatcherOutbound.getConnectorClient().getConnectorIdent()
                                    + additionalSubscriptionIdTest);
                    tenantDeviceToken.put(tenant, token);
                    CustomWebSocketClient client = connect(token, dispatcherOutbound);
                    deviceClientMap.get(tenant).put(dispatcherOutbound.getConnectorClient().getConnectorIdent(),
                            client);
                }

            } catch (URISyntaxException e) {
                log.error("Tenant {} - Error connecting device subscription: {}", tenant, e.getLocalizedMessage());
            }
        }
    }

    /*
     * public void subscribeAllDevices() {
     * InventoryFilter filter = new InventoryFilter();
     * filter.byFragmentType("c8y_IsDevice");
     * Iterator<ManagedObjectRepresentation> deviceIt =
     * inventoryApi.getManagedObjectsByFilter(filter).get().allPages()
     * .iterator();
     * NotificationSubscriptionRepresentation notRep = null;
     * while (deviceIt.hasNext()) {
     * ManagedObjectRepresentation mor = deviceIt.next();
     * log.info("Found device " + mor.getName());
     * try {
     * notRep = subscribeDevice(mor, API.OPERATION).get();
     * } catch (InterruptedException e) {
     * throw new RuntimeException(e);
     * } catch (ExecutionException e) {
     * throw new RuntimeException(e);
     * }
     * }
     * if (notRep != null) {
     * String deviceToken = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER +
     * additionalSubscriptionIdTest);
     * 
     * try {
     * device_client = connect(deviceToken, dispatcherOutbound);
     * } catch (URISyntaxException e) {
     * e.printStackTrace();
     * }
     * }
     * }
     * 
     */

    public CompletableFuture<NotificationSubscriptionRepresentation> subscribeDevice(ManagedObjectRepresentation mor,
            API api) throws ExecutionException, InterruptedException {
        /* Connect to all devices */
        String tenant = subscriptionsService.getTenant();
        String deviceName = mor.getName();
        log.info("Tenant {} - Creating new Subscription for Device {} with ID {}", tenant, deviceName,
                mor.getId().getValue());
        CompletableFuture<NotificationSubscriptionRepresentation> notificationFut = new CompletableFuture<NotificationSubscriptionRepresentation>();
        subscriptionsService.runForTenant(tenant, () -> {
            NotificationSubscriptionRepresentation notification = createDeviceSubscription(mor, api);
            notificationFut.complete(notification);
            if (deviceWSStatusCode.get(tenant) == null
                    || (deviceWSStatusCode.get(tenant) != null && deviceWSStatusCode.get(tenant) != 200)) {
                log.info("Tenant {} - Device Subscription not connected yet. Will connect...", tenant);

                try {
                    // Add Dispatcher for each Connector
                    if (dispatcherOutboundMaps.get(tenant).keySet().isEmpty())
                        log.info("No Outbound dispatcher for any connector is registered, add a connector first!");

                    for (AsynchronousDispatcherOutbound dispatcherOutbound : dispatcherOutboundMaps.get(tenant)
                            .values()) {
                        String token = createToken(DEVICE_SUBSCRIPTION,
                                DEVICE_SUBSCRIBER + dispatcherOutbound.getConnectorClient().getConnectorIdent()
                                        + additionalSubscriptionIdTest);
                        tenantDeviceToken.put(tenant, token);
                        CustomWebSocketClient client = connect(token, dispatcherOutbound);
                        deviceClientMap.get(tenant).put(dispatcherOutbound.getConnectorClient().getConnectorIdent(),
                                client);
                    }

                } catch (URISyntaxException e) {
                    log.error("Error on connecting to Notification Service: {}", e.getLocalizedMessage());
                    throw new RuntimeException(e);
                }
            }
        });
        return notificationFut;
    }

    public C8YAPISubscription getDeviceSubscriptions(String tenant, String deviceId, String deviceSubscription) {
        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        if (deviceSubscription != null)
            filter = filter.bySubscription(deviceSubscription);
        else
            filter = filter.bySubscription(DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");
        NotificationSubscriptionFilter finalFilter = filter;
        C8YAPISubscription c8YAPISubscription = new C8YAPISubscription();
        List<Device> devices = new ArrayStack();
        subscriptionsService.runForTenant(tenant, () -> {
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi
                    .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();
            NotificationSubscriptionRepresentation notification = null;
            while (subIt.hasNext()) {
                notification = subIt.next();
                if (!"tenant".equals(notification.getContext())) {
                    log.debug("Subscription with ID {} retrieved", notification.getId().getValue());
                    Device device = new Device();
                    device.setId(notification.getSource().getId().getValue());
                    ManagedObjectRepresentation mor = c8YAgent
                            .getManagedObjectForId(tenant, notification.getSource().getId().getValue());
                    if (mor != null)
                        device.setName(mor.getName());
                    else
                        log.warn("Device with ID {} does not exists!", notification.getSource().getId().getValue());
                    devices.add(device);
                    if (notification.getSubscriptionFilter().getApis().size() > 0) {
                        API api = API.fromString(notification.getSubscriptionFilter().getApis().get(0));
                        c8YAPISubscription.setApi(api);
                    }
                }
            }
        });
        c8YAPISubscription.setDevices(devices);
        return c8YAPISubscription;
    }

    public CompletableFuture<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptions(String deviceId,
            String deviceSubscription) {
        NotificationSubscriptionFilter filter = new NotificationSubscriptionFilter();
        if (deviceSubscription != null)
            filter = filter.bySubscription(deviceSubscription);
        else
            filter = filter.bySubscription(DEVICE_SUBSCRIPTION);

        if (deviceId != null) {
            GId id = new GId();
            id.setValue(deviceId);
            filter = filter.bySource(id);
        }
        filter = filter.byContext("mo");
        NotificationSubscriptionFilter finalFilter = filter;
        CompletableFuture<List<NotificationSubscriptionRepresentation>> deviceSubListFut = new CompletableFuture<>();
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            String tenant = subscriptionsService.getTenant();
            List<NotificationSubscriptionRepresentation> deviceSubList = new ArrayList<>();
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi
                    .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();
            NotificationSubscriptionRepresentation notification = null;
            while (subIt.hasNext()) {
                notification = subIt.next();
                if (!"tenant".equals(notification.getContext())) {
                    log.info("Tenant {} - Subscription with ID {} retrieved", tenant, notification.getId().getValue());
                    deviceSubList.add(notification);
                }
            }
            deviceSubListFut.complete(deviceSubList);
        });

        return deviceSubListFut;
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[0];
    }

    public void notificationSubscriberReconnect(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            disconnect(tenant, false);
            // notificationSubscriber.init();
            initTenantClient();
            initDeviceClient();
        });
    }

    public void subscribeTenant(String tenant) {
        log.info("Tenant {} - Creating new Tenant Subscription", tenant);
        NotificationSubscriptionRepresentation notification = createTenantSubscription();
        String tenantToken = createToken(notification.getSubscription(),
                TENANT_SUBSCRIBER + additionalSubscriptionIdTest);
        this.tenantToken.put(tenant, tenantToken);

        try {
            NotificationCallback tenantCallback = new NotificationCallback() {

                @Override
                public void onOpen(URI uri) {
                    log.info("Tenant {} - Connected to Cumulocity notification service over WebSocket {}", tenant, uri);
                    tenantWSStatusCode.put(tenant, 200);
                }

                @Override
                public void onNotification(Notification notification) {
                    try {
                        log.debug("Tenant {} - Tenant Notification received: <{}>", tenant, notification.getMessage());
                        log.debug("Tenant {} - Notification headers: <{}>", tenant,
                                notification.getNotificationHeaders());
                        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());
                        ManagedObjectRepresentation mor = JSONBase.getJSONParser()
                                .parse(ManagedObjectRepresentation.class, notification.getMessage());
                        /*
                         * if (notification.getNotificationHeaders().contains("CREATE")) {
                         * log.info("New Device created with name {} and id {}", mor.getName(),
                         * mor.getId().getValue());
                         * final ManagedObjectRepresentation morRetrieved =
                         * c8YAgent.getManagedObjectForId(mor.getId().getValue());
                         * if (morRetrieved != null) {
                         * subscribeDevice(morRetrieved);
                         * }
                         * }
                         */
                        if (notification.getNotificationHeaders().contains("DELETE")) {
                            log.info("Tenant {} - Device deleted with name {} and id {}", tenant, mor.getName(),
                                    mor.getId().getValue());
                            final ManagedObjectRepresentation morRetrieved = c8YAgent
                                    .getManagedObjectForId(tenant, mor.getId().getValue());
                            if (morRetrieved != null) {
                                unsubscribeDevice(morRetrieved);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error on processing Tenant Notification {}: {}", notification,
                                e.getLocalizedMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("We got an exception: " + t);
                }

                @Override
                public void onClose(int statusCode, String reason) {
                    log.info("Tenant {} - Tenant ws connection closed.", tenant);
                    if (reason.contains("401"))
                        tenantWSStatusCode.put(tenant, 401);
                    else
                        tenantWSStatusCode.put(tenant, 0);
                }
            };
            connect(tenantToken, tenantCallback);
            // tenantClientMap.put(tenant, tenant_client);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }

    // @Scheduled(fixedRate = 30000, initialDelay = 30000)
    public void reconnect() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                if (tenantClientMap != null) {
                    for (String currentTenant : tenantClientMap.keySet()) {
                        if (currentTenant.equals(tenant)) {
                            CustomWebSocketClient tenant_client = tenantClientMap.get(currentTenant);
                            if (tenant_client != null) {
                                log.debug("Running ws reconnect... tenant client: {}, tenant_isOpen: {}", tenant_client,
                                        tenant_client.isOpen());
                                if (!tenant_client.isOpen()) {
                                    if (tenantWSStatusCode.get(tenant) == 401
                                            || tenant_client.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                                        log.info("Tenant {} - Trying to reconnect ws tenant client... ", tenant);
                                        subscriptionsService.runForEachTenant(() -> {
                                            initTenantClient();
                                        });
                                    } else if (tenant_client.getReadyState().equals(ReadyState.CLOSING)
                                            || tenant_client.getReadyState().equals(ReadyState.CLOSED)) {
                                        log.info("Tenant {} - Trying to reconnect ws tenant client... ", tenant);
                                        tenant_client.reconnect();
                                    }
                                }
                            }
                        }
                    }
                }
                if (deviceClientMap.get(tenant) != null) {
                    for (CustomWebSocketClient device_client : deviceClientMap.get(tenant).values()) {
                        if (device_client != null) {
                            if (!device_client.isOpen()) {
                                if (deviceWSStatusCode.get(tenant) == 401
                                        || device_client.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                                    log.info("Tenant {} - Trying to reconnect ws device client... ", tenant);
                                    subscriptionsService.runForEachTenant(() -> {
                                        initDeviceClient();
                                    });
                                } else if (device_client.getReadyState().equals(ReadyState.CLOSING)
                                        || device_client.getReadyState().equals(ReadyState.CLOSED)) {
                                    log.info("Tenant {} - Trying to reconnect ws device client... ", tenant);
                                    device_client.reconnect();
                                }
                            }
                        }
                    }
                }
                c8YAgent.sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTED, null);
            } catch (Exception e) {
                log.error("Error reconnecting to Notification Service: {}", e.getLocalizedMessage());
                c8YAgent.sendNotificationLifecycle(tenant, ConnectorStatus.FAILED,  e.getLocalizedMessage());
                e.printStackTrace();
            }
        });

    }

    public NotificationSubscriptionRepresentation createTenantSubscription() {
        final String subscriptionName = TENANT_SUBSCRIPTION;
        String tenant = subscriptionsService.getTenant();
        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi
                .getSubscriptionsByFilter(
                        new NotificationSubscriptionFilter().bySubscription(subscriptionName).byContext("tenant"))
                .get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        while (subIt.hasNext()) {
            notification = subIt.next();
            // Needed for 1015 releases
            if (TENANT_SUBSCRIPTION.equals(notification.getSubscription())) {
                log.info("Tenant {} - Subscription with ID {} already exists.", tenant,
                        notification.getId().getValue());
                return notification;
            }
        }
        // log.info("Subscription does not exist. Creating ...");
        notification = new NotificationSubscriptionRepresentation();
        final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
        filterRepresentation.setApis(List.of("managedobjects"));
        notification.setContext("tenant");
        notification.setSubscription(subscriptionName);
        notification.setSubscriptionFilter(filterRepresentation);
        notification = subscriptionApi.subscribe(notification);
        return notification;
    }

    public NotificationSubscriptionRepresentation createDeviceSubscription(ManagedObjectRepresentation mor, API api) {
        final String subscriptionName = DEVICE_SUBSCRIPTION;

        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi
                .getSubscriptionsByFilter(
                        new NotificationSubscriptionFilter().bySubscription(subscriptionName).bySource(mor.getId()))
                .get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        while (subIt.hasNext()) {
            notification = subIt.next();
            if (DEVICE_SUBSCRIPTION.equals(notification.getSubscription())) {
                log.info("Subscription with ID {} and Source {} already exists.", notification.getId().getValue(),
                        notification.getSource().getId().getValue());
                return notification;
            }
        }

        // log.info("Subscription does not exist. Creating ...");
        notification = new NotificationSubscriptionRepresentation();
        notification.setSource(mor);
        final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
        // filterRepresentation.setApis(List.of("operations"));
        filterRepresentation.setApis(List.of(api.notificationFilter));
        notification.setContext("mo");
        notification.setSubscription(subscriptionName);
        notification.setSubscriptionFilter(filterRepresentation);
        notification = subscriptionApi.subscribe(notification);
        return notification;
    }

    public String createToken(String subscription, String subscriber) {
        final NotificationTokenRequestRepresentation tokenRequestRepresentation = new NotificationTokenRequestRepresentation(
                subscriber,
                subscription,
                1440,
                false);
        return tokenApi.create(tokenRequestRepresentation).getTokenString();
    }

    public void unsubscribe() {
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            subscriptionApi.deleteByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION));
        });
    }

    public void deleteAllSubscriptions(String tenant) {
        if (tenantDeviceToken.get(tenant) != null)
            tokenApi.unsubscribe(new Token(tenantDeviceToken.get(tenant)));
        if (tenantToken.get(tenant) != null)
            tokenApi.unsubscribe(new Token(tenantToken.get(tenant)));
    }

    public void unsubscribeDevice(ManagedObjectRepresentation mor) throws SDKException {
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            subscriptionApi.deleteByFilter(
                    new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION).bySource(mor.getId()));
            if (!subscriptionApi
                    .getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator().hasNext()) {
                disconnect(subscriptionsService.getTenant(), true);
            }
        });
    }

    public void disconnect(String tenant, Boolean onlyDeviceClient) {
        // Stop WS Reconnect Thread
        if (onlyDeviceClient != null && onlyDeviceClient) {
            for (CustomWebSocketClient device_client : deviceClientMap.get(tenant).values()) {
                if (device_client != null && device_client.isOpen()) {
                    log.info("Disconnecting WS Device Client {}", device_client.toString());
                    device_client.close();

                }
            }
            deviceClientMap.put(tenant, new HashMap<>());
            // dispatcherOutboundMap.put(tenant, new HashMap<>());
        } else {
            if (this.executorService != null)
                this.executorService.shutdownNow();
            if (deviceClientMap.get(tenant) != null) {
                for (CustomWebSocketClient device_client : deviceClientMap.get(tenant).values()) {
                    if (device_client != null && device_client.isOpen()) {
                        log.info("Disconnecting WS Device Client {}", device_client.toString());
                        device_client.close();
                    }
                }
                deviceClientMap.put(tenant, new HashMap<>());
            }
            // dispatcherOutboundMap.put(tenant, new HashMap<>());
            if (tenantClientMap.get(tenant) != null) {
                CustomWebSocketClient tenant_client = tenantClientMap.get(tenant);
                if (tenant_client != null && tenant_client.isOpen()) {
                    log.info("Disconnecting WS Tenant Client {}", tenant_client.toString());
                    tenant_client.close();
                    tenantClientMap.remove(tenant);
                }
            }
        }
        c8YAgent.sendNotificationLifecycle(tenant, ConnectorStatus.DISCONNECTED, null);
    }

    public void setDeviceConnectionStatus(String tenant, int status) {
        deviceWSStatusCode.put(tenant, status);
    }

    public CustomWebSocketClient connect(String token, NotificationCallback callback) throws URISyntaxException {
        String tenant = subscriptionsService.getTenant();
        c8YAgent.sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);
        try {
            baseUrl = baseUrl.replace("http", "ws");
            URI webSocketUrl = new URI(baseUrl + WEBSOCKET_PATH + token);
            final CustomWebSocketClient client = new CustomWebSocketClient(webSocketUrl, callback);
            client.setConnectionLostTimeout(30);
            client.connect();
            // wsClientList.add(client);
            // Only start it once
            if (this.executorService == null) {
                this.executorService = Executors.newScheduledThreadPool(1);
                c8YAgent.sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);
                this.executorService.scheduleAtFixedRate(() -> {
                    reconnect();
                }, 30, 30, TimeUnit.SECONDS);
            }
            return client;
        } catch (Exception e) {
            log.error("Error on connect to WS {}", e.getLocalizedMessage());
        }
        return null;
    }
}