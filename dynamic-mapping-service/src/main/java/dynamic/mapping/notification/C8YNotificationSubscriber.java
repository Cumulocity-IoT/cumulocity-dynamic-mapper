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
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.model.C8YNotificationSubscription;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class C8YNotificationSubscriber {
    private final static String WEBSOCKET_PATH = "/notification2/consumer/?token=";

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

    private Map<String, Map<String, CustomWebSocketClient>> deviceClientMap = new HashMap<>();
    private Map<String, CustomWebSocketClient> tenantClientMap = new HashMap<>();
    private Map<String, Integer> tenantWSStatusCode = new HashMap<>();
    private Map<String, Integer> deviceWSStatusCode = new HashMap<>();

     // structure: <tenant, <connectorIdent, tokenSeed>>
    private Map<String, Map<String, String>> deviceTokenPerConnector = new HashMap<>();
    private Map<String, String> tenantToken = new HashMap<>();


    public void addSubscriber(String tenant, String ident, AsynchronousDispatcherOutbound dispatcherOutbound) {
        Map<String, AsynchronousDispatcherOutbound> dispatcherOutboundMap = getDispatcherOutboundMaps().get(tenant);
        if (dispatcherOutboundMap == null) {
            dispatcherOutboundMap = new HashMap<>();
            dispatcherOutboundMap.put(ident, dispatcherOutbound);
            getDispatcherOutboundMaps().put(tenant, dispatcherOutboundMap);
        } else {
            dispatcherOutboundMap.put(ident, dispatcherOutbound);
        }

    }

    //
    // section 1: initializing tenant client and device client
    //
    public void initTenantClient() {
        // Subscribe on Tenant do get informed when devices get deleted/added
        String tenant = subscriptionsService.getTenant();
        log.info("Tenant {} - Initializing Operation Subscriptions...", tenant);
        subscribeTenantAndConnect(subscriptionsService.getTenant());
    }
     **/
    public void initDeviceClient() {
        String tenant = subscriptionsService.getTenant();
        Map<String, String> deviceTokens = deviceTokenPerConnector.get(tenant);
        if (deviceClientMap.get(tenant) == null)
            deviceClientMap.put(tenant, new HashMap<>());
        List<NotificationSubscriptionRepresentation> deviceSubList = null;

        try {
            // Getting existing subscriptions
            deviceSubList = getNotificationSubscriptionForDevices(null, DEVICE_SUBSCRIPTION).get();
            log.info("Tenant {} - Subscribing to devices {}", tenant, deviceSubList);
        } catch (InterruptedException | ExecutionException e  ) {
            throw new RuntimeException(e);
        }
        // When one subscription exists, connect...
        if (deviceSubList.size() > 0) {
            try {
                // For each dispatcher/connector create a new connection
                if (dispatcherOutboundMaps.get(tenant) != null) {
                    for (AsynchronousDispatcherOutbound dispatcherOutbound : dispatcherOutboundMaps.get(tenant).values()) {
                      String tokenSeed = DEVICE_SUBSCRIBER
                              + dispatcherOutbound.getConnectorClient().getConnectorIdent()
                              + additionalSubscriptionIdTest;
                      String token = createToken(DEVICE_SUBSCRIPTION,
                              tokenSeed);
                      deviceTokens.put(dispatcherOutbound.getConnectorClient().getConnectorIdent(), tokenSeed);
                      CustomWebSocketClient client = connect(token, dispatcherOutbound);
                      deviceClientMap.get(tenant).put(dispatcherOutbound.getConnectorClient().getConnectorIdent(),
                              client);

                  }
                }
            } catch (URISyntaxException e) {
                log.error("Tenant {} - Error connecting device subscription: {}", tenant, e.getLocalizedMessage());
            }
        }
    }

    public void notificationSubscriberReconnect(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            disconnect(tenant, false);
            // notificationSubscriber.init();
            //initTenantClient();
            initDeviceClient();
        });
    }

    //
    // section 2: handle subscription on tenant scope
    //

    // Not needed anymore
    /**
    public void subscribeTenantAndConnect(String tenant) {
        log.info("Tenant {} - Creating new Tenant Subscription", tenant);
        NotificationSubscriptionRepresentation notificationSubscriptionRepresentation = createTenantSubscription();
        String tenantToken = createToken(notificationSubscriptionRepresentation.getSubscription(),
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
                        String tenant = notification.getTenantFromNotificationHeaders();
                        ManagedObjectRepresentation mor = JSONBase.getJSONParser()
                                .parse(ManagedObjectRepresentation.class, notification.getMessage());
                        if (notification.getNotificationHeaders().contains("DELETE")) {
                            log.info("Tenant {} - Device deleted with name {} and id {}", tenant, mor.getName(),
                                    mor.getId().getValue());
                            final ManagedObjectRepresentation morRetrieved = configurationRegistry.getC8yAgent()
                                    .getManagedObjectForId(tenant, mor.getId().getValue());
                            if (morRetrieved != null) {
                                unsubscribeDeviceAndDisconnect(morRetrieved);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Tenant {} - Error on processing Tenant Notification {}: {}", tenant, notification.getMessage(),
                                e.getLocalizedMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Tenant {} - We got an exception: {}", tenant, t);
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
     **/

    public NotificationSubscriptionRepresentation createTenantSubscription() {
        final String subscriptionName = TENANT_SUBSCRIPTION;
        String tenant = subscriptionsService.getTenant();
        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                .getSubscriptionsByFilter(
                        new NotificationSubscriptionFilter().bySubscription(subscriptionName).byContext("tenant"))
                .get().allPages().iterator();
        NotificationSubscriptionRepresentation notificationSubscriptionRepresentation = null;
        while (subIt.hasNext()) {
            notificationSubscriptionRepresentation = subIt.next();
            // Needed for 1015 releases
            if (TENANT_SUBSCRIPTION.equals(notificationSubscriptionRepresentation.getSubscription())) {
                log.info("Tenant {} - Subscription with ID {} already exists.", tenant,
                        notificationSubscriptionRepresentation.getId().getValue());
                return notificationSubscriptionRepresentation;
            }
        }
        // log.info("Tenant {} - Subscription does not exist. Creating ...", tenant);
        notificationSubscriptionRepresentation = new NotificationSubscriptionRepresentation();
        final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
        filterRepresentation.setApis(List.of("managedobjects"));
        notificationSubscriptionRepresentation.setContext("tenant");
        notificationSubscriptionRepresentation.setSubscription(subscriptionName);
        notificationSubscriptionRepresentation.setSubscriptionFilter(filterRepresentation);
        notificationSubscriptionRepresentation = subscriptionAPI.subscribe(notificationSubscriptionRepresentation);
        return notificationSubscriptionRepresentation;
    }

    public void unsubscribeTenantSubscriber(String tenant) {
        if (tenantToken.get(tenant) != null)
            tokenApi.unsubscribe(new Token(tenantToken.get(tenant)));
    }

    //
    // section 3: handle subscription on device scope
    //

    public NotificationSubscriptionRepresentation createDeviceSubscription(ManagedObjectRepresentation mor, API api) {
        String tenant = subscriptionsService.getTenant();
        final String subscriptionName = DEVICE_SUBSCRIPTION;

        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                .getSubscriptionsByFilter(
                        new NotificationSubscriptionFilter().bySubscription(subscriptionName).bySource(mor.getId()))
                .get().allPages().iterator();
        NotificationSubscriptionRepresentation notificationSubscriptionRepresentation = null;
        while (subIt.hasNext()) {
            notificationSubscriptionRepresentation = subIt.next();
            if (DEVICE_SUBSCRIPTION.equals(notificationSubscriptionRepresentation.getSubscription())) {
                log.info("Tenant {} - Subscription with ID {} and Source {} already exists.", tenant,
                        notificationSubscriptionRepresentation.getId().getValue(),
                        notificationSubscriptionRepresentation.getSource().getId().getValue());
                return notificationSubscriptionRepresentation;
            }
        }

        notificationSubscriptionRepresentation = new NotificationSubscriptionRepresentation();
        notificationSubscriptionRepresentation.setSource(mor);
        final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
        // filterRepresentation.setApis(List.of("operations"));
        filterRepresentation.setApis(List.of(api.notificationFilter));
        notificationSubscriptionRepresentation.setContext("mo");
        notificationSubscriptionRepresentation.setSubscription(subscriptionName);
        notificationSubscriptionRepresentation.setSubscriptionFilter(filterRepresentation);
        notificationSubscriptionRepresentation = subscriptionAPI.subscribe(notificationSubscriptionRepresentation);
        return notificationSubscriptionRepresentation;
    }

    public CompletableFuture<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(
            ManagedObjectRepresentation mor,
            API api) throws ExecutionException, InterruptedException {
        /* Connect to all devices */
        String tenant = subscriptionsService.getTenant();
        String deviceName = mor.getName();
        log.info("Tenant {} - Creating new Subscription for Device {} with ID {}", tenant, deviceName,
                mor.getId().getValue());
        CompletableFuture<NotificationSubscriptionRepresentation> notificationFut = new CompletableFuture<NotificationSubscriptionRepresentation>();
        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, String> deviceTokens = deviceTokenPerConnector.get(tenant);
            NotificationSubscriptionRepresentation notification = createDeviceSubscription(mor, api);
            notificationFut.complete(notification);
            if (deviceWSStatusCode.get(tenant) == null
                    || (deviceWSStatusCode.get(tenant) != null && deviceWSStatusCode.get(tenant) != 200)) {
                log.info("Tenant {} - Device Subscription not connected yet. Will connect...", tenant);

                try {
                    // Add Dispatcher for each Connector
                    if (dispatcherOutboundMaps.get(tenant).keySet().isEmpty())
                        log.info(
                                "Tenant {} - No Outbound dispatcher for any connector is registered, add a connector first!",
                                tenant);

                    for (AsynchronousDispatcherOutbound dispatcherOutbound : dispatcherOutboundMaps.get(tenant)
                            .values()) {
                        String tokenSeed = DEVICE_SUBSCRIBER
                                + dispatcherOutbound.getConnectorClient().getConnectorIdent()
                                + additionalSubscriptionIdTest;
                        String token = createToken(DEVICE_SUBSCRIPTION,
                                tokenSeed);
                        deviceTokens.put(dispatcherOutbound.getConnectorClient().getConnectorIdent(), tokenSeed);
                        CustomWebSocketClient client = connect(token, dispatcherOutbound);
                        deviceClientMap.get(tenant).put(dispatcherOutbound.getConnectorClient().getConnectorIdent(),
                                client);
                    }

                } catch (URISyntaxException e) {
                    log.error("Tenant {} - Error on connecting to Notification Service: {}", tenant,
                            e.getLocalizedMessage());
                    throw new RuntimeException(e);
                }
            }
        });
        return notificationFut;
    }

    public CompletableFuture<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDevices(
            String deviceId,
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
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                    .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();
            NotificationSubscriptionRepresentation notificationSubscriptionRepresentation = null;
            while (subIt.hasNext()) {
                notificationSubscriptionRepresentation = subIt.next();
                if (!"tenant".equals(notificationSubscriptionRepresentation.getContext())) {
                    log.info("Tenant {} - Subscription with ID {} retrieved", tenant,
                            notificationSubscriptionRepresentation.getId().getValue());
                    deviceSubList.add(notificationSubscriptionRepresentation);
                }
            }
            deviceSubListFut.complete(deviceSubList);
        });

        return deviceSubListFut;
    }

    public C8YNotificationSubscription getDeviceSubscriptions(String tenant, String deviceId,
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
        C8YNotificationSubscription c8yNotificationSubscription = new C8YNotificationSubscription();
        List<Device> devices = new ArrayStack();
        subscriptionsService.runForTenant(tenant, () -> {
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionAPI
                    .getSubscriptionsByFilter(finalFilter).get().allPages().iterator();
            NotificationSubscriptionRepresentation notificationSubscriptionRepresentation = null;
            while (subIt.hasNext()) {
                notificationSubscriptionRepresentation = subIt.next();
                if (!"tenant".equals(notificationSubscriptionRepresentation.getContext())) {
                    log.debug("Tenant {} - Subscription with ID {} retrieved", tenant,
                            notificationSubscriptionRepresentation.getId().getValue());
                    Device device = new Device();
                    device.setId(notificationSubscriptionRepresentation.getSource().getId().getValue());
                    ManagedObjectRepresentation mor = configurationRegistry.getC8yAgent().getManagedObjectForId(tenant,
                            notificationSubscriptionRepresentation.getSource().getId().getValue());
                    if (mor != null)
                        device.setName(mor.getName());
                    else
                        log.warn("Tenant {} - Device with ID {} does not exists!", tenant,
                                notificationSubscriptionRepresentation.getSource().getId().getValue());
                    devices.add(device);
                    if (notificationSubscriptionRepresentation.getSubscriptionFilter().getApis().size() > 0) {
                        API api = API.fromString(
                                notificationSubscriptionRepresentation.getSubscriptionFilter().getApis().get(0));
                        c8yNotificationSubscription.setApi(api);
                    }
                }
            }
        });
        c8yNotificationSubscription.setDevices(devices);
        return c8yNotificationSubscription;
    }

    public void unsubscribeDeviceAndDisconnect(ManagedObjectRepresentation mor) throws SDKException {
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            subscriptionAPI.deleteByFilter(
                    new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION).bySource(mor.getId()));
            if (!subscriptionAPI
                    .getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator().hasNext()) {
                disconnect(subscriptionsService.getTenant(), true);
            }
        });
    }

    public void unsubscribeAllDevices() {
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            subscriptionAPI.deleteByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION));
        });
    }

    public void unsubscribeDeviceSubscriber(String tenant) {
        if (deviceTokenPerConnector.get(tenant) != null)
            for (String token : deviceTokenPerConnector.get(tenant).values()) {
                tokenApi.unsubscribe(new Token(token));
            }
    }

    //
    // section 4: general helper methods
    //

    public String createToken(String subscription, String subscriber) {
        final NotificationTokenRequestRepresentation tokenRequestRepresentation = new NotificationTokenRequestRepresentation(
                subscriber,
                subscription,
                1440,
                false);
        return tokenApi.create(tokenRequestRepresentation).getTokenString();
    }

    public void setDeviceConnectionStatus(String tenant, int status) {
        deviceWSStatusCode.put(tenant, status);
    }

    //
    // section 5: add, remove connectors
    //

    public void removeConnector(String tenant, String connectorIdent) {
        // Remove Dispatcher from list
        this.dispatcherOutboundMaps.get(tenant).remove(connectorIdent);
        // Close WS connection for connector
        this.deviceClientMap.get(tenant).get(connectorIdent).close();
        // Remove client from client Map
        this.deviceClientMap.get(tenant).remove(connectorIdent);
        if (dispatcherOutboundMaps.get(tenant).keySet().isEmpty())
            disconnect(tenant, false);
    }

    public void addConnector(String tenant, String connectorIdent, AsynchronousDispatcherOutbound dispatcherOutbound) {
        Map<String, AsynchronousDispatcherOutbound> dispatcherOutboundMap = getDispatcherOutboundMaps().get(tenant);
        if (dispatcherOutboundMap == null) {
            dispatcherOutboundMap = new HashMap<>();
            getDispatcherOutboundMaps().put(tenant, dispatcherOutboundMap);
        }
        Map<String, String> deviceTokens = deviceTokenPerConnector.get(tenant);
        if (deviceTokens == null) {
            deviceTokens = new HashMap<>();
            deviceTokenPerConnector.put(tenant, deviceTokens);
        }
        dispatcherOutboundMap.put(connectorIdent, dispatcherOutbound);
    }

    //
    // section 6: handle websocket connections: connect, disconnect, reconnect, ...
    //

    public void disconnect(String tenant, Boolean onlyDeviceClient) {
        // Stop WS Reconnect Thread
        if (onlyDeviceClient != null && onlyDeviceClient) {
            for (CustomWebSocketClient device_client : deviceClientMap.get(tenant).values()) {
                if (device_client != null && device_client.isOpen()) {
                    log.info("Tenant {} - Disconnecting WS Device Client {}", tenant, device_client.toString());
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
                        log.info("Tenant {} - Disconnecting WS Device Client {}", tenant, device_client.toString());
                        device_client.close();
                    }
                }
                deviceClientMap.put(tenant, new HashMap<>());
            }
            // dispatcherOutboundMap.put(tenant, new HashMap<>());
            if (tenantClientMap.get(tenant) != null) {
                CustomWebSocketClient tenant_client = tenantClientMap.get(tenant);
                if (tenant_client != null && tenant_client.isOpen()) {
                    log.info("Tenant {} - Disconnecting WS Tenant Client {}", tenant, tenant_client.toString());
                    tenant_client.close();
                    tenantClientMap.remove(tenant);
                }
            }
        }
        configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.DISCONNECTED, null);
    }

    public CustomWebSocketClient connect(String token, NotificationCallback callback) throws URISyntaxException {
        String tenant = subscriptionsService.getTenant();
        configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);
        try {
            baseUrl = baseUrl.replace("http", "ws");
            URI webSocketUrl = new URI(baseUrl + WEBSOCKET_PATH + token);
            final CustomWebSocketClient client = new CustomWebSocketClient(webSocketUrl, callback, tenant);
            client.setConnectionLostTimeout(30);
            client.connect();
            // wsClientList.add(client);
            // Only start it once
            if (this.executorService == null) {
                this.executorService = Executors.newScheduledThreadPool(1);
                configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);
                this.executorService.scheduleAtFixedRate(() -> {
                    reconnect();
                }, 30, 30, TimeUnit.SECONDS);
            }
            return client;
        } catch (Exception e) {
            log.error("Tenant {} - Error on connect to WS {}", tenant, e.getLocalizedMessage());
        }
        return null;
    }

    // @Scheduled(fixedRate = 30000, initialDelay = 30000)
    public void reconnect() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                if (tenantClientMap != null) {
                    for (String currentTenant : tenantClientMap.keySet()) {
                        if (currentTenant.equals(tenant)) {
                            CustomWebSocketClient tenantClient = tenantClientMap.get(currentTenant);
                            if (tenantClient != null) {
                                log.debug("Tenant {} - Running ws reconnect... tenant client: {}, tenant_isOpen: {}",
                                        tenant, tenantClient,
                                        tenantClient.isOpen());
                                if (!tenantClient.isOpen()) {
                                    if (tenantWSStatusCode.get(tenant) == 401
                                            || tenantClient.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                                        log.info("Tenant {} - Trying to reconnect ws tenant client... ", tenant);
                                        //subscriptionsService.runForEachTenant(() -> {
                                        //    initTenantClient();
                                        //});
                                    } else if (tenantClient.getReadyState().equals(ReadyState.CLOSING)
                                            || tenantClient.getReadyState().equals(ReadyState.CLOSED)) {
                                        log.info("Tenant {} - Trying to reconnect ws tenant client... ", tenant);
                                        tenantClient.reconnect();
                                    }
                                }
                            }
                        }
                    }
                }
                if (deviceClientMap.get(tenant) != null) {
                    for (CustomWebSocketClient deviceClient : deviceClientMap.get(tenant).values()) {
                        if (deviceClient != null) {
                            if (!deviceClient.isOpen()) {
                                if (deviceWSStatusCode.get(tenant) == 401
                                        || deviceClient.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                                    log.info("Tenant {} - Trying to reconnect ws device client... ", tenant);
                                    subscriptionsService.runForEachTenant(() -> {
                                        initDeviceClient();
                                    });
                                } else if (deviceClient.getReadyState().equals(ReadyState.CLOSING)
                                        || deviceClient.getReadyState().equals(ReadyState.CLOSED)) {
                                    log.info("Tenant {} - Trying to reconnect ws device client... ", tenant);
                                    deviceClient.reconnect();
                                }
                            }
                        }
                    }
                }
                configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTED, null);
            } catch (Exception e) {
                log.error("Tenant {} - Error reconnecting to Notification Service: {}", tenant,
                        e.getLocalizedMessage());
                configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.FAILED,
                        e.getLocalizedMessage());
                e.printStackTrace();
            }
        });
    }
}