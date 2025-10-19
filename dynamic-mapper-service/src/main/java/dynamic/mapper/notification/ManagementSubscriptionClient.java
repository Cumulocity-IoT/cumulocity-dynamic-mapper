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

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.model.Mapping;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.websocket.NotificationCallback;
import dynamic.mapper.processor.model.C8YMessage;
import dynamic.mapper.processor.model.ProcessingResultWrapper;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.task.UpdateSubscriptionDeviceGroupTask;
import dynamic.mapper.notification.task.UpdateSubscriptionDeviceTypeTask;
import dynamic.mapper.notification.websocket.Notification;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.concurrent.*;

@Slf4j
/**
 * Handles management notifications for device groups and device types.
 * Manages subscriptions when groups or devices change.
 */
public class ManagementSubscriptionClient implements NotificationCallback {

    public static final String CONNECTOR_NAME = "MANAGEMENT_SUBSCRIPTION_CONNECTOR";
    public static final String CONNECTOR_ID = "MANAGEMENT_SUBSCRIPTION_ID";

    private final String tenant;
    private final ExecutorService virtualThreadPool;
    private final ConfigurationRegistry configurationRegistry;
    private final NotificationSubscriber notificationSubscriber;
    private final GroupCacheManager groupCacheManager;
    
    public ManagementSubscriptionClient(ConfigurationRegistry configurationRegistry, String tenant) {
        this.tenant = tenant;
        this.configurationRegistry = configurationRegistry;
        this.virtualThreadPool = configurationRegistry.getVirtualThreadPool();
        this.notificationSubscriber = configurationRegistry.getNotificationSubscriber();
        this.groupCacheManager = new GroupCacheManager(tenant);
        
        log.info("{} - ManagementSubscriptionClient initialized", tenant);
    }

    @Override
    public void onOpen(URI serverUri) {
        log.info("{} - Management WebSocket connected", tenant);
        notificationSubscriber.setDeviceConnectionStatus(tenant, 200);
    }

    @Override
    public ProcessingResultWrapper<?> onNotification(Notification notification) {
        String notificationTenant = NotificationHelper.extractTenant(
            notification.getNotificationHeaders(), tenant);
        
        try {
            String operation = notification.getOperation();
            
            if ("UPDATE".equals(operation)) {
                return handleGroupUpdate(notification, notificationTenant);
            } else if ("CREATE".equals(operation)) {
                return handleDeviceCreation(notification, notificationTenant);
            } else {
                log.debug("{} - Ignoring operation: {}", notificationTenant, operation);
                return ProcessingResultWrapper.builder()
                    .consolidatedQos(Qos.AT_LEAST_ONCE)
                    .build();
            }
        } catch (Exception e) {
            log.error("{} - Error processing notification: {}", notificationTenant, e.getMessage(), e);
            return ProcessingResultWrapper.builder()
                .consolidatedQos(Qos.AT_LEAST_ONCE)
                .error(e)
                .build();
        }
    }

    private ProcessingResultWrapper<?> handleGroupUpdate(Notification notification, String notificationTenant) {
        C8YMessage message = NotificationHelper.createC8YMessage(notification, notificationTenant);
        log.debug("{} - Handling group update for: {}", notificationTenant, message.getSourceId());
        
        Future<?> future = virtualThreadPool.submit(
            new UpdateSubscriptionDeviceGroupTask(
                configurationRegistry, 
                message, 
                groupCacheManager.getCache()
            )
        );
        
        return ProcessingResultWrapper.builder()
            .consolidatedQos(Qos.AT_LEAST_ONCE)
            .future(future)
            .build();
    }

    private ProcessingResultWrapper<?> handleDeviceCreation(Notification notification, String notificationTenant) {
        C8YMessage message = NotificationHelper.createC8YMessage(notification, notificationTenant);
        log.debug("{} - Handling device creation for: {}", notificationTenant, message.getSourceId());
        
        Future<?> future = virtualThreadPool.submit(
            new UpdateSubscriptionDeviceTypeTask(configurationRegistry, message)
        );
        
        return ProcessingResultWrapper.builder()
            .consolidatedQos(Qos.AT_LEAST_ONCE)
            .future(future)
            .build();
    }

    // Group cache management
    public void addGroupToCache(ManagedObjectRepresentation groupMO) {
        groupCacheManager.addGroup(groupMO);
    }

    public void removeGroupFromCache(ManagedObjectRepresentation groupMO) {
        groupCacheManager.removeGroup(groupMO);
    }

    @Override
    public void onError(Throwable t) {
        log.error("{} - WebSocket error: {}", tenant, t.getMessage(), t);
    }

    @Override
    public void onClose(int statusCode, String reason) {
        log.info("{} - WebSocket closed: status={}, reason={}", tenant, statusCode, reason);
        
        if (reason != null && reason.contains("401")) {
            notificationSubscriber.setDeviceConnectionStatus(tenant, 401);
        } else {
            notificationSubscriber.setDeviceConnectionStatus(tenant, null);
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("{} - Cleaning up ManagementSubscriptionClient", tenant);
        groupCacheManager.cleanup();
        log.info("{} - ManagementSubscriptionClient cleanup completed", tenant);
    }

    @Override
    public ProcessingResultWrapper<?> onTestNotification(Notification notification, Mapping mapping) {
        throw new UnsupportedOperationException("Unimplemented method 'onTestNotification'");
    }
}