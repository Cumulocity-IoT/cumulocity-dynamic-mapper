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
import { Injectable } from '@angular/core';
import {
  IFetchResponse,
  IdentityService,
  IExternalIdentity,
  ApplicationService
} from '@c8y/client';
import {
  AGENT_ID,
  BASE_URL,
  Direction,
  Feature,
  PATH_CONFIGURATION_SERVICE_ENDPOINT,
  PATH_CONFIGURATION_CODE_TEMPLATE_ENDPOINT,
  PATH_FEATURE_ENDPOINT,
  PATH_OPERATION_ENDPOINT
} from '..';
import { Subject, takeUntil, timer } from 'rxjs';
import { FetchClient } from '@c8y/ngx-components/api';
import { CodeTemplate, CodeTemplateMap, ServiceConfiguration } from '../../configuration';
import { ServiceOperation } from './shared.model';
import { OptionsService } from '@c8y/ngx-components';

@Injectable({ providedIn: 'root' })
export class SharedService {

  constructor(
    private client: FetchClient,
    private identity: IdentityService,
    private option: OptionsService,
    private application: ApplicationService,

  ) {
    // console.log('Option:', this.option, this.application, window.location);
    const docBaseUrl = `${window.location.origin}/${window.location.pathname}`;
    this.option.set("docsBaseUrl", docBaseUrl);
  }

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

  async runOperation(serviceOperation: ServiceOperation): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(serviceOperation),
      method: 'POST'
    });
  }

  async updateServiceConfiguration(
    configuration: ServiceConfiguration
  ): Promise<IFetchResponse> {
    this._serviceConfiguration = undefined;
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

  async getCodeTemplate(id: string): Promise<CodeTemplate> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CODE_TEMPLATE_ENDPOINT}/${id}`,
      {
        headers: {
          accept: 'application/json'
        },
        method: 'GET'
      }
    );
    return await response.json();
  }

  async getCodeTemplates(): Promise<CodeTemplateMap> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CODE_TEMPLATE_ENDPOINT}`,
      {
        headers: {
          accept: 'application/json'
        },
        method: 'GET'
      }
    );
    return await response.json();
  }

  async updateCodeTemplate(id: string, codeTemplate: CodeTemplate) {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CODE_TEMPLATE_ENDPOINT}/${id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(codeTemplate),
        method: 'PUT'
      }
    );
    return response;
  }
}
