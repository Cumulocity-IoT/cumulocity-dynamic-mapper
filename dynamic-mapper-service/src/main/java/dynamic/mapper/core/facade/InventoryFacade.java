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

package dynamic.mapper.core.facade;

import java.util.HashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.CumulocityMediaType;
import com.cumulocity.rest.representation.inventory.ManagedObjectReferenceRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.Platform;
import com.cumulocity.sdk.client.RestOperations;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import dynamic.mapper.core.mock.MockInventory;

/**
 * Routes Inventory operations to either the real C8Y API (testing=false/null)
 * or the in-memory mock (testing=true). The mock is used for dry-run/UI-test
 * scenarios so no real objects are created in the platform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryFacade {

    private final MockInventory inventoryMock;
    private final InventoryApi inventoryApi;
    private final Platform platform;

    private boolean isProduction(Boolean testing) {
        return testing == null || Boolean.FALSE.equals(testing);
    }

    public ManagedObjectRepresentation create(ManagedObjectRepresentation mor, Boolean testing) {
        if (isProduction(testing)) {
            return inventoryApi.create(mor);
        } else {
            log.debug("Mock: creating managed object: {}", mor.getName());
            return inventoryMock.create(mor);
        }
    }

    public ManagedObjectRepresentation get(GId id, Boolean testing) {
        return get(id, testing, false);
    }

    public ManagedObjectRepresentation get(GId id, Boolean testing, boolean withParents) {
        if (isProduction(testing)) {
            if (withParents) {
                RestOperations restOperations = platform.rest();
                String url = "/inventory/managedObjects/" + id.getValue() + "?withParents=true";
                try {
                    return restOperations.get(url,
                        com.cumulocity.rest.representation.CumulocityMediaType.APPLICATION_JSON_TYPE,
                        ManagedObjectRepresentation.class);
                } catch (Exception e) {
                    log.warn("Failed to get managed object with parents: {}", id, e);
                    return null;
                }
            } else {
                return inventoryApi.get(id);
            }
        } else {
            log.debug("Mock: getting managed object: {}", id);
            return inventoryMock.get(id);
        }
    }

    public void delete(GId id, Boolean testing) {
        if (isProduction(testing)) {
            inventoryApi.delete(id);
        } else {
            log.debug("Mock: deleting managed object: {}", id);
            inventoryMock.delete(id);
        }
    }

    public ManagedObjectRepresentation update(ManagedObjectRepresentation mor, Boolean testing) {
        if (isProduction(testing)) {
            return inventoryApi.update(mor);
        } else {
            log.debug("Mock: updating managed object: {}", mor.getId());
            return inventoryMock.update(mor);
        }
    }

    public ManagedObjectCollection getManagedObjectsByFilter(InventoryFilter inventoryFilter, Boolean testing) {
        if (isProduction(testing)) {
            return inventoryApi.getManagedObjectsByFilter(inventoryFilter);
        } else {
            log.debug("Mock: querying managed objects by filter");
            return inventoryMock.getManagedObjectsByFilter(inventoryFilter);
        }
    }

    public ManagedObjectRepresentation findGroupByName(String name, Boolean testing) {
        InventoryFilter filter = new InventoryFilter().byType("c8y_DeviceGroup");
        ManagedObjectCollection collection = getManagedObjectsByFilter(filter, testing);
        ManagedObjectRepresentation first = null;
        for (ManagedObjectRepresentation mor : collection.get().allPages()) {
            if (name.equals(mor.getName())) {
                if (first == null) {
                    first = mor;
                } else {
                    log.warn("Multiple device groups found with name '{}' — using first match (id={}), ignoring id={}",
                            name, first.getId().getValue(), mor.getId().getValue());
                }
            }
        }
        return first;
    }

    public ManagedObjectRepresentation createGroup(String name, Boolean testing) {
        ManagedObjectRepresentation group = new ManagedObjectRepresentation();
        group.setName(name);
        group.setType("c8y_DeviceGroup");
        group.set(new HashMap<>(), "c8y_IsDeviceGroup");
        return create(group, testing);
    }

    public void addChildAsset(GId groupId, GId deviceId, Boolean testing) {
        if (Boolean.TRUE.equals(testing)) {
            log.debug("Mock: skipping child asset assignment: group={}, device={}", groupId, deviceId);
            return;
        }
        String url = "/inventory/managedObjects/" + groupId.getValue() + "/childAssets";
        ManagedObjectReferenceRepresentation ref = new ManagedObjectReferenceRepresentation();
        ManagedObjectRepresentation deviceMO = new ManagedObjectRepresentation();
        deviceMO.setId(deviceId);
        ref.setManagedObject(deviceMO);
        platform.rest().post(url, CumulocityMediaType.APPLICATION_JSON_TYPE, ref);
    }

    public void addChildAddition(GId parentId, GId childId, Boolean testing) {
        if (Boolean.TRUE.equals(testing)) {
            log.debug("Mock: skipping child addition registration: parent={}, child={}", parentId, childId);
            return;
        }
        String url = "/inventory/managedObjects/" + parentId.getValue() + "/childAdditions";
        ManagedObjectReferenceRepresentation ref = new ManagedObjectReferenceRepresentation();
        ManagedObjectRepresentation childMO = new ManagedObjectRepresentation();
        childMO.setId(childId);
        ref.setManagedObject(childMO);
        platform.rest().post(url, CumulocityMediaType.APPLICATION_JSON_TYPE, ref);
    }

    public void clearInventoryCache() {
        inventoryMock.clear();
    }
}
