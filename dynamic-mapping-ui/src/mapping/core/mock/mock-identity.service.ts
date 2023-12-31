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
import {
  IExternalIdentity,
  IFetchResponse,
  IIdentified,
  IResult
} from '@c8y/client';
import * as _ from 'lodash';

@Injectable({ providedIn: 'root' })
export class MockIdentityService {
  identityCache: Map<string, Map<string, IIdentified>>;

  constructor() {
    this.initializeCache();
  }
  initializeCache(): void {
    this.identityCache = new Map<string, Map<string, IIdentified>>();
  }

  detail(
    identity: IExternalIdentity
  ): Promise<IResult<IExternalIdentity>> {
    const externalIds = this.identityCache.get(identity.type);
    if (externalIds) {
      const externalId: IIdentified = externalIds.get(identity.externalId);
      if (externalId) {
        const copyExternalIdentity: IExternalIdentity = _.clone(identity);
        copyExternalIdentity.managedObject = externalId;
        const promise = Promise.resolve({
          data: copyExternalIdentity,
          res: { status: 200 } as IFetchResponse
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

  create(
    identity: IExternalIdentity
  ): Promise<IResult<IExternalIdentity>> {
    const id: number = Math.floor(100000 + Math.random() * 900000);
    const identified: IIdentified = { id: id };

    let externalIds = this.identityCache.get(identity.type);
    if (!externalIds) {
      externalIds = new Map<string, IIdentified>();
      externalIds.set(identity.externalId, identified);
      this.identityCache.set(identity.type, externalIds);
    } else {
      const sourceID = externalIds.get(identity.externalId);
      if (sourceID) {
        throw new Error(
          `External id ${identity.externalId} for type ${identity.type} already exists.`
        );
      }
      externalIds.set(identity.externalId, identified);
    }
    const copyExternalIdentity: IExternalIdentity = _.clone(identity);
    copyExternalIdentity.managedObject = identified;
    const promise = Promise.resolve({
      data: copyExternalIdentity,
      res: { status: 200 } as IFetchResponse
    });
    return promise;
  }
}
