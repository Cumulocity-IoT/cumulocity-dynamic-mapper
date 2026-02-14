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
import { AlertService } from '@c8y/ngx-components';
import * as _ from 'lodash';
import { getTypeOf, randomIdAsString } from '../../../mapping/shared/util';
import { API, getPathTargetForDeviceIdentifiers, Mapping, Substitution, MappingType, RepairStrategy, ContentChanges } from '../../../shared';
import { SubstitutionContext } from './processor-js.model';
import { Content } from 'vanilla-jsoneditor';
import {
  MappingTokens,
  IdentityPaths,
  ContextDataPaths,
  ContextDataKeys,
  PROTECTED_TOKENS,
  ProcessingConfig
} from './processor.constants';

export interface DynamicMapperRequest {
  predecessor?: number;
  method?: string;
  api?: string;
  sourceId?: any;
  externalId?: string;
  externalIdType?: string;
  request?: any;
  response?: any;
  error?: string;
  hidden?: boolean;
}

/**
 * @deprecated This monolithic interface mixes multiple concerns.
 *
 * For new code, import from context/processing-context.ts and use:
 * - ProcessingContext (refactored version that extends focused interfaces)
 * - ProcessingContextFactory for creating instances
 * - Individual context interfaces (MappingContext, RoutingContext, etc.) for type constraints
 *
 * This interface is maintained for backward compatibility only.
 */
export interface ProcessingContext {
  mapping: Mapping;
  topic: string;
  resolvedPublishTopic?: string;
  payload?: JSON;
  requests?: DynamicMapperRequest[];
  errors?: string[];
  warnings?: string[];
  processingType?: ProcessingType;
  mappingType: MappingType;
  processingCache: Map<string, SubstituteValue[]>;
  sendPayload?: boolean;
  sourceId?: string;
  logs?: any[];
  deviceName?: string;
  deviceType?: string;
}

export interface TestContext {
  mapping: Mapping;
  payload: string;
  send?: boolean;
}

export interface TestResult {
  success: boolean;
  requests: DynamicMapperRequest[];
  errors: string[];
  warnings?: string[];
  logs?: string[];
}

export enum ProcessingType {
  UNDEFINED,
  ONE_DEVICE_MULTIPLE_VALUE,
  MULTIPLE_DEVICE_ONE_VALUE,
  MULTIPLE_DEVICE_MULTIPLE_VALUE
}

export enum SubstituteValueType {
  NUMBER,
  TEXTUAL,
  BOOLEAN,
  OBJECT,
  IGNORE,
  ARRAY
}

export interface SubstituteValue {
  value: any;
  type?: SubstituteValueType;
  repairStrategy: RepairStrategy;
}export function getTypedValue(subValue: SubstituteValue): any {
  if (subValue.type == SubstituteValueType.NUMBER) {
    return Number(subValue.value);
  } else if (subValue.type == SubstituteValueType.TEXTUAL) {
    return String(subValue.value);
  } else {
    return subValue.value;
  }
}

export const isNumeric = (num: any) => (typeof num === 'number' || (typeof num === 'string' && num.trim() !== '')) &&
  !isNaN(num as number);


export function processSubstitute(processingCacheEntry: SubstituteValue[], extractedSourceContent: any, substitution: Substitution) {
  if (getTypeOf(extractedSourceContent) == 'null') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.IGNORE,
      repairStrategy: substitution.repairStrategy
    });
    // Note: This is a utility function without ProcessingContext access
    // Consider refactoring to accept logger or context parameter if structured logging needed
    console.error(
      'No substitution for: ',
      substitution.pathSource
    );
  } else if (getTypeOf(extractedSourceContent) == 'String') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.TEXTUAL,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Number') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.NUMBER,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Array') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.ARRAY,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Object') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.OBJECT,
      repairStrategy: substitution.repairStrategy
    });
  } else if (getTypeOf(extractedSourceContent) == 'Boolean') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.BOOLEAN,
      repairStrategy: substitution.repairStrategy
    });
  } else {
    // Note: This is a utility function without ProcessingContext access
    // Consider refactoring to accept logger or context parameter if structured logging needed
    console.warn(
      `Since result is not (number, array, textual, object), it is ignored: ${extractedSourceContent}`
    );
  }
}

