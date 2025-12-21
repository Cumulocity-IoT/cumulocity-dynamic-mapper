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
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { type JSONValue } from 'ai';

@Injectable({
  providedIn: 'root'
})
export class AIAgentService {
  private readonly client: FetchClient = inject(FetchClient);

  async getAIAgents(): Promise<AgentTextDefinition[]> {
    try {
      const res: IFetchResponse = await this.client.fetch(
        `${BASE_AI_URL}/${PATH_AGENT_ENDPOINT}`,
        {
          headers: {
            'content-type': 'application/json'
          },
          method: 'GET'
        }
      );

      // Check if the response is ok
      if (!res.ok) {
        console.error(`Failed to fetch agents: ${res.status} ${res.statusText}`);
        return []; // Return empty array on error
      }

      const data = await res.json(); // Don't forget 'await'

      // Ensure data is an array
      return Array.isArray(data) ? data : [];
    } catch (error) {
      console.error('Error fetching AI agents:', error);
      return []; // Return empty array on error
    }
  }

  async test(
    definition: AgentTextDefinition | AgentObjectDefinition
  ): Promise<string | JSONValue> {
    const data = await this.client.fetch(
      `${BASE_AI_URL}/${PATH_AGENT_ENDPOINT}/test/${definition.type}`,
      {
        method: 'POST',
        body: JSON.stringify(definition),
        headers: {
          ...this.client.defaultHeaders,
          'content-type': 'application/json'
        }
      }
    );

    if (definition.type === 'object') {
      return data.json();
    }

    return data.text();
  }


  async isAIOperable(): Promise<boolean> {
    try {
      const res: IFetchResponse = await this.client.fetch(
        `${BASE_AI_URL}/${PATH_AGENT_ENDPOINT}`,
        {
          headers: {
            'content-type': 'application/json'
          },
          method: 'GET'
        }
      );

      // Check if the response is ok and we have agents
      if (!res.ok) {
        console.error(`AI service not available: ${res.status} ${res.statusText}`);
        return false;
      }

      const data = await res.json();

      // AI is operable if we have a valid array with at least one agent
      return Array.isArray(data) && data.length > 0;
    } catch (error) {
      console.error('Error checking AI operability:', error);
      return false;
    }
  }

}
