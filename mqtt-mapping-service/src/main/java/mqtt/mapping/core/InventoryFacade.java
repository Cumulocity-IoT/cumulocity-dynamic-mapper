/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.inventory.ManagedObjectCollection;

import mqtt.mapping.core.mock.MockInventory;
import mqtt.mapping.processor.model.ProcessingContext;

@Service
public class InventoryFacade {

    @Autowired
    private MockInventory inventoryMock;

    @Autowired
    private InventoryApi inventoryApi;

    public ManagedObjectRepresentation create(ManagedObjectRepresentation mor, ProcessingContext<?> context) {
        if (context == null || context.isSendPayload()) {
            return inventoryApi.create(mor);
        } else {
            return inventoryMock.create(mor);
        }
    }

    public ManagedObjectRepresentation get(GId id) {
        return inventoryApi.get(id);
    }

    public void delete(GId id) {
        inventoryApi.delete(id);
    }

    public ManagedObjectRepresentation update(ManagedObjectRepresentation mor, ProcessingContext<?> context) {
        if (context == null || context.isSendPayload()) {
            return inventoryApi.update(mor);
        } else {
            return inventoryMock.update(mor);
        }
    }

    public ManagedObjectCollection getManagedObjectsByFilter(InventoryFilter inventoryFilter) {
        return inventoryApi.getManagedObjectsByFilter(inventoryFilter);
    }
}
