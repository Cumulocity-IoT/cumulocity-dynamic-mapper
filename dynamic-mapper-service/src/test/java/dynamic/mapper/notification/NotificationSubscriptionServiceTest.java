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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.model.API;
import dynamic.mapper.model.device.Device;

@ExtendWith(MockitoExtension.class)
class NotificationSubscriptionServiceTest {

    @Mock
    private C8YAgent c8yAgent;
    @Mock
    private ServiceRegistry serviceRegistry;
    @Mock
    private NotificationSubscriber subscriber;

    private NotificationSubscriptionService service;

    @BeforeEach
    void setUp() {
        lenient().when(serviceRegistry.getC8yAgent()).thenReturn(c8yAgent);
        lenient().when(subscriber.getSubscriptionsByDeviceType("t1")).thenReturn(typeResponse());
        service = new NotificationSubscriptionService(c8yAgent, serviceRegistry, subscriber);
    }

    // ---- group move ordering ------------------------------------------------------------

    @Test
    void updateGroupSubscription_removesOldGroupsBeforeAddingNewOnes() {
        Device groupA = device("A"), groupB = device("B"), shared = device("d1");
        stubGroups(groupA); // currently subscribed: A only
        stubMo("A", null);
        stubMo("B", null);
        stubMo("d1", null);
        stubMembers("A", shared);
        stubMembers("B", shared);

        service.updateGroupSubscription("t1", request(groupB));

        InOrder order = inOrder(subscriber);
        order.verify(subscriber).unsubscribeByDeviceGroup(eq("t1"), argThat(m -> "A".equals(idOf(m))));
        order.verify(subscriber).subscribeByDeviceGroup(eq("t1"), argThat(m -> "B".equals(idOf(m))));
    }

    @Test
    void updateGroupSubscription_deviceMovingBetweenGroups_isNotUnsubscribed() {
        Device groupA = device("A"), groupB = device("B"), shared = device("d1");
        stubGroups(groupA);
        stubMo("A", null);
        stubMo("B", null);
        stubMo("d1", null);
        stubMembers("A", shared);
        stubMembers("B", shared);

        service.updateGroupSubscription("t1", request(groupB));

        verify(subscriber, never()).unsubscribeDeviceAndDisconnect(any(), any(), any());
    }

    // ---- overlap protection -------------------------------------------------------------

    @Test
    void updateGroupSubscription_removedGroup_keepsDevicesCoveredByRemainingGroupOrType() {
        Device groupA = device("A"), groupC = device("C");
        Device inC = device("inC"), typed = device("typed"), plain = device("plain");
        stubGroups(groupA, groupC); // A and C subscribed; desired = C only
        stubMo("A", null);
        stubMo("C", null);
        stubMo("inC", null);
        stubMo("typed", "cBy_Typed");
        stubMo("plain", "other");
        stubMembers("A", inC, typed, plain);
        stubMembers("C", inC);
        when(subscriber.getSubscriptionsByDeviceType("t1")).thenReturn(typeResponse("cBy_Typed"));

        service.updateGroupSubscription("t1", request(groupC));

        verify(subscriber).unsubscribeDeviceAndDisconnect(eq("t1"), argThat(m -> "plain".equals(idOf(m))),
                eq(Utils.DYNAMIC_DEVICE_SUBSCRIPTION));
        verify(subscriber, never()).unsubscribeDeviceAndDisconnect(eq("t1"),
                argThat(m -> "inC".equals(idOf(m)) || "typed".equals(idOf(m))), any());
    }

    @Test
    void deleteGroupSubscription_keepsDevicesCoveredByOtherGroup() {
        Device groupA = device("A"), groupC = device("C");
        Device inC = device("inC"), plain = device("plain");
        stubGroups(groupA, groupC);
        stubMo("C", null);
        stubMo("inC", null);
        stubMo("plain", null);
        stubMembers("A", inC, plain);
        stubMembers("C", inC);

        service.deleteGroupSubscription("t1", mo("A", null));

        verify(subscriber).unsubscribeDeviceAndDisconnect(eq("t1"), argThat(m -> "plain".equals(idOf(m))),
                eq(Utils.DYNAMIC_DEVICE_SUBSCRIPTION));
        verify(subscriber, never()).unsubscribeDeviceAndDisconnect(eq("t1"),
                argThat(m -> "inC".equals(idOf(m))), any());
    }

