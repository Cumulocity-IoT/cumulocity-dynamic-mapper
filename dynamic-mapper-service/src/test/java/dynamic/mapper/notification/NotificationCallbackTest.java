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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dynamic.mapper.core.C8YAgent;
import dynamic.mapper.core.ConfigurationRegistry;

/**
 * Tests covering M7 — the 401-detection logic in the {@code onClose(int, String)}
 * implementations of {@link ManagementSubscriptionClient} and
 * {@link CacheInventoryUpdateClient}.
 *
 * <p>The fix uses {@code statusCode == 401} instead of {@code reason.contains("401")},
 * eliminating false negatives (genuine 401 with non-matching reason) and false positives
 * (non-401 status whose reason text happens to contain "401").
 */
@ExtendWith(MockitoExtension.class)
class NotificationCallbackTest {

    private static final String TENANT = "t1";

    @Mock
    private ConfigurationRegistry configurationRegistry;

    @Mock
    private NotificationSubscriber notificationSubscriber;

    @Mock
    private ExecutorService virtualThreadPool;

    @Mock
    private C8YAgent c8yAgent;

    @BeforeEach
    void setUp() {
        // The ManagementSubscriptionClient constructor reads both the virtual
        // thread pool and the notification subscriber; the
        // CacheInventoryUpdateClient constructor reads the C8YAgent and the
        // notification subscriber. lenient() avoids UnnecessaryStubbingException
        // for the stubs not exercised by a given test's target client.
        lenient().when(configurationRegistry.getNotificationSubscriber()).thenReturn(notificationSubscriber);
        lenient().when(configurationRegistry.getVirtualThreadPool()).thenReturn(virtualThreadPool);
        lenient().when(configurationRegistry.getC8yAgent()).thenReturn(c8yAgent);
    }

    // ---------------------------------------------------------------------
    // ManagementSubscriptionClient
    // ---------------------------------------------------------------------

    @Test
    void managementClient_onClose_statusCode401_triggersRefresh() {
        ManagementSubscriptionClient client = new ManagementSubscriptionClient(configurationRegistry, TENANT);

        // statusCode IS 401 — reason text is irrelevant.
        client.onClose(401, "Normal closure");

        // M7 fixed: genuine 401 is correctly detected via statusCode.
        verify(notificationSubscriber).setManagementConnectionStatus(eq(TENANT), eq(401));
    }

    @Test
    void managementClient_onClose_reasonContaining401ButNot401StatusCode_doesNotTriggerRefresh() {
        ManagementSubscriptionClient client = new ManagementSubscriptionClient(configurationRegistry, TENANT);

        // statusCode is NOT 401, but the reason text contains "401".
        client.onClose(1000, "error 401 Unauthorized");

        // M7 fixed: non-401 status code is not treated as 401 just because of the reason string.
        verify(notificationSubscriber, never()).setManagementConnectionStatus(eq(TENANT), eq(401));
        verify(notificationSubscriber).setManagementConnectionStatus(eq(TENANT), isNull());
    }

    @Test
    void managementClient_onClose_normal_doesNotTriggerRefresh() {
        ManagementSubscriptionClient client = new ManagementSubscriptionClient(configurationRegistry, TENANT);

        client.onClose(1000, "Normal closure");

        verify(notificationSubscriber, never()).setManagementConnectionStatus(eq(TENANT), eq(401));
        verify(notificationSubscriber).setManagementConnectionStatus(eq(TENANT), isNull());
    }

    // ---------------------------------------------------------------------
    // CacheInventoryUpdateClient
    // ---------------------------------------------------------------------

    @Test
    void cacheInventoryClient_onClose_statusCode401_triggersRefresh() {
        CacheInventoryUpdateClient client = new CacheInventoryUpdateClient(configurationRegistry, TENANT);

        // statusCode IS 401 — reason text is irrelevant.
        client.onClose(401, "Normal closure");

        // M7 fixed: genuine 401 is correctly detected via statusCode.
        verify(notificationSubscriber).setCacheInventoryConnectionStatus(eq(TENANT), eq(401));
    }

    @Test
    void cacheInventoryClient_onClose_reasonContaining401ButNot401StatusCode_doesNotTriggerRefresh() {
        CacheInventoryUpdateClient client = new CacheInventoryUpdateClient(configurationRegistry, TENANT);

        // statusCode is NOT 401, but the reason text contains "401".
        client.onClose(1000, "error 401 Unauthorized");

        // M7 fixed: non-401 status code is not treated as 401 just because of the reason string.
        verify(notificationSubscriber, never()).setCacheInventoryConnectionStatus(eq(TENANT), eq(401));
        verify(notificationSubscriber).setCacheInventoryConnectionStatus(eq(TENANT), isNull());
    }
}
