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

    private final TokenApi tokenApi;
    private final MicroserviceSubscriptionsService subscriptionsService;

    public TokenManager(TokenApi tokenApi, MicroserviceSubscriptionsService subscriptionsService) {
        this.tokenApi = tokenApi;
        this.subscriptionsService = subscriptionsService;
    }

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
        if (token == null) {
            Map<String, String> tenantTokens = deviceTokens.get(tenant);
            if (tenantTokens != null) {
                tenantTokens.remove(connectorId);
            }
        } else {
            deviceTokens.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                    .put(connectorId, token);
        }
    }

    public String getDeviceToken(String tenant, String connectorId) {
        if (tenant == null || connectorId == null) {
            return null;
        }
        Map<String, String> tenantTokens = deviceTokens.get(tenant);
        return tenantTokens != null ? tenantTokens.get(connectorId) : null;
    }

    public void storeManagementToken(String tenant, String token) {
        if (token == null) {
            managementTokens.remove(tenant);
        } else {
            managementTokens.put(tenant, token);
        }
    }

    public String getManagementToken(String tenant) {
        return tenant != null ? managementTokens.get(tenant) : null;
    }

    public void storeCacheInventoryToken(String tenant, String token) {
        if (token == null) {
            cacheInventoryTokens.remove(tenant);
        } else {
            cacheInventoryTokens.put(tenant, token);
        }
    }

    public String getCacheInventoryToken(String tenant) {
        return tenant != null ? cacheInventoryTokens.get(tenant) : null;
    }

    public void unsubscribeDeviceSubscriber(String tenant) {
        if (tenant == null) {
            return;
        }

        // L1 fix: device subscriber tokens live in deviceTokens (per-connector map), not managementTokens
        Map<String, String> tenantTokens = deviceTokens.remove(tenant);
        if (tenantTokens != null) {
            int unsubscribedCount = 0;
            for (String token : tenantTokens.values()) {
                try {
                    tokenApi.unsubscribe(new Token(token));
                    unsubscribedCount++;
                } catch (Exception e) {
                    log.warn("{} - Error unsubscribing device token: {}", tenant, e.getMessage());
                }
            }
            log.info("{} - Unsubscribed {} device subscribers", tenant, unsubscribedCount);
        }
    }

    public void unsubscribeDeviceGroupSubscriber(String tenant) {
        if (tenant == null) {
            return;
        }

        // L1 fix: device group (management) token lives in managementTokens, not deviceTokens
        String token = managementTokens.remove(tenant);
        if (token != null) {
            try {
                tokenApi.unsubscribe(new Token(token));
                log.info("{} - Unsubscribed device group subscriber", tenant);
            } catch (Exception e) {
                log.warn("{} - Error unsubscribing device group subscriber: {}", tenant, e.getMessage());
            }
        }
    }

    public void unsubscribeDeviceSubscriberByConnector(String tenant, String connectorIdentifier) {
        if (tenant == null || connectorIdentifier == null) {
            return;
        }

        Map<String, String> tenantTokens = deviceTokens.get(tenant);
        if (tenantTokens != null) {
            // Unsubscribe static and dynamic subscribers (stored with suffixed keys)
            unsubscribeTokenByKey(tenant, tenantTokens, connectorIdentifier + "_static", connectorIdentifier);
            unsubscribeTokenByKey(tenant, tenantTokens, connectorIdentifier + "_dynamic", connectorIdentifier);
            // Backward compat: plain key from before this fix
            unsubscribeTokenByKey(tenant, tenantTokens, connectorIdentifier, connectorIdentifier);
        }
    }

    private void unsubscribeTokenByKey(String tenant, Map<String, String> tenantTokens, String key,
            String connectorIdentifier) {
        String token = tenantTokens.remove(key);
        if (token != null) {
            try {
                tokenApi.unsubscribe(new Token(token));
                log.info("{} - Unsubscribed connector {} (key: {})", tenant, connectorIdentifier, key);
            } catch (SDKException e) {
                log.error("{} - Could not unsubscribe connector {} (key: {}): {}",
                        tenant, connectorIdentifier, key, e.getMessage(), e);
            }
        }
    }

    /**
     * Unsubscribe a subscriber by name from a given subscription.
     * Creates a temporary token for the subscriber, then immediately unsubscribes.
     * This handles cleanup of orphaned subscribers after a restart when no stored
     * token is available. Errors (e.g. 422 for non-alphanumeric subscriber names)
     * are silently ignored at debug level.
     */
    public void unsubscribeBySubscriberName(String subscription, String subscriberName) {
        if (subscription == null || subscriberName == null) {
            return;
        }
        try {
            // Create token directly (not via createToken()) to avoid its ERROR-level log on failure
            NotificationTokenRequestRepresentation tokenRequest =
                    new NotificationTokenRequestRepresentation(subscriberName, subscription, 1440, false);
            String token = tokenApi.create(tokenRequest).getTokenString();
            tokenApi.unsubscribe(new Token(token));
            log.info("Unsubscribed orphaned subscriber '{}' from subscription '{}'", subscriberName, subscription);
        } catch (Exception e) {
            log.debug("Could not unsubscribe subscriber '{}' from subscription '{}': {}",
                    subscriberName, subscription, e.getMessage());
        }
    }

    // H6: synchronized so concurrent callers can't each pass the null-check and create duplicate schedulers
    public synchronized void startTokenRefreshScheduler(String tenant) {
        if (tokenRefreshExecutor == null || tokenRefreshExecutor.isShutdown()) {
            tokenRefreshExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "token-refresh");
                t.setDaemon(true);
                return t;
            });
            tokenRefreshExecutor.scheduleAtFixedRate(this::refreshTokens,
                    TOKEN_REFRESH_INTERVAL_HOURS, TOKEN_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS);
            log.info("{} - Started token refresh scheduler", tenant);
        }
    }

    public void refreshTokens() {

        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            log.info("{} - Starting token refresh cycle", tenant);

            int refreshedCount = 0;
            int failedCount = 0;

            // Refresh device tokens
            Map<String, String> tenantDeviceTokens = deviceTokens.get(tenant);
            if (tenantDeviceTokens != null) {
                for (Map.Entry<String, String> entry : tenantDeviceTokens.entrySet()) {
                    String connectorId = entry.getKey();
                    String token = entry.getValue();
                    try {
                        String newToken = tokenApi.refresh(new Token(token)).getTokenString();
                        tenantDeviceTokens.put(connectorId, newToken);
                        refreshedCount++;
                        log.info("{} - Refreshed device token for connector {}", tenant, connectorId);
                    } catch (IllegalArgumentException e) {
                        failedCount++;
                        log.warn("{} - Could not refresh device token for connector {}: {}",
                                tenant, connectorId, e.getMessage());
                    } catch (Exception e) {
                        failedCount++;
                        log.error("{} - Error refreshing device token for connector {}: {}",
                                tenant, connectorId, e.getMessage());
                    }
                }
            }

            // Refresh management and cache inventory tokens.
            // The refreshed token is stored and will be picked up automatically on the next
            // reconnect (initializeManagementClient uses getManagementToken / getCacheInventoryToken
            // which prefer the stored refreshed token over creating a brand-new one).
            // This avoids disrupting a healthy connection just to rotate the token.
            String mgmtToken = managementTokens.get(tenant);
            if (mgmtToken != null) {
                try {
                    String newToken = tokenApi.refresh(new Token(mgmtToken)).getTokenString();
                    managementTokens.put(tenant, newToken);
                    refreshedCount++;
                    log.info("{} - Refreshed management token", tenant);
                } catch (Exception e) {
                    failedCount++;
                    log.warn("{} - Could not refresh management token: {}", tenant, e.getMessage());
                }
            }

            String cacheToken = cacheInventoryTokens.get(tenant);
            if (cacheToken != null) {
                try {
                    String newToken = tokenApi.refresh(new Token(cacheToken)).getTokenString();
                    cacheInventoryTokens.put(tenant, newToken);
                    refreshedCount++;
                    log.info("{} - Refreshed cache inventory token", tenant);
                } catch (Exception e) {
                    failedCount++;
                    log.warn("{} - Could not refresh cache inventory token: {}", tenant, e.getMessage());
                }
            }

            if (refreshedCount > 0 || failedCount > 0) {
                log.info("{} - Token refresh completed: {} successful, {} failed",
                        tenant, refreshedCount, failedCount);
            } else {
                log.debug("{} - No tokens to refresh", tenant);
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
