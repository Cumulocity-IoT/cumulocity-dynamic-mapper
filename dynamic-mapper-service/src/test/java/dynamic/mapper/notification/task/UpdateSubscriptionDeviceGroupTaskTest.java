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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.reliable.notification.NotificationSubscriptionRepresentation;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.notification.GroupCacheManager;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.processor.model.C8YMessage;

/**
 * Unit tests for {@link UpdateSubscriptionDeviceGroupTask}, focussing on the
 * cache-miss re-sync path added to make time-based cache expiry safe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UpdateSubscriptionDeviceGroupTaskTest {

    private static final String TENANT = "t1";
    private static final String GROUP_ID = "group-1";
    private static final String DEVICE_A = "dev-a";
    private static final String DEVICE_B = "dev-b";

    @Mock private ConfigurationRegistry configurationRegistry;
    @Mock private C8YAgent c8yAgent;
    @Mock private NotificationSubscriber notificationSubscriber;

    private GroupCacheManager groupCacheManager;

    @BeforeEach
    void setUp() {
        groupCacheManager = new GroupCacheManager(TENANT);
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        lenient().when(configurationRegistry.getNotificationSubscriber()).thenReturn(notificationSubscriber);

        // Default stub for subscribeDeviceAndConnect — returns a completed future
        lenient().when(notificationSubscriber.subscribeDeviceAndConnect(
                eq(TENANT), any(), any(API.class), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new NotificationSubscriptionRepresentation()));
    }

    // ------------------------------------------------------------------
    // Normal delta path — cache hit
    // ------------------------------------------------------------------

    @Test
    void call_cacheHit_normalDelta_subscribesNewAndRemovesGone() throws Exception {
        // Seed cache: group has device-a subscribed
        groupCacheManager.addGroup(group(GROUP_ID));
        groupCacheManager.updateSubscribedDevices(GROUP_ID, Collections.singleton(DEVICE_A));

        // Payload: device-a still present, device-b added
        C8YMessage msg = message(GROUP_ID, List.of(DEVICE_A, DEVICE_B));
        when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(DEVICE_B), anyBoolean()))
                .thenReturn(device(DEVICE_B));

        SubscriptionUpdateResult result = task(msg).call();

        assertEquals(1, result.getAddedCount(), "device-b must be subscribed");
        assertEquals(0, result.getRemovedCount(), "device-a is still present — must not be removed");
    }

    // ------------------------------------------------------------------
    // Cache-miss re-sync path
    // ------------------------------------------------------------------

    /**
     * Primary case: cache entry is absent (e.g. expired). The task must re-sync
     * from the payload instead of skipping — subscribing every device listed in
     * the payload's childAssets.
     */
    @Test
    void call_cacheMiss_withChildAssets_resyncsFromPayload() throws Exception {
        // Cache is empty — no entry for GROUP_ID

        // Payload carries device-a and device-b as current membership
        C8YMessage msg = message(GROUP_ID, List.of(DEVICE_A, DEVICE_B));

        // Group MO fetch succeeds
        when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(GROUP_ID), anyBoolean()))
                .thenReturn(group(GROUP_ID));

        // Both devices exist and can be subscribed
        when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(DEVICE_A), anyBoolean()))
                .thenReturn(device(DEVICE_A));
        when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(DEVICE_B), anyBoolean()))
                .thenReturn(device(DEVICE_B));

        SubscriptionUpdateResult result = task(msg).call();

        // Both payload devices must be subscribed
        assertEquals(2, result.getAddedCount(),
                "cache-miss re-sync must subscribe all devices from the payload");
        assertEquals(0, result.getRemovedCount(),
                "no removals — prior subscribed state is unknown");

        // Cache must be populated with the payload state for future deltas
        assertEquals(2, groupCacheManager.getSubscribedDevices(GROUP_ID).size(),
                "cache must reflect the re-synced payload state");
    }

    /**
     * Cache miss but payload has no childAssets — this is a property-only change
     * (name, description, etc.). The task must still skip subscription work, same
     * as the normal path, and must NOT attempt a re-sync.
     */
    @Test
    void call_cacheMiss_noChildAssets_skips() throws Exception {
        C8YMessage msg = new C8YMessage();
        msg.setTenant(TENANT);
        msg.setSourceId(GROUP_ID);
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "renamed-group");   // property change only
        msg.setParsedPayload(payload);

        SubscriptionUpdateResult result = task(msg).call();

        assertEquals(0, result.getAddedCount());
        // subscribeDeviceAndConnect must never be called
        verify(notificationSubscriber, never())
                .subscribeDeviceAndConnect(any(), any(), any(), any());
    }

    /**
     * Cache miss and group MO fetch from C8Y fails. The re-sync must still proceed
     * using the payload and must not propagate the fetch exception.
     */
    @Test
    void call_cacheMiss_groupMOFetchFails_stillResyncsFromPayload() throws Exception {
        C8YMessage msg = message(GROUP_ID, List.of(DEVICE_A));

        // Group MO fetch throws
        when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(GROUP_ID), anyBoolean()))
                .thenThrow(new RuntimeException("C8Y unavailable"));

        // Device MO is available for subscription
        when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(DEVICE_A), anyBoolean()))
                .thenReturn(device(DEVICE_A));

        SubscriptionUpdateResult result = task(msg).call();

        assertNotNull(result, "result must never be null");
        assertEquals(1, result.getAddedCount(),
                "device-a must still be subscribed even when group MO fetch fails");
    }

    /**
     * Cache miss with an empty childAssets list — re-sync initialises the cache
     * with an empty device set and returns an empty result.
     */
    @Test
    void call_cacheMiss_emptyChildAssets_initsCacheWithEmptySet() throws Exception {
        C8YMessage msg = message(GROUP_ID, List.of());   // no devices in payload

        lenient().when(c8yAgent.getManagedObjectForId(eq(TENANT), eq(GROUP_ID), anyBoolean()))
                .thenReturn(group(GROUP_ID));

        SubscriptionUpdateResult result = task(msg).call();

        assertEquals(0, result.getAddedCount());
        // Cache entry must exist with empty membership after re-sync
        assertNotNull(groupCacheManager.getCache().get(GROUP_ID),
                "cache entry must be created even when payload has no devices");
        assertEquals(0, groupCacheManager.getSubscribedDevices(GROUP_ID).size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UpdateSubscriptionDeviceGroupTask task(C8YMessage msg) {
        return new UpdateSubscriptionDeviceGroupTask(configurationRegistry, msg, groupCacheManager);
    }

    /** Builds a C8YMessage with a childAssets payload listing the given device IDs. */
    private C8YMessage message(String groupId, List<String> deviceIds) {
        C8YMessage msg = new C8YMessage();
        msg.setTenant(TENANT);
        msg.setSourceId(groupId);
        msg.setApi(API.INVENTORY);

        List<Map<String, Object>> refs = deviceIds.stream()
                .map(id -> {
                    Map<String, Object> mo = new HashMap<>();
                    mo.put("id", id);
                    Map<String, Object> ref = new HashMap<>();
                    ref.put("managedObject", mo);
                    return ref;
                })
                .toList();

        Map<String, Object> childAssets = new HashMap<>();
        childAssets.put("references", refs);

        Map<String, Object> payload = new HashMap<>();
        payload.put("childAssets", childAssets);
        msg.setParsedPayload(payload);
        return msg;
    }

    private ManagedObjectRepresentation group(String id) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(id));
        mo.setProperty("c8y_IsDeviceGroup", new HashMap<>());
        return mo;
    }

    private ManagedObjectRepresentation device(String id) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(new GId(id));
        mo.setProperty("c8y_IsDevice", new HashMap<>());
        return mo;
    }
}
