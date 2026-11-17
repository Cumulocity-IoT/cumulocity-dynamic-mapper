/*
 * Copyright (c) 2022-2026 Cumulocity GmbH.
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
 * @authors Christof Strack
 */

package dynamic.mapper.notification.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.sdk.client.SDKException;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.status.LoggingEventType;
import dynamic.mapper.notification.NotificationClientOperations;
import dynamic.mapper.notification.Utils;
import dynamic.mapper.processor.model.C8YMessage;

/** A new device of a selected type is subscribed with retries; the outcome says whether to acknowledge. */
class UpdateSubscriptionDeviceTypeTaskTest {

    private ServiceRegistry serviceRegistry;
    private C8YAgent c8yAgent;
    private NotificationClientOperations subscriber;

    @BeforeEach
    void setUp() {
        serviceRegistry = mock(ServiceRegistry.class);
        c8yAgent = mock(C8YAgent.class);
        subscriber = mock(NotificationClientOperations.class);
        when(serviceRegistry.getC8yAgent()).thenReturn(c8yAgent);
    }

    private UpdateSubscriptionDeviceTypeTask task(Map<String, Object> payload) {
        C8YMessage message = new C8YMessage();
        message.setTenant("t1");
        message.setSourceId("d1");
        message.setDeviceName("Pump");
        message.setParsedPayload(payload);
        UpdateSubscriptionDeviceTypeTask task = new UpdateSubscriptionDeviceTypeTask(serviceRegistry, subscriber,
                message);
        task.retryPausesMs = new long[] { 0, 0 };
        return task;
    }

    private Map<String, Object> typed() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "cBy_Pump");
        return payload;
    }

    private CompletableFuture<NotificationSubscriptionRepresentation> ok() {
        return CompletableFuture.completedFuture(new NotificationSubscriptionRepresentation());
    }

    private CompletableFuture<NotificationSubscriptionRepresentation> failed(Throwable t) {
        return CompletableFuture.failedFuture(t);
    }

    @Test
    void subscribesTheDeviceAndSucceeds() {
        when(subscriber.subscribeDeviceAndConnect(eq("t1"), any(), eq(API.ALL), eq(Utils.DYNAMIC_DEVICE_SUBSCRIPTION)))
                .thenReturn(ok());

        SubscriptionUpdateResult result = task(typed()).call();

        assertEquals(1, result.getAddedCount());
        assertFalse(result.isRetryable());
        verify(c8yAgent, never()).createLoggingEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTransientFailureIsRetriedAndASuccessfulAttemptSettlesIt() {
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(API.class), any()))
                .thenReturn(failed(new RuntimeException("platform 503")))
                .thenReturn(failed(new RuntimeException("platform 503")))
                .thenReturn(ok());

        SubscriptionUpdateResult result = task(typed()).call();

        assertEquals(1, result.getAddedCount());
        assertFalse(result.isRetryable());
        verify(subscriber, times(3)).subscribeDeviceAndConnect(any(), any(), any(API.class), any());
    }

    @Test
    void aTransientFailureThatOutlivesTheRetriesIsRetryableAndReported() {
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(API.class), any()))
                .thenReturn(failed(new RuntimeException("platform 503")));

        SubscriptionUpdateResult result = task(typed()).call();

        assertTrue(result.isRetryable(), "the notification must not be acknowledged");
        assertEquals(1, result.getFailedCount());
        verify(subscriber, times(3)).subscribeDeviceAndConnect(any(), any(), any(API.class), any());
        // not left to the log: a service event with the error severity
        verify(c8yAgent).createLoggingEvent(any(), eq(LoggingEventType.SUBSCRIPTION_EVENT_TYPE), eq("error"),
                any(), eq("t1"), any());
    }

    @Test
    void aPermanentFailureIsNotRetriedAndMayBeAcknowledged() {
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(API.class), any()))
                .thenReturn(failed(new RuntimeException("Failed to create subscription",
                        new SDKException(404, "device does not exist"))));

        SubscriptionUpdateResult result = task(typed()).call();

        assertFalse(result.isRetryable(), "a redelivery would only repeat it");
        assertEquals(1, result.getFailedCount());
        verify(subscriber, times(1)).subscribeDeviceAndConnect(any(), any(), any(API.class), any());
        verify(c8yAgent).createLoggingEvent(any(), any(), eq("error"), any(), eq("t1"), any());
    }

    @Test
    void aNotificationWithoutAValidTypeIsSettledWithoutSubscribing() {
        SubscriptionUpdateResult result = task(new HashMap<>()).call();

        assertTrue(result.isEmpty());
        assertFalse(result.isRetryable());
        verify(subscriber, never()).subscribeDeviceAndConnect(any(), any(), any(API.class), any());
    }

    @Test
    void whichPlatformErrorsAreWorthARetry() {
        assertTrue(UpdateSubscriptionDeviceTypeTask.isPermanent(new SDKException(400, "bad")));
        assertTrue(UpdateSubscriptionDeviceTypeTask.isPermanent(new SDKException(404, "gone")));
        assertTrue(UpdateSubscriptionDeviceTypeTask.isPermanent(new IllegalArgumentException("invalid")));
        assertFalse(UpdateSubscriptionDeviceTypeTask.isPermanent(new SDKException(408, "timeout")));
        assertFalse(UpdateSubscriptionDeviceTypeTask.isPermanent(new SDKException(429, "slow down")));
        assertFalse(UpdateSubscriptionDeviceTypeTask.isPermanent(new SDKException(503, "unavailable")));
        assertFalse(UpdateSubscriptionDeviceTypeTask.isPermanent(new RuntimeException("io")));
    }
}
