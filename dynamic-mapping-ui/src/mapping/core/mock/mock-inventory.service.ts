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
import { Injectable } from "@angular/core";
import { IFetchResponse, IManagedObject, IResult } from "@c8y/client";
import * as _ from "lodash";

@Injectable({ providedIn: "root" })
export class MockInventoryService {
  inventoryCache: Map<string, Map<string, any>>;
  constructor() {
    this.initializeCache();
  }

  public initializeCache(): void {
    this.inventoryCache = new Map<string, Map<string, IManagedObject>>();
  }

  public update(
    managedObject: Partial<IManagedObject>
  ): Promise<IResult<IManagedObject>> {
    let copyManagedObject: Partial<IManagedObject> = _.clone(managedObject);
    copyManagedObject = {
      ...this.inventoryCache.get(managedObject.id),
      lastUpdated: new Date().toISOString(),
    };
    copyManagedObject.lastUpdated = new Date().toISOString();
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: { status: 200 } as IFetchResponse,
    });
    return promise;
  }

  public create(
    managedObject: Partial<IManagedObject>
  ): Promise<IResult<IManagedObject>> {
    let copyManagedObject = {
      ...managedObject,
      id: Math.floor(100000 + Math.random() * 900000).toString(),
      lastUpdated: new Date().toISOString(),
    };
    const promise = Promise.resolve({
      data: copyManagedObject as IManagedObject,
      res: { status: 200 } as IFetchResponse,
    });
    return promise;
  }
}