    // ---- awaiting the asynchronous subscribe futures ------------------------------------

    @Test
    void createDeviceSubscription_failedFuture_reportsErrorWithDeviceId() {
        stubMo("d1", null);
        stubMo("d2", null);
        stubMembers("d1", device("d1"));
        stubMembers("d2", device("d2"));
        when(subscriber.subscribeDeviceAndConnect(eq("t1"), argThat(m -> "d1".equals(idOf(m))), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null)); // dedup skip: not a failure
        when(subscriber.subscribeDeviceAndConnect(eq("t1"), argThat(m -> "d2".equals(idOf(m))), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        NotificationSubscriptionResponse response = service.createDeviceSubscription("t1",
                request(device("d1"), device("d2")), Utils.STATIC_DEVICE_SUBSCRIPTION);

        assertEquals(NotificationSubscriptionResponse.SubscriptionStatus.ERROR, response.getStatus());
        assertEquals(List.of("d2"), response.getFailedDeviceIds());
    }

    @Test
    void createDeviceSubscription_allSucceedOrSkipped_isActive() {
        stubMo("d1", null);
        stubMembers("d1", device("d1"));
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        NotificationSubscriptionResponse response = service.createDeviceSubscription("t1",
                request(device("d1")), Utils.STATIC_DEVICE_SUBSCRIPTION);

        assertEquals(NotificationSubscriptionResponse.SubscriptionStatus.ACTIVE, response.getStatus());
        assertTrue(response.getFailedDeviceIds().isEmpty());
    }

    @Test
    void createDeviceSubscription_overlappingRoots_subscribesEachDeviceOnce() {
        stubMo("root1", null);
        stubMo("root2", null);
        stubMo("shared", null);
        stubMembers("root1", device("shared"));
        stubMembers("root2", device("shared"));
        when(subscriber.subscribeDeviceAndConnect(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        NotificationSubscriptionResponse response = service.createDeviceSubscription("t1",
                request(device("root1"), device("root2")), Utils.STATIC_DEVICE_SUBSCRIPTION);

        verify(subscriber, times(1)).subscribeDeviceAndConnect(any(), any(), any(), any());
        assertEquals(1, response.getDevices().size());
    }

    // ---- helpers ------------------------------------------------------------------------

    private Device device(String id) {
        Device d = new Device();
        d.setId(id);
        return d;
    }

    private ManagedObjectRepresentation mo(String id, String type) {
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(GId.asGId(id));
        mor.setType(type);
        return mor;
    }

    private String idOf(ManagedObjectRepresentation mor) {
        return mor.getId().getValue();
    }

    private void stubMo(String id, String type) {
        lenient().when(c8yAgent.getManagedObjectForId("t1", id, false)).thenReturn(mo(id, type));
    }

    /** What findAllRelatedDevicesByMO returns for the given root MO id. */
    private void stubMembers(String rootId, Device... members) {
        lenient().when(subscriber.findAllRelatedDevicesByMO(eq("t1"),
                argThat(m -> m != null && rootId.equals(idOf(m))), any(), anyBoolean()))
                .thenAnswer(inv -> new ArrayList<>(List.of(members)));
    }

    private void stubGroups(Device... groups) {
        NotificationSubscriptionResponse current = NotificationSubscriptionResponse.builder()
                .devices(new ArrayList<>(List.of(groups))).build();
        lenient().when(subscriber.getSubscriptionsByDeviceGroup("t1")).thenReturn(current);
    }

    private NotificationSubscriptionResponse typeResponse(String... types) {
        return NotificationSubscriptionResponse.builder().types(new ArrayList<>(List.of(types))).build();
    }

    private NotificationSubscriptionRequest request(Device... devices) {
        NotificationSubscriptionRequest r = new NotificationSubscriptionRequest();
        r.setApi(API.ALL);
        r.setDevices(new ArrayList<>(List.of(devices)));
        return r;
    }
}
