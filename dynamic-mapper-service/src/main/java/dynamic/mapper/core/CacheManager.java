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

package dynamic.mapper.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.core.cache.InboundExternalIdCache;
import dynamic.mapper.core.cache.InventoryCache;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CacheManager {

    private Map<String, InboundExternalIdCache> inboundExternalIdCaches = new ConcurrentHashMap<>();
    private Map<String, InventoryCache> inventoryCaches = new ConcurrentHashMap<>();

    public void initializeInboundExternalIdCache(String tenant, int inboundExternalIdCacheSize) {
        log.info("{} - Initialize inboundExternalIdCache {}", tenant, inboundExternalIdCacheSize);
        inboundExternalIdCaches.put(tenant, new InboundExternalIdCache(inboundExternalIdCacheSize, tenant));
    }

    public void initializeInventoryCache(String tenant, int inventoryCacheSize, 
            ConfigurationRegistry configurationRegistry) {
        log.info("{} - Initialize inventoryCache {}", tenant, inventoryCacheSize);
        InventoryCache inventoryCache = new InventoryCache(inventoryCacheSize, tenant);
        // Set up eviction listener
        inventoryCache.setEvictionListener(evictedSourceId -> {
            ManagedObjectRepresentation moRep = new ManagedObjectRepresentation();
            moRep.setId(new GId(evictedSourceId));
            configurationRegistry.getNotificationSubscriber().unsubscribeMOForInventoryCacheUpdates(tenant, moRep);
        });
        inventoryCaches.put(tenant, inventoryCache);
    }

    public InboundExternalIdCache getInboundExternalIdCache(String tenant) {
        return inboundExternalIdCaches.get(tenant);
    }

    public InboundExternalIdCache removeInboundExternalIdCache(String tenant) {
        return inboundExternalIdCaches.remove(tenant);
    }

    public Integer getInboundExternalIdCacheSize(String tenant) {
        return (inboundExternalIdCaches.get(tenant) != null 
                ? inboundExternalIdCaches.get(tenant).getCacheSize() : 0);
    }

    public InventoryCache removeInventoryCache(String tenant) {
        return inventoryCaches.remove(tenant);
    }

    public InventoryCache getInventoryCache(String tenant) {
        return inventoryCaches.get(tenant);
    }

    public void clearInboundExternalIdCache(String tenant, boolean recreate, int inboundExternalIdCacheSize) {
        InboundExternalIdCache inboundExternalIdCache = inboundExternalIdCaches.get(tenant);
        if (inboundExternalIdCache != null) {
            if (recreate) {
                inboundExternalIdCaches.put(tenant, new InboundExternalIdCache(inboundExternalIdCacheSize, tenant));
            } else {
                inboundExternalIdCache.clearCache();
            }
        }
    }

    public void removeDeviceFromInboundExternalIdCache(String tenant, ID identity) {
        InboundExternalIdCache inboundExternalIdCache = inboundExternalIdCaches.get(tenant);
        if (inboundExternalIdCache != null) {
            inboundExternalIdCache.removeIdForExternalId(identity);
        }
        log.info("{} - Removed device {} from InboundExternalIdCache", tenant, identity.getValue());
    }

    public int getSizeInboundExternalIdCache(String tenant) {
        InboundExternalIdCache inboundExternalIdCache = inboundExternalIdCaches.get(tenant);
        if (inboundExternalIdCache != null) {
            return inboundExternalIdCache.getCacheSize();
        } else {
            return 0;
        }
    }

    public void clearInventoryCache(String tenant, boolean recreate, int inventoryCacheSize,
            ConfigurationRegistry configurationRegistry) {
        InventoryCache inventoryCache = inventoryCaches.get(tenant);
        if (inventoryCache != null) {
            if (recreate) {
                configurationRegistry.getNotificationSubscriber().unsubscribeAllMOForInventoryCacheUpdates(tenant);
                inventoryCaches.put(tenant, new InventoryCache(inventoryCacheSize, tenant));
            } else {
                configurationRegistry.getNotificationSubscriber().unsubscribeAllMOForInventoryCacheUpdates(tenant);
                inventoryCache.clearCache();
            }
        }
    }

    public int getSizeInventoryCache(String tenant) {
        InventoryCache inventoryCache = inventoryCaches.get(tenant);
        if (inventoryCache != null) {
            return inventoryCache.getCacheSize();
        } else {
            return 0;
        }
    }
}