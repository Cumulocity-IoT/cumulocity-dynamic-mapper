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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.java_websocket.enums.ReadyState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;

import dynamic.mapper.connector.core.registry.ConnectorRegistry;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.websocket.CustomWebSocketClient;

/**
 * Unit tests for {@link NotificationConnectionManager}.
 *
 * NotificationConnectionManager is large and tightly coupled to live WebSocket
 * connections (the private {@code connect(...)} method actually opens a
 * {@link CustomWebSocketClient}). These tests therefore focus on the four
 * behaviors that can be exercised without a real WebSocket, using reflection to
 * read/populate the private maps and to invoke private methods.
 *
 * A full integration-level test of {@code initializeManagementClient} /
 * {@code connect} would require either a live Notification 2.0 endpoint or a
 * deeper refactor that extracts the WebSocket-creation step behind an injectable
 * factory (so it can be mocked). That is out of scope here; M3 below verifies the
 * observable post-condition (the connection map) on a failing init path instead.
 *
 * M4 (the blocking conflict-retry {@code TimeUnit.SECONDS.sleep}) is intentionally
 * NOT tested — driving it would make the suite slow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationConnectionManagerTest {

    private static final String TENANT = "t1";

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;
    @Mock
    private TokenManager tokenManager;
    @Mock
    private MqttPushManager mqttPushManager;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private SubscriptionQueryService queryService;
    @Mock
    private ConfigurationRegistry configurationRegistry;
    @Mock
    private C8YAgent c8yAgent;

    private NotificationConnectionManager manager;

    @BeforeEach
    void setUp() {
        // C8YAgent is reached via configurationRegistry.getC8yAgent() in several paths
        // (connect(), disconnect()). Make it always available; stubs are lenient so
        // unused paths don't trigger UnnecessaryStubbingException.
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);

        manager = new NotificationConnectionManager(
                subscriptionsService,
                tokenManager,
                mqttPushManager,
                connectorRegistry,
                queryService,
                configurationRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Shut down any reconnect scheduler still referenced by the field so daemon
        // threads / executors do not leak across tests. (H5 also shuts down any
        // orphaned executors it observed.)
        ScheduledExecutorService exec = (ScheduledExecutorService) getField("reconnectExecutor");
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // H5 — startReconnectScheduler concurrent invocations
    // ------------------------------------------------------------------

    /**
     * H5: 10 threads call {@code startReconnectScheduler()} simultaneously. The guard is a
     * plain {@code if (reconnectExecutor == null || isShutdown())} with NO synchronization,
     * so concurrent callers can each pass the null-check and construct their own
     * {@link ScheduledExecutorService} before one wins the field assignment. Every extra
     * instance is orphaned and leaks its daemon thread — the duplicate-scheduler bug.
     *
     * We capture every distinct executor instance the field ever holds and assert exactly
     * one was created. This pins the CORRECT (synchronized) behavior; if the guard
     * regresses to the racy form, more than one instance is observed and the test fails,
     * surfacing the leak.
     */
    @Test
    void startReconnectScheduler_concurrentInvocations_onlyOneSchedulerCreated() throws Exception {
        final int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        // Every distinct executor instance the field ever held during the race.
        Set<ScheduledExecutorService> observed = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        manager.startReconnectScheduler();
                        ScheduledExecutorService current =
                                (ScheduledExecutorService) getField("reconnectExecutor");
                        if (current != null) {
                            observed.add(current);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        ScheduledExecutorService finalExecutor =
                (ScheduledExecutorService) getField("reconnectExecutor");
        assertNotNull(finalExecutor, "a reconnect executor should have been created");
        assertTrue(observed.contains(finalExecutor),
                "the field's final executor should be one of the observed instances");

        // Shut down EVERY executor that was ever constructed during the race so orphaned
        // schedulers (the H5 leak) do not leave daemon threads running after the test.
        for (ScheduledExecutorService e : observed) {
            e.shutdownNow();
        }

        assertEquals(1, observed.size(),
                "startReconnectScheduler() must create exactly one executor even under "
                        + "concurrent invocation; more than one indicates the H5 race leaking schedulers");
    }

    // ------------------------------------------------------------------
    // M3 — half-constructed state left behind on connection failure
    // ------------------------------------------------------------------

    /**
     * M3: When the management-subscription query fails, {@code initializeManagementClient}
     * catches the {@link ExecutionException} and returns without ever creating a WebSocket.
     * We assert the connection map ({@code managementClients}) does NOT contain the tenant
     * afterwards — no half-open connection is registered.
     *
     * Note: the implementation DOES leave a callback in {@code managementCallbacks} (added
     * via computeIfAbsent before the failing query). That is the latent leak the M3 ticket
     * describes; this test pins the connection-map post-condition, which is the one safely
     * observable without a live WebSocket.
     */
    @Test
    @SuppressWarnings("unchecked")
    void initializeManagementClient_connectionFails_removesCallbackFromMap() throws Exception {
        // Simulate the device-group subscription query failing — the method body then hits
        // the ExecutionException catch and skips connection creation entirely.
        CompletableFuture<List<NotificationSubscriptionRepresentation>> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("simulated query failure"));
        when(queryService.getNotificationSubscriptionForDeviceGroup(eq(TENANT), any(), any()))
                .thenReturn(failing);

        manager.initializeManagementClient(TENANT);

        // M3 fixed: no WebSocket connection registered
        Map<String, CustomWebSocketClient> managementClients =
                (Map<String, CustomWebSocketClient>) getField("managementClients");
        assertFalse(managementClients.containsKey(TENANT),
                "no management WebSocket connection should be registered after a failed init");

        // M3 fixed: pre-registered callbacks must also be removed on failure (no resource leak)
        Map<?, ?> managementCallbacks = (Map<?, ?>) getField("managementCallbacks");
        assertFalse(managementCallbacks.containsKey(TENANT),
                "managementCallbacks must be cleaned up after a failed init");

        Map<?, ?> cacheInventoryCallbacks = (Map<?, ?>) getField("cacheInventoryCallbacks");
        assertFalse(cacheInventoryCallbacks.containsKey(TENANT),
                "cacheInventoryCallbacks must be cleaned up after a failed init");
    }

    // ------------------------------------------------------------------
    // M6 — reconnectDeviceClients break skips remaining bad clients
    // ------------------------------------------------------------------

    /**
     * M6 fixed: {@code reconnectDeviceClients} no longer breaks after calling
     * {@code initializeStaticDeviceClient} for a NOT_YET_CONNECTED client. The loop continues
     * so the subsequent CLOSED client ({@code c2}) is also reconnected in the same cycle.
     */
    @Test
    @SuppressWarnings("unchecked")
    void reconnectDeviceClients_breakOnFirstBadClient_secondClientAlsoReconnected() throws Exception {
        CustomWebSocketClient c1NotYetConnected = mock(CustomWebSocketClient.class);
        CustomWebSocketClient c2Closed = mock(CustomWebSocketClient.class);

        // c1: NOT_YET_CONNECTED — shouldReconnectClient() is true, and because the state is
        // NOT_YET_CONNECTED the loop re-initializes and breaks.
        lenient().when(c1NotYetConnected.isOpen()).thenReturn(false);
        lenient().when(c1NotYetConnected.getReadyState()).thenReturn(ReadyState.NOT_YET_CONNECTED);

        // c2: CLOSED and not open — shouldReconnectClient() is true; the happy path would
        // call c2.reconnect(). The M6 break prevents that.
        lenient().when(c2Closed.isOpen()).thenReturn(false);
        lenient().when(c2Closed.getReadyState()).thenReturn(ReadyState.CLOSED);

        // Insertion-ordered inner map so c1 (NOT_YET_CONNECTED) is visited first, triggering
        // the break before c2 is reached.
        Map<String, CustomWebSocketClient> inner = new LinkedHashMap<>();
        inner.put("connA", c1NotYetConnected);
        inner.put("connB", c2Closed);

        Map<String, Map<String, CustomWebSocketClient>> staticDeviceClients =
                (Map<String, Map<String, CustomWebSocketClient>>) getField("staticDeviceClients");
        staticDeviceClients.put(TENANT, inner);

        // initializeStaticDeviceClient (called from the NOT_YET_CONNECTED branch) hits the
        // query service; stub it to return an empty list so it returns quickly without a WS.
        when(queryService.getNotificationSubscriptionForDevices(eq(TENANT), any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));

        invokeReconnectDeviceClients(TENANT);

        // M6 fixed: the CLOSED client c2 IS reconnected because the loop no longer breaks
        // after re-initializing on the first (NOT_YET_CONNECTED) client.
        verify(c2Closed, times(1)).reconnect();
    }

    // ------------------------------------------------------------------
    // L5 — break in dynamic reconnect loop skips remaining CLOSED clients
    // ------------------------------------------------------------------

    /**
     * L5 fixed: same as the M6 fix for the static loop — the dynamic loop must also
     * continue past a NOT_YET_CONNECTED client after calling
     * {@code initializeDynamicDeviceClient}. Previously a {@code break} caused any
     * subsequent CLOSED client ({@code c2}) to be skipped.
     */
    @Test
    @SuppressWarnings("unchecked")
    void reconnectDeviceClients_dynamicBreakOnFirstBadClient_secondClientAlsoReconnected() throws Exception {
        CustomWebSocketClient c1NotYetConnected = mock(CustomWebSocketClient.class);
        CustomWebSocketClient c2Closed = mock(CustomWebSocketClient.class);

        lenient().when(c1NotYetConnected.isOpen()).thenReturn(false);
        lenient().when(c1NotYetConnected.getReadyState()).thenReturn(ReadyState.NOT_YET_CONNECTED);

        lenient().when(c2Closed.isOpen()).thenReturn(false);
        lenient().when(c2Closed.getReadyState()).thenReturn(ReadyState.CLOSED);

        Map<String, CustomWebSocketClient> inner = new LinkedHashMap<>();
        inner.put("connA", c1NotYetConnected);
        inner.put("connB", c2Closed);

        Map<String, Map<String, CustomWebSocketClient>> dynamicDeviceClients =
                (Map<String, Map<String, CustomWebSocketClient>>) getField("dynamicDeviceClients");
        dynamicDeviceClients.put(TENANT, inner);

        // reconnectDeviceClients processes staticDeviceClients first and returns early if null;
        // populate with an empty map so the method reaches the dynamic section
        Map<String, Map<String, CustomWebSocketClient>> staticDeviceClients =
                (Map<String, Map<String, CustomWebSocketClient>>) getField("staticDeviceClients");
        staticDeviceClients.put(TENANT, new ConcurrentHashMap<>());

        // initializeDynamicDeviceClient queries this; return empty list so it exits quickly
        when(queryService.getNotificationSubscriptionForDevices(eq(TENANT), any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));

        invokeReconnectDeviceClients(TENANT);

        // L5 fixed: loop does not break after re-initializing on first client;
        // the CLOSED second client is also reconnected
        verify(c2Closed, times(1)).reconnect();
    }

    // ------------------------------------------------------------------
    // M8 — double-cleanup of MQTT connections
    // ------------------------------------------------------------------

    /**
     * M8: {@code disconnect(tenant)} must call {@code mqttPushManager.disconnectAll(tenant)}
     * exactly once. Populate one static device client, then disconnect and verify the MQTT
     * cleanup is invoked precisely once (no double-cleanup).
     */
    @Test
    @SuppressWarnings("unchecked")
    void disconnect_mqttPushManagerDisconnectAllCalledExactlyOnce() throws Exception {
        CustomWebSocketClient client = mock(CustomWebSocketClient.class);
        lenient().when(client.isOpen()).thenReturn(true);

        Map<String, Map<String, CustomWebSocketClient>> staticDeviceClients =
                (Map<String, Map<String, CustomWebSocketClient>>) getField("staticDeviceClients");
        Map<String, CustomWebSocketClient> inner = new ConcurrentHashMap<>();
        inner.put("connA", client);
        staticDeviceClients.put(TENANT, inner);

        manager.disconnect(TENANT);

        verify(mqttPushManager, times(1)).disconnectAll(TENANT);
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    private Object getField(String name) throws Exception {
        Field f = NotificationConnectionManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(manager);
    }

    private void invokeReconnectDeviceClients(String tenant) throws Exception {
        Method m = NotificationConnectionManager.class
                .getDeclaredMethod("reconnectDeviceClients", String.class);
        m.setAccessible(true);
        m.invoke(manager, tenant);
    }
}
