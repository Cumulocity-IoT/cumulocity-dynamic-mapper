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
  TransformationType,
} from '../../shared';
import { AgentObjectDefinition, AgentTextDefinition } from '../shared/ai-prompt.model';
import { ServiceConfiguration } from '../../configuration';

/**
 * Resolves the service-configuration field naming the AI agent responsible for generating
 * content for the given transformation type. Shared by `MappingTypeDrawerComponent` (the
 * "Generate with AI" choice at mapping-creation time) and `MappingStepperService` (the
 * "Generate with AI" button in the transformation step) so the mapping between
 * `TransformationType` and agent config field can't silently diverge between the two.
 */
export function resolveRequiredAgentName(
  transformationType: TransformationType | undefined,
  serviceConfiguration: ServiceConfiguration | undefined
): string | undefined {
  switch (transformationType) {
    case TransformationType.JSONATA:
    // eslint-disable-next-line @typescript-eslint/no-deprecated -- legacy engine still resolves to the JSONata agent
    case TransformationType.DEFAULT:
      return serviceConfiguration?.jsonataAgent;
    case TransformationType.SMART_FUNCTION:
      return serviceConfiguration?.smartFunctionAgent;
    default:
      return serviceConfiguration?.javaScriptAgent;
  }
}

import {
  ClientAgentDefinition
} from '@c8y/ngx-components/ai';

/** Builds the SDK's thinner `ClientAgentDefinition` from this app's richer local agent model. */
export function toClientAgentDefinition(
  definition: AgentTextDefinition | AgentObjectDefinition
): ClientAgentDefinition {
  return {
    snapshot: true,
    label: definition.name,
    definition: {
      name: definition.name,
      type: definition.type,
      agent: { system: definition.agent?.system ?? '' },
      mcp: definition.mcp?.map(m => ({ serverName: m.serverName, tools: m.tools ?? [] }))
    }
  };
}

@Injectable({
  providedIn: 'root'
})
export class AIAgentService {
  private readonly client: FetchClient = inject(FetchClient);

  /** Short-lived cache so the drawer's and the stepper's independent agent-deployment checks
   *  (both happen within seconds of each other during a single mapping-creation flow) don't
   *  each fire their own network round trip. Not cached on a failed fetch, so a transient
   *  error is retried on the very next call rather than being remembered for the full TTL. */
  private agentsCache: { promise: Promise<AgentTextDefinition[]>; timestamp: number } | null = null;
  private static readonly AGENTS_CACHE_TTL_MS = 30_000;

  /**
   * Fetches the deployed AI agents. A non-ok HTTP response is treated as "no agents deployed"
   * (returns `[]`) since that's a legitimate server-side answer; a network/parse failure is
   * NOT swallowed here and propagates to the caller, so it can be distinguished from "AI is
   * genuinely not configured" (see `MappingTypeDrawerComponent.checkAIAgentAvailability`).
   */
  async getAIAgents(): Promise<AgentTextDefinition[]> {
    const now = Date.now();
    if (this.agentsCache && now - this.agentsCache.timestamp < AIAgentService.AGENTS_CACHE_TTL_MS) {
      return this.agentsCache.promise;
    }
    const promise = this.fetchAIAgents();
    this.agentsCache = { promise, timestamp: now };
    promise.catch(() => { this.agentsCache = null; });
    return promise;
  }

  private async fetchAIAgents(): Promise<AgentTextDefinition[]> {
    const res: IFetchResponse = await this.client.fetch(
      `${BASE_AI_URL}/${PATH_AGENT_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'GET'
      }
    );

    if (!res.ok) {
      console.error(`Failed to fetch agents: ${res.status} ${res.statusText}`);
      return [];
    }

    const data = await res.json();
    return Array.isArray(data) ? data : [];
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
