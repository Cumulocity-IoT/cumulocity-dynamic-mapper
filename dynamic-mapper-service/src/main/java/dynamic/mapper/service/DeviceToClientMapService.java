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

package dynamic.mapper.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import dynamic.mapper.core.ConfigurationRegistry;
import dynamic.mapper.core.facade.InventoryFacade;
import dynamic.mapper.model.DeviceToClientMapRepresentation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages device-to-client mapping persistence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceToClientMapService {

    private final InventoryFacade inventoryApi;
    private final ConfigurationRegistry configurationRegistry;
    private final MicroserviceSubscriptionsService subscriptionsService;

    /**
     * Sends the device-to-client map to inventory
     */
    public void sendToInventory(String tenant) {
        Map<String, String> clientToDeviceMap = configurationRegistry.getAllClientRelations(tenant);
        
        if (clientToDeviceMap == null) {
            log.debug("{} - No device-to-client map to send", tenant);
            return;
        }

        subscriptionsService.runForTenant(tenant, () -> {
            String deviceToClientMapId = configurationRegistry.getDeviceToClientMapId(tenant);

            log.debug("{} - Sending device-to-client map with {} entries", tenant, clientToDeviceMap.size());

            Map<String, Object> fragment = new ConcurrentHashMap<>();
            fragment.put(DeviceToClientMapRepresentation.DEVICE_TO_CLIENT_MAP_FRAGMENT, clientToDeviceMap);

            ManagedObjectRepresentation updateMor = new ManagedObjectRepresentation();
            updateMor.setId(GId.asGId(deviceToClientMapId));
            updateMor.setAttrs(fragment);

            inventoryApi.update(updateMor, false);
        });
    }
}
