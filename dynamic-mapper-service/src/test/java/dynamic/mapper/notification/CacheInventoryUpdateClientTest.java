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

package dynamic.mapper.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.notification.websocket.Notification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the Notification 2.0 DELETE handling added alongside the fix in
 * {@code attic/fix/inconsistant-cache/ISSUE.md}: before this, {@code CacheInventoryUpdateClient}
 * only reacted to UPDATE notifications, so a device deleted while still subscribed for
 * inventory-cache updates never had its cache entries evicted or its subscription torn down —
 * the live notification stream did not correct the stale caches, only a later, reactive lookup
 * did.
 */
class CacheInventoryUpdateClientTest {

    private static final String TENANT = "t12345";
    private static final String SOURCE_ID = "87168024789";

    private C8YAgent c8yAgent;
    private NotificationSubscriber notificationSubscriber;
    private CacheInventoryUpdateClient client;

    @BeforeEach
    void setUp() {
        c8yAgent = mock(C8YAgent.class);
        notificationSubscriber = mock(NotificationSubscriber.class);

        ConfigurationRegistry configurationRegistry = mock(ConfigurationRegistry.class);
        when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
        when(configurationRegistry.getNotificationSubscriber()).thenReturn(notificationSubscriber);

        client = new CacheInventoryUpdateClient(configurationRegistry, TENANT);
    }

    @Test
    void onNotification_delete_evictsCachesAndUnsubscribes() {
        Notification notification = parseNotification("DELETE", "{\"id\":\"" + SOURCE_ID + "\"}");

        client.onNotification(notification);

        verify(c8yAgent, times(1)).evictDeletedManagedObjectFromCaches(TENANT, SOURCE_ID);

        var morCaptor = org.mockito.ArgumentCaptor.forClass(ManagedObjectRepresentation.class);
        verify(notificationSubscriber, times(1))
                .unsubscribeMOForInventoryCacheUpdates(eq(TENANT), morCaptor.capture());
        assertEquals(SOURCE_ID, morCaptor.getValue().getId().getValue());
    }

    @Test
    void onNotification_update_doesNotEvictCaches() {
        Notification notification = parseNotification("UPDATE", "{\"id\":\"" + SOURCE_ID + "\"}");

        client.onNotification(notification);

        verify(c8yAgent, never()).evictDeletedManagedObjectFromCaches(any(), any());
        verify(notificationSubscriber, never()).unsubscribeMOForInventoryCacheUpdates(any(), any());
        verify(c8yAgent, times(1)).updateMOInInventoryCache(eq(TENANT), eq(SOURCE_ID), any(), eq(false));
    }

    @Test
    void onNotification_create_isIgnored() {
        Notification notification = parseNotification("CREATE", "{\"id\":\"" + SOURCE_ID + "\"}");

        client.onNotification(notification);

        verify(c8yAgent, never()).evictDeletedManagedObjectFromCaches(any(), any());
        verify(c8yAgent, never()).updateMOInInventoryCache(any(), any(), any(), any());
        verify(notificationSubscriber, never()).unsubscribeMOForInventoryCacheUpdates(any(), any());
    }

    /**
     * Builds a real {@link Notification} the way {@code CustomWebSocketClient} would, by
     * running the raw wire format through {@link Notification#parse}. Header line 1 is
     * {@code "<realtimeId>/<tenant>/managedobjects"}: token[1] is the tenant
     * ({@code NotificationHelper.extractTenant} reads it), token[2] is the resource name that
     * {@code Notification.parse} maps to {@code API.INVENTORY}. Header line 2 is the operation.
     */
    private Notification parseNotification(String operation, String payload) {
        String raw = "ack-1\n"
                + "rt/" + TENANT + "/managedobjects\n"
                + operation + "\n"
                + "\n"
                + payload;
        return Notification.parse(raw);
    }
}