export function getDeviceEntries(context: ProcessingContext): SubstituteValue[] {
  const { processingCache, mapping } = context;
  const pathsTargetForDeviceIdentifiers: string[] = getPathTargetForDeviceIdentifiers(context);
  const firstPathTargetForDeviceIdentifiers = pathsTargetForDeviceIdentifiers.length > 0
    ? pathsTargetForDeviceIdentifiers[0]
    : null;
  const deviceEntries: SubstituteValue[] = processingCache.get(
    firstPathTargetForDeviceIdentifiers
  );
  return deviceEntries;
}

export function prepareAndSubstituteInPayload(
  context: ProcessingContext,
  substitute: SubstituteValue,
  payloadTarget: JSON,
  pathTarget: string,
  alert: AlertService
) {
  if (ContextDataPaths.DEVICE_NAME == pathTarget) {
    context.deviceName = substitute.value;
  } else if (ContextDataPaths.DEVICE_TYPE == pathTarget) {
    context.deviceType = substitute.value;
  } else {
    substituteValueInPayload(substitute, payloadTarget, pathTarget);
  }
}

// Re-export refactored context types (Phase 6)
// New code should import directly from context/processing-context.ts
export {
  ProcessingContext as RefactoredProcessingContext,
  ProcessingContextFactory,
  MappingContext,
  RoutingContext,
  ProcessingState,
  DeviceContext,
  ErrorContext,
  RequestContext,
  ProcessingContextOverrides
} from './context/processing-context';

// Re-export constants for backward compatibility
export {
  TOKEN_IDENTITY,
  TOKEN_TOPIC_LEVEL,
  TOKEN_CONTEXT_DATA,
  CONTEXT_DATA_KEY_NAME,
  KEY_TIME,
  TOPIC_WILDCARD_MULTI,
  TOPIC_WILDCARD_SINGLE,
  MappingTokens,
  IdentityPaths,
  ContextDataKeys,
  ContextDataPaths,
  TopicWildcards,
  ProcessingConfig,
  PROTECTED_TOKENS
} from './processor.constants';


export function substituteValueInPayload(substitute: SubstituteValue, payloadTarget: JSON, pathTarget: string) {
  const subValueMissingOrNull: boolean = !substitute || substitute.value == null;

  if (pathTarget == '$') {
    Object.keys(getTypedValue(substitute)).forEach((key) => {
      payloadTarget[key] = getTypedValue(substitute)[key as keyof unknown];
    });
  } else {
    if ((substitute.repairStrategy == RepairStrategy.REMOVE_IF_MISSING_OR_NULL &&
      subValueMissingOrNull)) {
      _.unset(payloadTarget, pathTarget);
    } else if (substitute.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
      // const pathIsNested: boolean = keys.includes('.') || keys.includes('[');
      // if (pathIsNested) {
      //   throw new Error('Can only create new nodes on the root level!');
      // }
      // jsonObject.put("$", keys, sub.typedValue());
      _.set(payloadTarget, pathTarget, getTypedValue(substitute));
    } else {
      if (_.has(payloadTarget, pathTarget)) {
        _.set(payloadTarget, pathTarget, getTypedValue(substitute));
      } else {
        // alert.warning(`Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`);
        throw new Error(
          `Message could NOT be parsed, ignoring this message: Path: ${pathTarget} not found!`
        );
      }
    }
  }
}

/**
 * Sorts the processing cache by key
 * Optimized to avoid creating intermediate arrays when possible
 *
 * @param context - Processing context with cache to sort
 */
export function sortProcessingCache(context: ProcessingContext): void {
  // Optimization: Skip sorting if cache is empty or has only one entry
  if (context.processingCache.size <= 1) {
    return;
  }

  // Create sorted entries array
  const sortedEntries = Array.from(context.processingCache.entries())
    .sort(([keyA], [keyB]) => keyA.localeCompare(keyB));

  // Clear and repopulate - this is faster than creating a new Map
  context.processingCache.clear();
  for (const [key, value] of sortedEntries) {
    context.processingCache.set(key, value);
  }
}

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomIdAsString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, IdentityPaths.C8Y_SOURCE_ID, identifier);
}


