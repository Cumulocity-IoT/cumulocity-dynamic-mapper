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

package dynamic.mapper.mapping;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import dynamic.mapper.core.ServiceRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.device.DeviceToClientMapRepresentation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages device-to-client mapping persistence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceToClientMapService {

    private final InventoryFacade inventoryApi;
    private final ServiceRegistry serviceRegistry;
    private final MicroserviceSubscriptionsService subscriptionsService;

    /**
     * Persists the in-memory device-to-client map to the inventory managed object that the Cumulocity
     * MQTT Service reads to decide which client receives outbound messages.
     * <p>
     * The inventory merges nested fragments on update, so a relation removed on this instance
     * (device deleted, relation removed, cache cleared) would survive and come back after a restart.
     * Explicitly removed devices are therefore sent as {@code null}. A key that is merely absent from
     * this instance's map is left alone: it may have been registered by another instance, whose map
     * this one does not refresh. Nothing is written when no local relation differs from the stored one
     * and no removal is pending.
     */
    public void sendToInventory(String tenant) {
        String deviceToClientMapId = serviceRegistry.getDeviceToClientMapId(tenant);
        if (deviceToClientMapId == null) {
            log.warn("{} - Device-to-client map managed object is not initialized, skipping sync", tenant);
            return;
        }
        Map<String, String> deviceToClientMap = serviceRegistry.getAllClientRelations(tenant);
        Set<String> removed = serviceRegistry.getRemovedClientRelations(tenant);

        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, Object> persisted = readPersistedMap(inventoryApi.get(GId.asGId(deviceToClientMapId), false));

            Map<String, Object> merged = new HashMap<>(deviceToClientMap);
            Set<String> tombstones = new HashSet<>();
            removed.stream()
                    .filter(deviceId -> !deviceToClientMap.containsKey(deviceId))
                    .filter(persisted::containsKey)
                    .forEach(deviceId -> {
                        merged.put(deviceId, null);
                        tombstones.add(deviceId);
                    });

            boolean changed = !tombstones.isEmpty()
                    || deviceToClientMap.entrySet().stream()
                            .anyMatch(e -> !e.getValue().equals(persisted.get(e.getKey())));
            if (!changed) {
                log.debug("{} - Device-to-client map unchanged ({} entries), not sending", tenant,
                        deviceToClientMap.size());
                serviceRegistry.acknowledgeRemovedClientRelations(tenant, removed);
                return;
            }

            log.debug("{} - Sending device-to-client map with {} entries, {} removed", tenant,
                    deviceToClientMap.size(), tombstones.size());

            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(deviceToClientMapId));
            updateMor.setAttrs(Map.of(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT, merged));

            inventoryApi.update(updateMor, false);
            serviceRegistry.acknowledgeRemovedClientRelations(tenant, removed);
        });
    }

    private Map<String, Object> readPersistedMap(ManagedObjectRepresentation stored) {
        Object fragment = stored == null || stored.getAttrs() == null ? null
                : stored.getAttrs().get(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT);
        if (fragment instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new HashMap<>();
    }
}
