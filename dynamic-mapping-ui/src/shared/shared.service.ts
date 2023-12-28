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
import { FetchClient, IdentityService, IExternalIdentity } from '@c8y/client';
import { AGENT_ID, BASE_URL, PATH_FEATURE_ENDPOINT } from '.';
import { Feature } from '../configuration/shared/configuration.model';

@Injectable({ providedIn: 'root' })
export class SharedService {
  constructor(
    private client: FetchClient,
    private identity: IdentityService
  ) {
    this.initDynamicMappingServiceAgent()
      .then((id) => (this._agentId = id))
      .catch((e) => console.error('MappingService with id not subscribed!',e));
  }
  private _agentId: string;
  private _feature: Feature;

  getDynamicMappingServiceAgent(): string {
    return this._agentId;
  }

  async initDynamicMappingServiceAgent(): Promise<any> {
    const identity: IExternalIdentity = {
      type: 'c8y_Serial',
      externalId: AGENT_ID
    };
    const { data, res } = await this.identity.detail(identity);
    if (res.status == 404) {
      console.error('MappingService with id not subscribed!', AGENT_ID);
      return;
    }
    return data.managedObject.id;
  }

  async getFeatures(): Promise<Feature> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_FEATURE_ENDPOINT}`,
      {
        method: 'GET'
      }
    );
    this._feature = await response.json();
    return this._feature;
  }
}
