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
import { isTypeOf, randomIdAsString } from '../../../mapping/shared/util';
import { API, Direction, getPathTargetForDeviceIdentifiers, Mapping, MappingSubstitution, MappingType, RepairStrategy } from '../../../shared';

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
  if (isTypeOf(extractedSourceContent) == 'null') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.IGNORE,
      repairStrategy: substitution.repairStrategy
    });
    console.error(
      'No substitution for: ',
      substitution.pathSource
    );
  } else if (isTypeOf(extractedSourceContent) == 'String') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.TEXTUAL,
      repairStrategy: substitution.repairStrategy
    });
  } else if (isTypeOf(extractedSourceContent) == 'Number') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.NUMBER,
      repairStrategy: substitution.repairStrategy
    });
  } else if (isTypeOf(extractedSourceContent) == 'Array') {
    processingCacheEntry.push({
      value: extractedSourceContent,
      type: SubstituteValueType.ARRAY,
      repairStrategy: substitution.repairStrategy
    });
  } else if (isTypeOf(extractedSourceContent) == 'Object') {
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
  const pathsTargetForDeviceIdentifiers: string[] = getPathTargetForDeviceIdentifiers(mapping);
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

