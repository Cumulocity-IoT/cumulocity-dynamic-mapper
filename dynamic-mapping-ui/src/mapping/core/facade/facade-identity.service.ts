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
  IdentityService,
  IExternalIdentity,
  IIdentified,
  IResult,
} from '@c8y/client';
import { ProcessingContext } from '../processor/processor.model';
import { MockIdentityService } from '../mock/mock-identity.service';

@Injectable({ providedIn: 'root' })
export class FacadeIdentityService {
  identityCache: Map<string, Map<string, IIdentified>>;

  constructor(
    private mockIdentity: MockIdentityService,
    private identity: IdentityService
  ) { }

  initializeCache(): void {
    this.mockIdentity.initializeCache();
  }

  async resolveGlobalId2ExternalId(
    managedObjectId: string,
    externalIdType: string,
    context: ProcessingContext
  ): Promise<IExternalIdentity> {
    let result: IExternalIdentity;
    if (context.sendPayload) {
      const entries = new Array<IExternalIdentity>();
      let res = await this.identity.list(managedObjectId);
      while (res.data.length) {
        entries.push(...(res.data as IExternalIdentity[]));
        if (res.data.length < res.paging.pageSize) {
          break;
        }
        if (!res.paging.nextPage) {
          break;
        }

        res = await res.paging.next();
      }
      result = entries.find(ex => ex.type === externalIdType);
    } else {
      const entries = new Array<IExternalIdentity>();
      let res = await this.mockIdentity.list(managedObjectId);
      while (res.data.length) {
        entries.push(...(res.data as IExternalIdentity[]));
        if (res.data.length < res.paging.pageSize) {
          break;
        }
        if (!res.paging.nextPage) {
          break;
        }

        res = await res.paging.next();
      }
      result = entries.find(ex => ex.type === externalIdType);
    }
    return result;
  }

  resolveExternalId2GlobalId(
    identity: IExternalIdentity,
    context: ProcessingContext
  ): Promise<IResult<IExternalIdentity>> {
    if (context.sendPayload) {
      return this.identity.detail(identity);
    } else {
      return this.mockIdentity.detail(identity);
    }
  }

  create(
    identity: IExternalIdentity,
    context: ProcessingContext
  ): Promise<IResult<IExternalIdentity>> {
    if (context.sendPayload) {
      return this.identity.create(identity);
    } else {
      return this.mockIdentity.create(identity);
    }
  }
}
