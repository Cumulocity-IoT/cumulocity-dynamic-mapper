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

package dynamic.mapper.notification;

import java.util.List;
import java.util.concurrent.Future;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;

import dynamic.mapper.model.API;
import dynamic.mapper.model.device.Device;

/**
 * What the Notification 2.0 WebSocket clients and subscription tasks may call on
 * {@link NotificationSubscriber}.
 * <p>
 * {@code NotificationConnectionManager} creates those clients, so it cannot depend on
 * {@code NotificationSubscriber}, which depends on it. {@code NotificationSubscriber} implements this
 * interface and registers itself with the manager after construction instead.
 */
public interface NotificationClientOperations {

    void setManagementConnectionStatus(String tenant, Integer status);

    void setCacheInventoryConnectionStatus(String tenant, Integer status);

    boolean unsubscribeMOForInventoryCacheUpdates(String tenant, ManagedObjectRepresentation mor);

    Future<NotificationSubscriptionRepresentation> subscribeDeviceAndConnect(String tenant,
            ManagedObjectRepresentation mor, API api, String subscriptionName);

    void unsubscribeDeviceAndDisconnect(String tenant, ManagedObjectRepresentation mor, String subscription);

    int deleteSubscriptionsForDevice(String tenant, String deviceId, String subscriptionName);

    NotificationSubscriptionResponse getSubscriptionsByDeviceType(String tenant);

    /** The subscribed device groups (as devices carrying the group's id and name). */
    NotificationSubscriptionResponse getSubscriptionsByDeviceGroup(String tenant);

    /** The devices below a managed object: the device and its child devices, or all devices of a group. */
    List<Device> findAllRelatedDevicesByMO(String tenant, ManagedObjectRepresentation mor, List<Device> devices,
            boolean isChildDevice);
}
