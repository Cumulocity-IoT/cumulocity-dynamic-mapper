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
import * as _ from 'lodash';
import { randomIdAsString } from '../../../mapping/shared/util';
import { API, Mapping, RepairStrategy, ContentChanges } from '../../../shared';
import { Content } from 'vanilla-jsoneditor';
import {
  IdentityPaths,
  PROTECTED_TOKENS
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


// Re-export constants for backward compatibility
export {
  KEY_TIME,
  TOPIC_WILDCARD_MULTI,
  TOPIC_WILDCARD_SINGLE,
  MappingTokens,
  IdentityPaths,
  ContextDataKeys,
  ContextDataPaths,
  TopicWildcards,
  PROTECTED_TOKENS
} from './processor.constants';

export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomIdAsString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, IdentityPaths.C8Y_SOURCE_ID, identifier);
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