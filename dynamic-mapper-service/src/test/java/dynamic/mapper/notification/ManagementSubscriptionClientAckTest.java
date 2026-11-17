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

package dynamic.mapper.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.sdk.client.SDKException;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.Qos;
import dynamic.mapper.notification.websocket.Notification;
import dynamic.mapper.processor.runtime.ProcessingResultWrapper;
import dynamic.mapper.processor.util.ProcessingResultHelper;
import dynamic.mapper.processor.util.ProcessingResultHelper.AckOutcome;

/**
 * The CREATE notification of a new device is acknowledged only once the device is subscribed. Driven
 * through the same {@link ProcessingResultHelper#awaitOutcome} rule the WebSocket client applies.
 */
class ManagementSubscriptionClientAckTest {

    private ExecutorService pool;
    private NotificationClientOperations subscriber;
    private ManagementSubscriptionClient client;

    @BeforeEach
    void setUp() {
        pool = Executors.newVirtualThreadPerTaskExecutor();
        ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
        when(serviceRegistry.getVirtualThreadPool()).thenReturn(pool);
        when(serviceRegistry.getC8yAgent()).thenReturn(mock(C8YAgent.class));
        subscriber = mock(NotificationClientOperations.class);
        client = new ManagementSubscriptionClient(serviceRegistry, subscriber, "t1");
        client.deviceCreationRetryPausesMs = new long[] { 0, 0 };
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        client.cleanup();
    }

    private Notification createNotification() {
        return Notification.parse("ack-1\n/t1/managedobjects/d1\nCREATE\n\n{\"id\":\"d1\",\"name\":\"Pump\",\"type\":\"cBy_Pump\"}");
    }

    private AckOutcome outcome(ProcessingResultWrapper<?> wrapper) {
        return ProcessingResultHelper.awaitOutcome(wrapper, "t1", "CREATE", org.slf4j.LoggerFactory.getLogger("test"),
                10_000L);
    }

    @Test
    void aSubscribedDeviceIsAcknowledged() {
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(API.class), any()))
                .thenReturn(CompletableFuture.completedFuture(new NotificationSubscriptionRepresentation()));

        ProcessingResultWrapper<?> wrapper = client.onNotification(createNotification());

        assertEquals(Qos.AT_LEAST_ONCE, wrapper.getConsolidatedQos());
        assertEquals(AckOutcome.ACK, outcome(wrapper));
    }

    @Test
    void aTransientFailureIsNotAcknowledgedSoCumulocityDeliversItAgain() {
        // every attempt fails, and the (here zero) pauses between them pass
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(API.class), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("platform 503")));

        ProcessingResultWrapper<?> wrapper = client.onNotification(createNotification());

        assertEquals(AckOutcome.RETRY, outcome(wrapper));
    }

    @Test
    void aPermanentFailureIsAcknowledgedInsteadOfBeingRedeliveredForever() {
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(API.class), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("Failed to create subscription", new SDKException(404, "gone"))));

        assertEquals(AckOutcome.ACK, outcome(client.onNotification(createNotification())));
    }
}
