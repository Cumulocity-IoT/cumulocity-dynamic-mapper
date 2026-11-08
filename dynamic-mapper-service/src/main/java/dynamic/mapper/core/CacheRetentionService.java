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

package dynamic.mapper.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.configuration.ServiceConfigurationService;
import dynamic.mapper.processor.flow.FlowStateStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Clears the identity and inventory caches of each tenant once their configured retention (in days)
 * has elapsed, and expires old flow-state entries.
 * <p>
 * The retention clock of a cache starts when the tenant is initialized and restarts each time the
 * cache is cleared. A retention of {@code 0} disables clearing for that cache. The check runs every
 * minute for every subscribed tenant.
 */
@Slf4j
@Component
@Order(TenantScoped.ORDER_CACHES)
public class CacheRetentionService implements TenantScoped {

    private final CacheManager cacheManager;
    private final ServiceConfigurationService serviceConfigurationService;
    private final MicroserviceSubscriptionsService subscriptionsService;
    private final FlowStateStore flowStateStore;

    private final Map<String, Instant> inboundExternalIdRetentionStart = new ConcurrentHashMap<>();
    private final Map<String, Instant> outboundExternalIdRetentionStart = new ConcurrentHashMap<>();
    private final Map<String, Instant> inventoryRetentionStart = new ConcurrentHashMap<>();

    public CacheRetentionService(CacheManager cacheManager,
            ServiceConfigurationService serviceConfigurationService,
            MicroserviceSubscriptionsService subscriptionsService,
            FlowStateStore flowStateStore) {
        this.cacheManager = cacheManager;
        this.serviceConfigurationService = serviceConfigurationService;
        this.subscriptionsService = subscriptionsService;
        this.flowStateStore = flowStateStore;
    }

    @Override
    public void initializeTenant(TenantInitContext ctx) {
        String tenant = ctx.tenant();
        inboundExternalIdRetentionStart.put(tenant, Instant.now());
        outboundExternalIdRetentionStart.put(tenant, Instant.now());
        inventoryRetentionStart.put(tenant, Instant.now());
    }

    @Override
    public void removeTenant(String tenant) {
        TenantScoped.removeAll(tenant, this,
                () -> inboundExternalIdRetentionStart.remove(tenant),
                () -> outboundExternalIdRetentionStart.remove(tenant),
                () -> inventoryRetentionStart.remove(tenant));
    }

    @Scheduled(cron = "0 * * * * *")
    public void cleanUpCaches() {
        subscriptionsService.runForEachTenant(() -> {
            String tenant = subscriptionsService.getTenant();
            try {
                cleanupCachesForTenant(tenant);
            } catch (Exception e) {
                log.error("{} - Error executing Cache Cleanup Scheduler", tenant, e);
            }
        });
    }

    void cleanupCachesForTenant(String tenant) {
        ServiceConfiguration serviceConfig = serviceConfigurationService.getServiceConfiguration(tenant);

        clearIfExpired(tenant, "Identity", inboundExternalIdRetentionStart,
                serviceConfig.getInboundExternalIdCacheRetention(),
                () -> cacheManager.getSizeInboundExternalIdCache(tenant),
                size -> cacheManager.clearInboundExternalIdCache(tenant, false, size));

        clearIfExpired(tenant, "Outbound identity", outboundExternalIdRetentionStart,
                serviceConfig.getOutboundExternalIdCacheRetention(),
                () -> cacheManager.getSizeOutboundExternalIdCache(tenant),
                size -> cacheManager.clearOutboundExternalIdCache(tenant, false, size));

        clearIfExpired(tenant, "Inventory", inventoryRetentionStart,
                serviceConfig.getInventoryCacheRetention(),
                () -> cacheManager.getInventoryCache(tenant).getCacheSize(),
                size -> cacheManager.clearInventoryCache(tenant, false, size));

        if (serviceConfig.getFlowStateRetention() != null) {
            flowStateStore.clearExpiredEntries(tenant, serviceConfig.getFlowStateRetention());
        }
    }

    /** Clears one cache when its retention has elapsed, restarts its clock, and logs old and new size. */
    private void clearIfExpired(String tenant, String cacheName, Map<String, Instant> retentionStartMap,
            int retentionDays, IntSupplier sizeOf, java.util.function.IntConsumer clear) {
        Instant retentionStart = retentionStartMap.get(tenant);
        if (retentionStart == null || !shouldClearCache(retentionStart, retentionDays)) {
            return;
        }
        int cacheSize = sizeOf.getAsInt();
        clear.accept(cacheSize);
        retentionStartMap.put(tenant, Instant.now());
        log.info("{} - {} cache cleared. Old Size: {}, New size: {}", tenant, cacheName, cacheSize,
                sizeOf.getAsInt());
    }

    private boolean shouldClearCache(Instant retentionStart, int retentionDays) {
        return retentionDays > 0
                && Duration.between(retentionStart, Instant.now()).compareTo(Duration.ofDays(retentionDays)) >= 0;
    }
}
