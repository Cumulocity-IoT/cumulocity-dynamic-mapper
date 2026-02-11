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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.cache.InventoryCache;
import dynamic.mapper.processor.model.ExternalId;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class InventoryCacheEnrichmentService {

    @Autowired
    private CacheManager cacheManager;

    public Map<String, Object> getMOFromInventoryCacheByExternalId(String tenant, ExternalId externalId,
            Boolean testing, IdentityResolver identityResolver, ConfigurationRegistry configurationRegistry) {
        if (externalId == null || externalId.getExternalId() == null || externalId.getType() == null) {
            return null;
        }
        ID identity = new ID(externalId.getType(), externalId.getExternalId());
        ExternalIDRepresentation sourceId = identityResolver.resolveExternalId2GlobalId(tenant, identity, testing);
        if (sourceId != null) {
            return getMOFromInventoryCache(tenant, sourceId.getManagedObject().getId().getValue(), testing,
                    identityResolver, configurationRegistry);
        }
        return null;
    }

    public Map<String, Object> updateMOInInventoryCache(String tenant, String sourceId, Map<String, Object> updates,
            Boolean testing, IdentityResolver identityResolver, ConfigurationRegistry configurationRegistry) {
        InventoryCache inventoryCache = cacheManager.getInventoryCache(tenant);

        final Map<String, Object> newMO = new HashMap<>();
        inventoryCache.putMO(sourceId, newMO);

        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        // Check if assetParents is requested in fragments to cache
        boolean withParents = serviceConfiguration.getInventoryFragmentsToCache().stream()
                .anyMatch(frag -> "assetParents".equals(frag.trim()));

        // Use the identityResolver to get managed object
        ManagedObjectRepresentation device = getManagedObjectFromResolver(tenant, sourceId, testing, identityResolver, withParents);
        if (device != null) {
            Map<String, Object> attrs = device.getAttrs();

            serviceConfiguration.getInventoryFragmentsToCache().forEach(frag -> {
                frag = frag.trim();
                processFragment(frag, sourceId, device, attrs, newMO);
            });
        }

        return newMO;
    }

    public Map<String, Object> getMOFromInventoryCache(String tenant, String sourceId, Boolean testing,
            IdentityResolver identityResolver, ConfigurationRegistry configurationRegistry) {
        if (sourceId == null) {
            return null;
        }

        InventoryCache inventoryCache = cacheManager.getInventoryCache(tenant);
        Map<String, Object> result = inventoryCache.getMOBySource(sourceId);
        if (result != null) {
            return result;
        }

        final Map<String, Object> newMO = new HashMap<>();
        inventoryCache.putMO(sourceId, newMO);
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(new GId(sourceId));

        configurationRegistry.getNotificationSubscriber().subscribeMOForInventoryCacheUpdates(tenant, mor);

        ServiceConfiguration serviceConfiguration = configurationRegistry.getServiceConfiguration(tenant);
        // Check if assetParents is requested in fragments to cache
        boolean withParents = serviceConfiguration.getInventoryFragmentsToCache().stream()
                .anyMatch(frag -> "assetParents".equals(frag.trim()));

        ManagedObjectRepresentation device = getManagedObjectFromResolver(tenant, sourceId, testing, identityResolver, withParents);
        if (device != null) {
            Map<String, Object> attrs = device.getAttrs();

            serviceConfiguration.getInventoryFragmentsToCache().forEach(frag -> {
                frag = frag.trim();
                processFragment(frag, sourceId, device, attrs, newMO);
            });
        }

        return newMO;
    }

    private ManagedObjectRepresentation getManagedObjectFromResolver(String tenant, String deviceId,
            Boolean testing, IdentityResolver identityResolver) {
        return getManagedObjectFromResolver(tenant, deviceId, testing, identityResolver, false);
    }

    private ManagedObjectRepresentation getManagedObjectFromResolver(String tenant, String deviceId,
            Boolean testing, IdentityResolver identityResolver, boolean withParents) {
        // Since IdentityResolver is implemented by C8YAgent, we can cast it
        if (identityResolver instanceof C8YAgent) {
            return ((C8YAgent) identityResolver).getManagedObjectForId(tenant, deviceId, testing, withParents);
        }
        return null;
    }

    private void processFragment(String frag, String sourceId, ManagedObjectRepresentation device,
            Map<String, Object> attrs, Map<String, Object> newMO) {
        if ("id".equals(frag)) {
            newMO.put(frag, sourceId);
            return;
        }
        if ("name".equals(frag)) {
            newMO.put(frag, device.getName());
            return;
        }
        if ("owner".equals(frag)) {
            newMO.put(frag, device.getOwner());
            return;
        }
        if ("type".equals(frag)) {
            newMO.put(frag, device.getType());
            return;
        }
        if ("assetParents".equals(frag)) {
            // Extract and simplify assetParents
            Object assetParentsObj = attrs.get("assetParents");
            if (assetParentsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> assetParentsMap = (Map<String, Object>) assetParentsObj;
                Object referencesObj = assetParentsMap.get("references");

                if (referencesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> references = (List<Map<String, Object>>) referencesObj;
                    List<Map<String, String>> simplifiedParents = new ArrayList<>();

                    for (Map<String, Object> reference : references) {
                        Object moObj = reference.get("managedObject");
                        if (moObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mo = (Map<String, Object>) moObj;

                            Map<String, String> simplifiedParent = new HashMap<>();
                            if (mo.get("id") != null) {
                                simplifiedParent.put("id", String.valueOf(mo.get("id")));
                            }
                            if (mo.get("name") != null) {
                                simplifiedParent.put("name", String.valueOf(mo.get("name")));
                            }
                            if (mo.get("type") != null) {
                                simplifiedParent.put("type", String.valueOf(mo.get("type")));
                            }

                            if (!simplifiedParent.isEmpty()) {
                                simplifiedParents.add(simplifiedParent);
                            }
                        }
                    }

                    newMO.put(frag, simplifiedParents);
                    return;
                }
            }
            // If assetParents is not in the expected format, store empty list
            newMO.put(frag, new ArrayList<>());
            return;
        }

        Object value = resolveNestedAttribute(attrs, frag);
        if (value != null) {
            newMO.put(frag, value);
        }
    }

    private Object resolveNestedAttribute(Map<String, Object> attrs, String path) {
        if (path == null || attrs == null) {
            return null;
        }

        String[] pathParts = path.split("\\.");
        Object current = attrs;

        for (String part : pathParts) {
            if (!(current instanceof Map)) {
                return null;
            }

            Map<?, ?> currentMap = (Map<?, ?>) current;
            if (!currentMap.containsKey(part)) {
                return null;
            }

            current = currentMap.get(part);
        }

        return current;
    }
}