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
import java.util.Map;

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
     * The inventory merges nested fragments on update, so entries that were removed in memory
     * (device deleted, relation removed, cache cleared) would survive and come back after a restart.
     * Removed keys are therefore sent explicitly as {@code null}. Nothing is written when the stored
     * map already equals the in-memory one.
     */
    public void sendToInventory(String tenant) {
        String deviceToClientMapId = serviceRegistry.getDeviceToClientMapId(tenant);
        if (deviceToClientMapId == null) {
            log.warn("{} - Device-to-client map managed object is not initialized, skipping sync", tenant);
            return;
        }
        Map<String, String> deviceToClientMap = serviceRegistry.getAllClientRelations(tenant);

        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, Object> persisted = readPersistedMap(inventoryApi.get(GId.asGId(deviceToClientMapId), false));
            if (persisted.equals(deviceToClientMap)) {
                log.debug("{} - Device-to-client map unchanged ({} entries), not sending", tenant,
                        deviceToClientMap.size());
                return;
            }

            Map<String, Object> merged = new HashMap<>(deviceToClientMap);
            persisted.keySet().stream()
                    .filter(deviceId -> !deviceToClientMap.containsKey(deviceId))
                    .forEach(deviceId -> merged.put(deviceId, null));

            log.debug("{} - Sending device-to-client map with {} entries, {} removed", tenant,
                    deviceToClientMap.size(), merged.size() - deviceToClientMap.size());

            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(deviceToClientMapId));
            updateMor.setAttrs(Map.of(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT, merged));

            inventoryApi.update(updateMor, false);
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
