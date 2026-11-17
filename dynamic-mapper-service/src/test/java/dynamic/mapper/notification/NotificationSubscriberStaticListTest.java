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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dynamic.mapper.model.device.Device;
import dynamic.mapper.notification.service.SubscriptionManager;
import dynamic.mapper.notification.service.SubscriptionQueryService;

/**
 * The complete static selection (what the "manage subscriptions" drawer shows and the desired-state
 * update diffs against) includes static selections that a dynamic subscription holds back.
 */
class NotificationSubscriberStaticListTest {

    private NotificationSubscriber subscriber;
    private SubscriptionQueryService queryService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    void setUp() throws Exception {
        subscriber = mock(NotificationSubscriber.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        queryService = mock(SubscriptionQueryService.class);
        subscriptionManager = mock(SubscriptionManager.class);
        set("queryService", queryService);
        set("subscriptionManager", subscriptionManager);
    }

    private void set(String field, Object value) throws Exception {
        Field f = NotificationSubscriber.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(subscriber, value);
    }

    private static Device device(String id) {
        Device d = new Device();
        d.setId(id);
        return d;
    }

    @Test
    void theFullStaticListIncludesHeldBackSelectionsWithoutDuplicates() {
        when(queryService.getSubscriptionsDevices("t1", null, Utils.STATIC_DEVICE_SUBSCRIPTION)).thenReturn(
                NotificationSubscriptionResponse.builder().devices(new ArrayList<>(List.of(device("a")))).build());
        when(subscriptionManager.shadowedStaticDevices("t1")).thenReturn(List.of(device("a"), device("b")));

        List<Device> devices = subscriber
                .getSubscriptionsDevices("t1", null, Utils.STATIC_DEVICE_SUBSCRIPTION).getDevices();

        assertEquals(List.of("a", "b"), devices.stream().map(Device::getId).toList());
    }

    @Test
    void theDynamicListAndASingleDeviceLookupAreLeftAlone() {
        when(queryService.getSubscriptionsDevices("t1", null, Utils.DYNAMIC_DEVICE_SUBSCRIPTION)).thenReturn(
                NotificationSubscriptionResponse.builder().devices(new ArrayList<>(List.of(device("a")))).build());
        when(queryService.getSubscriptionsDevices("t1", "a", Utils.STATIC_DEVICE_SUBSCRIPTION)).thenReturn(
                NotificationSubscriptionResponse.builder().devices(new ArrayList<>()).build());

        assertEquals(1, subscriber.getSubscriptionsDevices("t1", null, Utils.DYNAMIC_DEVICE_SUBSCRIPTION)
                .getDevices().size());
        assertEquals(0, subscriber.getSubscriptionsDevices("t1", "a", Utils.STATIC_DEVICE_SUBSCRIPTION)
                .getDevices().size());
        verifyNoInteractions(subscriptionManager);
    }
}
