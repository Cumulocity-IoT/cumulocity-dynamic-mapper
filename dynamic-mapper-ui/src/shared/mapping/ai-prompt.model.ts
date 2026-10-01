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

import { AnthropicProviderSettings } from '@ai-sdk/anthropic';
import { OpenAIProviderSettings } from '@ai-sdk/openai';
import {
  CoreMessage,
  generateObject,
  generateText,
  LanguageModel,
  Message,
  ProviderMetadata,
  RepairTextFunction,
  TelemetrySettings,
} from 'ai';


// export interface AgentConfig {
//   name: string;
//   agent: {
//     system: string;
//     maxSteps: number;
//   };
//   type: string;
// }

export interface AgentMessage {
  content: string;
  role: string;
}

export type AgentProviderSettings =
  | OpenAIProviderSettings
  | AnthropicProviderSettings;

export type AgentProvider = {
  name: string;
  model: string;
  force?: boolean;
} & AgentProviderSettings;

/**
Prompt part of the AI function options.
It contains a system message, a simple text prompt, or a list of messages.
 */
type Prompt = {
  /**
System message to include in the prompt. Can be used with `prompt` or `messages`.
   */
  system?: string;
  /**
A simple text prompt. You can either use `prompt` or `messages` but not both.
 */
  prompt?: string;
  /**
A list of messages. You can either use `prompt` or `messages` but not both.
   */
  messages?: Array<CoreMessage> | Array<Omit<Message, 'id'>>;
};

export type TextCompletion = Parameters<typeof generateText>[0] & {
  variables?: { [key: string]: string };
};
export type TextCompletionResponse = ReturnType<typeof generateText>;
export type ObjectCompletion = Prompt & {
  output?: 'object' | undefined;
  /**
The language model to use.
   */
  model: LanguageModel;
  /**
The schema of the object that the model should generate.
   */
  schema: any;
  /**
Optional name of the output that should be generated.
Used by some providers for additional LLM guidance, e.g.
via tool or schema name.
   */
  schemaName?: string;
  /**
Optional description of the output that should be generated.
Used by some providers for additional LLM guidance, e.g.
via tool or schema description.
   */
  schemaDescription?: string;
  /**
The mode to use for object generation.

The schema is converted into a JSON schema and used in one of the following ways

- 'auto': The provider will choose the best mode for the model.
- 'tool': A tool with the JSON schema as parameters is provided and the provider is instructed to use it.
- 'json': The JSON schema and an instruction are injected into the prompt. If the provider supports JSON mode, it is enabled. If the provider supports JSON grammars, the grammar is used.

Please note that most providers do not support all modes.

Default and recommended: 'auto' (best mode for the model).
   */
  mode?: 'auto' | 'json' | 'tool';
  /**
A function that attempts to repair the raw output of the mode
to enable JSON parsing.
   */
  experimental_repairText?: RepairTextFunction;
  /**
Optional telemetry configuration (experimental).
     */
  experimental_telemetry?: TelemetrySettings;
  /**
Additional provider-specific options. They are passed through
to the provider from the AI SDK and enable provider-specific
functionality that can be fully encapsulated in the provider.
*/
  providerOptions?: any;
  /**
@deprecated Use `providerOptions` instead.
*/
  experimental_providerMetadata?: ProviderMetadata;

  variables?: { [key: string]: any };
};
export type ObjectCompletionResponse = ReturnType<typeof generateObject>;

export interface AgentBaseDefinition {
  /**
   * The name of the agent.
   */
  name: string;
  /**
   * The local provider to use. Only used if no global provider is set.
   */
  provider?: AgentProvider;

  /**
   * A optional C8Y Context to be used in the agent.
   * The key is the name of the variable and the value is the context.
   *
   * In any text message to the agent the variable can be placed using {{variablename}}.
   */
  context?: {
    [key: string]: C8yContext;
  };

  /**
   * A model context protocol (MCP) server to use.
   * The agent will request all tools, then limit it to the once specified in the `tools` array.
   */
  mcp?: {
    /** The name of the MCP server */
    serverName: string;
    /** The tools the agent should use of this MCP Server. If not defined, all will be used */
    tools?: Array<string>;
  }[];

  /**
   * The agent definition.
   */
  agent: Omit<TextCompletion, 'model'> | ObjectCompletion;

  /**
   * The type of the agent.
   */
  type: 'text' | 'object';
}

export interface AgentTextDefinition extends AgentBaseDefinition {
  type: 'text';
  agent: Omit<TextCompletion, 'model'>;
}

export interface AgentObjectDefinition extends AgentBaseDefinition {
  type: 'object';
  agent: ObjectCompletion;
}

export const contextPath = 'ai';
export const agentTenantOptionsKey = 'agent';
export const providerTenantOptionKey = 'global-provider';

export interface C8yContext {
  /**
   * The query to be used to get the value in C8Y
   * {{var1}} will be replaced by the value of variables.
   */
  query: string;
  /**
   * Method, defaults to GET
   */
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  /**
   * Body to be sent in the request.
   */
  body?: string;
  /**
   * Adding headers to the request.
   */
  headers?: {
    [key: string]: string;
  };
  /**
   * If set to required the agent will not request the AI if the variables are not given
   * or an error is thrown.
   */
  required?: boolean;
  /**
   * An optional description of the context.
   */
  description?: string;
}

// export type AgentConfigArray = AgentConfig[];
