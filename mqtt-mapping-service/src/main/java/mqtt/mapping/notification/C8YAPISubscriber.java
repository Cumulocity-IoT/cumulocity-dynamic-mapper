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

package mqtt.mapping.notification;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionFilterRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationTokenRequestRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.TokenApi;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.model.API;
import mqtt.mapping.model.C8YAPISubscription;
import mqtt.mapping.model.Device;
import mqtt.mapping.notification.websocket.CustomWebSocketClient;
import mqtt.mapping.notification.websocket.Notification;
import mqtt.mapping.notification.websocket.NotificationCallback;
import mqtt.mapping.processor.outbound.AsynchronousDispatcherOutbound;
import org.apache.commons.collections.ArrayStack;
import org.java_websocket.enums.ReadyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

import static java.lang.Boolean.FALSE;

@Service
public class C8YAPISubscriber {
    private final static String WEBSOCKET_PATH = "/notification2/consumer/?token=";
    private static final Logger logger = LoggerFactory.getLogger(C8YAPISubscriber.class);

    @Autowired
    private TokenApi tokenApi;

    @Autowired
    private NotificationSubscriptionApi subscriptionApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private C8YAgent c8YAgent;

    @Autowired
    private AsynchronousDispatcherOutbound dispatcherOutbound;

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    @Value("${APP.additionalSubscriptionIdTest}")
    private String additionalSubscriptionIdTest;

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private static ScheduledFuture<?> executorFuture = null;

    private final String DEVICE_SUBSCRIBER = "MQTTOutboundMapperDeviceSubscriber";
    private final String DEVICE_SUBSCRIPTION = "MQTTOutboundMapperDeviceSubscription";
    private final String TENANT_SUBSCRIBER = "MQTTOutboundMapperTenantSubscriber";
    private final String TENANT_SUBSCRIPTION = "MQTTOutboundMapperTenantSubscription";

    List<CustomWebSocketClient> wsClientList = new ArrayList<>();

    private CustomWebSocketClient device_client;
    private CustomWebSocketClient tenant_client;
    private int tenantWSStatusCode = 0;
    private int deviceWSStatusCode = 0;

    public void init() {
       initTenantClient();
       initDeviceClient();
    }

    public void initTenantClient() {
        // Subscribe on Tenant do get informed when devices get deleted/added
        logger.info("Initializing Operation Subscriptions...");
        subscribeTenant(subscriptionsService.getTenant());
    }

    public void initDeviceClient() {
        List<NotificationSubscriptionRepresentation> deviceSubList = null;
        try {
            deviceSubList = getNotificationSubscriptions(null, DEVICE_SUBSCRIPTION).get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        // When one subscription exsits, connect
        if (deviceSubList.size() > 0) {
            String token = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER + additionalSubscriptionIdTest);
            try {
                device_client = connect(token, dispatcherOutbound);
            } catch (URISyntaxException e) {
                logger.error("Error connecting device subscription: {}", e.getLocalizedMessage());
            }
        }
    }

    public void subscribeAllDevices() {
        InventoryFilter filter = new InventoryFilter();
        filter.byFragmentType("c8y_IsDevice");
        Iterator<ManagedObjectRepresentation> deviceIt = inventoryApi.getManagedObjectsByFilter(filter).get().allPages().iterator();
        NotificationSubscriptionRepresentation notRep = null;
        while (deviceIt.hasNext()) {
            ManagedObjectRepresentation mor = deviceIt.next();
            logger.info("Found device " + mor.getName());
            try {
                notRep = subscribeDevice(mor, API.OPERATION).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        if (notRep != null) {
            String deviceToken = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER + additionalSubscriptionIdTest);

            try {
                device_client = connect(deviceToken, dispatcherOutbound);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }

    public CompletableFuture<NotificationSubscriptionRepresentation> subscribeDevice(ManagedObjectRepresentation mor, API api) throws ExecutionException, InterruptedException {
        /* Connect to all devices */
        String deviceName = mor.getName();
        logger.info("Creating new Subscription for Device {} with ID {}", deviceName, mor.getId().getValue());
        CompletableFuture<NotificationSubscriptionRepresentation> notificationFut = new CompletableFuture<NotificationSubscriptionRepresentation>();
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            NotificationSubscriptionRepresentation notification = createDeviceSubscription(mor, api);
            notificationFut.complete(notification);
            if (deviceWSStatusCode != 200) {
                logger.info("Device Subscription not connected yet. Will connect...");
                String token = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER + additionalSubscriptionIdTest);
                try {
                    device_client = connect(token, dispatcherOutbound);
                } catch (URISyntaxException e) {
                    logger.error("Error on connecting to Notification Service: {}", e.getLocalizedMessage());
                    throw new RuntimeException(e);
                }
            }
        });
        return notificationFut;
    }

