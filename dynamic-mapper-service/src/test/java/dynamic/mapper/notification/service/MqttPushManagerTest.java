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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;

import dynamic.mapper.core.ConfigurationRegistry;

/**
 * Unit tests for {@link MqttPushManager}.
 *
 * <p>Notes on test strategy:
 * <ul>
 *   <li>{@code MqttPushManager} builds HiveMQ {@code Mqtt3AsyncClient} instances internally via
 *       a static {@code Mqtt3Client.builder()} call, which opens a real TLS socket. That code path
 *       cannot be unit-tested without a live broker (or a deeper refactor to inject a client
 *       factory). We therefore exercise the map-management logic directly by injecting mock
 *       {@link Mqtt3Client} instances into the private {@code activePushConnections} map via
 *       reflection.</li>
 *   <li>{@code Mqtt3Client#getState()} returns the {@link MqttClientState} enum. Mockito cannot
 *       mock enums, so the real constants {@code CONNECTED} / {@code DISCONNECTED} are used to
 *       drive the {@code isConnected()} branches.</li>
 *   <li>The disconnect path calls {@code client.toBlocking().disconnect()}, so each mock client
 *       is wired with a mock {@link Mqtt3BlockingClient} whose {@code disconnect()} can be
 *       verified.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MqttPushManagerTest {

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @Mock
    private ConfigurationRegistry configurationRegistry;

    private MqttPushManager pushManager;

    private static final String TENANT = "t1";
    private static final String DEVICE_ID = "device-1";

    @BeforeEach
    void setUp() {
        pushManager = new MqttPushManager(subscriptionsService, configurationRegistry);
    }

    // === Reflection helpers ===

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Mqtt3Client>> getActivePushConnections() throws Exception {
        Field field = MqttPushManager.class.getDeclaredField("activePushConnections");
        field.setAccessible(true);
        return (Map<String, Map<String, Mqtt3Client>>) field.get(pushManager);
    }

    /**
     * Injects a client into the private activePushConnections map for the given tenant/device,
     * simulating an entry that already exists (e.g. "client stored but connection still pending").
     */
    private void injectClient(String tenant, String deviceId, Mqtt3Client client) throws Exception {
        getActivePushConnections()
                .computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(deviceId, client);
    }

    /**
     * Creates a mock Mqtt3Client whose getState() returns the given real enum state and whose
     * toBlocking() returns the supplied (mock) blocking client.
     */
    private Mqtt3Client mockClient(MqttClientState state, Mqtt3BlockingClient blocking) {
        Mqtt3Client client = org.mockito.Mockito.mock(Mqtt3Client.class);
        lenient().when(client.getState()).thenReturn(state);
        lenient().when(client.toBlocking()).thenReturn(blocking);
        return client;
    }

    // === M9: client stored before connection completes ===

    /**
     * M9 fixed — A client is stored in the map before its connection future completes. A second
     * activation call for the same device now returns early for any existing entry (not just
     * connected ones), preventing the original pending client from being silently overwritten.
     */
    @Test
    void activatePushConnectivity_secondCallWhilePending_singleEntryInMap() throws Exception {
        // Arrange: a client that is stored but not yet connected (connection pending)
        Mqtt3BlockingClient blocking = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3Client pendingClient = mockClient(MqttClientState.DISCONNECTED, blocking);
        injectClient(TENANT, DEVICE_ID, pendingClient);

        Map<String, Mqtt3Client> beforeTenantMap = getActivePushConnections().get(TENANT);
        assertNotNull(beforeTenantMap);
        assertEquals(1, beforeTenantMap.size());

        // Act: a second activation for the same device while the first is still pending
        pushManager.activatePushConnectivity(TENANT, DEVICE_ID);

        // M9 fixed: early return fires for any existing entry — credentials never consulted
        verify(subscriptionsService, never()).getCredentials(TENANT);

        // The existing pending entry is untouched — still exactly one entry
        Map<String, Mqtt3Client> afterTenantMap = getActivePushConnections().get(TENANT);
        assertNotNull(afterTenantMap);
        assertEquals(1, afterTenantMap.size(),
                "M9 fixed: early return for any existing entry prevents duplicate activation");
        assertEquals(pendingClient, afterTenantMap.get(DEVICE_ID),
                "The original pending client must not be replaced");
    }

    /**
     * Complementary case: when the stored client IS connected, a second activation must return
     * early (idempotent) without consulting credentials or touching the map.
     */
    @Test
    void activatePushConnectivity_alreadyConnected_returnsEarly() throws Exception {
        Mqtt3BlockingClient blocking = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3Client connectedClient = mockClient(MqttClientState.CONNECTED, blocking);
        injectClient(TENANT, DEVICE_ID, connectedClient);

        pushManager.activatePushConnectivity(TENANT, DEVICE_ID);

        // Early return: credentials must never be requested
        verify(subscriptionsService, never()).getCredentials(TENANT);

        Map<String, Mqtt3Client> tenantMap = getActivePushConnections().get(TENANT);
        assertEquals(1, tenantMap.size());
        assertEquals(connectedClient, tenantMap.get(DEVICE_ID));
    }

    @Test
    void activatePushConnectivity_invalidParameters_noEntryAdded() throws Exception {
        pushManager.activatePushConnectivity(null, DEVICE_ID);
        pushManager.activatePushConnectivity(TENANT, null);
        pushManager.activatePushConnectivity(TENANT, "   ");

        assertTrue(getActivePushConnections().isEmpty(),
                "Invalid parameters must not create any push connection entry");
        verify(subscriptionsService, never()).getCredentials(org.mockito.ArgumentMatchers.anyString());
    }

    // === deactivatePushConnectivity ===

    @Test
    void deactivatePushConnectivity_removesClient() throws Exception {
        // Arrange: a connected client in the map
        Mqtt3BlockingClient blocking = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3Client client = mockClient(MqttClientState.CONNECTED, blocking);
        injectClient(TENANT, DEVICE_ID, client);

        // Act
        pushManager.deactivatePushConnectivity(TENANT, DEVICE_ID);

        // Assert: client disconnected and removed from the map
        verify(blocking, times(1)).disconnect();
        Map<String, Mqtt3Client> tenantMap = getActivePushConnections().get(TENANT);
        assertNotNull(tenantMap);
        assertFalse(tenantMap.containsKey(DEVICE_ID),
                "Device key must be removed after deactivation");
    }

    @Test
    void deactivatePushConnectivity_notConnected_removesWithoutDisconnect() throws Exception {
        Mqtt3BlockingClient blocking = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3Client client = mockClient(MqttClientState.DISCONNECTED, blocking);
        injectClient(TENANT, DEVICE_ID, client);

        pushManager.deactivatePushConnectivity(TENANT, DEVICE_ID);

        // Not connected -> disconnect() must not be called, but entry is still removed
        verify(blocking, never()).disconnect();
        assertFalse(getActivePushConnections().get(TENANT).containsKey(DEVICE_ID));
    }

    @Test
    void deactivatePushConnectivity_nullArguments_noop() throws Exception {
        injectClient(TENANT, DEVICE_ID, mockClient(MqttClientState.CONNECTED,
                org.mockito.Mockito.mock(Mqtt3BlockingClient.class)));

        pushManager.deactivatePushConnectivity(null, DEVICE_ID);
        pushManager.deactivatePushConnectivity(TENANT, null);

        // Existing entry untouched
        assertTrue(getActivePushConnections().get(TENANT).containsKey(DEVICE_ID));
    }

    // === cleanup / disconnectAll ===

    /**
     * Verifies that tearing down all connections for a tenant disconnects every connected client
     * and clears the tenant from the map.
     *
     * <p>Note: the source exposes the per-tenant teardown as {@code disconnectAll(String tenant)};
     * the no-arg {@code cleanup()} ({@code @PreDestroy}) iterates over all tenants and delegates to
     * {@code disconnectAll}. This test drives {@code disconnectAll("t1")} directly (a {@code
     * cleanup("t1")} overload does not exist in the current API).
     */
    @Test
    void cleanup_disconnectsAllAndClearsMap() throws Exception {
        Mqtt3BlockingClient blocking1 = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3BlockingClient blocking2 = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3Client client1 = mockClient(MqttClientState.CONNECTED, blocking1);
        Mqtt3Client client2 = mockClient(MqttClientState.CONNECTED, blocking2);

        injectClient(TENANT, "device-1", client1);
        injectClient(TENANT, "device-2", client2);
        assertEquals(2, getActivePushConnections().get(TENANT).size());

        // Act
        pushManager.disconnectAll(TENANT);

        // Assert: both clients disconnected, tenant removed from the map
        verify(blocking1, times(1)).disconnect();
        verify(blocking2, times(1)).disconnect();
        assertFalse(getActivePushConnections().containsKey(TENANT),
                "Tenant entry must be removed after disconnectAll");
    }

    /**
     * The no-arg {@code @PreDestroy cleanup()} hook must disconnect every connected client across
     * all tenants and leave the activePushConnections map empty.
     */
    @Test
    void cleanup_preDestroy_clearsEntireMap() throws Exception {
        Mqtt3BlockingClient blockingA = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        Mqtt3BlockingClient blockingB = org.mockito.Mockito.mock(Mqtt3BlockingClient.class);
        injectClient("t1", "device-a", mockClient(MqttClientState.CONNECTED, blockingA));
        injectClient("t2", "device-b", mockClient(MqttClientState.CONNECTED, blockingB));

        pushManager.cleanup();

        verify(blockingA, times(1)).disconnect();
        verify(blockingB, times(1)).disconnect();
        assertTrue(getActivePushConnections().isEmpty(),
                "cleanup() must clear all tenant entries");
    }

    @Test
    void disconnectAll_unknownTenant_noop() throws Exception {
        // No exception, nothing to disconnect
        pushManager.disconnectAll("unknown-tenant");
        assertTrue(getActivePushConnections().isEmpty());
    }

    // === L9: extractMqttHost strips only the scheme, returning a pure hostname ===

    /**
     * L9 fixed: the old chained replace() could corrupt hostnames containing "http"
     * as a substring and left path segments intact. The new implementation uses
     * {@link java.net.URI#getHost()} which is immune to both problems.
     */
    @Test
    void extractMqttHost_httpsUrl_returnsHostOnly() throws Exception {
        java.lang.reflect.Method m = MqttPushManager.class.getDeclaredMethod("extractMqttHost", String.class);
        m.setAccessible(true);

        // Standard HTTPS URL
        assertEquals("example.eu.cumulocity.com",
                m.invoke(pushManager, "https://example.eu.cumulocity.com"),
                "scheme and no path");

        // URL with port — URI.getHost() strips it
        assertEquals("example.eu.cumulocity.com",
                m.invoke(pushManager, "https://example.eu.cumulocity.com:8111"),
                "port must be stripped");

        // URL with path suffix — old replace() left this; URI.getHost() strips it
        assertEquals("example.eu.cumulocity.com",
                m.invoke(pushManager, "https://example.eu.cumulocity.com:8111/tenant"),
                "path suffix must be stripped");

        // L9 regression guard: hostname that contains the substring "http"
        // Old: replace("http","ws") would corrupt the hostname label
        assertEquals("my-http-proxy.eu.cumulocity.com",
                m.invoke(pushManager, "https://my-http-proxy.eu.cumulocity.com"),
                "L9 fixed: 'http' in hostname must not be replaced");
    }

    @Test
    void extractMqttHost_httpUrl_returnsHostOnly() throws Exception {
        java.lang.reflect.Method m = MqttPushManager.class.getDeclaredMethod("extractMqttHost", String.class);
        m.setAccessible(true);

        assertEquals("example.eu.cumulocity.com",
                m.invoke(pushManager, "http://example.eu.cumulocity.com"));
    }
}
