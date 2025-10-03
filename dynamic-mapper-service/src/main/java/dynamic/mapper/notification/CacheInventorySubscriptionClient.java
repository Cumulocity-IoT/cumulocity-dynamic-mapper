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
import dynamic.mapper.notification.websocket.Notification;
import java.net.URI;
import java.util.*;

@Slf4j
/**
 * CacheInventorySubscriptionClient handles notifications for management
 * subscriptions.
 * A CacheInventorySubscriptionClient is created per tenant and manages device
 * group updates and device type subscriptions.
 * It processes device group updates and device type subscriptions based on
 * incoming notifications.
 * It uses a virtual thread pool for asynchronous processing and maintains a
 * cache of groups.
 */
public class CacheInventorySubscriptionClient implements NotificationCallback {

    public static final String CONNECTOR_NAME = "CACHE_INVENTORY_SUBSCRIPTION_CONNECTOR";
    public static final String CONNECTOR_ID = "CACHE_INVENTORY_SUBSCRIPTION_ID";

    private String tenant;

    // Thread-safe cache with cleanup mechanism
    // < groupID, group>

    private C8YAgent c8yAgent;

    /**
     * 
     * @param configurationRegistry
     * @param tenant
     */

    public CacheInventorySubscriptionClient(ConfigurationRegistry configurationRegistry, String tenant) {
        this.tenant = tenant;
        this.c8yAgent = configurationRegistry.getC8yAgent();

        log.info("{} - CacheInventorySubscriptionClient initialized", tenant);
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Phase IV: Notification 2.0 connected over WebSocket, managing subscriptions inventory update",
                tenant);
    }

    @Override
    public ProcessingResult<?> onNotification(Notification notification) {
        Qos consolidatedQos = Qos.AT_LEAST_ONCE;
        ProcessingResult<?> result = ProcessingResult.builder()
                .consolidatedQos(consolidatedQos)
                .build();

        String notificationTenant = getTenantFromNotificationHeaders(notification.getNotificationHeaders());

        try {
            String operation = notification.getOperation();
             if ("UPDATE".equals(operation)) {
                handleUpdateNotification(notification, notificationTenant);
            } else {
                log.debug("{} - Ignoring notification with operation: {}", notificationTenant, operation);
            }
        } catch (Exception e) {
            log.error("{} - Error processing notification: {}", notificationTenant, e.getMessage(), e);
            result = ProcessingResult.builder()
                    .error(e)
                    .build();
        }

        return result;
    }

    private void handleUpdateNotification(Notification notification, String notificationTenant) {
        Map<String, Object> update = parsePayload(notification.getMessage());
        String sourceId = extractSourceId(update, notification.getApi());
        c8yAgent.updateMOInInventoryCache(notificationTenant, sourceId, update);
        log.debug("{} - Handling UPDATE notification for: {}", notificationTenant, sourceId);

    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payload) {
        try {
            return (Map<String, Object>) Json.parseJson(payload);
        } catch (Exception e) {
            log.warn("Failed to parse notification payload: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String extractSourceId(Map<String, Object> parsedPayload, dynamic.mapper.model.API api) {
        try {
            var expression = jsonata(api.identifier);
            Object result = expression.evaluate(parsedPayload);
            return result instanceof String ? (String) result : null;
        } catch (Exception e) {
            log.warn("Could not extract source.id from payload: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - WebSocket error occurred: {}", tenant, t.getMessage(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket connection closed with status {} and reason: {}",
                tenant, statusCode, reason);
    }

    public String getTenantFromNotificationHeaders(List<String> notificationHeaders) {
        if (notificationHeaders == null || notificationHeaders.isEmpty()) {
            log.warn("No notification headers provided");
            return tenant; // fallback to instance tenant
        }

        try {
            return notificationHeaders.get(0).split("/")[1];
        } catch (Exception e) {
            log.warn("Failed to extract tenant from headers: {}", e.getMessage());
            return tenant; // fallback to instance tenant
        }
    }

}