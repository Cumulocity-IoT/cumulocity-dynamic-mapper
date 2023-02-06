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
import mqtt.mapping.notification.websocket.Notification;
import mqtt.mapping.notification.websocket.NotificationCallback;
import mqtt.mapping.notification.websocket.SpringWebSocketListener;
import mqtt.mapping.processor.AsynchronousDispatcherOutgoing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.integration.websocket.ClientWebSocketContainer;
import org.springframework.integration.websocket.WebSocketListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.lang.Boolean.FALSE;

@Service
public class C8YAPISubscriber {
    private final static String WEBSOCKET_PATH = "/notification2/consumer/?token=";
    private static final Logger logger = LoggerFactory.getLogger(C8YAPISubscriber.class);

    public static boolean DEVICE_NOTIFICATION_CONNECTED = FALSE;

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
    private AsynchronousDispatcherOutgoing dispatcherOutgoing;

    @Value("${C8Y.BASEURL}")
    private String baseUrl;

    private final String DEVICE_SUBSCRIBER = "MQTTOutboundMapperDeviceSubscriber";
    private final String DEVICE_SUBSCRIPTION = "MQTTOutboundMapperDeviceSubscription";
    private final String TENANT_SUBSCRIBER = "MQTTOutboundMapperTenantSubscriber";
    private final String TENANT_SUBSCRIPTION = "MQTTOutboundMapperTenantSubscription";

    List<ClientWebSocketContainer> wsClientList = new ArrayList<>();
    List<NotificationSubscriptionRepresentation> subscriptionList = new ArrayList<>();


