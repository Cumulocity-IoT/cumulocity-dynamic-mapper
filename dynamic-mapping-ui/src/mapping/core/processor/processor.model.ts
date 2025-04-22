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
import { API, getPathTargetForDeviceIdentifiers, Mapping, MappingSubstitution, MappingType, RepairStrategy } from '../../../shared';
import { Java } from './processor-js.model';

export interface C8YRequest {
  predecessor?: number;
  method?: string;
  sourceId?: any;
  externalIdType?: string;
  request?: any;
  response?: any;
  targetAPI?: string;
  error?: string;
  hidden?: boolean;
}

export interface ProcessingContext {
  mapping: Mapping;
  topic: string;
  resolvedPublishTopic?: string;
  payload?: JSON;
  requests?: C8YRequest[];
  errors?: string[];
  processingType?: ProcessingType;
  mappingType: MappingType;
  processingCache: Map<string, SubstituteValue[]>;
  sendPayload?: boolean;
  sourceId?: string;
  logs?: any[];
}

export enum ProcessingType {
  UNDEFINED,
  ONE_DEVICE_ONE_VALUE,
  ONE_DEVICE_MULTIPLE_VALUE,
  MULTIPLE_DEVICE_ONE_VALUE,
  MULTIPLE_DEVICE_MULTIPLE_VALUE
}

export enum SubstituteValueType {
  NUMBER,
  TEXTUAL,
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


export function processSubstitute(processingCacheEntry: SubstituteValue[], extractedSourceContent: any, substitution: MappingSubstitution) {
  if (getTypeOf(extractedSourceContent) == 'null') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.IGNORE,
      repairStrategy: substitution.repairStrategy
    });
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
  } else {
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

export function substituteValueInPayload(
  sub: SubstituteValue,
  jsonObject: JSON,
  keys: string,
  alert: AlertService
) {
  const subValueMissingOrNull: boolean = !sub || sub.value == null;

  if (keys == '$') {
    Object.keys(getTypedValue(sub)).forEach((key) => {
      jsonObject[key] = getTypedValue(sub)[key as keyof unknown];
    });
  } else {
    if ((sub.repairStrategy == RepairStrategy.REMOVE_IF_MISSING_OR_NULL &&
      subValueMissingOrNull)) {
      _.unset(jsonObject, keys);
    } else if (sub.repairStrategy == RepairStrategy.CREATE_IF_MISSING) {
      // const pathIsNested: boolean = keys.includes('.') || keys.includes('[');
      // if (pathIsNested) {
      //   throw new Error('Can only create new nodes on the root level!');
      // }
      // jsonObject.put("$", keys, sub.typedValue());
      _.set(jsonObject, keys, getTypedValue(sub));
    } else {
      if (_.has(jsonObject, keys)) {
        _.set(jsonObject, keys, getTypedValue(sub));
      } else {
        alert.warning(`Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`);
        throw new Error(
          `Message could NOT be parsed, ignoring this message: Path: ${keys} not found!`
        );
      }
    }
  }
}

export const IDENTITY = '_IDENTITY_';
export const TOKEN_TOPIC_LEVEL = '_TOPIC_LEVEL_';
export const TOKEN_CONTEXT_DATA = '_CONTEXT_DATA_';
export const CONTEXT_DATA_KEY_NAME = 'key';
export const TIME = 'time';

export const TOPIC_WILDCARD_MULTI = '#';
export const TOPIC_WILDCARD_SINGLE = '+';


export function patchC8YTemplateForTesting(template: object, mapping: Mapping) {
  const identifier = randomIdAsString();
  _.set(template, API[mapping.targetAPI].identifier, identifier);
  _.set(template, `${IDENTITY}.c8ySourceId`, identifier);
}

/**
* Extract line and column numbers from a stack trace line
* @param {string} stackTraceLine - The stack trace line to parse
* @returns {object|null} An object with line and column numbers, or null if not found
*/
export function extractLineAndColumn(stackTraceLine) {
  // This pattern looks for "<anonymous>:X:Y" where X is line and Y is column
  const pattern = /<anonymous>:(\d+):(\d+)/;
  const match = stackTraceLine.match(pattern);

  if (match && match.length >= 3) {
    return {
      line: parseInt(match[1], 10),
      column: parseInt(match[2], 10)
    };
  }

  return null;
}

// export function evaluateWithArgs(codeString, ...args) {
//   // Add 'Java' as the first parameter
//   const paramNames = ['Java'].concat(args.map((_, i) => `arg${i}`)).join(',');

//   // Create the function with Java and your other parameters
//   const fn = new Function(paramNames, codeString);

//   // Call the function with Java as the first argument, followed by your other args
//   return fn(Java, ...args);
// }

export function evaluateWithArgs(codeString, ...args) {
  // Capture console output
  const logs = [];
  const originalConsoleLog = console.log;

  // Create a scoped console.log override that captures output
  const scopedConsole = {
    log: (...logArgs) => {
      logs.push(logArgs.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' '));
      // Still call the original for debugging visibility if needed
      originalConsoleLog.apply(console, logArgs);
    }
  };

  try {
    // Add 'Java' and 'console' as parameters
    const paramNames = ['Java', 'console'].concat(args.map((_, i) => `arg${i}`)).join(',');

    // Create the function with Java, console, and your other parameters
    const fn = new Function(paramNames, codeString);

    // Call the function with Java and our scoped console as arguments, followed by your other args
    const result = fn(Java, scopedConsole, ...args);

    // Return both the evaluation result and the logs
    return {
      result,
      logs,
      success: true
    };
  } catch (error) {
    // Return the error and logs in a structured way
    return {
      error: {
        message: error.message,
        stack: error.stack,
        location: extractLineAndColumn(error.stack)
      },
      logs,
      success: false
    };
  } finally {
    // No need to restore console.log as we used a scoped version
  }
}

export function removeJavaTypeLines(code) {
  // Split the code into lines
  const lines = code.split('\n');

  // Filter out lines containing Java.type
  const filteredLines = lines.filter(line => !line.includes('Java.type'));

  // Join the lines back together
  return filteredLines.join('\n');
}