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

package dynamic.mapper.notification.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionFilterRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionCollection;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.PagedNotificationSubscriptionCollectionRepresentation;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.LoggingEventType;
import dynamic.mapper.notification.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionManagerTest {

    @Mock
    private NotificationSubscriptionApi subscriptionAPI;
    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;
    @Mock
    private NotificationConnectionManager connectionManager;
    @Mock
    private MqttPushManager mqttPushManager;
    @Mock
    private ConfigurationRegistry configurationRegistry;

    private ExecutorService virtualThreadPool;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() {
        virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();

        // Make callForTenant invoke the callable directly
        lenient().when(subscriptionsService.callForTenant(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(1, java.util.concurrent.Callable.class).call());
        // Make runForTenant invoke the runnable directly
        lenient().doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(subscriptionsService).runForTenant(anyString(), any());

        subscriptionManager = new SubscriptionManager(
                subscriptionAPI,
                subscriptionsService,
                connectionManager,
                mqttPushManager,
                virtualThreadPool,
                configurationRegistry);
    }

    // -------------------------------------------------------------------------
    // H1: non-atomic contains()+add() allows concurrent duplicate subscriptions
    // -------------------------------------------------------------------------

    /**
     * H1: Concurrent calls for the same tenant+device must produce exactly one
     * subscriptionAPI.subscribe() call.
     * Fails against the current synchronizedSet contains()+add() because both
     * threads can pass contains() before either calls add().
     */
    @Test
    void subscribeDeviceAndConnect_concurrentCalls_subscribesExactlyOnce() throws Exception {
        ManagedObjectRepresentation mor = morWithId("device-1");

        AtomicInteger subscribeCount = new AtomicInteger();
        when(subscriptionAPI.subscribe(any())).thenAnswer(inv -> {
            Thread.sleep(100); // keep processingKey in set until all callers have checked add()
            subscribeCount.incrementAndGet();
            return stubNsr("device-1");
        });
        // Pre-build before when() to avoid UnfinishedStubbing — mock() inside thenReturn() corrupts state
        NotificationSubscriptionCollection empty1 = emptyCollection();
        lenient().when(subscriptionAPI.getSubscriptionsByFilter(any()))
                .thenReturn(empty1);

        int threadCount = 20;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(virtualThreadPool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try {
                    subscriptionManager
                            .subscribeDeviceAndConnect("t1", mor, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION)
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                return null;
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);

        // Contract: exactly one real C8Y subscription must be created
        assertEquals(1, subscribeCount.get(),
                "H1 bug: non-atomic contains()+add() lets concurrent threads each call subscribe(). " +
                "Got " + subscribeCount.get() + " calls instead of 1.");
    }

    // -------------------------------------------------------------------------
    // H2: processingDevices not tenant-scoped — same device ID, different tenants
    // -------------------------------------------------------------------------

    /**
     * H2: Two tenants sharing the same device ID must both get a subscription.
     * Fails because processingDevices is keyed by deviceId only — tenantB's
     * call is silently dropped while tenantA's entry is in the set.
     */
    @Test
    void subscribeDeviceAndConnect_sameDeviceIdDifferentTenants_bothProceed() throws Exception {
        ManagedObjectRepresentation morA = morWithId("device-1");
        ManagedObjectRepresentation morB = morWithId("device-1");

        AtomicInteger subscribeCount = new AtomicInteger();
        when(subscriptionAPI.subscribe(any())).thenAnswer(inv -> {
            Thread.sleep(50); // keep entry in processingDevices while second thread arrives
            subscribeCount.incrementAndGet();
            return stubNsr("device-1");
        });
        NotificationSubscriptionCollection empty2 = emptyCollection();
        lenient().when(subscriptionAPI.getSubscriptionsByFilter(any()))
                .thenReturn(empty2);

        CountDownLatch start = new CountDownLatch(1);
        Future<?> fa = virtualThreadPool.submit(() -> {
            start.await();
            subscriptionManager.subscribeDeviceAndConnect("tenantA", morA, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION)
                    .get(5, TimeUnit.SECONDS);
            return null;
        });
        Future<?> fb = virtualThreadPool.submit(() -> {
            start.await();
            subscriptionManager.subscribeDeviceAndConnect("tenantB", morB, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION)
                    .get(5, TimeUnit.SECONDS);
            return null;
        });

        start.countDown();
        fa.get(10, TimeUnit.SECONDS);
        fb.get(10, TimeUnit.SECONDS);

        assertEquals(2, subscribeCount.get(),
                "H2 bug: processingDevices is not tenant-scoped. " +
                "tenantB's device was dropped because tenantA's same ID was in the set. " +
                "Got " + subscribeCount.get() + " calls instead of 2.");
    }

    // -------------------------------------------------------------------------
    // DELETE-first order: old subscription is removed before the new one is created.
    // C8Y subscriptions are keyed by (subscription-name, context); attempting to
    // create a new tenant-scoped subscription while the old one still exists always
    // returns 409, so DELETE must come first.
    // Trade-off: if the subsequent CREATE fails the old entry is already gone.
    // -------------------------------------------------------------------------

    @Test
    void updateSubscriptionByType_createFails_oldSubscriptionAlreadyDeleted() {
        NotificationSubscriptionRepresentation existing = stubNsr("existing");
        existing.setContext("tenant"); // required so findExistingTypeSubscription() returns this NSR
        NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
        filter.setTypeFilter("cBy_OldType");
        existing.setSubscriptionFilter(filter);

        NotificationSubscriptionCollection existingCol = singletonCollection(existing);
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(existingCol);
        when(subscriptionAPI.subscribe(any())).thenThrow(new SDKException(500, "C8Y unavailable"));

        assertThrows(RuntimeException.class,
                () -> subscriptionManager.updateSubscriptionByType("t1", List.of("cBy_NewType")));

        // DELETE-first: the old subscription IS deleted before the (failing) create attempt
        verify(subscriptionAPI, times(1)).delete(existing);
        verify(subscriptionAPI, times(1)).subscribe(any());
    }

    // -------------------------------------------------------------------------
    // checkAndHandleDeduplication fast path: subscription families other than
    // STATIC/DYNAMIC (e.g. explorer) always proceed and must not pay the
    // per-device dedup lookup GETs, since their result was always discarded.
    // -------------------------------------------------------------------------

    @Test
    void subscribeDeviceAndConnect_explorerSubscription_skipsDedupLookupsEntirely() throws Exception {
        ManagedObjectRepresentation mor = morWithId("device-1");
        when(subscriptionAPI.subscribe(any())).thenReturn(stubNsr("device-1"));

        subscriptionManager
                .subscribeDeviceAndConnect("t1", mor, API.ALL, Utils.EXPLORER_DEVICE_SUBSCRIPTION)
                .get(5, TimeUnit.SECONDS);

        verify(subscriptionAPI, never()).getSubscriptionsByFilter(any());
        verify(subscriptionAPI, times(1)).subscribe(any());
    }

    // -------------------------------------------------------------------------
    // Batch dedup pre-fetch: fetchDeviceIdsForSubscription + the 5-arg
    // subscribeDeviceAndConnect overload for bulk DYNAMIC_DEVICE_SUBSCRIPTION callers.
    // -------------------------------------------------------------------------

    @Test
    void fetchDeviceIdsForSubscription_aggregatesSourceIdsAcrossAllPages() {
        NotificationSubscriptionRepresentation nsr1 = stubNsrWithSource("sub-1", "device-1");
        NotificationSubscriptionRepresentation nsr2 = stubNsrWithSource("sub-2", "device-2");
        NotificationSubscriptionCollection multi = multiCollection(nsr1, nsr2);
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(multi);

        java.util.Set<String> ids = subscriptionManager
                .fetchDeviceIdsForSubscription("t1", Utils.DYNAMIC_DEVICE_SUBSCRIPTION);

        assertEquals(java.util.Set.of("device-1", "device-2"), ids);
    }

    @Test
    void subscribeDeviceAndConnect_dynamicWithKnownId_skipsWithoutSubscribing() throws Exception {
        ManagedObjectRepresentation mor = morWithId("device-1");
        java.util.Set<String> known = java.util.Set.of("device-1");

        subscriptionManager
                .subscribeDeviceAndConnect("t1", mor, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION, known)
                .get(5, TimeUnit.SECONDS);

        verify(subscriptionAPI, never()).subscribe(any());
        verify(subscriptionAPI, never()).getSubscriptionsByFilter(any());
    }

    @Test
    void subscribeDeviceAndConnect_dynamicWithUnknownId_proceedsNormally() throws Exception {
        ManagedObjectRepresentation mor = morWithId("device-2");
        java.util.Set<String> known = java.util.Set.of("device-1"); // does not contain device-2
        when(subscriptionAPI.subscribe(any())).thenReturn(stubNsr("device-2"));
        NotificationSubscriptionCollection empty = emptyCollection();
        lenient().when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(empty);

        subscriptionManager
                .subscribeDeviceAndConnect("t1", mor, API.ALL, Utils.DYNAMIC_DEVICE_SUBSCRIPTION, known)
                .get(5, TimeUnit.SECONDS);

        verify(subscriptionAPI, times(1)).subscribe(any());
    }

    @Test
    void subscribeDeviceAndConnect_nonDynamicSubscription_ignoresKnownIdsSet() throws Exception {
        // Guard only applies when requesting DYNAMIC_DEVICE_SUBSCRIPTION — a STATIC request
        // for a device present in a "known dynamic" set must still go through the normal
        // (dynamic-beats-static) dedup path, not be silently skipped by the batch guard.
        ManagedObjectRepresentation mor = morWithId("device-1");
        java.util.Set<String> known = java.util.Set.of("device-1");
        // hasSubscriptionForDevice(DYNAMIC) -> true, so checkAndHandleDeduplication skips (dynamic beats static)
        NotificationSubscriptionRepresentation existingDynamic = stubNsrWithSource("sub-1", "device-1");
        NotificationSubscriptionCollection existingCol = singletonCollection(existingDynamic);
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(existingCol);

        subscriptionManager
                .subscribeDeviceAndConnect("t1", mor, API.ALL, Utils.STATIC_DEVICE_SUBSCRIPTION, known)
                .get(5, TimeUnit.SECONDS);

        // Reached the real dedup path (proven by the lookup GET happening) rather than the batch short-circuit
        verify(subscriptionAPI, atLeastOnce()).getSubscriptionsByFilter(any());
        verify(subscriptionAPI, never()).subscribe(any());
    }

    // -------------------------------------------------------------------------
    // resyncTypeSubscription / backfillDevicesForType: backfill existing devices
    // into an already-configured type subscription.
    // -------------------------------------------------------------------------

    @Test
    void resyncTypeSubscription_typeNotConfigured_throwsWithoutStartingBackfill() {
        NotificationSubscriptionRepresentation existing = stubNsr("type-sub");
        existing.setContext("tenant");
        NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
        filter.setTypeFilter("'otherType'");
        existing.setSubscriptionFilter(filter);
        NotificationSubscriptionCollection existingCol = singletonCollection(existing);
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(existingCol);

        assertThrows(IllegalArgumentException.class,
                () -> subscriptionManager.resyncTypeSubscription("t1", "myType"));

        verifyNoInteractions(configurationRegistry);
    }

    @Test
    void resyncTypeSubscription_noTypeSubscriptionConfiguredAtAll_throws() {
        NotificationSubscriptionCollection empty = emptyCollection();
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(empty);

        assertThrows(IllegalArgumentException.class,
                () -> subscriptionManager.resyncTypeSubscription("t1", "myType"));
    }

    @Test
    void resyncTypeSubscription_configuredType_subscribesNewDeviceAndSkipsKnownDevice() throws Exception {
        NotificationSubscriptionRepresentation existingTypeSub = stubNsr("type-sub");
        existingTypeSub.setContext("tenant");
        NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
        filter.setTypeFilter("'myType'");
        existingTypeSub.setSubscriptionFilter(filter);

        NotificationSubscriptionRepresentation alreadyDynamicNsr = stubNsrWithSource("existing-dyn", "device-1");

        // Distinguish the 3 kinds of getSubscriptionsByFilter calls by their actual filter shape:
        // (1) type-subscription lookup (subscription=MANAGEMENT, context=tenant, no source)
        // (2) bulk dedup pre-fetch (subscription=DYNAMIC, context=mo, no source)
        // (3) per-device dedup lookups for device-2 (subscription=DYNAMIC/STATIC, bySource set)
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenAnswer(inv -> {
            NotificationSubscriptionFilter f = inv.getArgument(0);
            if (Utils.MANAGEMENT_SUBSCRIPTION.equals(f.getSubscription()) && "tenant".equals(f.getContext())) {
                return singletonCollection(existingTypeSub);
            }
            if (Utils.DYNAMIC_DEVICE_SUBSCRIPTION.equals(f.getSubscription()) && f.getSource() == null) {
                return singletonCollection(alreadyDynamicNsr);
            }
            return emptyCollection(); // per-device dedup lookups for device-2 -> not subscribed yet
        });
        when(subscriptionAPI.subscribe(any())).thenReturn(stubNsr("device-2"));

        C8YAgent mockC8yAgent = mock(C8YAgent.class);
        when(configurationRegistry.getC8yAgent()).thenReturn(mockC8yAgent);
        doAnswer(inv -> {
            Consumer<ManagedObjectRepresentation> consumer = inv.getArgument(3);
            consumer.accept(morWithId("device-1")); // already dynamically subscribed -> should be skipped
            consumer.accept(morWithId("device-2")); // new -> should be subscribed
            return null;
        }).when(mockC8yAgent).forEachManagedObjectByType(eq("t1"), eq("myType"), any(), any());

        subscriptionManager.resyncTypeSubscription("t1", "myType");

        verify(mockC8yAgent, timeout(5000)).createLoggingEvent(
                contains("finished"),
                eq(LoggingEventType.BACKFILL_SUBSCRIPTION_EVENT_TYPE),
                any(), eq("t1"),
                argThat((Map<String, String> props) -> "1".equals(props.get("subscribed"))
                        && "1".equals(props.get("skipped"))
                        && "0".equals(props.get("failed"))));

        // Only device-2 was actually subscribed; device-1 was skipped via the batch pre-fetch
        verify(subscriptionAPI, times(1)).subscribe(any());
    }

    @Test
    void resyncTypeSubscription_deviceSubscribeFails_countedAsFailedNotThrown() throws Exception {
        NotificationSubscriptionRepresentation existingTypeSub = stubNsr("type-sub");
        existingTypeSub.setContext("tenant");
        NotificationSubscriptionFilterRepresentation filter = new NotificationSubscriptionFilterRepresentation();
        filter.setTypeFilter("'myType'");
        existingTypeSub.setSubscriptionFilter(filter);

        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenAnswer(inv -> {
            NotificationSubscriptionFilter f = inv.getArgument(0);
            if (Utils.MANAGEMENT_SUBSCRIPTION.equals(f.getSubscription()) && "tenant".equals(f.getContext())) {
                return singletonCollection(existingTypeSub);
            }
            return emptyCollection();
        });
        when(subscriptionAPI.subscribe(any())).thenThrow(new SDKException(500, "C8Y unavailable"));

        C8YAgent mockC8yAgent = mock(C8YAgent.class);
        when(configurationRegistry.getC8yAgent()).thenReturn(mockC8yAgent);
        doAnswer(inv -> {
            Consumer<ManagedObjectRepresentation> consumer = inv.getArgument(3);
            consumer.accept(morWithId("device-1"));
            return null;
        }).when(mockC8yAgent).forEachManagedObjectByType(eq("t1"), eq("myType"), any(), any());

        subscriptionManager.resyncTypeSubscription("t1", "myType");

        verify(mockC8yAgent, timeout(5000)).createLoggingEvent(
                contains("finished"),
                eq(LoggingEventType.BACKFILL_SUBSCRIPTION_EVENT_TYPE),
                any(), eq("t1"),
                argThat((Map<String, String> props) -> "0".equals(props.get("subscribed"))
                        && "1".equals(props.get("failed"))));
    }

    // -------------------------------------------------------------------------
    // M2: unsubscribe last device triggers connectionManager.disconnect()
    // -------------------------------------------------------------------------

    @Test
    void unsubscribeDeviceAndDisconnect_lastDevice_disconnectIsCalled() {
        // After unsubscribe, all subscription queries return empty → shouldDisconnect = true
        NotificationSubscriptionCollection empty3 = emptyCollection();
        when(subscriptionAPI.getSubscriptionsByFilter(any())).thenReturn(empty3);
        lenient().doNothing().when(subscriptionAPI).delete(any());

        ManagedObjectRepresentation mor = morWithId("device-1");
        subscriptionManager.unsubscribeDeviceAndDisconnect("t1", mor, Utils.STATIC_DEVICE_SUBSCRIPTION);

        verify(connectionManager, atLeastOnce()).disconnect("t1");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ManagedObjectRepresentation morWithId(String id) {
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(GId.asGId(id));
        return mor;
    }

    private NotificationSubscriptionRepresentation stubNsr(String id) {
        NotificationSubscriptionRepresentation nsr = new NotificationSubscriptionRepresentation();
        nsr.setId(GId.asGId(id));
        return nsr;
    }

    private NotificationSubscriptionRepresentation stubNsrWithSource(String id, String sourceDeviceId) {
        NotificationSubscriptionRepresentation nsr = stubNsr(id);
        nsr.setSource(morWithId(sourceDeviceId));
        return nsr;
    }

    private NotificationSubscriptionCollection multiCollection(NotificationSubscriptionRepresentation... nsrs) {
        PagedNotificationSubscriptionCollectionRepresentation paged =
                mock(PagedNotificationSubscriptionCollectionRepresentation.class);
        when(paged.allPages()).thenReturn(List.of(nsrs));
        NotificationSubscriptionCollection col = mock(NotificationSubscriptionCollection.class);
        when(col.get()).thenReturn(paged);
        return col;
    }

    private NotificationSubscriptionCollection emptyCollection() {
        PagedNotificationSubscriptionCollectionRepresentation paged =
                mock(PagedNotificationSubscriptionCollectionRepresentation.class);
        when(paged.allPages()).thenReturn(List.of());
        NotificationSubscriptionCollection col = mock(NotificationSubscriptionCollection.class);
        when(col.get()).thenReturn(paged);
        return col;
    }

    private NotificationSubscriptionCollection singletonCollection(NotificationSubscriptionRepresentation nsr) {
        PagedNotificationSubscriptionCollectionRepresentation paged =
                mock(PagedNotificationSubscriptionCollectionRepresentation.class);
        when(paged.allPages()).thenReturn(List.of(nsr));
        NotificationSubscriptionCollection col = mock(NotificationSubscriptionCollection.class);
        when(col.get()).thenReturn(paged);
        return col;
    }
}