    public void init() {
       // Subscribe on Tenant do get informed when devices get deleted/added
        logger.info("Initializing Operation Subscriptions...");
       subscribeTenant(subscriptionsService.getTenant());
       List<NotificationSubscriptionRepresentation> deviceSubList = getDeviceSubscriptions();
       // When one subscription exsits, connect
       if (deviceSubList.size() > 0) {
           String token = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER);
           try {
               connect(token, dispatcherOutgoing);
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
            String deviceToken = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER);

            try {
                connect(deviceToken, dispatcherOutgoing);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }

    public CompletableFuture<NotificationSubscriptionRepresentation> subscribeDevice(ManagedObjectRepresentation mor, API api) throws ExecutionException, InterruptedException {
        /* Connect to all devices */
        String deviceName = mor.getName();
        logger.info("Creating new Subscription for Device " + deviceName);
        CompletableFuture<NotificationSubscriptionRepresentation> notificationFut = new CompletableFuture<NotificationSubscriptionRepresentation>();
        subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
            NotificationSubscriptionRepresentation notification = createDeviceSubscription(mor, api);
            notificationFut.complete(notification);
            if (!DEVICE_NOTIFICATION_CONNECTED) {
                logger.info("Device Subscription not connected yet. Will connect...");
                String token = createToken(DEVICE_SUBSCRIPTION, DEVICE_SUBSCRIBER);
                try {
                    connect(token, dispatcherOutgoing);
                } catch (URISyntaxException e) {
                    logger.error("Error on connecting to Notification Service: {}", e.getLocalizedMessage());
                    throw new RuntimeException(e);
                }
                DEVICE_NOTIFICATION_CONNECTED = true;
            }
        });
        return notificationFut;
    }

    public List<NotificationSubscriptionRepresentation> getDeviceSubscriptions() {
        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION).byContext("mo")).get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        List<NotificationSubscriptionRepresentation> deviceSubList = new ArrayList<>();
        while (subIt.hasNext()) {
            notification = subIt.next();
            if (!"tenant".equals(notification.getContext())) {
                logger.info("Subscription with ID {} retrieved", notification.getId().getValue());
                deviceSubList.add(notification);
            }
        }
        return deviceSubList;
    }

    public void subscribeTenant(String tenant) {
        logger.info("Creating new Subscription for Tenant " + tenant);
        NotificationSubscriptionRepresentation notification = createTenantSubscription();
        String tenantToken = createToken(notification.getSubscription(), TENANT_SUBSCRIBER);

        try {
            NotificationCallback tenantCallback = new NotificationCallback() {

                @Override
                public void onOpen(URI uri) {
                    logger.info("Connected to Cumulocity notification service over WebSocket " + uri);
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
                                unsubscribeDevice(morRetrieved, API.OPERATION);
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
                public void onClose() {
                    logger.info("Connection was closed.");
                }
            };
            connect(tenantToken, tenantCallback);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }

    public NotificationSubscriptionRepresentation createTenantSubscription() {

        final String subscriptionName = TENANT_SUBSCRIPTION;
        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(subscriptionName).byContext("tenant")).get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        while (subIt.hasNext()) {
            notification = subIt.next();
            logger.info("Subscription with ID {} already exists.", notification.getId().getValue());
            return notification;
        }
        if (notification == null) {
            logger.info("Subscription does not exist. Creating ...");
            notification = new NotificationSubscriptionRepresentation();
            final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
            filterRepresentation.setApis(List.of("managedobjects"));
            notification.setContext("tenant");
            notification.setSubscription(subscriptionName);
            notification.setSubscriptionFilter(filterRepresentation);
            notification = subscriptionApi.subscribe(notification);
        }
        return notification;
    }

    public NotificationSubscriptionRepresentation createDeviceSubscription(ManagedObjectRepresentation mor, API api) {
        final GId sourceId = GId.asGId(mor.getId());
        final String subscriptionName = DEVICE_SUBSCRIPTION;

        Iterator<NotificationSubscriptionRepresentation> subIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(subscriptionName).bySource(mor.getId())).get().allPages().iterator();
        NotificationSubscriptionRepresentation notification = null;
        while (subIt.hasNext()) {
            notification = subIt.next();
            logger.info("Subscription with ID {} and Source {} already exists.", notification.getId().getValue(), notification.getSource().getId().getValue());
            return notification;
        }

        if (notification == null) {
            logger.info("Subscription does not exist. Creating ...");
            notification = new NotificationSubscriptionRepresentation();
            notification.setSource(mor);
            final NotificationSubscriptionFilterRepresentation filterRepresentation = new NotificationSubscriptionFilterRepresentation();
            //filterRepresentation.setApis(List.of("operations"));
            filterRepresentation.setApis(List.of(api.notificationFilter));
            notification.setContext("mo");
            notification.setSubscription(subscriptionName);
            notification.setSubscriptionFilter(filterRepresentation);
            notification = subscriptionApi.subscribe(notification);
        }
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
            subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                subscriptionApi.delete(notification);
            });

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

    public boolean unsubscribeDevice(ManagedObjectRepresentation mor, API api) throws SDKException{
        Iterator<NotificationSubscriptionRepresentation> deviceSubIt = subscriptionApi.getSubscriptionsByFilter(new NotificationSubscriptionFilter().bySubscription(DEVICE_SUBSCRIPTION).bySource(mor.getId())).get().allPages().iterator();
        while (deviceSubIt.hasNext()) {
            NotificationSubscriptionRepresentation notification = deviceSubIt.next();

            subscriptionsService.runForTenant(subscriptionsService.getTenant(), () -> {
                subscriptionApi.delete(notification);
            });
            return true;
        }
        return false;
    }

    public void disconnect() {
        for (ClientWebSocketContainer client : wsClientList) {
            client.stop();
        }
        wsClientList = new ArrayList<>();
    }

    public boolean getDeviceConnectionStatus() {
        return DEVICE_NOTIFICATION_CONNECTED;
    }

    public boolean setDeviceConnectionStatus(boolean status) {
        DEVICE_NOTIFICATION_CONNECTED = status;
        return DEVICE_NOTIFICATION_CONNECTED;
    }

    public void connect(String token, NotificationCallback callback) throws URISyntaxException {
        baseUrl = baseUrl.replace("http", "ws");

        URI webSocketUrl = new URI(baseUrl + WEBSOCKET_PATH + token);
        final WebSocketClient webSocketClient = new StandardWebSocketClient();
        ClientWebSocketContainer container = new ClientWebSocketContainer(webSocketClient, webSocketUrl.toString());
        WebSocketListener messageListener = new SpringWebSocketListener(callback);
        container.setMessageListener(messageListener);
        container.setConnectionTimeout(30);
        container.start();
        /*
        CompletableFuture.runAsync( () -> {

           while (!container.isRunning()) {
               try {
                   Thread.sleep(30000);
               } catch (InterruptedException e) {
                   throw new RuntimeException(e);
               }
               container.start();
           }
        });
         */
        wsClientList.add(container);

    }

}
