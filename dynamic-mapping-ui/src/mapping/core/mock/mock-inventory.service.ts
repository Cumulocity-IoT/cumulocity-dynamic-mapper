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
import { HttpStatusCode } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { IdReference, IFetchResponse, IManagedObject, IResult } from '@c8y/client';
import * as _ from 'lodash';
import { randomIdAsString } from '../../../mapping/shared/util';

@Injectable({ providedIn: 'root' })
export class MockInventoryService {
  inventoryCache: Map<string, Partial<IManagedObject>>;
  constructor() {
    this.initializeCache();
  }

  initializeCache(): void {
    this.inventoryCache = new Map<string, IManagedObject>();
  }

  detail(managedObjectOrId: IdReference): Promise<IResult<IManagedObject>> {
    let managedObject = this.inventoryCache.get(managedObjectOrId as string);
    let promise;
    if (managedObject) {
      promise = Promise.resolve({
        data: managedObject as IManagedObject,
        res: { status: HttpStatusCode.Ok } as IFetchResponse
      });
    } else {
      promise = Promise.resolve({
        res: { status: HttpStatusCode.NotFound } as IFetchResponse
      });
    }
    return promise;
  }

  update(
    managedObject: Partial<IManagedObject>
  ): Promise<IResult<IManagedObject>> {
    let copyManagedObject: Partial<IManagedObject> = _.clone(managedObject);
    copyManagedObject = {
      ...this.inventoryCache.get(managedObject.id),
      lastUpdated: new Date().toISOString()
    };
    copyManagedObject.lastUpdated = new Date().toISOString();
    this.inventoryCache.set(managedObject.id, copyManagedObject);
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: { status: HttpStatusCode.Ok } as IFetchResponse
    });
    return promise;
  }

  create(
    managedObject: Partial<IManagedObject>
  ): Promise<IResult<IManagedObject>> {
    // We force the creation of a device with a given id. 
    // This is required to keep the source.id and deviceId consistent, across request.
    // E.g. an alarm with a c8ySourceId = '102030' is tested in the UI, then we need 
    // to create a device with that given id = '102030'
    const id = managedObject.id ? managedObject.id : randomIdAsString();
    const copyManagedObject = {
      ...managedObject,
      id,
      lastUpdated: new Date().toISOString()
    };
    this.inventoryCache.set(id, copyManagedObject);
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: { status: HttpStatusCode.Ok } as IFetchResponse
    });
    return promise;
  }
}
