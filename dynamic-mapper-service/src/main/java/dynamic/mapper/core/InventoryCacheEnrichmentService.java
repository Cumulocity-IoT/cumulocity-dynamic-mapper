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

import org.springframework.stereotype.Component;

import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;

import dynamic.mapper.configuration.ServiceConfiguration;
import dynamic.mapper.core.cache.InventoryCache;
import dynamic.mapper.notification.NotificationSubscriber;
import dynamic.mapper.processor.inbound.deserializer.SparkPlugBDeserializer;
import dynamic.mapper.processor.model.ExternalId;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class InventoryCacheEnrichmentService {

    private final CacheManager cacheManager;
    private final TenantRegistry tenantRegistry;
    private final NotificationSubscriber notificationSubscriber;

    public InventoryCacheEnrichmentService(CacheManager cacheManager, TenantRegistry tenantRegistry,
            NotificationSubscriber notificationSubscriber) {
        this.cacheManager = cacheManager;
        this.tenantRegistry = tenantRegistry;
        this.notificationSubscriber = notificationSubscriber;
    }

    public Map<String, Object> getMOFromInventoryCacheByExternalId(String tenant, ExternalId externalId,
            Boolean testing, IdentityResolver identityResolver) {
        if (externalId == null || externalId.getExternalId() == null || externalId.getType() == null) {
            return null;
        }
        ID identity = new ID(externalId.getType(), externalId.getExternalId());
        ExternalIDRepresentation sourceId = identityResolver.resolveExternalId2GlobalId(tenant, identity, testing);
        if (sourceId != null) {
            return getMOFromInventoryCache(tenant, sourceId.getManagedObject().getId().getValue(), testing,
                    identityResolver);
        }
        return null;
    }

    public Map<String, Object> updateMOInInventoryCache(String tenant, String sourceId, Map<String, Object> updates,
            Boolean testing, IdentityResolver identityResolver) {
        InventoryCache inventoryCache = cacheManager.getInventoryCache(tenant);

        final Map<String, Object> newMO = new HashMap<>();
        inventoryCache.putMO(sourceId, newMO);

        ServiceConfiguration serviceConfiguration = tenantRegistry.getServiceConfiguration(tenant);
        List<String> effectiveFragments = buildEffectiveFragmentList(serviceConfiguration);
        // Check if assetParents is requested in fragments to cache
        boolean withParents = effectiveFragments.stream()
                .anyMatch(frag -> "assetParents".equals(frag.trim()));

        // Use the identityResolver to get managed object
        ManagedObjectRepresentation device = getManagedObjectFromResolver(tenant, sourceId, testing, identityResolver, withParents);
        if (device != null) {
            Map<String, Object> attrs = device.getAttrs();

            effectiveFragments.forEach(frag -> {
                processFragment(frag.trim(), sourceId, device, attrs, newMO);
            });
        } else {
            // sourceId no longer resolves in inventory (e.g. the managed object was deleted) —
            // drop any external-ID cache entry still pointing at it so the next message for the
            // same external ID re-resolves instead of reusing this stale, deleted id forever.
            tenantRegistry.removeFromExternalIdCacheByInternalId(tenant, sourceId);
        }

        return newMO;
    }

    public Map<String, Object> getMOFromInventoryCache(String tenant, String sourceId, Boolean testing,
            IdentityResolver identityResolver) {
        if (sourceId == null) {
            return null;
        }

        InventoryCache inventoryCache = cacheManager.getInventoryCache(tenant);
        Map<String, Object> result = inventoryCache.getMOBySource(sourceId);
        if (result != null) {
            return result;
        }

        // Subscribe BEFORE fetching so update notifications that arrive while the
        // REST call is in flight are not missed.
        ManagedObjectRepresentation mor = new ManagedObjectRepresentation();
        mor.setId(new GId(sourceId));
        notificationSubscriber.subscribeMOForInventoryCacheUpdates(tenant, mor);

        ServiceConfiguration serviceConfiguration = tenantRegistry.getServiceConfiguration(tenant);
        List<String> effectiveFragments = buildEffectiveFragmentList(serviceConfiguration);
        // Check if assetParents is requested in fragments to cache
        boolean withParents = effectiveFragments.stream()
                .anyMatch(frag -> "assetParents".equals(frag.trim()));

        ManagedObjectRepresentation device = getManagedObjectFromResolver(tenant, sourceId, testing, identityResolver, withParents);
        final Map<String, Object> newMO = new HashMap<>();
        if (device != null) {
            Map<String, Object> attrs = device.getAttrs();

            effectiveFragments.forEach(frag -> {
                processFragment(frag.trim(), sourceId, device, attrs, newMO);
            });
        } else {
            // sourceId no longer resolves in inventory (e.g. the managed object was deleted) —
            // drop any external-ID cache entry still pointing at it so the next message for the
            // same external ID re-resolves instead of reusing this stale, deleted id forever.
            tenantRegistry.removeFromExternalIdCacheByInternalId(tenant, sourceId);
        }

        // Store the fully-populated map. A concurrent thread that resolved the same
        // sourceId may already have stored its own copy — the last write wins, but
        // both contain equivalent data so correctness is preserved.
        inventoryCache.putMO(sourceId, newMO);
        return newMO;
    }

    /**
     * Builds the effective list of inventory fragments to cache, adding the
     * SparkPlug B BIRTH fragments ({@code sparkPlugB_NBIRTH} and the glob
     * {@code sparkPlugB_DBIRTH_*}) transparently when
     * {@link ServiceConfiguration#getCacheAliasMaps()} is {@code true}.
     * The BIRTH fragments are never exposed in the UI-visible
     * {@code inventoryFragmentsToCache} list.
     */
    private List<String> buildEffectiveFragmentList(ServiceConfiguration serviceConfiguration) {
        List<String> effective = new ArrayList<>(serviceConfiguration.getInventoryFragmentsToCache());
        if (Boolean.TRUE.equals(serviceConfiguration.getCacheAliasMaps())) {
            if (!effective.contains(SparkPlugBDeserializer.SPARKPLUGB_NBIRTH_FRAGMENT)) {
                effective.add(SparkPlugBDeserializer.SPARKPLUGB_NBIRTH_FRAGMENT);
            }
            // Use a glob so all per-device DBIRTH fragments (sparkPlugB_DBIRTH_<deviceId>) are cached.
            // processFragment() expands glob patterns against the actual MO attributes.
            String dbBirthGlob = SparkPlugBDeserializer.SPARKPLUGB_DBIRTH_FRAGMENT_PREFIX + "*";
            if (effective.stream().noneMatch(f -> f.startsWith(SparkPlugBDeserializer.SPARKPLUGB_DBIRTH_FRAGMENT_PREFIX))) {
                effective.add(dbBirthGlob);
            }
        }
        return effective;
    }

    private ManagedObjectRepresentation getManagedObjectFromResolver(String tenant, String deviceId,
            Boolean testing, IdentityResolver identityResolver, boolean withParents) {
        return identityResolver.getManagedObjectForId(tenant, deviceId, testing, withParents);
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

        // Glob pattern: match all attrs keys that fit the pattern
        if (isGlobPattern(frag)) {
            for (String key : attrs.keySet()) {
                if (matchesGlob(frag, key)) {
                    Object value = attrs.get(key);
                    if (value != null) {
                        newMO.put(key, value);
                    }
                }
            }
            return;
        }

        Object value = resolveNestedAttribute(attrs, frag);
        if (value != null) {
            newMO.put(frag, value);
        }
    }

    private boolean isGlobPattern(String frag) {
        return frag != null && (frag.contains("*") || frag.contains("?"));
    }

    /**
     * Matches a glob pattern (supporting {@code *} for any sequence and {@code ?}
     * for a single character) against a candidate string.
     */
    private boolean matchesGlob(String pattern, String candidate) {
        // Convert glob to regex: escape regex metacharacters except * and ?,
        // then replace * -> .* and ? -> .
        String regex = pattern
                .replace(".", "\\.")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("+", "\\+")
                .replace("|", "\\|")
                .replace("?", ".")
                .replace("*", ".*");
        return candidate.matches(regex);
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