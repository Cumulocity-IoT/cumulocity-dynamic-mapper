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
import { IManagedObject, InventoryService, IResult } from '@c8y/client';
import { ProcessingContext } from '../../processor/prosessor.model';
import { MockInventoryService } from '../mock/mock-inventory.service';

@Injectable({ providedIn: 'root' })
export class FacadeInventoryService {
  inventoryCache: Map<string, Map<string, string>>;
  constructor(
    private mockInventory: MockInventoryService,
    private inventory: InventoryService
  ) {}

  initializeCache(): void {
    this.mockInventory.initializeCache();
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
      return this.mockInventory.create(managedObject);
    }
  }
}
