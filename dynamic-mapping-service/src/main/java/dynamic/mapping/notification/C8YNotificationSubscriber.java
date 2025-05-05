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

package dynamic.mapping.notification;

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
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.core.ConnectorStatus;
import dynamic.mapping.model.API;
import dynamic.mapping.model.C8YNotificationSubscription;
import dynamic.mapping.model.Device;
import dynamic.mapping.notification.websocket.CustomWebSocketClient;
import dynamic.mapping.notification.websocket.NotificationCallback;
import dynamic.mapping.processor.outbound.DispatcherOutbound;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.ArrayStack;
import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class C8YNotificationSubscriber {
    private final static String WEBSOCKET_PATH = "/notification2/consumer/?token=";
    private final static Integer TOKEN_REFRESH_INTERVAL_IN_H = 12;
    private final static Integer RECONNECT_INTERVAL_IN_SEC = 60;

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
    @Qualifier("virtThreadPool")
    private ExecutorService virtThreadPool;

    // structure: <tenant, <connectorIdentifier, asynchronousDispatcherOutbound>>
    @Getter
    private Map<String, Map<String, DispatcherOutbound>> dispatcherOutboundMaps = new HashMap<>();

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    @Value("${APP.additionalSubscriptionIdTest}")
    private String additionalSubscriptionIdTest;
    private ScheduledExecutorService executorService = null;
    private ScheduledExecutorService executorTokenService = null;
    private final String DEVICE_SUBSCRIBER = "DynamicMapperDeviceSubscriber";
    private final String DEVICE_SUBSCRIPTION = "DynamicMapperDeviceSubscription";
    private Map<String, Map<String, CustomWebSocketClient>> deviceClientMap = new HashMap<>();
    private Map<String, Integer> deviceWSStatusCode = new HashMap<>();

    private Map<String, Map<String, Mqtt3Client>> activePushConnections = new HashMap<>();

    // structure: <tenant, <connectorIdentifier, tokenSeed>>
    private Map<String, Map<String, String>> deviceTokenPerConnector = new HashMap<>();

    public void addSubscriber(String tenant, String identifier, DispatcherOutbound dispatcherOutbound) {
        Map<String, DispatcherOutbound> dispatcherOutboundMap = getDispatcherOutboundMaps().get(tenant);
        if (dispatcherOutboundMap == null) {
            dispatcherOutboundMap = new HashMap<>();
            dispatcherOutboundMap.put(identifier, dispatcherOutbound);
            getDispatcherOutboundMaps().put(tenant, dispatcherOutboundMap);
        } else {
            dispatcherOutboundMap.put(identifier, dispatcherOutbound);
        }

    }

    //
    // section 1: initializing tenant client and device client
    //

    /**
     * public void initTenantClient() {
     * // Subscribe on Tenant do get informed when devices get deleted/added
     * String tenant = subscriptionsService.getTenant();
     * log.info("Tenant {} - Initializing Operation Subscriptions...", tenant);
     * //subscribeTenantAndConnect(subscriptionsService.getTenant());
     * }
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
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        // When one subscription exists, connect...
        if (deviceSubList.size() > 0) {
            try {
                // For each dispatcher/connector create a new connection
                if (dispatcherOutboundMaps.get(tenant) != null) {
                    for (DispatcherOutbound dispatcherOutbound : dispatcherOutboundMaps.get(tenant)
                            .values()) {
                        // Only connect if connector is enabled
                        if (dispatcherOutbound.getConnectorClient().getConnectorConfiguration().isEnabled()) {
                            String tokenSeed = DEVICE_SUBSCRIBER
                                    + dispatcherOutbound.getConnectorClient().getConnectorIdentifier()
                                    + additionalSubscriptionIdTest;
                            String token = createToken(DEVICE_SUBSCRIPTION,
                                    tokenSeed);
                            deviceTokens.put(dispatcherOutbound.getConnectorClient().getConnectorIdentifier(), token);
                            CustomWebSocketClient client = connect(token, dispatcherOutbound);
                            deviceClientMap.get(tenant).put(
                                    dispatcherOutbound.getConnectorClient().getConnectorIdentifier(),
                                    client);

                        }
                    }
                }
                for (NotificationSubscriptionRepresentation subscription : deviceSubList) {
                    if (subscription != null && subscription.getSource() != null) {
                        ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                                .resolveGlobalId2ExternalId(tenant, subscription.getSource().getId(), null, null);

                        if (extId != null)
                            activatePushConnectivityStatus(tenant, extId.getExternalId());
                    } else {
                        log.warn("Tenant {} - Error initializing initDeviceClient: {}", tenant, subscription);
                    }

                }

            } catch (URISyntaxException e) {
                log.error("Tenant {} - Error connecting device subscription: {}", tenant, e.getLocalizedMessage());
            }
        }
    }

    public void notificationSubscriberReconnect(String tenant) {
        subscriptionsService.runForTenant(tenant, () -> {
            disconnect(tenant);
            initDeviceClient();
        });
    }

    public boolean isNotificationServiceAvailable(String tenant) {
        boolean notificationAvailable = subscriptionsService.callForTenant(tenant, () -> {
            try {
                subscriptionAPI.getSubscriptions().get(1);
                log.info("Tenant {} - Notification 2.0 available, proceed connecting...", tenant);
                return true;
            } catch (SDKException e) {
                log.warn("Tenant {} - Notification 2.0 Service not available, disabling Outbound Mapping", tenant);
                return false;
            }
        });
        return notificationAvailable;
    }

    public void activatePushConnectivityStatus(String tenant, String deviceId) {
        String mqttHost = baseUrl.replace("http://", "").replace(":8111", "");
        MicroserviceCredentials credentials = subscriptionsService.getCredentials(tenant).get();
        Mqtt3SimpleAuth auth = Mqtt3SimpleAuth.builder()
                .username(credentials.getTenant() + "/" + credentials.getUsername())
                .password(credentials.getPassword().getBytes(StandardCharsets.UTF_8)).build();
        Mqtt3AsyncClient client = Mqtt3Client.builder().serverHost(mqttHost).serverPort(1883)
                .identifier(deviceId).automaticReconnectWithDefaultConfig().simpleAuth(auth).buildAsync();
        client.connectWith()
                .cleanSession(true)
                .keepAlive(60)
                .send().thenRun(() -> {
                    log.info("Tenant {} - Phase I-III, connected to C8Y MQTT host {} for device {}", tenant, mqttHost,
                            deviceId);
                    client.toAsync().subscribeWith().topicFilter("s/ds").qos(MqttQos.AT_LEAST_ONCE)
                            .callback(publish -> {
                                log.debug("Tenant {} - Message received from C8Y MQTT endpoint {}", tenant,
                                        publish.getPayload().get());
                            }).send();
                }).exceptionally(throwable -> {
                    log.error("Tenant {} - Failed to connect to {} with error: ", tenant, mqttHost,
                            throwable.getMessage());
                    return null;
                });
        if (activePushConnections.get(tenant) == null)
            activePushConnections.put(tenant, new HashMap<String, Mqtt3Client>());
        activePushConnections.get(tenant).put(deviceId, client);
    }

    public void deactivatePushConnectivityStatus(String tenant, String deviceId) {
        Map<String, Mqtt3Client> clients = activePushConnections.get(tenant);
        if (clients != null) {
            if (clients.get(deviceId) != null && clients.get(deviceId).getState().isConnected())
                clients.get(deviceId).toBlocking().disconnect();
            activePushConnections.get(tenant).remove(deviceId);
        }
    }

    //
    // section 2: handle subscription on device scope
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

    public Future<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(
            ManagedObjectRepresentation mor,
            API api) throws ExecutionException, InterruptedException {
        /* Connect to all devices */
        String tenant = subscriptionsService.getTenant();
        String deviceName = mor.getName();
        log.info("Tenant {} - Creating new Subscription for Device {} with ID {}", tenant, deviceName,
                mor.getId().getValue());

        Callable<NotificationSubscriptionRepresentation> callableTask = () -> subscriptionsService.callForTenant(tenant,
                () -> {
                    Map<String, String> deviceTokens = deviceTokenPerConnector.get(tenant);
                    NotificationSubscriptionRepresentation notification = createDeviceSubscription(mor, api);
                    if (deviceWSStatusCode.get(tenant) == null
                            || (deviceWSStatusCode.get(tenant) != null && deviceWSStatusCode.get(tenant) != 200)) {
                        log.info("Tenant {} - Device Subscription not connected yet. Will connect...", tenant);

                        try {
                            // Add Dispatcher for each Connector
                            if (dispatcherOutboundMaps.get(tenant).keySet().isEmpty())
                                log.info(
                                        "Tenant {} - No Outbound dispatcher for any connector is registered, add a connector first!",
                                        tenant);

                            for (DispatcherOutbound dispatcherOutbound : dispatcherOutboundMaps.get(tenant)
                                    .values()) {
                                String tokenSeed = DEVICE_SUBSCRIBER
                                        + dispatcherOutbound.getConnectorClient().getConnectorIdentifier()
                                        + additionalSubscriptionIdTest;
                                String token = createToken(DEVICE_SUBSCRIPTION,
                                        tokenSeed);
                                log.info(
                                        "Tenant {} - Creating new Subscription for Device {} with ID {} for Connector {}",
                                        tenant, deviceName,
                                        mor.getId().getValue(),
                                        dispatcherOutbound.getConnectorClient().getConnectorName());
                                deviceTokens.put(dispatcherOutbound.getConnectorClient().getConnectorIdentifier(),
                                        token);
                                CustomWebSocketClient client = connect(token, dispatcherOutbound);
                                deviceClientMap.get(tenant).put(
                                        dispatcherOutbound.getConnectorClient().getConnectorIdentifier(),
                                        client);
                            }
                        } catch (URISyntaxException e) {
                            log.error("Tenant {} - Error on connecting to Notification Service: {}", tenant,
                                    e.getLocalizedMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    ExternalIDRepresentation extId = configurationRegistry.getC8yAgent().resolveGlobalId2ExternalId(
                            tenant,
                            mor.getId(), null, null);
                    if (extId != null)
                        activatePushConnectivityStatus(tenant, extId.getExternalId());
                    return notification;
                });
        return virtThreadPool.submit(callableTask);
    }

    public Future<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptionForDevices(
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
        String tenant = subscriptionsService.getTenant();
        Callable<List<NotificationSubscriptionRepresentation>> callableTask = () -> subscriptionsService
                .callForTenant(tenant, () -> {
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
                    return deviceSubList;
                });
        return virtThreadPool.submit(callableTask);
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
                        c8yNotificationSubscription
                                .setSubscriptionName(notificationSubscriptionRepresentation.getSubscription());
                    }
                }
            }
        });
        c8yNotificationSubscription.setDevices(devices);
        return c8yNotificationSubscription;
    }

    public void unsubscribeDeviceAndDisconnect(ManagedObjectRepresentation mor) throws SDKException {
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            try {
                getNotificationSubscriptionForDevices(mor.getId().getValue(), DEVICE_SUBSCRIPTION).get()
                        .forEach(sub -> {
                            subscriptionAPI.delete(sub);
                            log.info("Tenant {} - Subscription {} deleted for device with ID {}",
                                    subscriptionsService.getTenant(), sub.getSubscription(), mor.getId().getValue());
                        });
            } catch (Exception e) {
                log.error("Tenant {} - Error on unsubscribing device: {}", subscriptionsService.getTenant(),
                        e.getLocalizedMessage());
            }
            if (!subscriptionAPI
                    .getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION))
                    .get().allPages().iterator().hasNext()) {
                disconnect(subscriptionsService.getTenant());
            }
            ExternalIDRepresentation extId = configurationRegistry.getC8yAgent()
                    .resolveGlobalId2ExternalId(subscriptionsService.getTenant(), mor.getId(), null, null);
            // use dummy external ID
            if (extId == null) {
                extId = new ExternalIDRepresentation();
                extId.setExternalId(String.format("NOT_EXISTING_EXTERNAL_ID-%s!", mor.getId()));
            }

            deactivatePushConnectivityStatus(subscriptionsService.getTenant(), extId.getExternalId());
        });
    }

    public void unsubscribeAllDevices() {
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            subscriptionAPI.deleteByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION));
        });
    }

    public void unsubscribeDeviceSubscriber(String tenant) {
        if (deviceTokenPerConnector.get(tenant) != null) {
            for (String token : deviceTokenPerConnector.get(tenant).values()) {
                tokenApi.unsubscribe(new Token(token));
            }
            deviceTokenPerConnector.remove(tenant);
        }

    }

    public void unsubscribeDeviceSubscriberByConnector(String tenant, String connectorIdentifier) {
        if (deviceTokenPerConnector.get(tenant) != null) {
            if (deviceTokenPerConnector.get(tenant).get(connectorIdentifier) != null) {
                try {
                    tokenApi.unsubscribe(new Token(deviceTokenPerConnector.get(tenant).get(connectorIdentifier)));
                    log.info("Tenant {} - Subscriber for Connector {} successfully unsubscribed for Notification 2.0!",
                            tenant, connectorIdentifier);
                    deviceTokenPerConnector.get(tenant).remove(connectorIdentifier);
                } catch (SDKException e) {
                    log.error("Tenant {} - Could not unsubscribe subscriber for connector {}:", tenant,
                            connectorIdentifier, e);
                }
            }
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

    public void setDeviceConnectionStatus(String tenant, Integer status) {
        deviceWSStatusCode.put(tenant, status);
    }

    public Integer getDeviceConnectionStatus(String tenant) {
        return deviceWSStatusCode.get(tenant);
    }

    //
    // section 5: add, remove connectors
    //

    public void removeConnector(String tenant, String connectorIdentifier) {
        // Remove Dispatcher from list
        if (this.dispatcherOutboundMaps.get(tenant) != null)
            this.dispatcherOutboundMaps.get(tenant).remove(connectorIdentifier);
        if (this.deviceClientMap.get(tenant) != null) {
            // Test if connector was created at all and then close WS connection for
            // connector
            if (this.deviceClientMap.get(tenant).get(connectorIdentifier) != null) {
                this.deviceClientMap.get(tenant).get(connectorIdentifier).close();
                // Remove client from client Map
                this.deviceClientMap.get(tenant).remove(connectorIdentifier);
            }
        }
        if (this.dispatcherOutboundMaps.get(tenant) != null && dispatcherOutboundMaps.get(tenant).keySet().isEmpty()) {
            disconnect(tenant);
        }
    }

    public void addConnector(String tenant, String connectorIdentifier, DispatcherOutbound dispatcherOutbound) {
        Map<String, DispatcherOutbound> dispatcherOutboundMap = getDispatcherOutboundMaps().get(tenant);
        if (dispatcherOutboundMap == null) {
            dispatcherOutboundMap = new HashMap<>();
            getDispatcherOutboundMaps().put(tenant, dispatcherOutboundMap);
        }
        Map<String, String> deviceTokens = deviceTokenPerConnector.get(tenant);
        if (deviceTokens == null) {
            deviceTokens = new HashMap<>();
            deviceTokenPerConnector.put(tenant, deviceTokens);
        }
        dispatcherOutboundMap.put(connectorIdentifier, dispatcherOutbound);
    }

    //
    // section 6: handle websocket connections: connect, disconnect, reconnect, ...
    //

    public void disconnect(String tenant) {
        // Stop WS Reconnect Thread
        if (this.executorService != null) {
            this.executorService.shutdownNow();
            this.executorService = null;
        }
        if (this.executorTokenService != null) {
            this.executorTokenService.shutdownNow();
            this.executorTokenService = null;
        }
        if (deviceClientMap.get(tenant) != null) {
            for (CustomWebSocketClient device_client : deviceClientMap.get(tenant).values()) {
                if (device_client != null && device_client.isOpen()) {
                    log.info("Tenant {} - Disconnecting WS Device Client", tenant);
                    device_client.close();
                }
            }
            deviceClientMap.put(tenant, new HashMap<>());
        }
        configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.DISCONNECTED, null);
    }

    public CustomWebSocketClient connect(String token, NotificationCallback callback) throws URISyntaxException {
        String tenant = subscriptionsService.getTenant();
        configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);
        try {
            String baseUrl = this.baseUrl.replace("http", "ws");
            URI webSocketUrl = new URI(baseUrl + WEBSOCKET_PATH + token);
            final CustomWebSocketClient client = new CustomWebSocketClient(tenant, configurationRegistry, webSocketUrl,
                    callback);
            client.setConnectionLostTimeout(30);
            client.connect();
            configurationRegistry.getC8yAgent().sendNotificationLifecycle(tenant, ConnectorStatus.CONNECTING, null);

            // wsClientList.add(client);
            // Only start it once
            if (this.executorService == null) {
                this.executorService = Executors.newScheduledThreadPool(1);
                this.executorService.scheduleAtFixedRate(() -> {
                    reconnect();
                }, 120, RECONNECT_INTERVAL_IN_SEC, TimeUnit.SECONDS);
            }

            if (this.executorTokenService == null) {
                this.executorTokenService = Executors.newScheduledThreadPool(1);
                this.executorTokenService.scheduleAtFixedRate(() -> {
                    refreshTokens();
                }, TOKEN_REFRESH_INTERVAL_IN_H, TOKEN_REFRESH_INTERVAL_IN_H, TimeUnit.HOURS);
            }

            return client;
        } catch (Exception e) {
            log.error("Tenant {} - Error on connect to WS {}", tenant, e.getLocalizedMessage());
        }
        return null;
    }

    /* Needed for unsubscribing the client */
    // @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void refreshTokens() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            for (String connectorId : deviceTokenPerConnector.get(tenant).keySet()) {
                try {

                    String token = deviceTokenPerConnector.get(tenant).get(connectorId);
                    String newToken = tokenApi.refresh(new Token(token)).getTokenString();
                    log.info("Tenant {} - Successfully refreshed token for connector {}", tenant, connectorId);
                    deviceTokenPerConnector.get(tenant).put(connectorId, newToken);
                } catch (IllegalArgumentException e) {
                    log.warn("Tenant {} - Could not refresh token for connector {}. Reason: {}", tenant, connectorId,
                            e.getMessage());
                }
            }

        });

    }

    public void reconnect() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                if (deviceClientMap.get(tenant) != null) {
                    for (CustomWebSocketClient deviceClient : deviceClientMap.get(tenant).values()) {
                        if (deviceClient != null) {
                            if (!deviceClient.isOpen()) {
                                if (deviceWSStatusCode.get(tenant) != null && deviceWSStatusCode.get(tenant) == 401
                                        || deviceClient.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                                    log.info("Tenant {} - Trying to reconnect ws device client... ", tenant);
                                    initDeviceClient();
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

    public List<Device> findAllRelatedDevicesByMO(ManagedObjectRepresentation mor, List<Device> devices,
            boolean isChildDevice) {
        if (devices == null)
            devices = new ArrayList<>();
        String tenant = subscriptionsService.getTenant();
        if (mor.hasProperty("c8y_IsDevice") || isChildDevice) {
            // MO is already a device - just check for child devices

            Device device = new Device();
            device.setId(mor.getId().getValue());
            device.setName(mor.getName());
            if (!devices.contains(device)) {
                log.info("Tenant {} - Adding child Device {} to be subscribed to", tenant, mor.getId());
                devices.add(device);
            }
            Iterator<ManagedObjectReferenceRepresentation> childDeviceIt = mor.getChildDevices().iterator();
            while (childDeviceIt.hasNext()) {
                ManagedObjectRepresentation currentChild = childDeviceIt.next().getManagedObject();
                currentChild = configurationRegistry.getC8yAgent().getManagedObjectForId(tenant,
                        currentChild.getId().getValue());
                devices = findAllRelatedDevicesByMO(currentChild, devices, true);
            }
        } else if (mor.hasProperty("c8y_IsDeviceGroup")) {
            // MO is a group check for subgroups or child devices
            Iterator<ManagedObjectReferenceRepresentation> childAssetIt = mor.getChildAssets().iterator();
            while (childAssetIt.hasNext()) {
                ManagedObjectRepresentation currentChild = childAssetIt.next().getManagedObject();
                currentChild = configurationRegistry.getC8yAgent().getManagedObjectForId(tenant,
                        currentChild.getId().getValue());
                devices = findAllRelatedDevicesByMO(currentChild, devices, false);
            }
        }
        return devices;
    }
}