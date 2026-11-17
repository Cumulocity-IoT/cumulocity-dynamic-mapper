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

package dynamic.mapper.notification.task;

import dynamic.mapper.notification.NotificationClientOperations;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;

import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.processor.model.C8YMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.cumulocity.sdk.client.SDKException;
import dynamic.mapper.model.status.LoggingEventType;
import org.joda.time.DateTime;


/**
 * Task to subscribe a newly created device based on type filters.
 * Triggered when a CREATE notification is received for a device matching
 * the configured type subscription.
 */
@Slf4j
public class UpdateSubscriptionDeviceTypeTask implements Callable<SubscriptionUpdateResult> {

    private final C8YMessage c8yMessage;
    private final ServiceRegistry serviceRegistry;
    private final NotificationClientOperations notificationSubscriber;

    public UpdateSubscriptionDeviceTypeTask(
            ServiceRegistry serviceRegistry,
            NotificationClientOperations notificationSubscriber,
            C8YMessage c8yMessage) {
        this.c8yMessage = c8yMessage;
        this.serviceRegistry = serviceRegistry;
        this.notificationSubscriber = notificationSubscriber;
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

        // Create ManagedObject representation for new device
        ManagedObjectRepresentation newMO = createManagedObjectRepresentation(deviceId, deviceName, typeInfo);

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // Subscribe the device with API.ALL to receive all notifications for this device
                Future<NotificationSubscriptionRepresentation> future = notificationSubscriber
                        .subscribeDeviceAndConnect(tenant, newMO, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION);
                // Wait for it: the subscribe runs asynchronously, and the notification may only be
                // acknowledged once the device is really subscribed (null = already covered, also fine)
                future.get(SUBSCRIBE_WAIT_SECONDS, TimeUnit.SECONDS);

                // Pre-populate inventory cache for this device to ensure inventory filters work correctly
                log.debug("{} - Pre-populating inventory cache for new device {} of type {}",
                        tenant, deviceId, typeInfo.getType());
                serviceRegistry.getC8yAgent().getMOFromInventoryCache(tenant, deviceId, false);

                log.info("{} - Successfully subscribed new device {} of type {}",
                        tenant, deviceId, typeInfo.getType());
                return SubscriptionUpdateResult.builder()
                        .addSubscription(deviceId, future)
                        .build();

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                last = ie;
                break;
            } catch (Exception e) {
                last = e;
                if (isPermanent(e)) {
                    break;
                }
                log.warn("{} - Subscribing new device {} failed (attempt {}/{}): {}",
                        tenant, deviceId, attempt, MAX_ATTEMPTS, rootMessage(e));
                if (attempt < MAX_ATTEMPTS && !pause(attempt)) {
                    break;
                }
            }
        }

        boolean retryable = last != null && !isPermanent(last) && !(last instanceof InterruptedException);
        log.error("{} - Failed to subscribe new device {} of type {}: {} ({})",
                tenant, deviceId, typeInfo.getType(), rootMessage(last),
                retryable ? "transient, the notification is not acknowledged" : "permanent");
        reportFailure(tenant, deviceId, typeInfo.getType(), last, retryable);

        return SubscriptionUpdateResult.builder()
                .addFailed(deviceId, rootMessage(last))
                .withError(last)
                .retryable(retryable)
                .build();
    }

    /** Attempts per notification, with {@link #retryPausesMs} between them. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long SUBSCRIBE_WAIT_SECONDS = 30;

    /** Pauses before the 2nd and the 3rd attempt. */
    long[] retryPausesMs = { 1000, 5000 };

    /** Replaces the pauses between attempts (tests use none). */
    public void setRetryPausesMs(long[] pausesMs) {
        this.retryPausesMs = pausesMs;
    }

    /** @return false if interrupted while pausing */
    private boolean pause(int afterAttempt) {
        long ms = retryPausesMs[Math.min(afterAttempt - 1, retryPausesMs.length - 1)];
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A failure no later attempt can fix: the platform rejected the request itself (a 4xx other than
     * "timeout" and "too many requests"), or the arguments are invalid. Retrying would only repeat it, so
     * the notification is acknowledged instead of being redelivered until the poison-pill limit.
     */
    static boolean isPermanent(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SDKException sdk) {
                int status = sdk.getHttpStatus();
                return status >= 400 && status < 500 && status != 408 && status != 429;
            }
            if (t instanceof IllegalArgumentException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null ? "unknown error" : String.valueOf(root.getMessage());
    }

    /** A failure that outlived the retries is not left to the log: it becomes a service event. */
    private void reportFailure(String tenant, String deviceId, String type, Exception failure, boolean retryable) {
        try {
            java.util.Map<String, String> props = new java.util.HashMap<>();
            props.put("deviceId", deviceId);
            props.put("type", type);
            props.put("error", rootMessage(failure));
            props.put("retryable", String.valueOf(retryable));
            serviceRegistry.getC8yAgent().createLoggingEvent(
                    String.format("Could not subscribe new device %s of type %s: %s", deviceId, type,
                            rootMessage(failure)),
                    LoggingEventType.SUBSCRIPTION_EVENT_TYPE, "error", DateTime.now(), tenant, props);
        } catch (Exception e) {
            log.warn("{} - Could not report the failed subscription of device {}: {}", tenant, deviceId, e.getMessage());
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
