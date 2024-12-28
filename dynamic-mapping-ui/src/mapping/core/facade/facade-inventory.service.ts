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
 * @authors Christof Strack
 */
import { Injectable } from '@angular/core';
import { IdReference, IManagedObject, InventoryService, IResult } from '@c8y/client';
import { ProcessingContext } from '../processor/processor.model';
import { MockInventoryService } from '../mock/mock-inventory.service';

@Injectable({ providedIn: 'root' })
export class FacadeInventoryService {
  inventoryCache: Map<string, Map<string, string>>;
  constructor(
    private mockInventory: MockInventoryService,
    private inventory: InventoryService
  ) { }

  initializeCache(): void {
    this.mockInventory.initializeCache();
  }

  detail(
    managedObjectOrId: IdReference,
    context: ProcessingContext
  ): Promise<IResult<IManagedObject>> {
    if (context.sendPayload) {
      return this.inventory.detail(managedObjectOrId);
    } else {
      return this.mockInventory.detail(managedObjectOrId);
    }
  }

  update(
    managedObject: Partial<IManagedObject>,
    context: ProcessingContext
  ): Promise<IResult<IManagedObject>> {
    if (context.sendPayload) {
      return this.inventory.update(managedObject);
    } else {
      return this.mockInventory.update(managedObject);
    }
  }

  create(
    managedObject: Partial<IManagedObject>,
    context: ProcessingContext
  ): Promise<IResult<IManagedObject>> {
    if (context.sendPayload) {
      return this.inventory.create(managedObject);
    } else {
      // We force the creation of a device with a given id. 
      // This is required to keep the source.id and deviceId consistant, across request.
      // E.g. an alarm with a c8ySourceId = '102030' is tested in teh UI, then we need 
      // to create a device with that given id = '102030'
      managedObject.id = context.sourceId;
      return this.mockInventory.create(managedObject);
    }
  }
}