export interface EvaluationError {
  message: string;
  stack: string | null;
  location: any | null;
}

export interface EvaluationResult {
  success: boolean;
  result?: any;
  error?: EvaluationError;
  logs: string[];
}

/**
 * Evaluates JavaScript code with arguments and a timeout.
 *
 * @deprecated This function now delegates to CodeEvaluatorService.
 * For new code, use CodeEvaluatorService directly for better control and testability.
 *
 * @param codeString The JavaScript code to evaluate
 * @param ctx The substitution context
 * @returns Promise resolving to evaluation result
 */
export function evaluateWithArgsWebWorker(codeString: string, ctx: SubstitutionContext): Promise<EvaluationResult> {
  // Import the service dynamically to avoid circular dependencies
  // In production, this should be injected properly
  const { CodeEvaluatorService } = require('./web-worker/code-evaluator.service');
  const evaluator = new CodeEvaluatorService();

  // Serialize the SubstitutionContext object
  const serializableCtx = {
    identifier: ctx.getGenericDeviceIdentifier ? ctx.getGenericDeviceIdentifier() : ctx['deviceIdentifier'],
    payload: ctx.getPayload ? ctx.getPayload() : ctx['payload'],
    topic: ctx.getTopic ? ctx.getTopic() : ctx['topic'],
  };

  // Delegate to the CodeEvaluatorService
  return evaluator.evaluate(codeString, serializableCtx, { timeoutMs: ProcessingConfig.DEFAULT_TIMEOUT_MS });
}

function contentChangeAllowed(contentChanges: ContentChanges): boolean {
  // Convert both contents to JSON
  const previousJSON = contentToJSON(contentChanges.previousContent);
  const updatedJSON = contentToJSON(contentChanges.updatedContent);

  // Check if any protected token has changed
  return !hasProtectedTokenChanges(previousJSON, updatedJSON);
}

function contentToJSON(content: Content): unknown {
  if ('json' in content) {
    return content.json;
  }
  // TextContent - try to parse as JSON
  try {
    return JSON.parse(content.text);
  } catch {
    // If not valid JSON, return as-is wrapped in an object
    return { text: content.text };
  }
}

function hasProtectedTokenChanges(previous: unknown, updated: unknown): boolean {
  // Check each protected token
  for (const token of PROTECTED_TOKENS) {
    const previousValue = findTokenValue(previous, token);
    const updatedValue = findTokenValue(updated, token);

    // If token exists in either version, check if they differ
    if (previousValue !== undefined || updatedValue !== undefined) {
      if (!deepEqual(previousValue, updatedValue)) {
        return true; // Protected token has changed
      }
    }
  }
  return false;
}

function findTokenValue(obj: unknown, token: string): unknown {
  if (obj === null || obj === undefined) {
    return undefined;
  }

  if (typeof obj !== 'object') {
    return undefined;
  }

  // Check if current object has the token as a key
  if (Object.prototype.hasOwnProperty.call(obj, token)) {
    return (obj as Record<string, unknown>)[token];
  }

  // Recursively search in nested objects and arrays
  if (Array.isArray(obj)) {
    for (const item of obj) {
      const found = findTokenValue(item, token);
      if (found !== undefined) {
        return found;
      }
    }
  } else {
    for (const value of Object.values(obj as Record<string, unknown>)) {
      const found = findTokenValue(value, token);
      if (found !== undefined) {
        return found;
      }
    }
  }

  return undefined;
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;

  if (a === null || b === null || a === undefined || b === undefined) {
    return a === b;
  }

  if (typeof a !== typeof b) return false;

  if (typeof a !== 'object') return a === b;

  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false;
    return a.every((item, index) => deepEqual(item, b[index]));
  }

  if (Array.isArray(a) || Array.isArray(b)) return false;

  const aObj = a as Record<string, unknown>;
  const bObj = b as Record<string, unknown>;

  const aKeys = Object.keys(aObj);
  const bKeys = Object.keys(bObj);

  if (aKeys.length !== bKeys.length) return false;

  return aKeys.every(key =>
    Object.prototype.hasOwnProperty.call(bObj, key) &&
    deepEqual(aObj[key], bObj[key])
  );
}

export { contentChangeAllowed };