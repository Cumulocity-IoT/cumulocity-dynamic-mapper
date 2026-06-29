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
import com.cumulocity.sdk.client.messaging.notifications.Token;
import com.cumulocity.sdk.client.messaging.notifications.TokenApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenManagerTest {

    @Mock
    private TokenApi tokenApi;
    @Mock
    private MicroserviceSubscriptionsService subscriptionsService;

    private TokenManager tokenManager;

    @BeforeEach
    void setUp() {
        tokenManager = new TokenManager(tokenApi, subscriptionsService);
    }

    @AfterEach
    void tearDown() {
        tokenManager.cleanup();
    }

    // -------------------------------------------------------------------------
    // H6: startTokenRefreshScheduler concurrent invocations create duplicate schedulers
    // -------------------------------------------------------------------------

    /**
     * H6: 10 threads calling startTokenRefreshScheduler() simultaneously must
     * result in only one ScheduledExecutorService (one "token-refresh" thread).
     * The current volatile check-then-create is not atomic — multiple schedulers
     * are created and all but one are leaked.
     */
    @Test
    void startTokenRefreshScheduler_concurrentInvocations_createsSingleScheduler() throws Exception {
        int threadCount = 10;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    tokenManager.startTokenRefreshScheduler("t1");
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        Thread.sleep(100); // let thread names settle

        long liveTokenRefreshThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("token-refresh"))
                .count();

        // Contract: exactly one scheduler thread
        // Currently FAILS — H6 bug produces >= 2 "token-refresh" threads
        assertEquals(1, liveTokenRefreshThreads,
                "H6 bug: volatile check-then-create allows concurrent threads to each create a scheduler. " +
                "Found " + liveTokenRefreshThreads + " 'token-refresh' threads instead of 1.");
    }

    // -------------------------------------------------------------------------
    // H7: refreshTokens() never refreshes management or cacheInventory tokens
    // -------------------------------------------------------------------------

    /**
     * H7 fixed: refreshTokens() now refreshes all three token maps
     * (deviceTokens, managementTokens, cacheInventoryTokens).
     */
    @Test
    @SuppressWarnings("unchecked")
    void refreshTokens_refreshesAllTokenMaps() throws Exception {
        // Store tokens in all three maps
        tokenManager.storeManagementToken("t1", "mgmt-original");
        tokenManager.storeCacheInventoryToken("t1", "cache-original");
        tokenManager.storeDeviceToken("t1", "connector-1", "device-original");

        // runForEachTenant runs the lambda inline for "t1"
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(subscriptionsService).runForEachTenant(any());
        when(subscriptionsService.getTenant()).thenReturn("t1");

        // tokenApi.refresh returns a new token (same value for simplicity)
        Token refreshedToken = mock(Token.class);
        when(refreshedToken.getTokenString()).thenReturn("refreshed");
        when(tokenApi.refresh(any(Token.class))).thenReturn(refreshedToken);

        tokenManager.refreshTokens();

        // All three tokens were refreshed — 3 calls total
        verify(tokenApi, times(3)).refresh(any(Token.class));

        // Management token was refreshed
        Field managementTokensField = TokenManager.class.getDeclaredField("managementTokens");
        managementTokensField.setAccessible(true);
        Map<String, String> managementTokens = (Map<String, String>) managementTokensField.get(tokenManager);
        assertEquals("refreshed", managementTokens.get("t1"),
                "H7 fixed: management token should have been refreshed");

        // Cache inventory token was refreshed
        Field cacheTokensField = TokenManager.class.getDeclaredField("cacheInventoryTokens");
        cacheTokensField.setAccessible(true);
        Map<String, String> cacheTokens = (Map<String, String>) cacheTokensField.get(tokenManager);
        assertEquals("refreshed", cacheTokens.get("t1"),
                "H7 fixed: cacheInventory token should have been refreshed");
    }

    // -------------------------------------------------------------------------
    // Basic store / retrieve / remove
    // -------------------------------------------------------------------------

    @Test
    void storeAndRetrieveDeviceToken() throws Exception {
        tokenManager.storeDeviceToken("t1", "c1", "tok-123");

        Field field = TokenManager.class.getDeclaredField("deviceTokens");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> deviceTokens = (Map<String, Map<String, String>>) field.get(tokenManager);

        assertEquals("tok-123", deviceTokens.get("t1").get("c1"));
    }

    @Test
    void storeAndRetrieveManagementToken() throws Exception {
        tokenManager.storeManagementToken("t1", "mgmt-tok");

        Field field = TokenManager.class.getDeclaredField("managementTokens");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(tokenManager);

        assertEquals("mgmt-tok", map.get("t1"));
    }

    @Test
    void storeAndRetrieveCacheInventoryToken() throws Exception {
        tokenManager.storeCacheInventoryToken("t1", "cache-tok");

        Field field = TokenManager.class.getDeclaredField("cacheInventoryTokens");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(tokenManager);

        assertEquals("cache-tok", map.get("t1"));
    }

    /**
     * L1 fixed: unsubscribeDeviceSubscriber must remove from deviceTokens (not managementTokens).
     * unsubscribeDeviceGroupSubscriber must remove from managementTokens (not deviceTokens).
     */
    @Test
    void unsubscribeDeviceSubscriber_removesFromDeviceTokensNotManagementTokens() throws Exception {
        tokenManager.storeManagementToken("t1", "mgmt-tok");
        tokenManager.storeDeviceToken("t1", "c1", "device-tok");

        doNothing().when(tokenApi).unsubscribe(any(Token.class));
        tokenManager.unsubscribeDeviceSubscriber("t1");

        Field managementField = TokenManager.class.getDeclaredField("managementTokens");
        managementField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> managementTokens = (Map<String, String>) managementField.get(tokenManager);

        Field deviceField = TokenManager.class.getDeclaredField("deviceTokens");
        deviceField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> deviceTokens = (Map<String, Map<String, String>>) deviceField.get(tokenManager);

        // L1 fixed: device tokens removed, management token untouched
        assertNotNull(managementTokens.get("t1"),
                "managementToken must NOT be removed by unsubscribeDeviceSubscriber");
        assertNull(deviceTokens.get("t1"),
                "deviceTokens for t1 must be removed by unsubscribeDeviceSubscriber");
        verify(tokenApi, times(1)).unsubscribe(any(Token.class));
    }

    @Test
    void unsubscribeDeviceGroupSubscriber_removesFromManagementTokensNotDeviceTokens() throws Exception {
        tokenManager.storeManagementToken("t1", "mgmt-tok");
        tokenManager.storeDeviceToken("t1", "c1", "device-tok");

        doNothing().when(tokenApi).unsubscribe(any(Token.class));
        tokenManager.unsubscribeDeviceGroupSubscriber("t1");

        Field managementField = TokenManager.class.getDeclaredField("managementTokens");
        managementField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> managementTokens = (Map<String, String>) managementField.get(tokenManager);

        Field deviceField = TokenManager.class.getDeclaredField("deviceTokens");
        deviceField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> deviceTokens = (Map<String, Map<String, String>>) deviceField.get(tokenManager);

        // L1 fixed: management token removed, device tokens untouched
        assertNull(managementTokens.get("t1"),
                "managementToken must be removed by unsubscribeDeviceGroupSubscriber");
        assertNotNull(deviceTokens.get("t1"),
                "deviceTokens must NOT be removed by unsubscribeDeviceGroupSubscriber");
        verify(tokenApi, times(1)).unsubscribe(any(Token.class));
    }

    @Test
    void refreshTokens_noDeviceTokens_doesNotCallRefreshApi() {
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(subscriptionsService).runForEachTenant(any());
        when(subscriptionsService.getTenant()).thenReturn("t1");

        tokenManager.refreshTokens();

        verify(tokenApi, never()).refresh(any());
    }

    @Test
    void cleanup_shutsDownScheduler() throws Exception {
        tokenManager.startTokenRefreshScheduler("t1");

        Field field = TokenManager.class.getDeclaredField("tokenRefreshExecutor");
        field.setAccessible(true);
        ScheduledExecutorService executor = (ScheduledExecutorService) field.get(tokenManager);
        assertNotNull(executor);
        assertFalse(executor.isShutdown());

        tokenManager.cleanup();

        assertTrue(executor.isShutdown());
    }
}
