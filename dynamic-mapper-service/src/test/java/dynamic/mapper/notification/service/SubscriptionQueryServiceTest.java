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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionApi;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionCollection;
import com.cumulocity.sdk.client.messaging.notifications.NotificationSubscriptionFilter;
import com.cumulocity.sdk.client.messaging.notifications.PagedNotificationSubscriptionCollectionRepresentation;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.NotificationSubscriptionResponse;
import dynamic.mapper.notification.Utils;

/**
 * Unit tests for {@link SubscriptionQueryService}.
 *
 * <p>
 * Focus: the M10 fix — when a subscription's device managed object can no longer
 * be found, {@code getSubscriptionsDevices} must NOT delete the subscription
 * synchronously inside the read path. Instead it schedules async cleanup via the
 * {@code virtualThreadPool}, keeping the read path free of mutations.
 * </p>
 *
 * <p>
 * Uses {@code @MockitoSettings(strictness = LENIENT)} because the service builds
 * a {@link NotificationSubscriptionFilter} internally and several collaborator
 * stubs (filter chaining, paged collection) are only exercised on some paths.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionQueryServiceTest {

    @Mock
    private NotificationSubscriptionApi subscriptionAPI;

    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private ExecutorService virtualThreadPool;

    @Mock
    private C8YAgent c8yAgent;

    @Mock
    private NotificationSubscriptionCollection subscriptionCollection;

    @Mock
    private PagedNotificationSubscriptionCollectionRepresentation pagedCollection;

    private SubscriptionQueryService queryService;

    private static final String TEST_TENANT = "t1";
    private static final String DEVICE_ID = "d1";

    @BeforeEach
    void setUp() {
        queryService = new SubscriptionQueryService(
                subscriptionAPI, subscriptionsService, configurationRegistry, virtualThreadPool);

        // runForTenant(tenant, Runnable) -> invoke the runnable inline so the read
        // logic executes on the calling thread within the test.
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return null;
        }).when(subscriptionsService).runForTenant(eq(TEST_TENANT), any(Runnable.class));

        // The whole getSubscriptionsByFilter(...).get().allPages() chain.
        when(subscriptionAPI.getSubscriptionsByFilter(any(NotificationSubscriptionFilter.class)))
                .thenReturn(subscriptionCollection);
        when(subscriptionCollection.get()).thenReturn(pagedCollection);
    }

    /**
     * Builds a "device" (context = "mo") subscription pointing at the given source
     * device id, so that {@link SubscriptionQueryService#processDeviceSubscription}
     * treats it as a device subscription rather than a tenant one.
     */
    private NotificationSubscriptionRepresentation deviceSubscription(String subId, String sourceDeviceId) {
        NotificationSubscriptionRepresentation nsr = new NotificationSubscriptionRepresentation();
        GId id = new GId();
        id.setValue(subId);
        nsr.setId(id);
        nsr.setContext("mo");
        nsr.setSubscription(Utils.STATIC_DEVICE_SUBSCRIPTION);

        ManagedObjectRepresentation source = new ManagedObjectRepresentation();
        GId sourceId = new GId();
        sourceId.setValue(sourceDeviceId);
        source.setId(sourceId);
        nsr.setSource(source);
        return nsr;
    }

    @Test
    void getSubscriptionsDevices_deviceMONotFound_schedulesAsyncDeletionWithoutSyncDelete() {
        // Arrange: one device subscription for "d1"
        NotificationSubscriptionRepresentation nsr = deviceSubscription("sub-1", DEVICE_ID);
        when(pagedCollection.allPages()).thenReturn(Collections.singletonList(nsr));

        // The device MO no longer exists.
        when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        when(c8yAgent.getManagedObjectForId(eq(TEST_TENANT), eq(DEVICE_ID), anyBoolean(), anyBoolean()))
                .thenReturn(null);

        // Act
        NotificationSubscriptionResponse response = queryService.getSubscriptionsDevices(
                TEST_TENANT, null, Utils.STATIC_DEVICE_SUBSCRIPTION);

        // M10 fixed: deletion is async via virtualThreadPool.submit() — never called synchronously
        verify(subscriptionAPI, never()).delete(any(NotificationSubscriptionRepresentation.class));

        // M10 fixed: a cleanup task was submitted to the thread pool instead
        verify(virtualThreadPool, times(1)).submit(any(Runnable.class));

        // The stale device is excluded from the response.
        assertNotNull(response);
        assertNotNull(response.getDevices());
        assertTrue(response.getDevices().isEmpty(),
                "Device with missing MO must not appear in the returned devices list");
    }

    @Test
    void getSubscriptionsDevices_deviceMOExists_doesNotDeleteSubscription() {
        // Arrange: one device subscription for "d1"
        NotificationSubscriptionRepresentation nsr = deviceSubscription("sub-1", DEVICE_ID);
        when(pagedCollection.allPages()).thenReturn(Collections.singletonList(nsr));

        // The device MO exists.
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        GId morId = new GId();
        morId.setValue(DEVICE_ID);
        mor.setId(morId);
        mor.setName("Device One");
        mor.setType("c8y_Device");

        when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        when(c8yAgent.getManagedObjectForId(eq(TEST_TENANT), eq(DEVICE_ID), anyBoolean(), anyBoolean()))
                .thenReturn(mor);

        // Act
        NotificationSubscriptionResponse response = queryService.getSubscriptionsDevices(
                TEST_TENANT, null, Utils.STATIC_DEVICE_SUBSCRIPTION);

        // Assert: no deletion happens when the MO is present.
        verify(subscriptionAPI, never()).delete(any(NotificationSubscriptionRepresentation.class));

        assertNotNull(response);
        assertNotNull(response.getDevices());
        assertEquals(1, response.getDevices().size());
        assertEquals(DEVICE_ID, response.getDevices().get(0).getId());
    }

    @Test
    void getSubscriptionsDevices_noSubscriptions_returnsEmptyResponse() {
        // Arrange: filter returns no subscriptions at all.
        when(pagedCollection.allPages()).thenReturn(Collections.<NotificationSubscriptionRepresentation>emptyList());

        // Act
        NotificationSubscriptionResponse response = queryService.getSubscriptionsDevices(
                TEST_TENANT, null, Utils.STATIC_DEVICE_SUBSCRIPTION);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getDevices());
        assertTrue(response.getDevices().isEmpty());

        // No MO lookups and no deletions should occur for an empty result set.
        verify(subscriptionAPI, never()).delete(any(NotificationSubscriptionRepresentation.class));
    }

    @Test
    void getSubscriptionsDevices_skipsTenantContextSubscriptions() {
        // A "tenant" context subscription must be ignored entirely (no MO lookup,
        // no deletion, not added to the device list).
        NotificationSubscriptionRepresentation tenantSub = deviceSubscription("sub-tenant", DEVICE_ID);
        tenantSub.setContext("tenant");
        NotificationSubscriptionRepresentation deviceSub = deviceSubscription("sub-device", "d2");

        when(pagedCollection.allPages()).thenReturn(Arrays.asList(tenantSub, deviceSub));

        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        GId morId = new GId();
        morId.setValue("d2");
        mor.setId(morId);
        mor.setName("Device Two");
        when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        when(c8yAgent.getManagedObjectForId(eq(TEST_TENANT), eq("d2"), anyBoolean(), anyBoolean()))
                .thenReturn(mor);

        // Act
        NotificationSubscriptionResponse response = queryService.getSubscriptionsDevices(
                TEST_TENANT, null, Utils.STATIC_DEVICE_SUBSCRIPTION);

        // Assert: only the "mo" context device is returned.
        assertNotNull(response);
        List<dynamic.mapper.model.Device> devices = response.getDevices();
        assertNotNull(devices);
        assertEquals(1, devices.size());
        assertEquals("d2", devices.get(0).getId());
        verify(subscriptionAPI, never()).delete(any(NotificationSubscriptionRepresentation.class));
    }

    @Test
    void getSubscriptionsDevices_nullTenant_throwsIllegalArgument() {
        // The service guards against a null tenant up front.
        try {
            queryService.getSubscriptionsDevices(null, null, Utils.STATIC_DEVICE_SUBSCRIPTION);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException for null tenant");
        } catch (IllegalArgumentException expected) {
            assertEquals("Tenant cannot be null", expected.getMessage());
        }
    }
}
