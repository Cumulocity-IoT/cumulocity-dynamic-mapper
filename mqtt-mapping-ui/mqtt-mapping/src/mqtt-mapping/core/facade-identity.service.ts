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
import { IdentityService, IExternalIdentity, IIdentified, InventoryService, IResult } from "@c8y/client";
import * as _ from 'lodash';
import { ProcessingContext } from "../processor/prosessor.model";
import { MockIdentityService } from "./mock/mock-identity.service";

@Injectable({ providedIn: 'root' })
export class FacadeIdentityService {

  identityCache: Map<string, Map<string, IIdentified>>;

  constructor(private mockIdentity: MockIdentityService,
    private identity: IdentityService) {
  }

  public detail(identity: IExternalIdentity, context: ProcessingContext): Promise<IResult<IExternalIdentity>> {
    if (context.sendPayload) {
      return this.identity.detail(identity);
    } else {
      return this.mockIdentity.detail(identity);
    }
  }

  public create(identity: IExternalIdentity, context: ProcessingContext): Promise<IResult<IExternalIdentity>> {
    if (context.sendPayload) {
      return this.identity.create(identity);
    } else {
      return this.mockIdentity.create(identity);
    }
  }
}