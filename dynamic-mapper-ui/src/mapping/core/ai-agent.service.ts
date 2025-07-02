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
  BASE_URL,
  PATH_AGENT_ENDPOINT,
  PATH_SUBSCRIPTIONS_ENDPOINT
} from '../../shared';
import { C8YNotificationSubscription } from '../shared/mapping.model';


interface AgentConfig {
  name: string;
  agent: {
    system: string;
    maxSteps: number;
  };
  type: string;
}

// If you need to represent the array structure:
type AgentConfigArray = AgentConfig[];

@Injectable({
  providedIn: 'root'
})
export class AIAgentService {

  client: FetchClient = inject(FetchClient);
  async getSubscriptions(): Promise<AgentConfigArray> {

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

}
