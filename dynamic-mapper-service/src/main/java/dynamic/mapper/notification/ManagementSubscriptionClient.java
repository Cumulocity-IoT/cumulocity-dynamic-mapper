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

import static com.dashjoin.jsonata.Jsonata.jsonata;

import com.dashjoin.jsonata.json.Json;

import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.ProcessingResult;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.C8YMessage;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class ManagementSubscriptionClient implements NotificationCallback {

    public static String CONNECTOR_NAME = "MANAGEMENT_SUBSCRIPTION_CONNECTOR";
    public static String CONNECTOR_ID = "MANAGEMENT_SUBSCRIPTION_ID";

    protected C8YNotificationSubscriber notificationSubscriber;

    protected C8YAgent c8yAgent;

    protected String tenant;

    protected ExecutorService virtualThreadPool;

    protected ConfigurationRegistry configurationRegistry;

    // The Outbound Dispatcher is hardly connected to the Connector otherwise it is
    // not possible to correlate messages received bei Notification API to the
    // correct Connector
    public ManagementSubscriptionClient(ConfigurationRegistry configurationRegistry,
            String tenant) {
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.configurationRegistry = configurationRegistry;
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, managing subscriptions to connector: {}",
                tenant);
        notificationSubscriber.setDeviceConnectionStatus(tenant, 200);
    }

    @Override
    public ProcessingResult<?> onNotification(Notification notification) {
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder().consolidatedQos(consolidatedQos).build();

        // We don't care about UPDATES nor DELETES and ignore notifications if connector
        // is not connected
        String tenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());

        // log.info("{} - Notification message received {}",
        // tenant, operation);
        if (("CREATE".equals(notification.getOperation()) || "UPDATE".equals(notification.getOperation()))) {
            // log.info("{} - Notification received: <{}>, <{}>, <{}>, <{}>", tenant,
            // notification.getMessage(),
            // notification.getNotificationHeaders(),
            // connectorClient.connectorConfiguration.name,
            // connectorClient.isConnected());
            // if ("UPDATE".equals(notification.getOperation()) &&
            // notification.getApi().equals(API.OPERATION)) {
            // log.info("{} - Update Operation message for managing subscriptions received,
            // ignoring it",
            // tenant);
            // return result;
            // }
            C8YMessage c8yMessage = new C8YMessage();
            Map parsedPayload = (Map) Json.parseJson(notification.getMessage());
            c8yMessage.setParsedPayload(parsedPayload);
            c8yMessage.setApi(notification.getApi());
            c8yMessage.setOperation(notification.getOperation());
            String messageId = String.valueOf(parsedPayload.get("id"));
            c8yMessage.setMessageId(messageId);
            try {
                var expression = jsonata(notification.getApi().identifier);
                Object sourceIdResult = expression.evaluate(parsedPayload);
                String sourceId = (sourceIdResult instanceof String) ? (String) sourceIdResult : null;
                c8yMessage.setSourceId(sourceId);
            } catch (Exception e) {
                log.debug("Could not extract source.id: {}", e.getMessage());

            }
            c8yMessage.setPayload(notification.getMessage());
            c8yMessage.setTenant(tenant);
            c8yMessage.setSendPayload(true);
            // TODO Return a future so it can be blocked for QoS 1 or 2
            return manageSubscriptions(c8yMessage);
        }
        return result;
    }

    private ProcessingResult<?> manageSubscriptions(C8YMessage c8yMessage) {
        log.info("Update DeviceGroup: {}", c8yMessage);
        return null;
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - We got an exception: ", tenant, t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed", tenant);
        if (reason.contains("401"))
            notificationSubscriber.setDeviceConnectionStatus(tenant, 401);
        else
            notificationSubscriber.setDeviceConnectionStatus(tenant, null);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        return notificationHeaders.get(0).split("/")[1];
    }

}