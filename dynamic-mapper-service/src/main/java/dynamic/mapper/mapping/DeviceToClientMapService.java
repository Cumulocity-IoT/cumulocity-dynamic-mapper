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
     * Persists the relation changes made on this instance to the inventory managed object that the
     * Cumulocity MQTT Service reads to decide which client receives outbound messages.
     * <p>
     * The inventory merges nested fragments on update, so a removed relation is sent as {@code null}.
     * Only changes made here are sent: the map this instance loaded at startup, and keys it merely does
     * not know, may belong to other instances, whose maps this one does not refresh. Replaying them
     * would write deleted or reassigned relations back. Nothing is written when every pending change
     * already matches the stored map.
     */
    public void sendToInventory(String tenant) {
        String deviceToClientMapId = serviceRegistry.getDeviceToClientMapId(tenant);
        if (deviceToClientMapId == null) {
            log.warn("{} - Device-to-client map managed object is not initialized, skipping sync", tenant);
            return;
        }
        // One snapshot of the changes: it is consistent with the relation map, and only these are
        // acknowledged afterwards, so a change made while syncing stays pending.
        Map<String, String> pending = serviceRegistry.getPendingClientChanges(tenant);
        if (pending.isEmpty()) {
            log.debug("{} - No device-to-client changes pending, not sending", tenant);
            return;
        }

        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, Object> persisted = readPersistedMap(inventoryApi.get(GId.asGId(deviceToClientMapId), false));

            Map<String, Object> changes = new HashMap<>();
            pending.forEach((deviceId, clientId) -> {
                boolean differs = clientId == null ? persisted.containsKey(deviceId)
                        : !clientId.equals(persisted.get(deviceId));
                if (differs) {
                    changes.put(deviceId, clientId);
                }
            });
            if (changes.isEmpty()) {
                log.debug("{} - Device-to-client changes already persisted ({} pending), not sending", tenant,
                        pending.size());
                serviceRegistry.acknowledgeClientChanges(tenant, pending);
                return;
            }

            log.debug("{} - Sending {} device-to-client changes ({} pending)", tenant, changes.size(),
                    pending.size());

            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(deviceToClientMapId));
            updateMor.setAttrs(Map.of(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT, changes));

            inventoryApi.update(updateMor, false);
            serviceRegistry.acknowledgeClientChanges(tenant, pending);
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
