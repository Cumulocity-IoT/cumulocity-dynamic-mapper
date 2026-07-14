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

import {
  AIAssistantMessage,
  AIMessage,
  AIService,
  ClientAgentDefinition
} from '@c8y/ngx-components/ai';

/** Token usage for a single AI response, as reported by the AI Agent Manager. */
export interface AgentUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
}

export interface AgentTestResult {
  content: string;
  usage?: AgentUsage;
}

@Injectable({
  providedIn: 'root'
})
export class AIAgentService {
  private readonly client: FetchClient = inject(FetchClient);
  private readonly aiService: AIService = inject(AIService);

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

  /**
   * Sends the given message history to the agent via the AI Agent Manager's snapshot/test endpoint
   * (using `AIService` from `@c8y/ngx-components/ai`), returning both the generated content and,
   * if reported by the backend, token usage for this request.
   */
  async test(
    definition: AgentTextDefinition | AgentObjectDefinition,
    messages: AIMessage[],
    variables: Record<string, unknown>,
    abortController: AbortController
  ): Promise<AgentTestResult> {
    const clientAgent: ClientAgentDefinition = {
      snapshot: true,
      label: definition.name,
      definition: {
        name: definition.name,
        type: definition.type,
        agent: { system: definition.agent?.system ?? '' },
        mcp: definition.mcp?.map(m => ({ serverName: m.serverName, tools: m.tools ?? [] }))
      }
    };

    if (definition.type === 'object') {
      const response = await this.aiService.callObjectAgent(
        clientAgent,
        messages,
        variables,
        abortController
      );
      return { content: JSON.stringify(response.object, null, 2), usage: response.totalUsage };
    }

    const stream$ = await this.aiService.stream$(clientAgent, messages, variables, abortController);

    return new Promise<AgentTestResult>((resolve, reject) => {
      let finalMessage: AIAssistantMessage | undefined;
      stream$.subscribe({
        next: response => {
          finalMessage = response.message;
        },
        error: reject,
        complete: () => {
          if (!finalMessage) {
            reject(new Error('No response received from AI agent'));
            return;
          }
          const text = finalMessage.content
            .filter((part): part is { type: 'text'; text: string } => part.type === 'text')
            .map(part => part.text)
            .join('\n\n');
          resolve({ content: text, usage: finalMessage.usage });
        }
      });
    });
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
