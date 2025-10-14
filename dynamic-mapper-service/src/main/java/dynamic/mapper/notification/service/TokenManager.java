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

import com.cumulocity.rest.representation.reliable.notification.NotificationTokenRequestRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.messaging.notifications.Token;
import com.cumulocity.sdk.client.messaging.notifications.TokenApi;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages notification tokens (creation, refresh, cleanup).
 */
@Slf4j
@Service
public class TokenManager {

    private static final Integer TOKEN_REFRESH_INTERVAL_HOURS = 12;

    @Autowired
    private TokenApi tokenApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    // Token storage
    private final Map<String, Map<String, String>> deviceTokens = new ConcurrentHashMap<>();
    private final Map<String, String> managementTokens = new ConcurrentHashMap<>();
    private final Map<String, String> cacheInventoryTokens = new ConcurrentHashMap<>();

    // Token refresh executor
    private volatile ScheduledExecutorService tokenRefreshExecutor;

    // === Public API ===

    public String createToken(String subscription, String subscriber) {
        if (subscription == null || subscriber == null) {
            throw new IllegalArgumentException("Subscription and subscriber cannot be null");
        }

        try {
            NotificationTokenRequestRepresentation tokenRequest = new NotificationTokenRequestRepresentation(
                    subscriber, subscription, 1440, false);
            return tokenApi.create(tokenRequest).getTokenString();
        } catch (Exception e) {
            log.error("Error creating token for subscription {} and subscriber {}: {}",
                    subscription, subscriber, e.getMessage(), e);
            throw new RuntimeException("Failed to create token: " + e.getMessage(), e);
        }
    }

    public void storeDeviceToken(String tenant, String connectorId, String token) {
        deviceTokens.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                .put(connectorId, token);
    }

    public void storeManagementToken(String tenant, String token) {
        managementTokens.put(tenant, token);
    }

    public void storeCacheInventoryToken(String tenant, String token) {
        cacheInventoryTokens.put(tenant, token);
    }

    public void unsubscribeDeviceSubscriber(String tenant) {
        if (tenant == null) {
            return;
        }

        String token = managementTokens.remove(tenant);
        if (token != null) {
            try {
                tokenApi.unsubscribe(new Token(token));
                log.info("{} - Unsubscribed device subscriber", tenant);
            } catch (Exception e) {
                log.warn("{} - Error unsubscribing device subscriber: {}", tenant, e.getMessage());
            }
        }
    }

    public void unsubscribeDeviceGroupSubscriber(String tenant) {
        if (tenant == null) {
            return;
        }

        Map<String, String> tenantTokens = deviceTokens.remove(tenant);
        if (tenantTokens != null) {
            int unsubscribedCount = 0;
            for (String token : tenantTokens.values()) {
                try {
                    tokenApi.unsubscribe(new Token(token));
                    unsubscribedCount++;
                } catch (Exception e) {
                    log.warn("{} - Error unsubscribing token: {}", tenant, e.getMessage());
                }
            }
            log.info("{} - Unsubscribed {} device group subscribers", tenant, unsubscribedCount);
        }
    }

    public void unsubscribeDeviceSubscriberByConnector(String tenant, String connectorIdentifier) {
        if (tenant == null || connectorIdentifier == null) {
            return;
        }

        Map<String, String> tenantTokens = deviceTokens.get(tenant);
        if (tenantTokens != null) {
            String token = tenantTokens.remove(connectorIdentifier);
            if (token != null) {
                try {
                    tokenApi.unsubscribe(new Token(token));
                    log.info("{} - Unsubscribed connector {}", tenant, connectorIdentifier);
                } catch (SDKException e) {
                    log.error("{} - Could not unsubscribe connector {}: {}",
                            tenant, connectorIdentifier, e.getMessage(), e);
                }
            }
        }
    }

    public void startTokenRefreshScheduler() {
        if (tokenRefreshExecutor == null || tokenRefreshExecutor.isShutdown()) {
            tokenRefreshExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "token-refresh");
                t.setDaemon(true);
                return t;
            });
            tokenRefreshExecutor.scheduleAtFixedRate(this::refreshTokens,
                    TOKEN_REFRESH_INTERVAL_HOURS, TOKEN_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS);
            log.debug("Started token refresh scheduler");
        }
    }

    public void refreshTokens() {
        log.debug("Starting token refresh cycle");

        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            Map<String, String> tenantTokens = deviceTokens.get(tenant);

            if (tenantTokens == null || tenantTokens.isEmpty()) {
                log.debug("{} - No device tokens to refresh", tenant);
                return;
            }

            int refreshedCount = 0;
            int failedCount = 0;

            for (Map.Entry<String, String> entry : tenantTokens.entrySet()) {
                String connectorId = entry.getKey();
                String token = entry.getValue();

                try {
                    String newToken = tokenApi.refresh(new Token(token)).getTokenString();
                    tenantTokens.put(connectorId, newToken);
                    refreshedCount++;
                    log.debug("{} - Refreshed token for connector {}", tenant, connectorId);
                } catch (IllegalArgumentException e) {
                    failedCount++;
                    log.warn("{} - Could not refresh token for connector {}: {}",
                            tenant, connectorId, e.getMessage());
                } catch (Exception e) {
                    failedCount++;
                    log.error("{} - Error refreshing token for connector {}: {}",
                            tenant, connectorId, e.getMessage());
                }
            }

            if (refreshedCount > 0 || failedCount > 0) {
                log.info("{} - Token refresh completed: {} successful, {} failed",
                        tenant, refreshedCount, failedCount);
            }
        });
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up TokenManager");

        // Stop token refresh executor
        if (tokenRefreshExecutor != null && !tokenRefreshExecutor.isShutdown()) {
            try {
                tokenRefreshExecutor.shutdown();
                if (!tokenRefreshExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    tokenRefreshExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                tokenRefreshExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Clear all token maps
        deviceTokens.clear();
        managementTokens.clear();
        cacheInventoryTokens.clear();

        log.info("TokenManager cleanup completed");
    }
}
