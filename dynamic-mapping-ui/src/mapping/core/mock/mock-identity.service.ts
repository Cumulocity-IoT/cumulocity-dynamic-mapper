/*
 * Copyright (c) 2025 Cumulocity GmbH
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
import {
  IExternalIdentity,
  IFetchResponse,
  IResult,
  IResultList
} from '@c8y/client';
import * as _ from 'lodash';

@Injectable({ providedIn: 'root' })
export class MockIdentityService {
  // <type , <externalId, IIdentified>>
  identityCacheByType: Map<string, Map<string, string>>;
  // <IIdentified , <type, externalId>>

  identityCacheByC8YId: Map<string, Map<string, string>>;

  constructor() {
    this.initializeCache();
  }
  initializeCache(): void {
    this.identityCacheByType = new Map<string, Map<string, string>>();
    this.identityCacheByC8YId = new Map<string, Map<string, string>>();
  }

  detail(identity: IExternalIdentity): Promise<IResult<IExternalIdentity>> {
    const externalIds = this.identityCacheByType.get(identity.type);
    if (externalIds) {
      const externalId = externalIds.get(identity.externalId);
      if (externalId) {
        const copyExternalIdentity: IExternalIdentity = _.clone(identity);
        copyExternalIdentity.managedObject = { id: externalId };
        const promise = Promise.resolve({
          data: copyExternalIdentity,
          res: { status: HttpStatusCode.Ok } as IFetchResponse
        });
        return promise;
      } else {
        throw new Error(
          `External id ${identity.externalId} for type ${identity.type} does not exist.`
        );
      }
    } else {
      throw new Error(
        `External id ${identity.externalId} for type ${identity.type} does not exist.`
      );
    }
  }

  create(identity: IExternalIdentity): Promise<IResult<IExternalIdentity>> {

    let externalIdsForType = this.identityCacheByType.get(identity.type);
    // update identityCacheByType
    if (!externalIdsForType) {
      externalIdsForType = new Map<string, string>();
      externalIdsForType.set(identity.externalId, identity.managedObject.id as string);
      this.identityCacheByType.set(identity.type, externalIdsForType);
    } else {
      const sourceId = externalIdsForType.get(identity.externalId);
      if (sourceId) {
        throw new Error(
          `External id ${identity.externalId} for type ${identity.type} already exists.`
        );
      }
      externalIdsForType.set(identity.externalId, identity.managedObject.id as string);
    }

    let externalIdsForIdentified = this.identityCacheByC8YId.get(identity.managedObject.id as string);
    // update identityCacheByC8YId
    if (!externalIdsForIdentified) {
      externalIdsForIdentified = new Map<string, string>();
      externalIdsForIdentified.set(identity.type, identity.externalId);
      this.identityCacheByC8YId.set(identity.managedObject.id as string, externalIdsForIdentified);
    } else {
      const externalId = externalIdsForIdentified.get(identity.type);
      if (externalId) {
        throw new Error(
          `External id ${identity.externalId} for type ${identity.type} already exists.`
        );
      }
      externalIdsForIdentified.set(identity.type, identity.externalId);
    }

    const copyExternalIdentity: IExternalIdentity = _.clone(identity);
    copyExternalIdentity.managedObject = identity.managedObject;
    const promise = Promise.resolve({
      data: copyExternalIdentity,
      res: { status: HttpStatusCode.Ok } as IFetchResponse
    });
    return promise;
  }

  list(managedObjectId: string): Promise<IResultList<IExternalIdentity>> {
    let externalIdsForIdentified = this.identityCacheByC8YId.get(managedObjectId);
    if (externalIdsForIdentified) {
      const externalIds: IExternalIdentity[] = [];
      externalIdsForIdentified.forEach((value, key) => {
        externalIds.push({ externalId: value, type: key, managedObject: { id: managedObjectId } })
      });

      const copyExternalIdentities: IExternalIdentity[] = _.clone(externalIdsForIdentified);
      const promise = Promise.resolve({
        data: copyExternalIdentities,
        res: { status: HttpStatusCode.Ok } as IFetchResponse
      });
      return promise;
    } else {
      const promise = Promise.resolve({
        data: [],
        res: { status: HttpStatusCode.Ok } as IFetchResponse
      });
      return promise;
    }
  }
}
