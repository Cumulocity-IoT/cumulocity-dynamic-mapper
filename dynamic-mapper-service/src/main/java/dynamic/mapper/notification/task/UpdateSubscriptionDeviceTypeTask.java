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

package dynamic.mapper.notification.task;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.processor.model.C8YMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Task to subscribe a newly created device based on type filters.
 * Triggered when a CREATE notification is received for a device matching
 * the configured type subscription.
 */
@Slf4j
public class UpdateSubscriptionDeviceTypeTask implements Callable<SubscriptionUpdateResult> {

    private final C8YMessage c8yMessage;
    private final ConfigurationRegistry configurationRegistry;

    public UpdateSubscriptionDeviceTypeTask(
            ConfigurationRegistry configurationRegistry,
            C8YMessage c8yMessage) {
        this.c8yMessage = c8yMessage;
        this.configurationRegistry = configurationRegistry;
    }

    @Override
    public SubscriptionUpdateResult call() {
        String tenant = c8yMessage.getTenant();
        String deviceId = c8yMessage.getSourceId();
        String deviceName = c8yMessage.getDeviceName();

        log.debug("{} - Processing device type subscription for: {}", tenant, deviceId);

        // Validate device ID
        if (deviceId == null || deviceId.trim().isEmpty()) {
            log.warn("{} - No device ID found in message, skipping type subscription", tenant);
            return SubscriptionUpdateResult.empty();
        }

        // Extract and validate device type
        DeviceTypeInfo typeInfo = extractDeviceType();
        if (!typeInfo.isValid()) {
            log.warn("{} - No valid type found for device {}, skipping subscription", tenant, deviceId);
            return SubscriptionUpdateResult.empty();
        }

        log.info("{} - Processing new device {} (name: {}, type: {})",
                tenant, deviceId, deviceName, typeInfo.getType());

        try {
            // Create ManagedObject representation for new device
            ManagedObjectRepresentation newMO = createManagedObjectRepresentation(
                    deviceId, deviceName, typeInfo);

            // Subscribe the device
            Future<NotificationSubscriptionRepresentation> future = configurationRegistry
                    .getNotificationSubscriber()
                    .subscribeDeviceAndConnect(tenant, newMO, c8yMessage.getApi());

            log.info("{} - Successfully subscribed new device {} of type {}",
                    tenant, deviceId, typeInfo.getType());

            return SubscriptionUpdateResult.builder()
                    .addSubscription(deviceId, future)
                    .build();

        } catch (Exception e) {
            log.error("{} - Failed to subscribe new device {} of type {}: {}",
                    tenant, deviceId, typeInfo.getType(), e.getMessage(), e);

            return SubscriptionUpdateResult.builder()
                    .addFailed(deviceId, e.getMessage())
                    .withError(e)
                    .build();
        }
    }

    /**
     * Extract device type from payload
     */
    private DeviceTypeInfo extractDeviceType() {
        if (c8yMessage.getParsedPayload() == null) {
            return DeviceTypeInfo.invalid();
        }

        Object typeObj = c8yMessage.getParsedPayload().get("type");
        if (typeObj == null) {
            return DeviceTypeInfo.invalid();
        }

        String type = String.valueOf(typeObj);
        if (type.trim().isEmpty() || "null".equals(type)) {
            return DeviceTypeInfo.invalid();
        }

        return DeviceTypeInfo.of(type);
    }

    /**
     * Create ManagedObjectRepresentation for the new device
     */
    private ManagedObjectRepresentation createManagedObjectRepresentation(
            String deviceId, String deviceName, DeviceTypeInfo typeInfo) {

        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(GId.asGId(deviceId));
        mo.setName(deviceName != null ? deviceName : deviceId);
        mo.setType(typeInfo.getType());

        return mo;
    }

    /**
     * Helper class to hold device type information
     */
    private static class DeviceTypeInfo {
        private final String type;
        private final boolean valid;

        private DeviceTypeInfo(String type, boolean valid) {
            this.type = type;
            this.valid = valid;
        }

        public static DeviceTypeInfo of(String type) {
            return new DeviceTypeInfo(type, true);
        }

        public static DeviceTypeInfo invalid() {
            return new DeviceTypeInfo(null, false);
        }

        public String getType() {
            return type;
        }

        public boolean isValid() {
            return valid;
        }
    }
}
