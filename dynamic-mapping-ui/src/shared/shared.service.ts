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
  IFetchResponse,
  IdentityService,
  IExternalIdentity
} from '@c8y/client';
import {
  AGENT_ID,
  BASE_URL,
  Direction,
  Feature,
  Operation,
  PATH_CONFIGURATION_SERVICE_ENDPOINT,
  PATH_FEATURE_ENDPOINT,
  PATH_OPERATION_ENDPOINT
} from '.';
import { Subject, takeUntil, timer } from 'rxjs';
import { FetchClient } from '@c8y/ngx-components/api';
import { ServiceConfiguration } from '../configuration';

@Injectable({ providedIn: 'root' })
export class SharedService {
  constructor(
    private client: FetchClient,
    private identity: IdentityService
  ) {}
  private _agentId: string;
  private _featurePromise: Promise<Feature>;
  reloadInbound$: Subject<void> = new Subject<void>();
  reloadOutbound$: Subject<void> = new Subject<void>();
  private _serviceConfiguration: ServiceConfiguration;

  async getDynamicMappingServiceAgent(): Promise<string> {
    if (!this._agentId) {
      const identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: AGENT_ID
      };
      const { data, res } = await this.identity.detail(identity);
      if (res.status == 404) {
        console.error('MappingService with id not subscribed!', AGENT_ID);
        return undefined;
      }
      // this._agentId = data.managedObject.id as string;
      this._agentId = data.managedObject.id as string;
    }
    return this._agentId;
  }

  async getFeatures(): Promise<Feature> {
    if (!this._featurePromise) {
      this._featurePromise = this.fetchFeatures();
    }
    return this._featurePromise;
  }

  private async fetchFeatures(): Promise<Feature> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_FEATURE_ENDPOINT}`,
      {
        method: 'GET'
      }
    );
    return await response.json();
  }

  refreshMappings(direction: Direction) {
    // delay the reload of mappings as the subscriptions are updated asynchronously. This can take a while
    if (direction == Direction.INBOUND) {
      timer(2000)
        .pipe(takeUntil(this.reloadInbound$))
        .subscribe(() => {
          this.reloadInbound$.next();
        });
      // this.reloadInbound$.next();
    } else {
      timer(2000)
        .pipe(takeUntil(this.reloadOutbound$))
        .subscribe(() => {
          this.reloadOutbound$.next();
        });
      // this.reloadOutbound$.next();
    }
  }

  async runOperation(op: Operation, parameter?: any): Promise<IFetchResponse> {
    let body: any = {
      operation: op
    };
    if (parameter) {
      body = {
        ...body,
        parameter: parameter
      };
    }
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(body),
      method: 'POST'
    });
  }

  async updateServiceConfiguration(
    configuration: ServiceConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_SERVICE_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(configuration),
        method: 'PUT'
      }
    );
  }

  async getServiceConfiguration(): Promise<ServiceConfiguration> {
    if (!this._serviceConfiguration) {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_SERVICE_ENDPOINT}`,
        {
          headers: {
            accept: 'application/json'
          },
          method: 'GET'
        }
      );
      this._serviceConfiguration = await response.json();
    }

    return this._serviceConfiguration;
  }
}
