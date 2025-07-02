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
import { inject, Injectable } from '@angular/core';
import {
  FetchClient,
  IFetchResponse
} from '@c8y/client';
import {
  BASE_AI_URL,
  PATH_AGENT_ENDPOINT,
} from '../../shared';
import { AgentConfigArray, AgentTextDefinition } from '../shared/ai-prompt.model';
import { type JSONValue } from 'ai';

@Injectable({
  providedIn: 'root'
})
export class AIAgentService {

  client: FetchClient = inject(FetchClient);
  async getAgents(): Promise<AgentConfigArray> {

    const res: IFetchResponse = await this.client.fetch(
      `${BASE_AI_URL}/${PATH_AGENT_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );
    const data = res.json();
    return data;

  }

  async test(
    definition: AgentTextDefinition ,
  ): Promise<string | JSONValue> {
    const data = await this.client.fetch(
      BASE_AI_URL + '/' + PATH_AGENT_ENDPOINT + '/test/' + definition.type,
      {
        method: 'POST',
        body: JSON.stringify(definition),
        headers: {
          ...this.client.defaultHeaders,
          'content-type': 'application/json',
        },
      },
    );

    if (definition.type === 'object') {
      return data.json();
    }

    return data.text();
  }

}