    public C8YAPISubscription getDeviceSubscriptions(String deviceId, String deviceSubscription) {
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
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(finalFilter).get().allPages().iterator();
            NotificationSubscriptionRepresentation notification = null;
            while (subIt.hasNext()) {
                notification = subIt.next();
                if (!"tenant".equals(notification.getContext())) {
                    logger.debug("Subscription with ID {} retrieved", notification.getId().getValue());
                    Device device = new Device();
                    device.setId(notification.getSource().getId().getValue());
                    ManagedObjectRepresentation mor = c8YAgent.getManagedObjectForId(notification.getSource().getId().getValue());
                    if (mor != null)
                        device.setName(mor.getName());
                    else
                        logger.warn("Device with ID {} does not exists!", notification.getSource().getId().getValue());
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

    public CompletableFuture<List<NotificationSubscriptionRepresentation>> getNotificationSubscriptions(String deviceId, String deviceSubscription) {
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
            List<NotificationSubscriptionRepresentation> deviceSubList = new ArrayList<>();
            Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(finalFilter).get().allPages().iterator();
            NotificationSubscriptionRepresentation notification = null;
            while (subIt.hasNext()) {
                notification = subIt.next();
                if (!"tenant".equals(notification.getContext())) {
                    logger.info("Subscription with ID {} retrieved", notification.getId().getValue());
                    deviceSubList.add(notification);
                }
            }
            deviceSubListFut.complete(deviceSubList);
        });

        return deviceSubListFut;
    }

    public void subscribeTenant(String tenant) {
        logger.info("Creating new Subscription for Tenant " + tenant);
        NotificationSubscriptionRepresentation notification = createTenantSubscription();
        String tenantToken = createToken(notification.getSubscription(), TENANT_SUBSCRIBER + additionalSubscriptionIdTest);

        try {
            NotificationCallback tenantCallback = new NotificationCallback() {

                @Override
                public void onOpen(URI uri) {
                    logger.info("Connected to Cumulocity notification service over WebSocket " + uri);
                    tenantWSStatusCode = 200;
                }

                @Override
                public void onNotification(Notification notification) {
                    try {
                        logger.info("Tenant Notification received: <{}>", notification.getMessage());
                        logger.info("Notification headers: <{}>", notification.getNotificationHeaders());

                        ManagedObjectRepresentation mor = JSONBase.getJSONParser().parse(ManagedObjectRepresentation.class, notification.getMessage());
                        /*
                        if (notification.getNotificationHeaders().contains("CREATE")) {
                            logger.info("New Device created with name {} and id {}", mor.getName(), mor.getId().getValue());
                            final ManagedObjectRepresentation morRetrieved = c8YAgent.getManagedObjectForId(mor.getId().getValue());
                            if (morRetrieved != null) {
                                subscribeDevice(morRetrieved);
                            }
                        }
                         */
                        if (notification.getNotificationHeaders().contains("DELETE")) {

                            logger.info("Device deleted with name {} and id {}", mor.getName(), mor.getId().getValue());
                            final ManagedObjectRepresentation morRetrieved = c8YAgent.getManagedObjectForId(mor.getId().getValue());
                            if (morRetrieved != null) {
                                unsubscribeDevice(morRetrieved);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error on processing Tenant Notification {}: {}", notification, e.getLocalizedMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("We got an exception: " + t);
                }

                @Override
                public void onClose(int statusCode, String reason) {
                    logger.info("Tenant ws connection closed.");
                    if (reason.contains("401"))
                        tenantWSStatusCode = 401;
                    else
                        tenantWSStatusCode = 0;
                }
            };
            tenant_client = connect(tenantToken, tenantCallback);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }

    public void reconnect(MicroserviceSubscriptionsService subscriptionsService) {
        try {
            if (tenant_client != null) {

                if (!tenant_client.isOpen()) {
                    if (tenantWSStatusCode == 401 || tenant_client.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                            logger.info("Trying to reconnect ws tenant client... ");
                            initTenantClient();
                        });
                    } else if (tenant_client.getReadyState().equals(ReadyState.CLOSING) || tenant_client.getReadyState().equals(ReadyState.CLOSED)) {
                        logger.info("Trying to reconnect ws tenant client... ");
                        tenant_client.reconnect();
                    }
                }
            }
            if (device_client != null) {
                if (!device_client.isOpen()) {
                    if (deviceWSStatusCode == 401 || device_client.getReadyState().equals(ReadyState.NOT_YET_CONNECTED)) {
                        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                            logger.info("Trying to reconnect ws device client... ");
                            initDeviceClient();
                        });
                    } else if (device_client.getReadyState().equals(ReadyState.CLOSING) || device_client.getReadyState().equals(ReadyState.CLOSED)) {
                        logger.info("Trying to reconnect ws device client... ");
                        device_client.reconnect();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error reconnecting to Notification Service: {}", e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    public NotificationSubscriptionRepresentation createTenantSubscription() {

        final String subscriptionName = TENANT_SUBSCRIPTION;
        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(subscriptionName).byContext("tenant")).get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        while (subIt.hasNext()) {
            notification = subIt.next();
            //Needed for 1015 releases
            if (TENANT_SUBSCRIPTION.equals(notification.getSubscription())) {
                logger.info("Subscription with ID {} already exists.", notification.getId().getValue());
                return notification;
            }
        }
        //logger.info("Subscription does not exist. Creating ...");
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
        final GId sourceId = GId.asGId(mor.getId());
        final String subscriptionName = DEVICE_SUBSCRIPTION;

        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(subscriptionName).bySource(mor.getId())).get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        while (subIt.hasNext()) {
            notification = subIt.next();
            if (DEVICE_SUBSCRIPTION.equals(notification.getSubscription())) {
                logger.info("Subscription with ID {} and Source {} already exists.", notification.getId().getValue(), notification.getSource().getId().getValue());
                return notification;
            }
        }

        //logger.info("Subscription does not exist. Creating ...");
        notification = new NotificationSubscriptionRepresentation();
        notification.setSource(mor);
        final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
        //filterRepresentation.setApis(List.of("operations"));
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
        Iterator<NotificationSubscriptionRepresentation> deviceSubIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION)).get().allPages().iterator();
        while (deviceSubIt.hasNext()) {
            NotificationSubscriptionRepresentation notification = deviceSubIt.next();
            logger.info("Deleting Subscription with ID {}", notification.getId().getValue());
            //FIXME Issues bySubscription Filter does not work yet
            if (DEVICE_SUBSCRIPTION.equals(notification.getSubscription())) {
                subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                    subscriptionApi.delete(notification);
                });
            }

        }
        Iterator<NotificationSubscriptionRepresentation> tenantSubIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(TENANT_SUBSCRIPTION)).get().allPages().iterator();
        while (tenantSubIt.hasNext()) {
            NotificationSubscriptionRepresentation notification = tenantSubIt.next();
            logger.info("Deleting Subscription with ID {}", notification.getId().getValue());
            subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                subscriptionApi.delete(notification);
            });
        }
    }

    public boolean unsubscribeDevice(ManagedObjectRepresentation mor) throws SDKException {
        //Iterator<NotificationSubscriptionRepresentation> deviceSubIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION).bySource(mor.getId())).get().allPages().iterator();
        Iterator<NotificationSubscriptionRepresentation> deviceSubIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION)).get().allPages().iterator();
        int subsFound = 0;
        List<Boolean> deviceDeleted = new ArrayList<>();
        while (deviceSubIt.hasNext()) {
            NotificationSubscriptionRepresentation notification = deviceSubIt.next();

            //FIXME Issues bySubscription Filter does not work yet
            if (DEVICE_SUBSCRIPTION.equals(notification.getSubscription())) {
                subsFound++;
                if (mor.getId().getValue().equals(notification.getSource().getId().getValue())) {
                    subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                        logger.info("Deleting Subscription {} for Device {} with ID {}", notification.getId().getValue(), mor.getName(), mor.getId().getValue());
                        subscriptionApi.delete(notification);
                        deviceDeleted.add(true);
                    });

                }
            }
        }

        if (deviceDeleted.size() > 0 && deviceDeleted.get(0)) {
            if (subsFound == 1)
                disconnect(device_client);
            return true;
        }

        return false;
    }

    public void disconnect(CustomWebSocketClient client) {
        //Stop WS Reconnect Thread
        if (executorFuture != null) {
            executorFuture.cancel(true);
            executorFuture = null;
        }
        if (client != null) {
            logger.info("Disconnecting WS Client {}", client.toString());
            client.close();
        } else {
            if (device_client != null && device_client.isOpen()) {
                logger.info("Disconnecting WS Device Client {}", device_client.toString());
                device_client.close();
            }
            if (tenant_client != null && tenant_client.isOpen()) {
                logger.info("Disconnecting WS Tenant Client {}", tenant_client.toString());
                tenant_client.close();
            }
        }
    }

    public void setDeviceConnectionStatus(int status) {
        deviceWSStatusCode = status;
    }

    public CustomWebSocketClient connect(String token, NotificationCallback callback) throws URISyntaxException {
        try {
            baseUrl = baseUrl.replace("http", "ws");
            URI webSocketUrl = new URI(baseUrl + WEBSOCKET_PATH + token);
            final CustomWebSocketClient client = new CustomWebSocketClient(webSocketUrl, callback);
            client.setConnectionLostTimeout(30);
            client.connect();
            wsClientList.add(client);
            //Only start it once
            if (executorFuture == null) {
                executorFuture = executorService.scheduleAtFixedRate(() -> {
                    reconnect(subscriptionsService);
                }, 30, 30, TimeUnit.SECONDS);
            }
            return client;
        } catch (Exception e) {
            logger.error("Error on connect to WS {}", e.getLocalizedMessage());
        }
        return null;
    }


}
