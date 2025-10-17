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

import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.websocket.Notification;
import java.net.URI;
import java.util.*;

@Slf4j
/**
 * Handles inventory cache update notifications.
 * Processes UPDATE operations to keep the local inventory cache synchronized.
 */
public class CacheInventoryUpdateClient implements NotificationCallback {

    public static final String CONNECTOR_NAME = "CACHE_INVENTORY_SUBSCRIPTION_CONNECTOR";
    public static final String CONNECTOR_ID = "CACHE_INVENTORY_SUBSCRIPTION_ID";

    private final String tenant;
    private final C8YAgent c8yAgent;

    public CacheInventoryUpdateClient(ConfigurationRegistry configurationRegistry, String tenant) {
        this.tenant = tenant;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        log.info("{} - CacheInventorySubscriptionClient initialized", tenant);
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Inventory cache update WebSocket connected", tenant);
    }

    @Override
    public ProcessingResultWrapper<?> onNotification(Notification notification) {
        if (!"UPDATE".equals(notification.getOperation())) {
            log.debug("{} - Ignoring non-UPDATE notification", tenant);
            return ProcessingResultWrapper.builder()
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .build();
        }

        try {
            String notificationTenant = NotificationHelper.extractTenant(
                notification.getNotificationHeaders(), tenant);
            
            Map<String, Object> update = NotificationHelper.parsePayload(notification.getMessage());
            String sourceId = NotificationHelper.extractSourceId(update, notification.getApi());
            
            if (sourceId != null) {
                c8yAgent.updateMOInInventoryCache(notificationTenant, sourceId, update, false);
                log.debug("{} - Updated inventory cache for MO: {}", notificationTenant, sourceId);
            }
            
            return ProcessingResultWrapper.builder()
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .build();
                
        } catch (Exception e) {
            log.error("{} - Error processing inventory cache update: {}", tenant, e.getMessage(), e);
            return ProcessingResultWrapper.builder()
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .error(e)
                .build();
        }
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - WebSocket error: {}", tenant, t.getMessage(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket closed: status={}, reason={}", tenant, statusCode, reason);
    }
}